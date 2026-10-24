package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TableColumn;

import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundStatus;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Model;
import name.abuchen.portfolio.ui.views.panes.InformationPanePage;

/**
 * Read-only status page for the look-through data. It shows the effective
 * storage mode with the persistence reason and the restart limitation above a
 * table with one row per fund.
 * <p>
 * The page only renders the model it receives. It performs no retrieval and no
 * persistence access itself, and a recalculation request does not change that:
 * {@link #onRecalculationNeeded()} intentionally does nothing, so a redraw can
 * never cause provider traffic. The owning view pushes a new model whenever the
 * status changed.
 */
public class LookThroughDataStatusPane implements InformationPanePage
{
    private static final int COLUMN_WIDTH = 180;

    private Composite container;
    private Label modeLabel;
    private Label reasonLabel;
    private Label limitationLabel;
    private Label emptyLabel;
    private TableViewer viewer;

    @Override
    public String getLabel()
    {
        return Messages.LookThroughStatusPaneTitle;
    }

    @Override
    public Control createViewControl(Composite parent)
    {
        container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).margins(5, 5).applyTo(container);

        modeLabel = createWrappingLabel(container);
        reasonLabel = createWrappingLabel(container);
        limitationLabel = createWrappingLabel(container);
        emptyLabel = createWrappingLabel(container);

        var tableContainer = new Composite(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(tableContainer);
        tableContainer.setLayout(new FillLayout());

        viewer = new TableViewer(tableContainer, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());

        addColumns();

        viewer.setInput(List.of());
        render(null);
        return container;
    }

    private Label createWrappingLabel(Composite parent)
    {
        var label = new Label(parent, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(label);
        return label;
    }

    private void addColumns()
    {
        addColumn(Messages.LookThroughStatusColumnFund, this::fundText);
        addColumn(Messages.LookThroughStatusColumnProvider, status -> status.providerAccountText());
        addColumn(Messages.LookThroughStatusColumnRetrieved, status -> status.retrievalText().orElse("")); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnProviderRefreshed,
                        status -> status.providerRefreshedText().orElse("")); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnHoldings, status -> status.holdingsText().orElse("")); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnFreshness, status -> status.freshnessText().orElse("")); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnSourceData, status -> status.sourceDataText().orElse("")); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnDenominator, status -> status.denominatorText().orElse("")); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnAttempt, status -> status.attemptText());
        addColumn(Messages.LookThroughStatusColumnRetry, status -> status.retryText().orElse("")); //$NON-NLS-1$
    }

    private void addColumn(String title, Function<FundStatus, String> text)
    {
        var column = new TableColumn(viewer.getTable(), SWT.NONE);
        column.setText(title);
        column.setWidth(COLUMN_WIDTH);

        new TableViewerColumn(viewer, column).setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return text.apply((FundStatus) element);
            }
        });
    }

    /**
     * A pending refresh is marked with a short text after the fund label
     * instead of a row colour: it stays readable, needs no image or colour
     * resource and also reaches the copied cell text.
     */
    private String fundText(FundStatus status)
    {
        if (status.refreshPending())
            return status.fundLabel() + " " + Messages.LookThroughStatusPendingRefresh; //$NON-NLS-1$
        return status.fundLabel();
    }

    @Override
    public void setInput(Object input)
    {
        if (input instanceof Model status)
            render(status);
        else if (input == null)
            render(null);
        // Any other input belongs to a different selection and is ignored.
    }

    private void render(Model status)
    {
        if (viewer == null || viewer.getControl().isDisposed())
            return;

        viewer.setInput(status != null ? status.funds() : List.of());
        viewer.refresh();

        setText(modeLabel, status != null ? status.modeText() : ""); //$NON-NLS-1$
        setText(reasonLabel, status != null ? status.reasonText().orElse("") : ""); //$NON-NLS-1$
        setText(limitationLabel, status != null ? status.limitationText().orElse("") : ""); //$NON-NLS-1$
        setText(emptyLabel, status == null ? Messages.LookThroughStatusPaneEmpty : ""); //$NON-NLS-1$

        container.layout(true);
    }

    private void setText(Label label, String text)
    {
        label.setText(text);
        boolean visible = !text.isEmpty();
        label.setVisible(visible);
        ((GridData) label.getLayoutData()).exclude = !visible;
    }

    @Override
    public void onRecalculationNeeded()
    {
        // Deliberately empty: a recalculation must never trigger a request.
        // The owning view pushes a new status when the model changed.
    }
}
