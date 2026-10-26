package name.abuchen.portfolio.ui.views.lookthrough;

import java.text.MessageFormat;
import java.util.Optional;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.RowLayoutFactory;
import org.eclipse.swt.SWT;
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
    private Composite fundsFigure;
    private Label fundsLabel;
    private Label asOfLabel;

    private Optional<Kind> bannerKind;
    private ProgressBar progressBar;
    private Label progressLabel;

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

        setBanner(Optional.empty(), 0, 0);
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

        var funds = createFigure(strip, Messages.LookThroughSummaryFunds, 1);
        fundsFigure = funds.figure();
        fundsLabel = funds.value();

        asOfLabel = new Label(strip, SWT.NONE);
        asOfLabel.setForeground(Colors.theme().grayForeground());
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
            fundsLabel.setText(summary.fundsText().orElse("")); //$NON-NLS-1$
            setVisible(fundsFigure, summary.fundsText().isPresent());
            asOfLabel.setText(summary.asOfText());

            indicator.setImage(summary.attention() ? Images.WARNING.image() : Images.INFO.image());
            indicator.setToolTipText(summary.indicatorText());
            coverageBar.setCoverage(coverage);
        }

        relayout();
    }

    /**
     * Shows the banner of the given kind. The progress counts are only used
     * by the progress banner; repeated calls with the same kind only update
     * the counts.
     */
    public void setBanner(Optional<Kind> kind, int completed, int total)
    {
        if (!kind.equals(bannerKind))
        {
            bannerKind = kind;
            for (var child : banner.getChildren())
                child.dispose();
            progressBar = null;
            progressLabel = null;
            kind.ifPresent(this::createBanner);
            setVisible(banner, kind.isPresent());
            relayout();
        }

        if (progressBar != null && !progressBar.isDisposed())
        {
            progressBar.setMaximum(Math.max(1, total));
            progressBar.setSelection(completed);
            progressLabel.setText(MessageFormat.format(Messages.LookThroughLabelLoading, completed, total));
            banner.layout(true);
        }
    }

    private void createBanner(Kind kind)
    {
        switch (kind)
        {
            case PROGRESS:
                createProgressBanner();
                break;
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

    private void createProgressBanner()
    {
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(8, 0).applyTo(banner);

        progressLabel = new Label(banner, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(progressLabel);

        progressBar = new ProgressBar(banner, SWT.HORIZONTAL | SWT.SMOOTH);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).grab(true, false).hint(120, SWT.DEFAULT)
                        .applyTo(progressBar);

        var stop = new Button(banner, SWT.FLAT);
        stop.setImage(Images.REMOVE.image());
        stop.setToolTipText(Messages.LookThroughStopRefresh);
        stop.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> onStop.run()));
        GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).applyTo(stop);
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
