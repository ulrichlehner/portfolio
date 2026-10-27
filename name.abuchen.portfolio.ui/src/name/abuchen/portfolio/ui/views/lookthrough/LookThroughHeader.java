package name.abuchen.portfolio.ui.views.lookthrough;

import java.text.MessageFormat;
import java.util.Optional;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.RowLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.ProgressBar;

import name.abuchen.portfolio.ui.Images;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughBanner.Kind;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Summary;

/**
 * Header of the look-through view: at most one banner, the summary strip with
 * the key figures and the coverage bar. Notices are not shown as paragraphs;
 * they are collected in the tooltip of the indicator icon next to the
 * resolved share.
 * <p>
 * The progress of a refresh replaces the valuation date in the summary strip.
 * Both share one slot whose width is the larger of the two, so starting or
 * ending a refresh moves nothing.
 */
public class LookThroughHeader
{
    /** Shown instead of a percentage that cannot be computed. */
    private static final String NOT_AVAILABLE = "–"; //$NON-NLS-1$

    private final Runnable onStop;
    private final Runnable onOpenSettings;

    private final Composite container;
    private final Composite banner;
    private final Composite strip;
    private final LookThroughCoverageBar coverageBar;

    private Label valueLabel;
    private Label resolvedLabel;
    private Label indicator;
    private Label topTenLabel;
    private Label effectiveLabel;
    private Composite effectiveFigure;
    private Label largestLabel;
    private Composite fundsFigure;
    private Label fundsLabel;
    private Composite asOfSlot;
    private final StackLayout asOfStack = new StackLayout();
    private Label asOfLabel;
    private static final int MAX_NAME_LENGTH = 24;
    private Composite progress;
    private ProgressBar progressBar;
    private Label progressLabel;
    private boolean progressVisible;

    private Optional<Kind> bannerKind;

    public LookThroughHeader(Composite parent, Runnable onStop, Runnable onOpenSettings)
    {
        this.onStop = onStop;
        this.onOpenSettings = onOpenSettings;

        container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).spacing(0, 8).applyTo(container);

        banner = new Composite(container, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(banner);

        strip = new Composite(container, SWT.NONE);
        RowLayoutFactory.fillDefaults().wrap(true).spacing(24).center(true).applyTo(strip);
        GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(strip);
        createFigures();

        coverageBar = new LookThroughCoverageBar(container);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(coverageBar.getControl());

        setBanner(Optional.empty());
        setSummary(null, Optional.empty());
    }

    public Control getControl()
    {
        return container;
    }

    private void createFigures()
    {
        valueLabel = createFigure(strip, Messages.LookThroughSummaryValue, 1).value();

        var resolved = createFigure(strip, Messages.LookThroughSummaryResolved, 2);
        resolvedLabel = resolved.value();
        indicator = new Label(resolved.valueRow(), SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(indicator);

        topTenLabel = createFigure(strip, Messages.LookThroughSummaryTopTen, 1).value();

        var effective = createFigure(strip, Messages.LookThroughSummaryEffective, 1);
        effectiveLabel = effective.value();
        effectiveFigure = effective.figure();

        var largest = createFigure(strip, Messages.LookThroughSummaryLargest, 1);
        largestLabel = largest.value();
        largest.figure().setToolTipText(Messages.LookThroughSummaryLargestTooltip);

        var funds = createFigure(strip, Messages.LookThroughSummaryFunds, 1);
        fundsFigure = funds.figure();
        fundsLabel = funds.value();

        // The stack layout sizes the slot by its largest child, so the slot
        // keeps its width whether the date or the progress is on top.
        asOfSlot = new Composite(strip, SWT.NONE);
        asOfSlot.setLayout(asOfStack);

        asOfLabel = new Label(asOfSlot, SWT.NONE);
        asOfLabel.setForeground(Colors.theme().grayForeground());

        createProgress();
        asOfStack.topControl = asOfLabel;
    }

    private void createProgress()
    {
        progress = new Composite(asOfSlot, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(8, 0).applyTo(progress);

        progressLabel = new Label(progress, SWT.NONE);
        progressLabel.setForeground(Colors.theme().grayForeground());
        // Reserve the width of large counts so the label never grows.
        progressLabel.setText(MessageFormat.format(Messages.LookThroughSummaryOf, "8888", "8888")); //$NON-NLS-1$ //$NON-NLS-2$
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER)
                        .hint(progressLabel.computeSize(SWT.DEFAULT, SWT.DEFAULT).x, SWT.DEFAULT)
                        .applyTo(progressLabel);
        progressLabel.setText(""); //$NON-NLS-1$

        progressBar = new ProgressBar(progress, SWT.HORIZONTAL | SWT.SMOOTH);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).hint(100, SWT.DEFAULT).applyTo(progressBar);

        var stop = new Button(progress, SWT.FLAT);
        stop.setImage(Images.REMOVE.image());
        stop.setToolTipText(Messages.LookThroughStopRefresh);
        stop.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> onStop.run()));
        GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).applyTo(stop);
    }

    private record Figure(Composite figure, Composite valueRow, Label value)
    {
    }

    /** A caption above an emphasised value, like the dashboard indicators. */
    private Figure createFigure(Composite parent, String caption, int valueColumns)
    {
        var figure = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).spacing(0, 2).applyTo(figure);

        var captionLabel = new Label(figure, SWT.NONE);
        captionLabel.setText(caption);
        captionLabel.setForeground(Colors.theme().grayForeground());

        var valueRow = new Composite(figure, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(valueColumns).spacing(4, 0).applyTo(valueRow);

        var value = new Label(valueRow, SWT.NONE);
        value.setData(UIConstants.CSS.CLASS_NAME, UIConstants.CSS.HEADING2);
        return new Figure(figure, valueRow, value);
    }

    /**
     * Shows the key figures of the summary, or hides the strip and the bar
     * when there is no result or the portfolio is empty.
     */
    public void setSummary(Summary summary, Optional<LookThroughViewPresenter.Coverage> coverage)
    {
        var visible = summary != null;
        setVisible(strip, visible);
        setVisible(coverageBar.getControl(), visible);

        if (visible)
        {
            valueLabel.setText(summary.valueText());
            resolvedLabel.setText(summary.resolvedText().orElse(NOT_AVAILABLE));
            topTenLabel.setText(summary.topTenText().orElse(NOT_AVAILABLE));
            effectiveLabel.setText(summary.effectiveText().orElse(NOT_AVAILABLE));
            var tooltip = summary.effectiveCompanies()
                            .map(value -> MessageFormat.format(Messages.LookThroughSummaryEffectiveTooltip,
                                            LookThroughViewPresenter.effectiveCount(value)))
                            .orElse(null);
            effectiveFigure.setToolTipText(tooltip);
            effectiveLabel.setToolTipText(tooltip);
            setLargest(summary.largest());
            fundsLabel.setText(summary.fundsText().orElse("")); //$NON-NLS-1$
            setVisible(fundsFigure, summary.fundsText().isPresent());
            asOfLabel.setText(summary.asOfText());

            indicator.setImage(summary.attention() ? Images.WARNING.image() : Images.INFO.image());
            indicator.setToolTipText(summary.indicatorText());
            coverageBar.setCoverage(coverage);
        }

        relayout();
    }

    /** The name is shortened; the tooltip has the full name and the explanation. */
    private void setLargest(Optional<LookThroughViewPresenter.Largest> largest)
    {
        var text = largest.map(value -> shorten(value.name()) + " " + value.shareText()).orElse(NOT_AVAILABLE); //$NON-NLS-1$
        largestLabel.setText(text);
        var tooltip = Messages.LookThroughSummaryLargestTooltip;
        largestLabel.setToolTipText(largest.map(value -> value.name() + " " + value.shareText() + "\n" + tooltip) //$NON-NLS-1$ //$NON-NLS-2$
                        .orElse(tooltip));
    }

    static String shorten(String name)
    {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH - 1) + "\u2026"; //$NON-NLS-1$
    }

    /**
     * Shows the strip with placeholders while the first result is being
     * calculated. Captions stay, so the strip already has its final height.
     */
    public void setCalculating()
    {
        setVisible(strip, true);
        setVisible(coverageBar.getControl(), false);

        valueLabel.setText(NOT_AVAILABLE);
        resolvedLabel.setText(NOT_AVAILABLE);
        topTenLabel.setText(NOT_AVAILABLE);
        effectiveLabel.setText(NOT_AVAILABLE);
        effectiveFigure.setToolTipText(null);
        effectiveLabel.setToolTipText(null);
        setLargest(Optional.empty());
        fundsLabel.setText(NOT_AVAILABLE);
        setVisible(fundsFigure, true);
        asOfLabel.setText(""); //$NON-NLS-1$

        indicator.setImage(Images.INFO.image());
        indicator.setToolTipText(Messages.LookThroughLabelCalculating);

        relayout();
    }

    /**
     * Shows the refresh progress in place of the valuation date, or the date
     * again. Only the slot changes; the strip keeps its layout.
     */
    public void setProgress(boolean visible, int completed, int total)
    {
        if (visible)
        {
            progressBar.setMaximum(Math.max(1, total));
            progressBar.setSelection(completed);
            progressLabel.setText(MessageFormat.format(Messages.LookThroughSummaryOf, completed, total));
        }

        if (visible != progressVisible)
        {
            progressVisible = visible;
            asOfStack.topControl = visible ? progress : asOfLabel;
        }
        asOfSlot.layout(true, true);
    }

    /** Shows the banner of the given kind, or none. */
    public void setBanner(Optional<Kind> kind)
    {
        if (kind.equals(bannerKind))
            return;

        bannerKind = kind;
        for (var child : banner.getChildren())
            child.dispose();
        kind.ifPresent(this::createBanner);
        setVisible(banner, kind.isPresent());
        relayout();
    }

    private void createBanner(Kind kind)
    {
        switch (kind)
        {
            case SUSPENDED:
                createMessageBanner(Images.WARNING, Messages.LookThroughBannerSuspended,
                                Messages.LookThroughBannerCheckKey);
                break;
            case NO_KEY:
                createMessageBanner(Images.INFO, Messages.LookThroughBannerNoKey, Messages.LookThroughBannerSetUpKey);
                break;
            case EMPTY:
                createMessageBanner(Images.INFO, Messages.LookThroughLabelEmptyPortfolio, null);
                break;
            default:
                throw new IllegalArgumentException(kind.name());
        }
    }

    private void createMessageBanner(Images image, String text, String linkText)
    {
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(8, 0).applyTo(banner);

        var icon = new Label(banner, SWT.NONE);
        icon.setImage(image.image());
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.BEGINNING).applyTo(icon);

        if (linkText == null)
        {
            var label = new Label(banner, SWT.WRAP);
            label.setText(text);
            GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(label);
        }
        else
        {
            var link = new Link(banner, SWT.WRAP);
            link.setText(escape(text) + "  <a>" + escape(linkText) + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
            link.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> onOpenSettings.run()));
            GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(link);
        }
    }

    /** A link text treats an ampersand as mnemonic marker. */
    private static String escape(String text)
    {
        return text.replace("&", "&&"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void setVisible(Control control, boolean visible)
    {
        control.setVisible(visible);
        var data = control.getLayoutData();
        if (data instanceof GridData gridData)
            gridData.exclude = !visible;
        else if (data instanceof RowData rowData)
            rowData.exclude = !visible;
        else if (control.getParent().getLayout() instanceof RowLayout)
        {
            var rowData = new RowData();
            rowData.exclude = !visible;
            control.setLayoutData(rowData);
        }
    }

    private void relayout()
    {
        if (container.getParent() != null && !container.getParent().isDisposed())
            container.getParent().layout(true, true);
    }
}
