package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

import de.engehausen.treemap.ICancelable;
import de.engehausen.treemap.IColorProvider;
import de.engehausen.treemap.ILabelProvider;
import de.engehausen.treemap.IRectangle;
import de.engehausen.treemap.IRectangleRenderer;
import de.engehausen.treemap.ITreeMapLayout;
import de.engehausen.treemap.ITreeModel;
import de.engehausen.treemap.IWeightedTreeModel;
import de.engehausen.treemap.impl.RectangleImpl;
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
    /** Neutral frame of a group, readable behind white text on light and dark themes. */
    private static final int GROUP_RED = 84;
    private static final int GROUP_GREEN = 84;
    private static final int GROUP_BLUE = 92;
    private static final int MINIMUM_LABEL_WIDTH = 24;
    /** Smaller tiles get no gap, otherwise the gaps cover them entirely. */
    private static final int MINIMUM_GAP_SIZE = 6;

    /** Space for the label of a group above its companies. */
    private static final int GROUP_HEADER = 18;
    private static final int GROUP_BORDER = 2;
    private static final int MINIMUM_GROUP_WIDTH = 40;
    private static final int MINIMUM_GROUP_HEIGHT = 40;

    /**
     * The root has neither company nor group. A group node has a group and
     * its company nodes as children; without grouping the company nodes are
     * children of the root.
     */
    private static final class Node
    {
        private final ChartCompany company;
        private final ChartCompany group;
        private final long weight;
        private final List<Node> children;

        private Node(ChartCompany company, ChartCompany group, long weight, List<Node> children)
        {
            this.company = company;
            this.group = group;
            this.weight = weight;
            this.children = children;
        }

        /** The company or group that this tile stands for, or null. */
        private ChartCompany item()
        {
            return company != null ? company : group;
        }
    }

    private static final class TileModel implements IWeightedTreeModel<Node>
    {
        private final Node root;
        private final Map<Node, Node> parents = new IdentityHashMap<>();

        private TileModel(Node root)
        {
            this.root = root;
            index(root);
        }

        private void index(Node parent)
        {
            for (var child : parent.children)
            {
                parents.put(child, parent);
                index(child);
            }
        }

        @Override
        public Iterator<Node> getChildren(Node item)
        {
            return item.children.iterator();
        }

        @Override
        public Node getParent(Node item)
        {
            return parents.get(item);
        }

        @Override
        public Node getRoot()
        {
            return root;
        }

        @Override
        public boolean hasChildren(Node item)
        {
            return !item.children.isEmpty();
        }

        @Override
        public long getWeight(Node item)
        {
            return item.weight;
        }
    }

    /** Rectangles of a layout, with the parent and the children of each. */
    private static final class TileTree implements ITreeModel<IRectangle<Node>>
    {
        private final IRectangle<Node> root;
        private final Map<IRectangle<Node>, List<IRectangle<Node>>> children = new IdentityHashMap<>();
        private final Map<IRectangle<Node>, IRectangle<Node>> parents = new IdentityHashMap<>();

        private TileTree(IRectangle<Node> root)
        {
            this.root = root;
        }

        private void add(IRectangle<Node> parent, IRectangle<Node> child)
        {
            children.computeIfAbsent(parent, key -> new ArrayList<>()).add(child);
            parents.put(child, parent);
        }

        @Override
        public IRectangle<Node> getRoot()
        {
            return root;
        }

        @Override
        public IRectangle<Node> getParent(IRectangle<Node> item)
        {
            return parents.get(item);
        }

        @Override
        public Iterator<IRectangle<Node>> getChildren(IRectangle<Node> item)
        {
            return children.getOrDefault(item, List.of()).iterator();
        }

        @Override
        public boolean hasChildren(IRectangle<Node> item)
        {
            return children.containsKey(item);
        }
    }

    /**
     * Squarified layout of two levels. The library nests tiles without room
     * for a label, so the groups are laid out first and the companies of each
     * group below a header strip inside it.
     */
    private static final class GroupLayout implements ITreeMapLayout<Node>
    {
        @Override
        public ITreeModel<IRectangle<Node>> layout(IWeightedTreeModel<Node> model, Node root, int width, int height)
        {
            return layout(model, root, width, height, () -> false);
        }

        @Override
        public ITreeModel<IRectangle<Node>> layout(IWeightedTreeModel<Node> model, Node root, int width,
                        int height, ICancelable cancel)
        {
            var level = new SquarifiedLayout<Node>(1).layout(model, root, width, height, cancel);
            var tree = new TileTree(level.getRoot());
            var groups = level.getChildren(level.getRoot());
            while (groups.hasNext())
            {
                var group = groups.next();
                tree.add(tree.getRoot(), group);
                if (!model.hasChildren(group.getNode()))
                    continue;

                int header = group.getWidth() >= MINIMUM_GROUP_WIDTH && group.getHeight() >= MINIMUM_GROUP_HEIGHT
                                ? GROUP_HEADER
                                : 0;
                int border = header > 0 ? GROUP_BORDER : 0;
                int innerWidth = group.getWidth() - 2 * border;
                int innerHeight = group.getHeight() - header - border;
                if (innerWidth < 1 || innerHeight < 1)
                    continue;

                var inner = new SquarifiedLayout<Node>(1).layout(model, group.getNode(), innerWidth, innerHeight,
                                cancel);
                var companies = inner.getChildren(inner.getRoot());
                while (companies.hasNext())
                {
                    var company = companies.next();
                    tree.add(group, new RectangleImpl<>(company.getNode(), group.getX() + border + company.getX(),
                                    group.getY() + header + company.getY(), company.getWidth(),
                                    company.getHeight()));
                }
            }
            return tree;
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

        private Node nodeAt(int x, int y)
        {
            if (rectangles == null)
                return null;
            var rectangle = findRectangle(x, y);
            return rectangle == null ? null : rectangle.getNode();
        }

        @Override
        protected boolean selectRectangle(int x, int y)
        {
            if (selected == null || !selected.contains(x, y))
            {
                var rectangle = findRectangle(x, y);
                selected = rectangle == null || rectangle.getNode().item() == null ? null : rectangle;

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
                // rounding of the layout must never leave an unpainted area
                gc.setBackground(LookThroughChartPane.indirectColor());
                gc.fillRectangle(0, 0, width, height);
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

        treeMap.setTreeMapLayout(new GroupLayout());
        treeMap.setLabelProvider((nodes, rectangle) -> {
            var item = rectangle.getNode().item();
            return item != null ? item.label() : ""; //$NON-NLS-1$
        });
        treeMap.setRectangleRenderer(new TileRenderer());

        treeMap.addMouseMoveListener(event -> {
            var node = treeMap.nodeAt(event.x, event.y);
            String tooltip = null;
            if (node != null && node.item() != null)
                tooltip = LookThroughChartPane.tooltip(node.item(), currencyCode);
            if (!Objects.equals(tooltip, treeMap.getToolTipText()))
                treeMap.setToolTipText(tooltip);
        });

        treeMap.addMouseListener(MouseListener.mouseUpAdapter(event -> {
            if (event.button != 1)
                return;
            var node = treeMap.nodeAt(event.x, event.y);
            if (node != null && node.item() != null)
                onSelect.accept(node.item().key());
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
        if (model.isGrouped())
        {
            for (var group : model.groups())
            {
                var companies = new ArrayList<Node>();
                long groupSum = 0;
                for (var company : group.companies())
                {
                    var weight = weight(company.total());
                    companies.add(new Node(company, null, weight, List.of()));
                    groupSum += weight;
                }
                if (companies.isEmpty())
                    continue;
                children.add(new Node(null, group.group(), groupSum, companies));
                sum += groupSum;
            }
        }
        else
        {
            for (var company : model.companies())
            {
                var weight = weight(company.total());
                children.add(new Node(company, null, weight, List.of()));
                sum += weight;
            }
        }
        if (children.isEmpty())
            return;
        treeMap.setTreeModel(new TileModel(new Node(null, null, sum, children)));
    }

    private static long weight(BigDecimal amount)
    {
        return Math.max(1L, amount.setScale(0, RoundingMode.HALF_UP).longValue());
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
            var node = rectangle.getNode();
            if (node.group != null)
            {
                renderGroup(event.gc, rectangle, node.group);
                return;
            }
            var company = node.company;
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
            if (width >= MINIMUM_GAP_SIZE && height >= MINIMUM_GAP_SIZE)
            {
                gc.setForeground(Colors.theme().defaultBackground());
                gc.drawRectangle(x, y, width - 1, height - 1);
            }

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

        /**
         * Draws only the strips around the companies, so the order in which
         * the library renders parents and children does not matter.
         */
        private void renderGroup(GC gc, IRectangle<Node> rectangle, ChartCompany group)
        {
            var foreground = gc.getForeground();
            var background = gc.getBackground();
            int x = rectangle.getX();
            int y = rectangle.getY();
            int width = rectangle.getWidth();
            int height = rectangle.getHeight();

            if (width >= MINIMUM_GROUP_WIDTH && height >= MINIMUM_GROUP_HEIGHT)
            {
                gc.setBackground(Colors.getColor(GROUP_RED, GROUP_GREEN, GROUP_BLUE));
                gc.fillRectangle(x, y, width, GROUP_HEADER);
                gc.fillRectangle(x, y + GROUP_HEADER, GROUP_BORDER, height - GROUP_HEADER);
                gc.fillRectangle(x + width - GROUP_BORDER, y + GROUP_HEADER, GROUP_BORDER, height - GROUP_HEADER);
                gc.fillRectangle(x, y + height - GROUP_BORDER, width, GROUP_BORDER);

                int available = width - 2 * PADDING;
                if (available >= MINIMUM_LABEL_WIDTH)
                {
                    // the share is always visible: reserve its width first and
                    // shorten only the label
                    var share = group.shareText().orElse(""); //$NON-NLS-1$
                    int shareWidth = share.isEmpty() ? 0 : gc.textExtent(share).x;
                    int gap = share.isEmpty() ? 0 : 2 * PADDING;
                    gc.setForeground(Colors.WHITE);
                    if (shareWidth > 0 && shareWidth <= available)
                        gc.drawText(share, x + width - PADDING - shareWidth, y + 2, true);
                    int labelWidth = available - (shareWidth > 0 ? shareWidth + gap : 0);
                    if (labelWidth >= MINIMUM_LABEL_WIDTH)
                        gc.drawText(shorten(gc, group.label(), labelWidth), x + PADDING, y + 2, true);
                }
            }

            if (group.key().equals(selectedKey))
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
            drawLines(gc, x, y, width, height, company.label(), company.shareText().orElse(null));
        }

        private void drawLines(GC gc, int x, int y, int width, int height, String first, String second)
        {
            int available = width - 2 * PADDING;
            int lineHeight = gc.getFontMetrics().getHeight();
            if (available < MINIMUM_LABEL_WIDTH || height < lineHeight + 4)
                return;

            gc.setForeground(Colors.WHITE);
            gc.drawText(shorten(gc, first, available), x + PADDING, y + 3, true);

            if (height >= 2 * lineHeight + 8 && second != null)
                gc.drawText(shorten(gc, second, available), x + PADDING, y + 3 + lineHeight, true);
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
