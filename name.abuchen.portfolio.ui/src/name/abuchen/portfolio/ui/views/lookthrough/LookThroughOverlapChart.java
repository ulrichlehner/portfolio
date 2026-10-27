package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import name.abuchen.portfolio.ui.util.Colors;

/**
 * Triangle of the decomposed funds: every pair appears once, below the
 * diagonal, and there are no diagonal cells. A cell is shaded from the
 * background color towards the accent color by its overlap and shows the
 * overlap and the number of shared companies as text. The axis labels are
 * shortened, the tooltip has the full names.
 */
class LookThroughOverlapChart
{
    private static final int MARGIN = 8;
    private static final int GAP = 6;
    private static final int MAX_CELL = 96;
    private static final int MIN_CELL = 48;
    private static final String ELLIPSIS = "…"; //$NON-NLS-1$

    private final Canvas canvas;

    private LookThroughOverlapModel model = LookThroughOverlapModel.empty();
    private int hoverRow = -1;
    private int hoverColumn = -1;

    // layout of the last paint
    private int originX;
    private int originY;
    private int cell = MAX_CELL;
    private int leftWidth;
    private int topHeight;

    LookThroughOverlapChart(Composite parent)
    {
        canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED);
        canvas.setBackground(Colors.theme().defaultBackground());
        canvas.addPaintListener(event -> paint(event.gc));
        canvas.addListener(SWT.MouseMove, event -> {
            // grid index; the rows show the funds 1..n-1, the columns 0..n-2
            int row = indexAt(event.y, originY);
            int column = indexAt(event.x, originX);
            String tooltip = null;
            if (row >= 0 && column >= 0 && column <= row)
                tooltip = model.cell(row + 1, column).tooltip();
            else if (column >= 0 && event.y < originY && event.y >= MARGIN)
                tooltip = model.name(column);
            else if (row >= 0 && event.x < originX && event.x >= MARGIN)
                tooltip = model.name(row + 1);
            if (!Objects.equals(tooltip, canvas.getToolTipText()))
                canvas.setToolTipText(tooltip);
            if (row != hoverRow || column != hoverColumn)
            {
                hoverRow = row;
                hoverColumn = column;
                canvas.redraw();
            }
        });
        canvas.addListener(SWT.MouseExit, event -> {
            hoverRow = -1;
            hoverColumn = -1;
            canvas.redraw();
        });
    }

    Control getControl()
    {
        return canvas;
    }

    void setModel(LookThroughOverlapModel model)
    {
        if (model == this.model)
            return;
        this.model = model;
        hoverRow = -1;
        hoverColumn = -1;
        canvas.setToolTipText(null);
        canvas.redraw();
    }

    /** Index of the row or column at the position, or -1. */
    private int indexAt(int position, int origin)
    {
        if (model.isEmpty() || position < origin)
            return -1;
        int index = (position - origin) / cell;
        return index < model.size() - 1 ? index : -1;
    }

    private void paint(GC gc)
    {
        var theme = Colors.theme();
        gc.setBackground(theme.defaultBackground());
        var area = canvas.getClientArea();
        gc.fillRectangle(area);
        if (model.isEmpty())
            return;

        int count = model.size() - 1;
        var excluded = model.excludedText();
        int lineHeight = gc.getFontMetrics().getHeight();
        int footer = excluded.isPresent() ? lineHeight + GAP : 0;

        int widest = 0;
        for (int ii = 0; ii <= count; ii++)
            widest = Math.max(widest, gc.textExtent(model.name(ii)).x);
        leftWidth = Math.min(widest, Math.max(60, area.width / 4));
        topHeight = Math.min(widest, Math.max(60, area.height / 4));

        int availableWidth = area.width - 2 * MARGIN - leftWidth - GAP;
        int availableHeight = area.height - 2 * MARGIN - topHeight - GAP - footer;
        cell = Math.max(MIN_CELL, Math.min(MAX_CELL, Math.min(availableWidth / count, availableHeight / count)));
        originX = MARGIN + leftWidth + GAP;
        originY = MARGIN + topHeight + GAP;

        var background = theme.defaultBackground().getRGB();
        var accent = LookThroughChartPane.indirectColor().getRGB();
        var foreground = theme.defaultForeground().getRGB();
        var grid = Colors.getColor(Colors.interpolate(background, foreground, 0.25f));

        gc.setForeground(theme.defaultForeground());
        for (int ii = 0; ii < count; ii++)
        {
            // row label (funds 1..n-1), right aligned and vertically centered
            var rowLabel = shorten(gc, model.name(ii + 1), leftWidth);
            int rowWidth = gc.textExtent(rowLabel).x;
            gc.drawText(rowLabel, originX - GAP - rowWidth, originY + ii * cell + (cell - lineHeight) / 2, true);
            drawColumnLabel(gc, shorten(gc, model.name(ii), topHeight), originX + ii * cell + (cell - lineHeight) / 2,
                            originY - GAP);
        }

        for (int row = 0; row < count; row++)
        {
            for (int column = 0; column <= row; column++)
            {
                int x = originX + column * cell;
                int y = originY + row * cell;
                var entry = model.cell(row + 1, column);
                var fill = Colors.getColor(Colors.interpolate(background, accent, (float) (0.08 + 0.92 * entry.shade())));
                gc.setBackground(fill);
                gc.fillRectangle(x, y, cell, cell);
                gc.setForeground(grid);
                gc.drawRectangle(x, y, cell, cell);

                gc.setForeground(Colors.getTextColor(fill));
                gc.setBackground(fill);
                var first = shorten(gc, entry.text(), cell - 4);
                var second = shorten(gc, entry.detail(), cell - 4);
                int top = y + (cell - 2 * lineHeight) / 2;
                gc.drawText(first, x + (cell - gc.textExtent(first).x) / 2, top, true);
                gc.drawText(second, x + (cell - gc.textExtent(second).x) / 2, top + lineHeight, true);
            }
        }

        if (hoverRow >= 0 && hoverColumn >= 0 && hoverColumn <= hoverRow)
        {
            gc.setForeground(theme.defaultForeground());
            gc.setLineWidth(2);
            gc.drawRectangle(originX + hoverColumn * cell, originY + hoverRow * cell, cell, cell);
            gc.setLineWidth(1);
        }

        excluded.ifPresent(text -> {
            gc.setForeground(theme.grayForeground());
            gc.setBackground(theme.defaultBackground());
            gc.drawText(shorten(gc, text, area.width - 2 * MARGIN), MARGIN, originY + count * cell + GAP, true);
        });
    }

    /** Draws the label upwards, ending at the bottom edge of the header. */
    private void drawColumnLabel(GC gc, String text, int x, int bottom)
    {
        var transform = new Transform(gc.getDevice());
        try
        {
            transform.translate(x, bottom);
            transform.rotate(-90);
            gc.setTransform(transform);
            gc.setBackground(Colors.theme().defaultBackground());
            gc.setForeground(Colors.theme().defaultForeground());
            gc.drawText(text, 0, 0, true);
        }
        finally
        {
            gc.setTransform(null);
            transform.dispose();
        }
    }

    /** Cuts the text with an ellipsis so that it fits into the width. */
    static String shorten(GC gc, String text, int width)
    {
        if (gc.textExtent(text).x <= width)
            return text;
        int length = text.length();
        while (length > 1 && gc.textExtent(text.substring(0, length - 1) + ELLIPSIS).x > width)
            length--;
        return text.substring(0, Math.max(0, length - 1)) + ELLIPSIS;
    }
}
