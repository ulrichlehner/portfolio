package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.e4.ui.services.IStylingEngine;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.RowLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.snapshot.lookthrough.FundOverlap;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartCompany;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartModel;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ContentState;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.RowKey;

/**
 * Holds the company charts and shows one of them, or a message: while the
 * first result is being calculated, or when a calculated result has no
 * company exposure. Only the visible chart is updated. The pane displays the
 * model it was given and never starts a retrieval.
 */
class LookThroughChartPane
{
    enum Kind
    {
        TREEMAP, TOP_COMPANIES, OVERLAP;

        String label()
        {
            return switch (this)
            {
                case TREEMAP -> Messages.LookThroughChartTreemap;
                case TOP_COMPANIES -> Messages.LookThroughChartTopCompanies;
                case OVERLAP -> Messages.LookThroughChartOverlap;
            };
        }
    }

    private static final int SWATCH_SIZE = 10;
    private static final int SCALE_WIDTH = 80;

    private final Composite container;
    private final Composite stackArea;
    private final StackLayout stack = new StackLayout();
    private final LookThroughTreeMap treeMap;
    private final LookThroughBarChart barChart;
    private final LookThroughOverlapChart overlapChart;
    private final Composite emptyArea;
    private final Label emptyMessage;
    private final Composite legend;

    private Kind kind = Kind.TREEMAP;
    private ChartModel model = new ChartModel(List.of());
    private LookThroughOverlapModel overlap = LookThroughOverlapModel.empty();
    private String currencyCode = ""; //$NON-NLS-1$
    private ContentState state = ContentState.CALCULATED;
    private Kind legendKind;

    LookThroughChartPane(Composite parent, IStylingEngine stylingEngine, Consumer<RowKey> onSelect)
    {
        container = new Composite(parent, SWT.NONE);
        container.setBackground(Colors.theme().defaultBackground());
        GridLayoutFactory.fillDefaults().numColumns(1).spacing(0, 4).applyTo(container);

        stackArea = new Composite(container, SWT.NONE);
        stackArea.setBackground(Colors.theme().defaultBackground());
        stackArea.setLayout(stack);
        GridDataFactory.fillDefaults().grab(true, true).hint(100, 100).applyTo(stackArea);

        treeMap = new LookThroughTreeMap(stackArea, stylingEngine, onSelect);
        barChart = new LookThroughBarChart(stackArea, onSelect);
        overlapChart = new LookThroughOverlapChart(stackArea);

        emptyArea = new Composite(stackArea, SWT.NONE);
        emptyArea.setBackground(Colors.theme().defaultBackground());
        GridLayoutFactory.fillDefaults().margins(8, 8).applyTo(emptyArea);
        emptyMessage = new Label(emptyArea, SWT.WRAP | SWT.CENTER);
        emptyMessage.setForeground(Colors.theme().grayForeground());
        emptyMessage.setBackground(Colors.theme().defaultBackground());
        GridDataFactory.fillDefaults().grab(true, true).align(SWT.FILL, SWT.CENTER).hint(100, SWT.DEFAULT)
                        .applyTo(emptyMessage);

        legend = new Composite(container, SWT.NONE);
        legend.setBackground(Colors.theme().defaultBackground());
        RowLayoutFactory.fillDefaults().spacing(16).margins(4, 2).applyTo(legend);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(legend);

        update();
    }

    Control getControl()
    {
        return container;
    }

    Kind getKind()
    {
        return kind;
    }

    void setKind(Kind kind)
    {
        if (this.kind == kind)
            return;
        this.kind = kind;
        update();
    }

    void setModel(ChartModel model, String currencyCode)
    {
        setModel(model, currencyCode, ContentState.CALCULATED);
    }

    /**
     * Shows the model. The message for a missing company exposure is only
     * shown for a calculated result; before the first result the pane says
     * that the breakdown is being calculated.
     */
    void setModel(ChartModel model, String currencyCode, ContentState state)
    {
        this.model = model;
        this.currencyCode = currencyCode;
        this.state = state;
        update();
    }

    /**
     * Sets the overlap of the funds; it is shown with the next update. Null
     * if there is no result.
     */
    void setOverlap(FundOverlap overlap)
    {
        this.overlap = LookThroughOverlapModel.of(overlap);
    }

    /**
     * Highlights the company or group in the charts. The bar chart of a
     * grouping shows groups, so it falls back to the group of the company.
     * The selection is not reported back.
     */
    void setSelected(RowKey key, RowKey groupKey)
    {
        treeMap.setSelected(key);
        barChart.setSelected(key, groupKey);
    }

    private void update()
    {
        if (container.isDisposed())
            return;

        Control top;
        boolean showLegend = !model.isEmpty() && kind != Kind.OVERLAP;
        if (kind == Kind.OVERLAP && overlap.isEmpty())
        {
            emptyMessage.setText(switch (state)
            {
                case CALCULATING -> Messages.LookThroughLabelCalculating;
                case CALCULATED -> Messages.LookThroughOverlapNeedTwoFunds;
                default -> ""; //$NON-NLS-1$
            });
            top = emptyArea;
        }
        else if (kind == Kind.OVERLAP)
        {
            overlapChart.setModel(overlap);
            top = overlapChart.getControl();
        }
        else if (model.isEmpty())
        {
            var message = switch (state)
            {
                case CALCULATING -> Messages.LookThroughLabelCalculating;
                case CALCULATED -> Messages.LookThroughChartEmpty;
                default -> ""; //$NON-NLS-1$
            };
            emptyMessage.setText(message);
            top = emptyArea;
        }
        else if (kind == Kind.TREEMAP)
        {
            treeMap.setModel(model, currencyCode);
            top = treeMap.getControl();
        }
        else
        {
            barChart.setModel(model, currencyCode);
            top = barChart.getControl();
        }

        if (showLegend)
            updateLegend();
        stack.topControl = top;
        ((org.eclipse.swt.layout.GridData) legend.getLayoutData()).exclude = !showLegend;
        legend.setVisible(showLegend);
        container.layout(true, true);
    }

    /** The treemap legend is a color scale, the bar chart legend has two swatches. */
    private void updateLegend()
    {
        if (legendKind == kind)
            return;
        legendKind = kind;

        for (var child : legend.getChildren())
            child.dispose();

        var caption = new Label(legend, SWT.NONE);
        caption.setBackground(Colors.theme().defaultBackground());
        caption.setForeground(Colors.theme().grayForeground());

        if (kind == Kind.TREEMAP)
        {
            caption.setText(Messages.LookThroughChartTileColor);
            createScale();
        }
        else
        {
            caption.setText(Messages.LookThroughChartBarColor);
            createLegendEntry(Messages.LookThroughChartHeldDirectly, directColor());
            createLegendEntry(Messages.LookThroughChartThroughFunds, indirectColor());
        }
        legend.layout(true, true);
    }

    /** Gradient from the color of directly held tiles to the color of tiles through funds. */
    private void createScale()
    {
        var entry = new Composite(legend, SWT.NONE);
        entry.setBackground(Colors.theme().defaultBackground());
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(4, 0).applyTo(entry);

        createText(entry, Messages.LookThroughChartHeldDirectly);

        var scale = new Canvas(entry, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).hint(SCALE_WIDTH, SWATCH_SIZE).applyTo(scale);
        scale.addPaintListener(event -> {
            event.gc.setForeground(directColor());
            event.gc.setBackground(indirectColor());
            event.gc.fillGradientRectangle(0, 0, SCALE_WIDTH, SWATCH_SIZE, false);
        });

        createText(entry, Messages.LookThroughChartThroughFunds);
    }

    private void createText(Composite parent, String text)
    {
        var label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setBackground(Colors.theme().defaultBackground());
        label.setForeground(Colors.theme().defaultForeground());
    }

    private void createLegendEntry(String text, Color color)
    {
        var entry = new Composite(legend, SWT.NONE);
        entry.setBackground(Colors.theme().defaultBackground());
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(4, 0).applyTo(entry);

        var swatch = new Canvas(entry, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).hint(SWATCH_SIZE, SWATCH_SIZE).applyTo(swatch);
        swatch.addPaintListener(event -> {
            event.gc.setBackground(color);
            event.gc.fillRectangle(0, 0, SWATCH_SIZE, SWATCH_SIZE);
        });

        createText(entry, text);
    }

    /** Mid-tone for the part held directly, readable on light and dark themes. */
    static Color directColor()
    {
        return Colors.getColor(44, 160, 44); // 2CA02C
    }

    /** Mid-tone for the part held through funds, the blue of the coverage bar. */
    static Color indirectColor()
    {
        return Colors.getColor(31, 119, 180); // 1F77B4
    }

    /** Blends from the fund color to the direct color by the direct part. */
    static Color tileColor(double directFraction)
    {
        // 20 steps keep the number of cached colors small
        double fraction = Math.round(Math.max(0, Math.min(1, directFraction)) * 20) / 20d;
        var direct = directColor();
        var indirect = indirectColor();
        return Colors.getColor(blend(indirect.getRed(), direct.getRed(), fraction),
                        blend(indirect.getGreen(), direct.getGreen(), fraction),
                        blend(indirect.getBlue(), direct.getBlue(), fraction));
    }

    private static int blend(int from, int to, double fraction)
    {
        return (int) Math.round(from + (to - from) * fraction);
    }

    static String tooltip(ChartCompany company, String currencyCode)
    {
        var total = LookThroughViewPresenter.formatAmount(company.total(), currencyCode)
                        + company.shareText().map(share -> " (" + share + ")").orElse(""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var lines = new ArrayList<String>();
        lines.add(Objects.toString(company.label(), "")); //$NON-NLS-1$
        company.detail().ifPresent(lines::add);
        lines.addAll(List.of(//
                        Messages.LookThroughColumnTotal + ": " + total, //$NON-NLS-1$
                        Messages.LookThroughColumnDirect + ": " //$NON-NLS-1$
                                        + LookThroughViewPresenter.formatAmount(company.direct(), currencyCode),
                        Messages.LookThroughColumnIndirect + ": " //$NON-NLS-1$
                                        + LookThroughViewPresenter.formatAmount(company.indirect(), currencyCode)));
        return String.join("\n", lines); //$NON-NLS-1$
    }
}
