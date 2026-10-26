package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * the restart limitation in its tooltip. The table lists each fund with a
 * status icon, the retrieval date and the decomposed share; all other facts
 * are in the row tooltip.
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
     * fund, keyed by the identifiers of the fund instrument. The share comes
     * from the calculation result, which the status model does not know.
     */
    public record Input(Model status, Map<Map<String, String>, String> decomposedShares)
    {
        public Input
        {
            Objects.requireNonNull(status);
            decomposedShares = Map.copyOf(decomposedShares);
        }
    }

    private Composite container;
    private Label storageLabel;
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
        GridLayoutFactory.fillDefaults().numColumns(1).margins(5, 5).applyTo(container);

        storageLabel = createWrappingLabel(container);
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

    private void addColumns()
    {
        addColumn(Messages.LookThroughStatusColumnFund, SWT.LEFT, 220, FundStatus::fundLabel, null);
        addColumn("", SWT.CENTER, 28, status -> "", this::healthImage); //$NON-NLS-1$
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
        return input.decomposedShares().getOrDefault(status.reference().fund().identifiers(), ""); //$NON-NLS-1$
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
        setRows(status != null ? status.funds() : List.of());

        setText(storageLabel, status != null ? status.storageText() : ""); //$NON-NLS-1$
        storageLabel.setToolTipText(status != null ? storageTooltip(status) : null);
        setText(emptyLabel, status == null ? Messages.LookThroughStatusPaneEmpty : ""); //$NON-NLS-1$

        container.layout(true);
    }

    private List<Object> visible(Input input)
    {
        if (input == null)
            return List.of();
        var status = input.status();
        var visible = new ArrayList<Object>();
        visible.add(status.storageText());
        visible.add(storageTooltip(status));
        for (var fund : status.funds())
            visible.add(List.of(fund.reference(), fund.fundLabel(), fund.health(), fund.refreshPending(),
                            fund.retrievedDateText(), fund.tooltipText(), input.decomposedShares()
                                            .getOrDefault(fund.reference().fund().identifiers(), ""))); //$NON-NLS-1$
        return visible;
    }

    /** The mode and the restart limitation, which the single line leaves out. */
    private String storageTooltip(Model status)
    {
        var lines = new ArrayList<String>();
        lines.add(status.modeText());
        status.limitationText().ifPresent(lines::add);
        return String.join("\n\n", lines); //$NON-NLS-1$
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
        boolean visible = !text.isEmpty();
        label.setVisible(visible);
        ((GridData) label.getLayoutData()).exclude = !visible;
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
