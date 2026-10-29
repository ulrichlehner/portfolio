package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IElementComparer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TableColumn;

import name.abuchen.portfolio.ui.Images;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundStatus;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Model;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.panes.InformationPanePage;

/**
 * Read-only status page for the look-through data. One line names the
 * effective storage mode (or the reason for session-only storage) and keeps
 * the restart limitation in its tooltip. A second line tells how much company
 * data is loaded and why the rest is missing. The table lists each fund, and each
 * security that is not classified yet, with a status icon and short status
 * text, the retrieval date and the decomposed share; all other facts are in
 * the row tooltip.
 * <p>
 * The page only renders the model it receives. It performs no retrieval and no
 * persistence access itself, and a recalculation request does not change that:
 * {@link #onRecalculationNeeded()} intentionally does nothing, so a redraw can
 * never cause provider traffic. The owning view pushes a new model whenever the
 * status changed. An equal model is not rendered again, and a new model
 * keeps the selected rows and the top row by fund reference.
 */
public class LookThroughDataStatusPane implements InformationPanePage
{
    /**
     * Input of the page: the status model plus the decomposed share text per
     * fund, keyed by the identifiers of the fund instrument, and the
     * identifiers of the held securities that the result classifies as
     * something other than a fund. Both come from the calculation result,
     * which the status model does not know.
     */
    public record Input(Model status, Map<Map<String, String>, String> decomposedShares,
                    Set<Map<String, String>> directlyClassified, Optional<String> companyData,
                    Optional<String> assumedCompanies)
    {
        public Input
        {
            Objects.requireNonNull(status);
            decomposedShares = Map.copyOf(decomposedShares);
            directlyClassified = Set.copyOf(directlyClassified);
            Objects.requireNonNull(companyData);
            Objects.requireNonNull(assumedCompanies);
        }

        public Input(Model status, Map<Map<String, String>, String> decomposedShares,
                        Set<Map<String, String>> directlyClassified, Optional<String> companyData)
        {
            this(status, decomposedShares, directlyClassified, companyData, Optional.empty());
        }

        public Input(Model status, Map<Map<String, String>, String> decomposedShares,
                        Set<Map<String, String>> directlyClassified)
        {
            this(status, decomposedShares, directlyClassified, Optional.empty());
        }

        public Input(Model status, Map<Map<String, String>, String> decomposedShares)
        {
            this(status, decomposedShares, Set.of());
        }

        /** The rows of the pane, without directly classified securities. */
        public List<FundStatus> rows()
        {
            return LookThroughDataStatus.listed(status, directlyClassified);
        }
    }

    private Composite container;
    private CLabel summaryLabel;
    private CLabel warningLabel;
    private Label emptyLabel;
    private TableViewer viewer;
    private Input rendered;
    private boolean hasRendered;
    private List<Object> renderedVisible = List.of();

    @Override
    public String getLabel()
    {
        return Messages.LookThroughStatusPaneTitle;
    }

    @Override
    public Control createViewControl(Composite parent)
    {
        container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).margins(2, 2).applyTo(container);

        summaryLabel = createWrappingCLabel(container);
        warningLabel = createWrappingCLabel(container);
        warningLabel.setImage(Images.WARNING.image());
        emptyLabel = createWrappingLabel(container);

        var tableContainer = new Composite(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(tableContainer);
        tableContainer.setLayout(new FillLayout());

        viewer = new TableViewer(tableContainer, SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        ColumnViewerToolTipSupport.enableFor(viewer);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setComparer(new FundComparer());
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

    private CLabel createWrappingCLabel(Composite parent)
    {
        var label = new CLabel(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(label);
        return label;
    }

    private void addColumns()
    {
        addColumn(Messages.LookThroughStatusColumnFund, SWT.LEFT, 220, FundStatus::fundLabel, null);
        addColumn(Messages.LookThroughStatusColumnStatus, SWT.LEFT, 240, FundStatus::statusText, this::healthImage);
        addColumn(Messages.LookThroughStatusColumnRetrieved, SWT.LEFT, 130,
                        status -> status.retrievedDateText().orElse(""), null); //$NON-NLS-1$
        addColumn(Messages.LookThroughStatusColumnShare, SWT.RIGHT, 110, this::shareText, null);
    }

    private void addColumn(String title, int style, int width, Function<FundStatus, String> text,
                    Function<FundStatus, Image> image)
    {
        var column = new TableColumn(viewer.getTable(), style);
        column.setText(title);
        column.setWidth(width);

        new TableViewerColumn(viewer, column).setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getToolTipText(Object element)
            {
                return ((FundStatus) element).tooltipText();
            }

            @Override
            public String getText(Object element)
            {
                return text.apply((FundStatus) element);
            }

            @Override
            public Image getImage(Object element)
            {
                return image != null ? image.apply((FundStatus) element) : null;
            }
        });
    }

    private Image healthImage(FundStatus status)
    {
        return switch (status.health())
        {
            case OK -> Images.OK.image();
            case WARNING -> Images.WARNING.image();
            case ERROR -> Images.ERROR.image();
            case PENDING -> Images.CLOCK.image();
            case NONE -> null;
            default -> null;
        };
    }

    private String shareText(FundStatus status)
    {
        var input = rendered;
        if (input == null)
            return ""; //$NON-NLS-1$
        return LookThroughDataStatus.shareText(status, input.decomposedShares());
    }

    @Override
    public void setInput(Object input)
    {
        if (input instanceof Input status)
            render(status);
        else if (input instanceof Model status)
            render(new Input(status, Map.of()));
        else if (input == null)
            render(null);
        // Any other input belongs to a different selection and is ignored.
    }

    private void render(Input input)
    {
        if (viewer == null || viewer.getControl().isDisposed())
            return;

        // The fund ages move with the clock, so models are compared by what
        // the pane shows; an unchanged display keeps the table untouched.
        var visible = visible(input);
        rendered = input;
        if (hasRendered && visible.equals(renderedVisible))
            return;
        hasRendered = true;
        renderedVisible = visible;

        var status = input != null ? input.status() : null;
        setRows(input != null ? input.rows() : List.of());

        var line = status != null && input != null
                        ? LookThroughDataStatus.summaryLine(status, input.companyData(), input.assumedCompanies())
                        : null;
        setText(summaryLabel, line != null ? line.text() : ""); //$NON-NLS-1$
        summaryLabel.setToolTipText(line != null ? line.tooltip() : null);
        setText(warningLabel, line != null ? line.warningText().orElse("") : ""); //$NON-NLS-1$
        warningLabel.setToolTipText(line != null ? line.warningTooltip().orElse(null) : null);
        setText(emptyLabel, status == null ? Messages.LookThroughStatusPaneEmpty : ""); //$NON-NLS-1$

        container.layout(true);
    }

    private List<Object> visible(Input input)
    {
        if (input == null)
            return List.of();
        var status = input.status();
        var line = LookThroughDataStatus.summaryLine(status, input.companyData(), input.assumedCompanies());
        var visible = new ArrayList<Object>();
        visible.add(line.text());
        visible.add(line.tooltip());
        visible.add(line.warningText());
        visible.add(line.warningTooltip());
        for (var fund : input.rows())
            visible.add(List.of(fund.reference(), fund.fundLabel(), fund.health(), fund.statusText(),
                            fund.refreshPending(), fund.retrievedDateText(), fund.tooltipText(),
                            LookThroughDataStatus.shareText(fund, input.decomposedShares())));
        return visible;
    }

    private void setRows(List<FundStatus> funds)
    {
        var table = viewer.getTable();
        var selected = new ArrayList<FundRef>();
        for (var element : viewer.getStructuredSelection().toList())
            if (element instanceof FundStatus status)
                selected.add(status.reference());
        var top = table.getTopIndex();

        table.setRedraw(false);
        try
        {
            viewer.setInput(funds);
            // Funds that are gone are dropped from the selection silently.
            viewer.setSelection(new StructuredSelection(
                            funds.stream().filter(status -> selected.contains(status.reference())).toList()));
            if (top > 0 && top < table.getItemCount())
                table.setTopIndex(top);
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    private void setText(Label label, String text)
    {
        label.setText(text);
        setVisible(label, text);
    }

    private void setText(CLabel label, String text)
    {
        label.setText(text);
        setVisible(label, text);
    }

    private void setVisible(Control control, String text)
    {
        boolean visible = !text.isEmpty();
        control.setVisible(visible);
        ((GridData) control.getLayoutData()).exclude = !visible;
    }

    /** Matches fund rows by fund reference, other elements by equality. */
    private static final class FundComparer implements IElementComparer
    {
        @Override
        public boolean equals(Object a, Object b)
        {
            if (a instanceof FundStatus first && b instanceof FundStatus second)
                return first.reference().equals(second.reference());
            return Objects.equals(a, b);
        }

        @Override
        public int hashCode(Object element)
        {
            return element instanceof FundStatus status ? status.reference().hashCode() : Objects.hashCode(element);
        }
    }

    @Override
    public void onRecalculationNeeded()
    {
        // Deliberately empty: a recalculation must never trigger a request.
        // The owning view pushes a new status when the model changed.
    }
}
