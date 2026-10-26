package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swtchart.ICustomPaintListener;
import org.eclipse.swtchart.IAxis;
import org.eclipse.swtchart.IBarSeries;
import org.eclipse.swtchart.ISeries.SeriesType;
import org.eclipse.swtchart.LineStyle;
import org.eclipse.swtchart.Range;

import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.chart.PlainChart;
import name.abuchen.portfolio.ui.util.format.ThousandsNumberFormat;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartCompany;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartModel;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.RowKey;

/**
 * Horizontal stacked bars of the largest companies: the direct part and the
 * part through funds. The largest company is on top. A selected company is
 * highlighted with a band across its row.
 */
class LookThroughBarChart
{
    private static final String DIRECT = "direct"; //$NON-NLS-1$
    private static final String INDIRECT = "indirect"; //$NON-NLS-1$
    private static final int LABEL_LENGTH = 28;

    private final PlainChart chart;

    /** Companies in display order: the first one is at the bottom. */
    private List<ChartCompany> displayed = List.of();
    private ChartModel model;
    private String currencyCode = ""; //$NON-NLS-1$
    private RowKey selectedKey;

    LookThroughBarChart(Composite parent, Consumer<RowKey> onSelect)
    {
        chart = new PlainChart(parent, SWT.NONE);
        chart.setData(UIConstants.CSS.CLASS_NAME, "chart"); //$NON-NLS-1$
        chart.setOrientation(SWT.VERTICAL);
        chart.getTitle().setVisible(false);
        chart.getLegend().setVisible(false);

        IAxis categoryAxis = chart.getAxisSet().getXAxis(0);
        categoryAxis.getTitle().setVisible(false);
        categoryAxis.getGrid().setStyle(LineStyle.NONE);
        categoryAxis.setCategorySeries(new String[0]);
        categoryAxis.enableCategory(true);

        IAxis valueAxis = chart.getAxisSet().getYAxis(0);
        valueAxis.getTick().setFormat(new ThousandsNumberFormat());

        chart.getPlotArea().addCustomPaintListener(new ICustomPaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                paintSelection(e.gc);
            }

            @Override
            public boolean drawBehindSeries()
            {
                return false;
            }
        });

        var plot = chart.getPlotArea().getControl();
        plot.addListener(SWT.MouseMove, event -> {
            var index = indexAt(event.y);
            var tooltip = index >= 0 ? LookThroughChartPane.tooltip(displayed.get(index), currencyCode) : null;
            if (!Objects.equals(tooltip, plot.getToolTipText()))
                plot.setToolTipText(tooltip);
        });
        plot.addListener(SWT.MouseUp, event -> {
            if (event.button != 1)
                return;
            var index = indexAt(event.y);
            if (index >= 0)
                onSelect.accept(displayed.get(index).key());
        });
    }

    Control getControl()
    {
        return chart;
    }

    void setModel(ChartModel model, String currencyCode)
    {
        if (model.equals(this.model) && currencyCode.equals(this.currencyCode))
            return;
        this.model = model;
        this.currencyCode = currencyCode;

        var seriesSet = chart.getSeriesSet();
        for (var id : new String[] { DIRECT, INDIRECT })
            if (seriesSet.getSeries(id) != null)
                seriesSet.deleteSeries(id);

        var top = new ArrayList<>(model.top());
        Collections.reverse(top);
        displayed = top;

        chart.getAxisSet().getYAxis(0).getTitle().setText(currencyCode);
        chart.getAxisSet().getYAxis(0).getTitle().setVisible(!currencyCode.isEmpty());

        if (top.isEmpty())
        {
            chart.getAxisSet().getXAxis(0).setCategorySeries(new String[0]);
            chart.redraw();
            return;
        }

        var categories = new String[top.size()];
        var direct = new double[top.size()];
        var indirect = new double[top.size()];
        double maximum = 0;
        for (int ii = 0; ii < top.size(); ii++)
        {
            var company = top.get(ii);
            categories[ii] = shorten(company.label());
            direct[ii] = company.direct().doubleValue() / Values.Amount.divider();
            indirect[ii] = company.indirect().doubleValue() / Values.Amount.divider();
            maximum = Math.max(maximum, direct[ii] + indirect[ii]);
        }
        chart.getAxisSet().getXAxis(0).setCategorySeries(categories);

        addSeries(DIRECT, Messages.LookThroughColumnDirect, direct, LookThroughChartPane.directColor());
        addSeries(INDIRECT, Messages.LookThroughColumnIndirect, indirect, LookThroughChartPane.indirectColor());

        chart.getAxisSet().adjustRange();
        chart.getAxisSet().getYAxis(0).setRange(new Range(0, maximum * 1.05));
        chart.redraw();
    }

    private void addSeries(String id, String label, double[] values, org.eclipse.swt.graphics.Color color)
    {
        var series = (IBarSeries<?>) chart.getSeriesSet().createSeries(SeriesType.BAR, id);
        series.setDescription(label);
        series.setYSeries(values);
        series.setBarColor(color);
        series.setBarPadding(25);
        series.enableStack(true);
    }

    /** Highlights the bar of the company without notifying anybody. */
    void setSelected(RowKey key)
    {
        if (Objects.equals(selectedKey, key))
            return;
        selectedKey = key;
        chart.getPlotArea().getControl().redraw();
    }

    private static String shorten(String label)
    {
        return label.length() <= LABEL_LENGTH ? label : label.substring(0, LABEL_LENGTH - 1).stripTrailing() + "…"; //$NON-NLS-1$
    }

    /** The row whose bar center is nearest to the position, or -1. */
    private int indexAt(int y)
    {
        var series = (IBarSeries<?>) chart.getSeriesSet().getSeries(DIRECT);
        if (series == null)
            return -1;
        var bounds = series.getBounds();
        if (bounds == null || bounds.length != displayed.size() || bounds.length == 0)
            return -1;

        int best = -1;
        int distance = Integer.MAX_VALUE;
        int pitch = Integer.MAX_VALUE;
        for (int ii = 0; ii < bounds.length; ii++)
        {
            if (bounds[ii] == null)
                return -1;
            int center = bounds[ii].y + bounds[ii].height / 2;
            if (Math.abs(y - center) < distance)
            {
                distance = Math.abs(y - center);
                best = ii;
            }
            if (ii > 0)
                pitch = Math.min(pitch, Math.abs(center - (bounds[ii - 1].y + bounds[ii - 1].height / 2)));
        }
        if (pitch == Integer.MAX_VALUE)
            pitch = bounds[0].height * 2;
        return distance <= Math.max(1, pitch / 2) ? best : -1;
    }

    private void paintSelection(GC gc)
    {
        if (selectedKey == null)
            return;
        var series = (IBarSeries<?>) chart.getSeriesSet().getSeries(DIRECT);
        if (series == null)
            return;
        var bounds = series.getBounds();
        if (bounds == null || bounds.length != displayed.size())
            return;

        for (int ii = 0; ii < displayed.size(); ii++)
        {
            if (!displayed.get(ii).key().equals(selectedKey) || bounds[ii] == null)
                continue;

            int width = chart.getPlotArea().getControl().getBounds().width;
            int y = bounds[ii].y - 3;
            int height = bounds[ii].height + 6;

            var foreground = gc.getForeground();
            var background = gc.getBackground();
            var alpha = gc.getAlpha();

            gc.setAlpha(45);
            gc.setBackground(Colors.theme().defaultForeground());
            gc.fillRectangle(0, y, width, height);
            gc.setAlpha(255);
            gc.setForeground(Colors.theme().defaultForeground());
            gc.drawRectangle(0, y, width - 1, height);

            gc.setAlpha(alpha);
            gc.setForeground(foreground);
            gc.setBackground(background);
            return;
        }
    }
}
