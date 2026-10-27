package name.abuchen.portfolio.ui.views.lookthrough;

import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.RowLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Coverage;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Segment;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.SegmentKind;

/**
 * Horizontal bar that splits the securities value into the coverage
 * categories, with a wrapping legend below. Each segment and legend entry has
 * a tooltip with the exact amount and share. A nonzero segment keeps a
 * minimum width, so a small unresolved remainder never disappears. Without a
 * denominator the bar is replaced by a short notice.
 */
public class LookThroughCoverageBar
{
    private static final int BAR_HEIGHT = 10;
    private static final int MINIMUM_SEGMENT_WIDTH = 3;
    private static final int GAP = 2;
    private static final int SWATCH_SIZE = 10;

    private final Composite container;
    private final Canvas bar;
    private final Composite legend;
    private final Label notice;

    private List<Segment> segments = List.of();

    public LookThroughCoverageBar(Composite parent)
    {
        container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).spacing(0, 4).applyTo(container);

        bar = new Canvas(container, SWT.DOUBLE_BUFFERED);
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, BAR_HEIGHT).applyTo(bar);
        bar.addPaintListener(event -> paint(event.gc));
        bar.addListener(SWT.MouseMove, event -> {
            var segment = segmentAt(event.x);
            var tooltip = segment.map(LookThroughCoverageBar::tooltip).orElse(null);
            if (!Objects.equals(tooltip, bar.getToolTipText()))
                bar.setToolTipText(tooltip);
        });
        bar.addListener(SWT.Resize, event -> bar.redraw());

        legend = new Composite(container, SWT.NONE);
        RowLayoutFactory.fillDefaults().wrap(true).spacing(16).applyTo(legend);
        GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(legend);

        notice = new Label(container, SWT.WRAP);
        notice.setText(Messages.LookThroughCoverageUnavailable);
        notice.setForeground(Colors.theme().grayForeground());
        GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(notice);

        setCoverage(Optional.empty());
    }

    public Control getControl()
    {
        return container;
    }

    public void setCoverage(Optional<Coverage> coverage)
    {
        segments = coverage.map(Coverage::segments).orElse(List.of());

        for (var child : legend.getChildren())
            child.dispose();
        if (coverage.isPresent())
        {
            var caption = new Label(legend, SWT.NONE);
            caption.setText(Messages.LookThroughCoverageCaption);
            caption.setForeground(Colors.theme().grayForeground());
            for (var segment : coverage.get().legend())
                createLegendEntry(segment);
        }

        var present = coverage.isPresent();
        setVisible(bar, present);
        setVisible(legend, present);
        setVisible(notice, !present);

        bar.setToolTipText(null);
        bar.redraw();
        container.layout(true, true);
    }

    private void createLegendEntry(Segment segment)
    {
        var entry = new Composite(legend, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(4, 0).applyTo(entry);

        var swatch = new Canvas(entry, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).hint(SWATCH_SIZE, SWATCH_SIZE).applyTo(swatch);
        swatch.addPaintListener(event -> {
            event.gc.setBackground(color(segment.kind()));
            event.gc.fillRectangle(0, 0, SWATCH_SIZE, SWATCH_SIZE);
        });

        var label = new Label(entry, SWT.NONE);
        label.setText(MessageFormat.format("{0} {1}", segment.label(), segment.shareText())); //$NON-NLS-1$

        var tooltip = tooltip(segment);
        entry.setToolTipText(tooltip);
        swatch.setToolTipText(tooltip);
        label.setToolTipText(tooltip);
    }

    private static String tooltip(Segment segment)
    {
        return MessageFormat.format("{0}: {1} ({2})", segment.label(), segment.valueText(), segment.shareText()); //$NON-NLS-1$
    }

    private void setVisible(Control control, boolean visible)
    {
        control.setVisible(visible);
        ((GridData) control.getLayoutData()).exclude = !visible;
    }

    private int[] widths()
    {
        var area = bar.getClientArea();
        int count = 0;
        for (var segment : segments)
            if (segment.nonzero())
                count++;
        int available = area.width - Math.max(0, count - 1) * GAP;
        return new Coverage(segments).widths(available, MINIMUM_SEGMENT_WIDTH);
    }

    private void paint(GC gc)
    {
        if (segments.isEmpty())
            return;

        var area = bar.getClientArea();
        gc.setAntialias(SWT.ON);

        var widths = widths();
        int x = 0;
        for (int ii = 0; ii < widths.length; ii++)
        {
            if (widths[ii] <= 0)
                continue;
            gc.setBackground(color(segments.get(ii).kind()));
            gc.fillRectangle(x, 0, widths[ii], area.height);
            x += widths[ii] + GAP;
        }
    }

    private Optional<Segment> segmentAt(int position)
    {
        var widths = widths();
        int x = 0;
        for (int ii = 0; ii < widths.length; ii++)
        {
            if (widths[ii] <= 0)
                continue;
            if (position >= x && position < x + widths[ii] + GAP)
                return Optional.of(segments.get(ii));
            x += widths[ii] + GAP;
        }
        return Optional.empty();
    }

    /**
     * Mid-tone colors that read on light and dark backgrounds. Companies and
     * ungrouped equities share a hue, the unresolved part uses the theme's
     * warning color.
     */
    private static Color color(SegmentKind kind)
    {
        return switch (kind)
        {
            case COMPANIES -> Colors.getColor(31, 119, 180); // 1F77B4
            case UNGROUPED_EQUITY -> Colors.getColor(126, 178, 221); // 7EB2DD
            case CASH_OR_OTHER -> Colors.getColor(154, 165, 177); // 9AA5B1
            case UNVERIFIED -> Colors.getColor(148, 103, 189); // 9467BD
            case UNRESOLVED -> Colors.theme().warningForeground();
            default -> Colors.OTHER_CATEGORY;
        };
    }
}
