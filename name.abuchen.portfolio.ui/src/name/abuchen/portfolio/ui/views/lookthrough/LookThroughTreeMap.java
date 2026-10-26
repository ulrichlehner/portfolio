package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.e4.ui.services.IStylingEngine;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

import de.engehausen.treemap.IColorProvider;
import de.engehausen.treemap.ILabelProvider;
import de.engehausen.treemap.IRectangle;
import de.engehausen.treemap.IRectangleRenderer;
import de.engehausen.treemap.ITreeModel;
import de.engehausen.treemap.IWeightedTreeModel;
import de.engehausen.treemap.impl.SquarifiedLayout;
import de.engehausen.treemap.swt.TreeMap;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartCompany;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartModel;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.RowKey;

/**
 * Treemap of the companies, sized by total exposure. The tile color blends
 * from the "through funds" color to the "direct" color by the direct part of
 * the company. The selected company gets a thick outline. The treemap never
 * triggers retrieval; it only draws the model it was given.
 */
class LookThroughTreeMap
{
    private static final int PADDING = 4;
    private static final int MINIMUM_LABEL_WIDTH = 24;

    /** The root has no company. */
    private static final class Node
    {
        private final ChartCompany company;
        private final long weight;

        private Node(ChartCompany company, long weight)
        {
            this.company = company;
            this.weight = weight;
        }
    }

    private static final class Tiles implements IWeightedTreeModel<Node>
    {
        private final Node root;
        private final List<Node> children;

        private Tiles(Node root, List<Node> children)
        {
            this.root = root;
            this.children = children;
        }

        @Override
        public Iterator<Node> getChildren(Node item)
        {
            return item == root ? children.iterator() : Collections.emptyIterator();
        }

        @Override
        public Node getParent(Node item)
        {
            return item == root ? null : root;
        }

        @Override
        public Node getRoot()
        {
            return root;
        }

        @Override
        public boolean hasChildren(Node item)
        {
            return item == root && !children.isEmpty();
        }

        @Override
        public long getWeight(Node item)
        {
            return item.weight;
        }
    }

    /**
     * Extends the treemap so that the image can be redrawn without a new
     * layout (the layout would reset the hover selection), restricts the
     * hover selection to company tiles and injects the styled font into the
     * graphics context that draws the image.
     */
    private static final class XTreeMap extends TreeMap<Node>
    {
        private XTreeMap(Composite parent)
        {
            // Without the library's navigation listener: a click selects the
            // company in the table instead of zooming into the tile.
            super(parent, false);
        }

        private void refresh()
        {
            if (rectangles == null || isDisposed())
                return;
            var bounds = getBounds();
            rebuildImage(bounds.width, bounds.height, rectangles);
            redraw();
        }

        private ChartCompany companyAt(int x, int y)
        {
            if (rectangles == null)
                return null;
            var rectangle = findRectangle(x, y);
            return rectangle == null ? null : rectangle.getNode().company;
        }

        @Override
        protected boolean selectRectangle(int x, int y)
        {
            if (selected == null || !selected.contains(x, y))
            {
                var rectangle = findRectangle(x, y);
                selected = rectangle == null || rectangle.getNode().company == null ? null : rectangle;

                redraw();
                if (selected != null && listeners != null)
                {
                    for (int ii = listeners.size() - 1; ii >= 0; ii--)
                        listeners.get(ii).selectionChanged(rectangles, selected, null);
                }
                return false;
            }
            return true;
        }

        @Override
        protected void rebuildImage(int width, int height, ITreeModel<IRectangle<Node>> rects)
        {
            if (width * height <= 0)
                return;

            var display = getDisplay();
            Image result = new Image(display, width, height);
            GC gc = new GC(result);
            gc.setFont(getFont());
            var synth = new Event();
            synth.widget = this;
            synth.display = display;
            synth.gc = gc;
            synth.x = 0;
            synth.y = 0;
            synth.width = width;
            synth.height = height;
            try
            {
                render(new PaintEvent(synth), rects);
            }
            finally
            {
                gc.dispose();
            }
            setImage(result);
        }
    }

    private final XTreeMap treeMap;
    private volatile RowKey selectedKey;
    private ChartModel model;
    private String currencyCode = ""; //$NON-NLS-1$

    LookThroughTreeMap(Composite parent, IStylingEngine stylingEngine, Consumer<RowKey> onSelect)
    {
        treeMap = new XTreeMap(parent);
        if (stylingEngine != null)
            stylingEngine.style(treeMap);

        treeMap.setTreeMapLayout(new SquarifiedLayout<>(10));
        treeMap.setLabelProvider((tiles, rectangle) -> rectangle.getNode().company != null
                        ? rectangle.getNode().company.label()
                        : ""); //$NON-NLS-1$
        treeMap.setRectangleRenderer(new TileRenderer());

        treeMap.addMouseMoveListener(event -> {
            var company = treeMap.companyAt(event.x, event.y);
            var tooltip = company != null ? LookThroughChartPane.tooltip(company, currencyCode) : null;
            if (!Objects.equals(tooltip, treeMap.getToolTipText()))
                treeMap.setToolTipText(tooltip);
        });

        treeMap.addMouseListener(MouseListener.mouseUpAdapter(event -> {
            if (event.button != 1)
                return;
            var company = treeMap.companyAt(event.x, event.y);
            if (company != null)
                onSelect.accept(company.key());
        }));
    }

    Control getControl()
    {
        return treeMap;
    }

    /** Lays out the tiles again, unless the model did not change. */
    void setModel(ChartModel model, String currencyCode)
    {
        this.currencyCode = currencyCode;
        if (model.equals(this.model))
            return;
        this.model = model;

        treeMap.setToolTipText(null);

        var children = new ArrayList<Node>();
        long sum = 0;
        for (var company : model.companies())
        {
            var weight = Math.max(1L, company.total().setScale(0, RoundingMode.HALF_UP).longValue());
            children.add(new Node(company, weight));
            sum += weight;
        }
        if (children.isEmpty())
            return;
        treeMap.setTreeModel(new Tiles(new Node(null, sum), children));
    }

    /** Highlights the tile of the company without notifying anybody. */
    void setSelected(RowKey key)
    {
        if (Objects.equals(selectedKey, key))
            return;
        selectedKey = key;
        treeMap.refresh();
    }

    private final class TileRenderer implements IRectangleRenderer<Node, PaintEvent, Color>
    {
        @Override
        public void render(PaintEvent event, ITreeModel<IRectangle<Node>> tiles, IRectangle<Node> rectangle,
                        IColorProvider<Node, Color> colorProvider, ILabelProvider<Node> labelProvider)
        {
            var company = rectangle.getNode().company;
            if (company == null)
                return;

            var gc = event.gc;
            var foreground = gc.getForeground();
            var background = gc.getBackground();

            int x = rectangle.getX();
            int y = rectangle.getY();
            int width = rectangle.getWidth();
            int height = rectangle.getHeight();

            gc.setBackground(LookThroughChartPane.tileColor(company.directFraction()));
            gc.fillRectangle(x, y, width, height);

            // thin gap between neighbours in the background color
            gc.setForeground(Colors.theme().defaultBackground());
            gc.drawRectangle(x, y, width - 1, height - 1);

            drawLabel(gc, company, x, y, width, height);

            if (company.key().equals(selectedKey))
            {
                gc.setForeground(Colors.theme().defaultForeground());
                gc.setLineWidth(3);
                gc.drawRectangle(x + 1, y + 1, width - 3, height - 3);
                gc.setLineWidth(1);
            }

            gc.setForeground(foreground);
            gc.setBackground(background);
        }

        private void drawLabel(GC gc, ChartCompany company, int x, int y, int width, int height)
        {
            int available = width - 2 * PADDING;
            int lineHeight = gc.getFontMetrics().getHeight();
            if (available < MINIMUM_LABEL_WIDTH || height < lineHeight + 4)
                return;

            gc.setForeground(Colors.WHITE);
            gc.drawText(shorten(gc, company.label(), available), x + PADDING, y + 3, true);

            if (height >= 2 * lineHeight + 8 && company.shareText().isPresent())
                gc.drawText(shorten(gc, company.shareText().get(), available), x + PADDING, y + 3 + lineHeight, true);
        }

        @Override
        public void highlight(PaintEvent event, ITreeModel<IRectangle<Node>> tiles, IRectangle<Node> rectangle,
                        IColorProvider<Node, Color> colorProvider, ILabelProvider<Node> labelProvider)
        {
            event.gc.setForeground(Colors.theme().defaultForeground());
            event.gc.drawRectangle(rectangle.getX(), rectangle.getY(), rectangle.getWidth() - 1,
                            rectangle.getHeight() - 1);
        }
    }

    /** Cuts the text so that it fits the width and ends with an ellipsis. */
    private static String shorten(GC gc, String text, int width)
    {
        if (gc.textExtent(text).x <= width)
            return text;

        var ellipsis = "…"; //$NON-NLS-1$
        int length = text.length();
        while (length > 1 && gc.textExtent(text.substring(0, length) + ellipsis).x > width)
            length--;
        return text.substring(0, length).stripTrailing() + ellipsis;
    }
}
