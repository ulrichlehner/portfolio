package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.editor.AbstractFinanceView;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.viewers.Column;
import name.abuchen.portfolio.ui.util.viewers.ShowHideColumnHelper;
import name.abuchen.portfolio.ui.views.panes.InformationPanePage;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CategoryRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CompanyRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ContributionRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ExcludedRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Row;

/**
 * Read-only presentation of the look-through result. The view performs no
 * retrieval, holds no credentials and persists no analysis data. Supplying the
 * result, cancellable background jobs and the detailed data status are a later
 * increment.
 */
public class LookThroughView extends AbstractFinanceView
{
    private final LookThroughViewPresenter presenter = new LookThroughViewPresenter();

    private Label scopeLabel;
    private Label denominatorLabel;
    private Label completenessLabel;
    private TreeViewer viewer;

    private LookThroughResult result;
    private LookThroughCompositionSource compositionSource;
    private LookThroughStatusModel statusModel;
    private SimpleAction refreshAction;
    private SimpleAction cancelAction;
    private String currencyCode = ""; //$NON-NLS-1$

    @Override
    protected String getDefaultTitle()
    {
        return Messages.LabelPortfolioLens;
    }

    /**
     * Supplies the exact calculation result, either through the eclipse context
     * or by the wiring added in a later increment.
     */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setResult(LookThroughResult result)
    {
        this.result = result;
        applyResult();
    }

    /**
     * Supplies the memory-only composition access. Without a source the view
     * shows no status and offers no refresh.
     */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setCompositionSource(LookThroughCompositionSource source)
    {
        this.compositionSource = source;

        if (source == null)
        {
            this.statusModel = null;
            updateStatus();
            return;
        }

        this.statusModel = new LookThroughStatusModel(Clock.systemDefaultZone(), source.gateway(),
                        Display.getDefault()::asyncExec, () -> {
                            var current = this.compositionSource;
                            if (current == null)
                                return Optional.empty();
                            return Optional.of(new LookThroughStatusModel.Snapshot(current.globalFacts(),
                                            current.funds()));
                        });
        this.statusModel.setChangeListener(this::onStatusChanged);

        updateStatus();
    }

    /** Called on the user interface thread after every status change. */
    private void onStatusChanged()
    {
        updateStatus();
    }

    @Override
    protected Control createBody(Composite parent)
    {
        var container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).margins(5, 5).applyTo(container);

        scopeLabel = new Label(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(scopeLabel);

        denominatorLabel = new Label(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(denominatorLabel);

        completenessLabel = new Label(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(completenessLabel);

        var tableContainer = new Composite(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(tableContainer);

        var layout = new TreeColumnLayout();
        tableContainer.setLayout(layout);

        viewer = new TreeViewer(tableContainer, SWT.FULL_SELECTION);
        ColumnViewerToolTipSupport.enableFor(viewer, ToolTip.NO_RECREATE);

        var columns = new ShowHideColumnHelper(LookThroughView.class.getSimpleName(), getPreferenceStore(), viewer,
                        layout);
        addColumns(columns);
        columns.createColumns();

        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        viewer.setContentProvider(new RowContentProvider());
        viewer.setInput(List.of());

        applyResult();
        updateStatus();
        return container;
    }

    private void addColumns(ShowHideColumnHelper support)
    {
        var column = new Column("instrument", Messages.LookThroughColumnCompany, SWT.NONE, 320); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ((Row) element).label();
            }

            @Override
            public String getToolTipText(Object element)
            {
                if (element instanceof ExcludedRow excluded)
                    return excluded.reasonText();
                return null;
            }
        });
        column.setRemovable(false);
        support.addColumn(column);

        column = new Column("source", Messages.LookThroughColumnSource, SWT.NONE, 280); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof ContributionRow contribution ? contribution.sourceText() : null;
            }
        });
        support.addColumn(column);

        column = new Column("direct", Messages.LookThroughColumnDirect, SWT.RIGHT, 110); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof CompanyRow company)
                    return formatAmount(company.direct());
                if (element instanceof ContributionRow contribution && contribution.direct())
                    return formatAmount(contribution.value());
                return null;
            }
        });
        support.addColumn(column);

        column = new Column("indirect", Messages.LookThroughColumnIndirect, SWT.RIGHT, 110); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof CompanyRow company)
                    return formatAmount(company.indirect());
                if (element instanceof ContributionRow contribution && !contribution.direct())
                    return formatAmount(contribution.value());
                return null;
            }
        });
        support.addColumn(column);

        column = new Column("total", Messages.LookThroughColumnTotal, SWT.RIGHT, 110); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof CompanyRow company)
                    return formatAmount(company.total());
                if (element instanceof ContributionRow contribution)
                    return formatAmount(contribution.value());
                if (element instanceof CategoryRow category)
                    return formatAmount(category.value());
                return null;
            }
        });
        support.addColumn(column);

        column = new Column("share", Messages.LookThroughColumnShare, SWT.RIGHT, 150); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof CompanyRow company)
                    return company.shareText().orElse(null);
                if (element instanceof ContributionRow contribution)
                    return contribution.shareText().orElse(null);
                if (element instanceof CategoryRow category)
                    return category.shareText().orElse(null);
                return null;
            }
        });
        support.addColumn(column);
    }

    private String formatAmount(BigDecimal value)
    {
        return LookThroughViewPresenter.formatAmount(value, currencyCode);
    }

    private void applyResult()
    {
        if (viewer == null || viewer.getControl().isDisposed())
            return;

        if (result == null)
        {
            currencyCode = ""; //$NON-NLS-1$
            scopeLabel.setText(""); //$NON-NLS-1$
            denominatorLabel.setText(Messages.LookThroughLabelNoResult);
            completenessLabel.setText(""); //$NON-NLS-1$
            viewer.setInput(List.of());
            updateStatus();
            return;
        }

        var model = presenter.present(result);
        currencyCode = model.currencyCode();
        scopeLabel.setText(model.scopeText());
        denominatorLabel.setText(model.denominatorText().orElse(Messages.LookThroughLabelNoDenominator));
        completenessLabel.setText(model.completenessText());
        viewer.setInput(model.rows());
        updateStatus();
    }

    /**
     * Pushes the current status into the information pane. The status is read
     * from memory only: this method never starts a refresh, so rendering,
     * resizing and recalculation cannot cause provider traffic.
     */
    private void updateStatus()
    {
        var model = statusModel;
        setInformationPaneInput(model != null ? model.status().orElse(null) : null);
        updateActionEnablement();
    }

    private void updateActionEnablement()
    {
        if (refreshAction != null)
            refreshAction.setEnabled(canRefresh());
        if (cancelAction != null)
            cancelAction.setEnabled(isRefreshing());

        var toolBarManager = getViewToolBarManager();
        if (toolBarManager != null && toolBarManager.getControl() != null
                        && !toolBarManager.getControl().isDisposed())
            toolBarManager.update(true);
    }

    private boolean canRefresh()
    {
        var source = compositionSource;
        var model = statusModel;
        return source != null && model != null && !source.funds().isEmpty() && !model.isRefreshing();
    }

    private boolean isRefreshing()
    {
        var model = statusModel;
        return model != null && model.isRefreshing();
    }

    @Override
    protected void addViewButtons(ToolBarManager toolBarManager)
    {
        refreshAction = new SimpleAction(Messages.LookThroughRefreshAction, a -> {
            var model = statusModel;
            if (model == null)
                return;
            model.refresh();
            updateStatus();
        });
        refreshAction.setEnabled(canRefresh());
        toolBarManager.add(refreshAction);

        cancelAction = new SimpleAction(Messages.LookThroughCancelAction, a -> {
            var model = statusModel;
            if (model == null)
                return;
            model.cancel();
            updateStatus();
        });
        cancelAction.setEnabled(isRefreshing());
        toolBarManager.add(cancelAction);
    }

    @Override
    protected void addPanePages(List<InformationPanePage> pages)
    {
        super.addPanePages(pages);
        pages.add(new LookThroughDataStatusPane());
    }

    @Override
    public void notifyModelUpdated()
    {
        updateStatus();
    }

    /**
     * The information pane is created after the body, so the status pushed
     * while building the body is dropped. Push it again once the view is
     * complete.
     */
    @Override
    protected void notifyViewCreationCompleted()
    {
        updateStatus();
    }

    private static final class RowContentProvider implements ITreeContentProvider
    {
        private static final Object[] NO_CHILDREN = new Object[0];

        @Override
        public Object[] getElements(Object inputElement)
        {
            return ((List<?>) inputElement).toArray();
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof CompanyRow company)
                return company.contributions().toArray();
            return NO_CHILDREN;
        }

        @Override
        public Object getParent(Object element)
        {
            return null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof CompanyRow company && !company.contributions().isEmpty();
        }
    }
}
