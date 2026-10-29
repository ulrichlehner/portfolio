package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.eclipse.core.commands.Parameterization;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.e4.core.commands.ECommandService;
import org.eclipse.e4.core.commands.EHandlerService;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IElementComparer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TreeItem;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.FundOverlap;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.ui.Images;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.editor.AbstractFinanceView;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.DropDown;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.ui.util.viewers.Column;
import name.abuchen.portfolio.ui.util.viewers.ShowHideColumnHelper;
import name.abuchen.portfolio.ui.views.panes.InformationPanePage;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughChartPane.Kind;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CategoryRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ChartModel;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CompanyRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ContentState;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ContributionRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.DetailRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughGrouping.Grouping;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ExcludedRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.GroupKind;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.GroupRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Row;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.RowKey;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.SegmentKind;

/**
 * Read-only presentation of the look-through result. The view performs no
 * retrieval during rendering, holds no credentials and persists no analysis
 * data. Initial/timed eligibility passes are independent of redraws.
 * <p>
 * Status changes take a cheap path that only updates the progress text, the
 * actions and the status pane. The calculation runs in the background and
 * only when its inputs changed: the retained compositions, the
 * classifications, the funds or the client model.
 */
public class LookThroughView extends AbstractFinanceView
{
    private static final int ELIGIBILITY_INTERVAL = 60_000;
    private static final String CHART_KEY = LookThroughView.class.getSimpleName() + "-chart"; //$NON-NLS-1$
    private static final String GROUPING_KEY = LookThroughView.class.getSimpleName() + "-grouping"; //$NON-NLS-1$
    private static final String SASH_KEY = LookThroughView.class.getSimpleName() + "-sash"; //$NON-NLS-1$
    private static final int[] DEFAULT_WEIGHTS = { 1, 1 };
    /** Enrichment notifications are presented at most this often. */
    private static final Duration ENRICHMENT_UPDATE_INTERVAL = Duration.ofMillis(1500);

    /**
     * Opt-in restore timing, enabled with {@code -Dportfolio.lookthrough.timing=true}.
     * Off by default so the normal log stays quiet.
     */
    private static final boolean TIMING = Boolean.getBoolean("portfolio.lookthrough.timing"); //$NON-NLS-1$

    private final LookThroughViewPresenter presenter = new LookThroughViewPresenter();

    private LookThroughHeader header;
    private TreeViewer viewer;
    private LookThroughChartPane chartPane;
    private Composite tableArea;
    private final StackLayout tableStack = new StackLayout();
    private Composite tableContainer;
    private Composite tableMessage;
    private Composite statusRow;
    private Link statusToggle;
    private Canvas statusArrow;
    private boolean statusArrowCollapsed;
    private Label statusWarning;
    private Label statusText;
    /** The unshortened status row text; the label shows as much as fits. */
    private String statusRowText = ""; //$NON-NLS-1$
    private DropDown chartDropDown;
    private DropDown groupingDropDown;
    private Kind chartKind;
    private Grouping grouping;
    private Runnable enrichmentRegistration = () -> {
    };
    /** At most one enrichment update per interval; null before the body exists. */
    private volatile LookThroughUpdateThrottle enrichmentThrottle;
    /** The company data line of the status pane; user interface thread only. */
    private Optional<String> companyData = Optional.empty();
    private Map<RowKey, Row> rowsByKey = Map.of();
    private Map<RowKey, RowKey> companyKeys = Map.of();
    private Map<RowKey, RowKey> groupKeys = Map.of();
    private Map<RowKey, RowKey> parentKeys = Map.of();
    private Map<RowKey, Integer> ranks = Map.of();
    private Column companyColumn;
    private LookThroughViewPresenter.Model presented;
    /** The overlap of the presented result, kept for the session cache. */
    private FundOverlap overlap;

    /** Empty until the credential state was read off the UI thread. */
    private Optional<Boolean> keyConfigured = Optional.empty();
    private final Listener shellActivation = event -> readCredentialState();

    private final Runnable automaticRefresh = this::automaticRefresh;
    private volatile boolean disposed;

    /** Memory-only inputs of the calculation, compared to skip recalculation. */
    private volatile LookThroughInputs calculatedInputs;

    private ExecutorService calculationExecutor;

    /** One data status read in the background, for the source it was read from. */
    private record StatusUpdate(LookThroughCompositionSource source, Optional<LookThroughDataStatus.Model> status,
                    boolean hasFunds)
    {
    }

    /**
     * Reads the data status off the user interface thread: the first read
     * starts the backend, which prunes and reads the local stores.
     */
    private ExecutorService statusExecutor;
    private LookThroughRecalculation<StatusUpdate> statusCalculation;
    /** The last data status read in the background; user interface thread only. */
    private Optional<LookThroughDataStatus.Model> dataStatus = Optional.empty();
    private boolean hasFunds;
    private boolean statusRead;
    /** An automatic pass waits until the backend was started in the background. */
    private boolean automaticPending;
    private Shell shell;
    private LookThroughRecalculation<LookThroughResult> recalculation;

    /** Timing of the current view generation; only written when {@link #TIMING} is on. */
    private final long timingStarted = System.nanoTime();
    private volatile long timingGather;
    private volatile long timingAnalysis;
    private volatile long timingPresent;
    private int timingPresentations;

    @Inject
    private ExchangeRateProviderFactory rates;

    private LookThroughResult result;
    private LookThroughCompositionSource compositionSource;
    private LookThroughStatusModel statusModel;
    private SimpleAction refreshAction;
    private String currencyCode = ""; //$NON-NLS-1$

    @Inject
    private LookThroughCompositionService compositionService;

    /** Optional so the view can also be created in a bare context. */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    private ECommandService commandService;

    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    private EHandlerService handlerService;

    @Override
    protected String getDefaultTitle()
    {
        // The grouping is named by the selector's tooltip and the first table
        // column. A constant title keeps the header stable and leaves room for
        // the refresh action in narrow windows.
        return Messages.LabelBreakdown;
    }

    /**
     * Supplies an explicit calculation result, for example for a preview.
     */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setResult(LookThroughResult result)
    {
        this.result = result;
        // Enrichment runs after each result, outside the calculation.
        var source = compositionSource;
        if (result != null && source != null)
            source.enrich(result);
        applyResult();
    }

    /**
     * Supplies the memory-only composition access. Without a source the view
     * shows no status and offers no refresh.
     */
    @Inject
    @org.eclipse.e4.core.di.annotations.Optional
    public void setCompositionSource(LookThroughCompositionSource source)
    {
        applyCompositionSource(source);
    }

    private void applyCompositionSource(LookThroughCompositionSource source)
    {
        if (statusModel != null)
            statusModel.cancel();
        // A result belongs to the portfolio of its source. When the source
        // changes, the view is calculating again until the first new result.
        var reset = this.compositionSource != null && source != this.compositionSource;
        if (reset)
        {
            this.result = null;
            this.presented = null;
        }
        enrichmentRegistration.run();
        enrichmentRegistration = () -> {
        };
        this.compositionSource = source;
        this.calculatedInputs = null;
        this.dataStatus = Optional.empty();
        this.hasFunds = false;
        this.statusRead = false;
        this.keyConfigured = Optional.empty();
        this.companyData = Optional.empty();

        if (source == null)
        {
            this.statusModel = null;
            updateStatus();
            if (reset)
                applyResult();
            return;
        }

        this.statusModel = new LookThroughStatusModel(Clock.systemDefaultZone(), source.gateway(),
                        runnable -> {
                            if (!disposed)
                                Display.getDefault().asyncExec(() -> {
                                    if (!disposed)
                                        runnable.run();
                                });
                        }, () -> {
                            var current = this.compositionSource;
                            if (current == null)
                                return Optional.empty();
                            return Optional.of(new LookThroughStatusModel.Snapshot(current.globalFacts(),
                                            current.funds()));
                        });
        this.statusModel.setChangeListener(this::onStatusChanged);
        enrichmentRegistration = source.addEnrichmentListener(this::onEnrichmentChanged);

        updateStatus();
        if (reset)
        {
            applyResult();
            recalculate(true);
        }
    }

    /**
     * Called on a background thread whenever company attributes changed.
     * Notifications are coalesced to at most one update per
     * {@link #ENRICHMENT_UPDATE_INTERVAL}.
     */
    private void onEnrichmentChanged()
    {
        var throttle = enrichmentThrottle;
        if (!disposed && throttle != null)
            throttle.request();
    }

    /**
     * Presents the current result again with the latest company attributes:
     * no recalculation, no request. The snapshot and the presentation of
     * thousands of companies are computed on the calculation thread; the user
     * interface thread only applies the model, and only if the result and
     * grouping are still the same. Only a grouped view depends on the
     * attributes; every view updates the company data line.
     */
    private void refreshEnrichment()
    {
        var source = compositionSource;
        var executor = calculationExecutor;
        var current = result;
        var currentGrouping = grouping();
        if (disposed || source == null || executor == null || executor.isShutdown())
            return;
        var display = Display.getDefault();
        try
        {
            executor.execute(() -> {
                CompanyEnrichmentService.Snapshot attributes;
                LookThroughViewPresenter.Model model;
                try
                {
                    attributes = source.companyAttributes();
                    model = current != null && currentGrouping != Grouping.COMPANY
                                    ? presenter.present(current, currentGrouping, attributes)
                                    : null;
                }
                catch (RuntimeException e)
                {
                    PortfolioPlugin.log(e);
                    return;
                }
                if (display.isDisposed())
                    return;
                display.asyncExec(() -> {
                    if (disposed || source != compositionSource)
                        return;
                    companyData = companyText(attributes);
                    if (model != null && current == result && currentGrouping == grouping())
                        applyModel(model);
                    else
                        updateStatus();
                });
            });
        }
        catch (RejectedExecutionException e)
        {
            // The view is being disposed; nothing is left to update.
        }
    }

    private static Optional<String> companyText(CompanyEnrichmentService.Snapshot attributes)
    {
        return LookThroughDataStatus.companyText(attributes.progress(), Instant.now(),
                        ZoneId.systemDefault());
    }

    /** Called on the user interface thread after every status change. */
    private void onStatusChanged()
    {
        updateStatus();
        recalculate(false);
    }

    /**
     * Requests a background calculation. Unless forced, nothing happens when
     * the memory-only inputs equal those of the last request. The inputs are
     * gathered on the calculation thread: reading the retained compositions
     * may wait for the store while compositions are being loaded.
     */
    private void recalculate(boolean force)
    {
        var source = compositionSource;
        var client = getClient();
        var exchangeRates = rates;
        var executor = calculationExecutor;
        if (disposed || recalculation == null || source == null || exchangeRates == null || client == null
                        || executor == null || executor.isShutdown())
            return;

        try
        {
            executor.execute(() -> {
                if (disposed || source != compositionSource)
                    return;
                LookThroughInputs inputs;
                try
                {
                    var gatherStarted = TIMING ? System.nanoTime() : 0;
                    var funds = source.funds();
                    var retained = funds.stream().map(fund -> source.gateway().read(fund.account(), fund.fund()))
                                    .toList();
                    inputs = new LookThroughInputs(funds, retained, source.classifications());
                    if (TIMING)
                        timingGather = System.nanoTime() - gatherStarted;
                }
                catch (RuntimeException e)
                {
                    PortfolioPlugin.log(e);
                    return;
                }
                if (!force && inputs.equals(calculatedInputs))
                    return;
                calculatedInputs = inputs;

                // The calculation reads the client but never modifies it.
                recalculation.request(() -> {
                    var analysisStarted = TIMING ? System.nanoTime() : 0;
                    var result = calculate(client, exchangeRates, source, inputs);
                    if (TIMING)
                        timingAnalysis = System.nanoTime() - analysisStarted;
                    return result;
                });
            });
        }
        catch (RejectedExecutionException e)
        {
            // The view is being disposed; nothing is left to calculate.
        }
    }

    private static LookThroughResult calculate(Client client, ExchangeRateProviderFactory rates,
                    LookThroughCompositionSource source, LookThroughInputs inputs)
    {
        var converter = new CurrencyConverterImpl(rates, client.getBaseCurrency());
        var snapshot = ClientSnapshot.create(client, converter, LocalDate.now());
        var result = new LookThroughAnalysis().calculate(snapshot, rates, source.securities(), inputs.retained(),
                        inputs.classifications());
        PortfolioPlugin.info(LookThroughAnalysis.diagnostics(result));
        return result;
    }

    private void automaticRefresh()
    {
        if (disposed)
            return;
        if (statusModel != null && statusRead)
            statusModel.refreshAutomatically();
        else if (statusModel != null)
            automaticPending = true;
        Display.getDefault().timerExec(ELIGIBILITY_INTERVAL, automaticRefresh);
    }

    /**
     * Reads whether an API key is configured. The secure storage may be slow,
     * so the read runs on the calculation thread and the banner is updated
     * afterwards. Called when the view is created, when its window becomes
     * active again (for example after the settings dialog closed) and after
     * the settings were opened from the banner.
     */
    private void readCredentialState()
    {
        var source = compositionSource;
        var executor = calculationExecutor;
        if (disposed || source == null || executor == null || executor.isShutdown())
            return;
        var display = Display.getDefault();
        try
        {
            executor.execute(() -> {
                Optional<Boolean> configured;
                try
                {
                    configured = source.keyConfigured();
                }
                catch (RuntimeException e)
                {
                    PortfolioPlugin.log(e);
                    configured = Optional.empty();
                }
                var value = configured;
                if (!display.isDisposed())
                    display.asyncExec(() -> {
                        if (disposed || source != compositionSource)
                            return;
                        keyConfigured = value;
                        updateStatus();
                    });
            });
        }
        catch (RejectedExecutionException e)
        {
            // The view is being disposed; nothing is left to update.
        }
    }

    /**
     * Opens the settings dialog on the Eulerpool page. The dialog is modal,
     * so the credential state is read again once it returns.
     */
    private void openProviderSettings()
    {
        if (commandService == null || handlerService == null)
            return;
        try
        {
            var command = commandService.getCommand(UIConstants.Command.PREFERENCES);
            var page = command.getParameter(UIConstants.Parameter.PAGE);
            var parameterized = new ParameterizedCommand(command,
                            new Parameterization[] { new Parameterization(page, "eulerpool") }); //$NON-NLS-1$
            if (handlerService.canExecute(parameterized))
                handlerService.executeHandler(parameterized);
        }
        catch (NotDefinedException e)
        {
            PortfolioPlugin.log(e);
        }
        readCredentialState();
    }

    @Override
    public void dispose()
    {
        disposed = true;
        enrichmentRegistration.run();
        // Closing the view stops the enrichment after its current request;
        // answers so far are kept and the next result continues.
        if (compositionSource != null)
            compositionSource.stopEnrichment();
        if (shell != null && !shell.isDisposed())
            shell.removeListener(SWT.Activate, shellActivation);
        Display.getDefault().timerExec(-1, automaticRefresh);
        if (statusModel != null)
            statusModel.cancel();
        if (recalculation != null)
            recalculation.cancel();
        if (calculationExecutor != null)
            calculationExecutor.shutdownNow();
        if (statusCalculation != null)
            statusCalculation.cancel();
        if (statusExecutor != null)
            statusExecutor.shutdownNow();
        super.dispose();
    }

    /** A thin line across the view; native separators are not visible on all themes. */
    private static void createSeparator(Composite parent)
    {
        var separator = new Canvas(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, 1).applyTo(separator);
        separator.addPaintListener(event -> {
            event.gc.setBackground(Colors.theme().defaultForeground());
            event.gc.setAlpha(80);
            event.gc.fillRectangle(0, 0, separator.getSize().x, 1);
        });
    }

    /**
     * The always-visible row between the table and the data status pane. It
     * opens and closes the pane and states the refresh situation in plain
     * words, so the pane itself can stay collapsed.
     */
    private void createStatusRow(Composite parent)
    {
        statusRow = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(4).spacing(6, 0).applyTo(statusRow);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusRow);

        statusToggle = new Link(statusRow, SWT.NONE);
        statusToggle.setText("<a>" + Messages.LookThroughStatusPaneTitle + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        statusToggle.setToolTipText(Messages.LookThroughStatusRowTooltip);
        statusToggle.addSelectionListener(
                        SelectionListener.widgetSelectedAdapter(event -> toggleStatusPane()));

        statusArrow = new Canvas(statusRow, SWT.NONE);
        statusArrow.setBackground(Colors.theme().defaultBackground());
        GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).hint(12, 12).applyTo(statusArrow);
        statusArrow.addPaintListener(event -> paintStatusArrow(event.gc));

        statusWarning = new Label(statusRow, SWT.NONE);
        statusWarning.setImage(Images.WARNING.image());
        var warningData = GridDataFactory.fillDefaults().align(SWT.CENTER, SWT.CENTER).create();
        warningData.exclude = true;
        statusWarning.setLayoutData(warningData);
        statusWarning.setVisible(false);

        statusText = new Label(statusRow, SWT.NONE);
        statusText.setForeground(Colors.theme().grayForeground());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusText);
        statusText.addListener(SWT.Resize, event -> applyStatusRowText());

        // The whole row toggles the pane; the link stays the accessible control.
        var toggle = new MouseAdapter()
        {
            @Override
            public void mouseUp(MouseEvent event)
            {
                if (event.button == 1)
                    toggleStatusPane();
            }
        };
        for (var control : new Control[] { statusRow, statusArrow, statusWarning, statusText })
        {
            control.addMouseListener(toggle);
            control.setCursor(control.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        }

        updateStatusRow();
    }

    private void toggleStatusPane()
    {
        flipPane();
        updateStatusRow();
    }

    /** A disclosure triangle instead of a text character; theme coloured. */
    private void paintStatusArrow(GC gc)
    {
        var size = statusArrow.getSize();
        if (size.x <= 0 || size.y <= 0)
            return;
        var cx = size.x / 2;
        var cy = size.y / 2;

        gc.setBackground(Colors.theme().grayForeground());
        if (statusArrowCollapsed)
            gc.fillPolygon(new int[] { cx - 3, cy - 4, cx - 3, cy + 4, cx + 4, cy });
        else
            gc.fillPolygon(new int[] { cx - 4, cy - 3, cx + 4, cy - 3, cx, cy + 4 });
    }

    @Override
    protected void paneVisibilityChanged(boolean hidden)
    {
        updateStatusRow();
    }

    /**
     * Renders the row from the last status read and the current pane state.
     * Memory-only, like the pane: no request and no store access.
     */
    private void updateStatusRow()
    {
        if (statusRow == null || statusRow.isDisposed())
            return;

        var hidden = isPaneHidden();
        statusArrowCollapsed = hidden;
        statusArrow.redraw();
        statusArrow.setToolTipText(hidden ? Messages.LookThroughStatusRowExpand
                        : Messages.LookThroughStatusRowCollapse);

        var classified = presented != null ? presented.directlyClassified() : Set.<Map<String, String>>of();
        var rows = dataStatus.map(model -> LookThroughDataStatus.listed(model, classified)).orElse(List.of());
        var summary = LookThroughDataStatus.summaryRow(rows, Clock.systemDefaultZone());

        statusRowText = summary.map(LookThroughDataStatus.SummaryRow::text).orElse(""); //$NON-NLS-1$
        var warning = summary.map(LookThroughDataStatus.SummaryRow::warning).orElse(false);
        var warningData = (GridData) statusWarning.getLayoutData();
        warningData.exclude = !warning;
        statusWarning.setVisible(warning);
        statusWarning.setToolTipText(warning ? summary.map(LookThroughDataStatus.SummaryRow::tooltip).orElse(null)
                        : null);

        var tooltip = summary.map(LookThroughDataStatus.SummaryRow::tooltip).orElse(null);
        statusText.setToolTipText(tooltip);
        statusToggle.setToolTipText(tooltip != null ? tooltip + "\n\n" + Messages.LookThroughStatusRowTooltip //$NON-NLS-1$
                        : Messages.LookThroughStatusRowTooltip);

        applyStatusRowText();
        statusRow.layout(true);
    }

    /** One line only: the text is shortened with an ellipsis when it does not fit. */
    private void applyStatusRowText()
    {
        if (statusText == null || statusText.isDisposed())
            return;

        var available = statusText.getSize().x;
        if (available <= 0 || statusRowText.isEmpty())
        {
            setStatusText(statusRowText);
            return;
        }

        var gc = new GC(statusText);
        try
        {
            if (gc.textExtent(statusRowText).x <= available)
            {
                setStatusText(statusRowText);
                return;
            }

            var ellipsis = "\u2026"; //$NON-NLS-1$
            int low = 0;
            int high = statusRowText.length();
            while (low < high)
            {
                var middle = (low + high + 1) / 2;
                if (gc.textExtent(statusRowText.substring(0, middle) + ellipsis).x <= available)
                    low = middle;
                else
                    high = middle - 1;
            }
            setStatusText(statusRowText.substring(0, low) + ellipsis);
        }
        finally
        {
            gc.dispose();
        }
    }

    /**
     * Sets the text only when it changed: a resize caused by the text itself
     * must not start another pass.
     */
    private void setStatusText(String text)
    {
        if (!text.equals(statusText.getText()))
            statusText.setText(text);
    }

    @Override
    protected Control createBody(Composite parent)
    {
        var display = parent.getDisplay();
        calculationExecutor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "lookthrough-calculation"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        recalculation = new LookThroughRecalculation<>(calculationExecutor, runnable -> {
            if (!disposed && !display.isDisposed())
                display.asyncExec(() -> {
                    if (!disposed)
                        runnable.run();
                });
        }, this::setResult, PortfolioPlugin::log);
        statusExecutor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "lookthrough-status"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        statusCalculation = new LookThroughRecalculation<>(statusExecutor, runnable -> {
            if (!disposed && !display.isDisposed())
                display.asyncExec(() -> {
                    if (!disposed)
                        runnable.run();
                });
        }, this::applyStatusUpdate, PortfolioPlugin::log);
        enrichmentThrottle = new LookThroughUpdateThrottle(Clock.systemUTC(), ENRICHMENT_UPDATE_INTERVAL,
                        (task, delay) -> {
                            if (!display.isDisposed())
                                display.asyncExec(() -> {
                                    if (!disposed)
                                        display.timerExec((int) delay.toMillis(), () -> {
                                            if (!disposed)
                                                task.run();
                                        });
                                });
                        }, this::refreshEnrichment);

        var container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(1).extendedMargins(5, 5, 5, 10)
                        .spacing(0, 8).applyTo(container);

        header = new LookThroughHeader(container, this::stopRefresh, this::openProviderSettings,
                        this::revealCategory);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header.getControl());

        // separates the header from chart and table
        createSeparator(container);

        // chart and table side by side; the weights are kept in the preferences
        var sash = new SashForm(container, SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(sash);

        // separates the view from the data status row below
        createSeparator(container);
        createStatusRow(container);

        chartPane = new LookThroughChartPane(sash, getStylingEngine(), this::selectCompany);
        chartPane.setKind(chartKind());

        // The table and the calculating message share the right side.
        tableArea = new Composite(sash, SWT.NONE);
        tableArea.setLayout(tableStack);

        tableContainer = new Composite(tableArea, SWT.NONE);
        tableMessage = createCalculatingMessage(tableArea);
        tableStack.topControl = tableContainer;

        var layout = new TreeColumnLayout();
        tableContainer.setLayout(layout);

        viewer = new TreeViewer(tableContainer, SWT.FULL_SELECTION);
        ColumnViewerToolTipSupport.enableFor(viewer, ToolTip.NO_RECREATE);

        var columns = new ShowHideColumnHelper(LookThroughView.class.getSimpleName(), getPreferenceStore(), viewer,
                        layout);
        addColumns(columns);
        keepNameColumnFirst();
        columns.createColumns();

        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        sash.setWeights(loadWeights());
        sash.addDisposeListener(e -> saveWeights(sash.getWeights()));

        viewer.addSelectionChangedListener(event -> showSelectionInChart());
        viewer.setComparer(new RowKeyComparer());
        viewer.setContentProvider(new RowContentProvider(this::parentOf));
        viewer.setInput(List.of());

        // A source injected by the platform wins; otherwise the view uses the
        // application wiring so the status and refresh work without extra setup.
        if (compositionSource == null)
            applyCompositionSource(compositionService.sourceFor(getClient(), rates));

        shell = container.getShell();
        shell.addListener(SWT.Activate, shellActivation);

        if (!applyCachedResult())
            applyResult();
        updateStatus();
        return container;
    }

    /**
     * Shows the presentation of an earlier opening of this portfolio when the
     * session cache has one. The view then starts with data instead of the
     * calculating state and marks that the background calculation updates it.
     * Never for a portfolio with unsaved changes and never for an injected
     * source.
     */
    private boolean applyCachedResult()
    {
        var client = getClient();
        var source = compositionSource;
        if (client == null || source == null || !source.cacheable())
            return cacheMiss("no source"); //$NON-NLS-1$

        var entry = LookThroughCompositionService.resultCache().get(client);
        if (entry.isEmpty())
            return cacheMiss("no entry"); //$NON-NLS-1$
        if (entry.orElseThrow().grouping() != grouping())
            return cacheMiss("other grouping"); //$NON-NLS-1$

        var cached = entry.orElseThrow();
        result = cached.result();
        chartPane.setOverlap(cached.overlap());
        applyModel(cached.model());
        header.setUpdating(true);
        if (TIMING)
            PortfolioPlugin.info("lookthrough cache: hit"); //$NON-NLS-1$
        return true;
    }

    private boolean cacheMiss(String reason)
    {
        if (TIMING)
            PortfolioPlugin.info("lookthrough cache: miss, " + reason); //$NON-NLS-1$
        return false;
    }

    /** Keeps the presentation of the current result for the next opening. */
    private void storeCachedResult(LookThroughViewPresenter.Model model)
    {
        var client = getClient();
        var source = compositionSource;
        var inputs = calculatedInputs;
        if (client == null || source == null || !source.cacheable() || inputs == null || result == null
                        || overlap == null)
            return;

        LookThroughCompositionService.resultCache().put(client, new LookThroughResultCache.Entry(result, inputs,
                        grouping(), model, overlap, Instant.now()));
    }

    /**
     * Columns are created in the saved order and the first one holds the
     * tree's expand arrow. A configuration saved with another column first
     * gets the name column moved to the front.
     */
    private void keepNameColumnFirst()
    {
        var key = LookThroughView.class.getSimpleName();
        var config = getPreferenceStore().getString(key);
        if (config == null || !config.startsWith("{")) //$NON-NLS-1$
            return;
        try
        {
            var json = JsonParser.parseString(config).getAsJsonObject();
            var items = json.getAsJsonArray("items"); //$NON-NLS-1$
            if (items == null)
                return;
            for (int index = 1; index < items.size(); index++)
            {
                var item = items.get(index);
                if (item.isJsonObject() && "instrument".equals(item.getAsJsonObject().get("id").getAsString())) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    var reordered = new JsonArray();
                    reordered.add(item);
                    for (int other = 0; other < items.size(); other++)
                        if (other != index)
                            reordered.add(items.get(other));
                    json.add("items", reordered); //$NON-NLS-1$
                    getPreferenceStore().setValue(key, json.toString());
                    return;
                }
            }
        }
        catch (RuntimeException e)
        {
            // An unreadable configuration falls back to the default columns.
            getPreferenceStore().setToDefault(key);
        }
    }

    private static Composite createCalculatingMessage(Composite parent)
    {
        var area = new Composite(parent, SWT.NONE);
        area.setBackground(Colors.theme().defaultBackground());
        GridLayoutFactory.fillDefaults().margins(8, 8).applyTo(area);
        var message = new Label(area, SWT.WRAP | SWT.CENTER);
        message.setText(Messages.LookThroughLabelCalculating);
        message.setForeground(Colors.theme().grayForeground());
        message.setBackground(Colors.theme().defaultBackground());
        GridDataFactory.fillDefaults().grab(true, true).align(SWT.FILL, SWT.CENTER).hint(100, SWT.DEFAULT)
                        .applyTo(message);
        return area;
    }

    private void addColumns(ShowHideColumnHelper support)
    {
        // The first column holds the tree's expand arrow, so the name comes first.
        var column = new Column("instrument", companyColumnLabel(), SWT.NONE, 200); //$NON-NLS-1$
        companyColumn = column;
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof ContributionRow contribution)
                    return contribution.sourceText();
                if (element instanceof DetailRow detail)
                {
                    var label = detail.label().isBlank() ? Messages.LookThroughDetailUnnamedInstrument
                                    : detail.label();
                    var source = label + " (" + detail.sourceText() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    // the reason is part of the row, not only of its tooltip
                    return detail.note() == null || detail.note().isBlank() ? source
                                    : source + " · " + detail.note(); //$NON-NLS-1$
                }
                return ((Row) element).label();
            }

            @Override
            public String getToolTipText(Object element)
            {
                if (element instanceof ExcludedRow excluded)
                    return excluded.reasonText();
                if (element instanceof ContributionRow contribution)
                    return contribution.label();
                if (element instanceof GroupRow group)
                    return MessageFormat.format(Messages.LookThroughGroupCompanies, group.count());
                if (element instanceof DetailRow detail)
                    return detail.note() == null || detail.note().isBlank() ? null : detail.note();
                if (element instanceof CompanyRow company)
                    return company.detail().orElse(null);
                if (element instanceof CategoryRow category)
                    return categoryTooltip(category.category());
                return null;
            }
        });
        column.setRemovable(false);
        support.addColumn(column);

        column = new Column("rank", Messages.LookThroughColumnRank, SWT.RIGHT, 40); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                var rank = element instanceof Row row ? ranks.get(row.key()) : null;
                return rank != null ? String.valueOf(rank) : null;
            }
        });
        support.addColumn(column);

        column = new Column("direct", Messages.LookThroughColumnDirect, SWT.RIGHT, 95); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof GroupRow group)
                    return formatAmount(group.direct());
                if (element instanceof CompanyRow company)
                    return formatAmount(company.direct());
                if (element instanceof ContributionRow contribution && contribution.direct())
                    return formatAmount(contribution.value());
                if (element instanceof DetailRow detail && detail.direct())
                    return formatAmount(detail.value());
                return null;
            }
        });
        support.addColumn(column);

        column = new Column("indirect", Messages.LookThroughColumnIndirect, SWT.RIGHT, 95); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof GroupRow group)
                    return formatAmount(group.indirect());
                if (element instanceof CompanyRow company)
                    return formatAmount(company.indirect());
                if (element instanceof ContributionRow contribution && !contribution.direct())
                    return formatAmount(contribution.value());
                if (element instanceof DetailRow detail && !detail.direct())
                    return formatAmount(detail.value());
                return null;
            }
        });
        support.addColumn(column);

        column = new Column("total", Messages.LookThroughColumnTotal, SWT.RIGHT, 95); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof GroupRow group)
                    return formatAmount(group.total());
                if (element instanceof CompanyRow company)
                    return formatAmount(company.total());
                if (element instanceof ContributionRow contribution)
                    return formatAmount(contribution.value());
                if (element instanceof DetailRow detail)
                    return formatAmount(detail.value());
                if (element instanceof CategoryRow category)
                    return formatAmount(category.value());
                return null;
            }
        });
        support.addColumn(column);

        column = new Column("share", Messages.LookThroughColumnShare, SWT.RIGHT, 75); //$NON-NLS-1$
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof GroupRow group)
                    return group.shareText().orElse(null);
                if (element instanceof CompanyRow company)
                    return company.shareText().orElse(null);
                if (element instanceof ContributionRow contribution)
                    return contribution.shareText().orElse(null);
                if (element instanceof DetailRow detail)
                    return detail.shareText().orElse(null);
                if (element instanceof CategoryRow category)
                    return category.shareText().orElse(null);
                return null;
            }
        });
        support.addColumn(column);
    }

    private String companyColumnLabel()
    {
        return grouping() == Grouping.COMPANY ? Messages.LookThroughColumnCompany
                        : MessageFormat.format(Messages.LookThroughColumnGrouped, grouping().label());
    }

    private static String categoryTooltip(Category category)
    {
        return switch (category)
        {
            case UNGROUPED_EQUITY -> Messages.LookThroughCategoryUngroupedEquityTooltip;
            case CASH_OR_OTHER -> Messages.LookThroughCategoryCashOrOtherTooltip;
            case UNRESOLVED -> Messages.LookThroughCategoryUnresolvedTooltip;
            case UNVERIFIED_HOLDING -> Messages.LookThroughCategoryUnverifiedTooltip;
            default -> null;
        };
    }

    /** Selects, expands and shows the category row of a clicked coverage legend entry. */
    private void revealCategory(SegmentKind kind)
    {
        var category = switch (kind)
        {
            case UNGROUPED_EQUITY -> Category.UNGROUPED_EQUITY;
            case CASH_OR_OTHER -> Category.CASH_OR_OTHER;
            case UNVERIFIED -> Category.UNVERIFIED_HOLDING;
            case UNRESOLVED -> Category.UNRESOLVED;
            default -> null;
        };
        if (category == null || presented == null || viewer == null || viewer.getControl().isDisposed())
            return;
        presented.rows().stream() //
                        .filter(row -> row instanceof CategoryRow categoryRow && categoryRow.category() == category) //
                        .findFirst().ifPresent(row -> {
                            viewer.setExpandedState(row, true);
                            viewer.setSelection(new StructuredSelection(row), true);
                            viewer.getTree().setFocus();
                        });
    }

    private String formatAmount(BigDecimal value)
    {
        return LookThroughViewPresenter.formatAmount(value, currencyCode);
    }

    private void applyResult()
    {
        if (viewer == null || viewer.getControl().isDisposed() || chartPane == null)
            return;

        var state = LookThroughViewPresenter.contentState(result != null, compositionSource != null);
        setTableCalculating(state == ContentState.CALCULATING);

        if (result == null)
        {
            currencyCode = ""; //$NON-NLS-1$
            presented = null;
            if (state == ContentState.CALCULATING)
                header.setCalculating();
            else
                header.setSummary(null, Optional.empty());
            chartPane.setOverlap(null);
            chartPane.setModel(new ChartModel(List.of()), currencyCode, state);
            setRows(List.of());
            updateStatus();
            return;
        }

        // Until the first model is presented the table keeps its calculating message.
        if (presented == null)
            setTableCalculating(true);
        present(result, grouping());
    }

    /**
     * Presents a result off the user interface thread: the company attributes
     * may wait for the enrichment cache and the presentation covers thousands
     * of companies. The model is applied only while result and grouping are
     * still the same.
     */
    private void present(LookThroughResult current, Grouping currentGrouping)
    {
        var source = compositionSource;
        var executor = calculationExecutor;
        if (disposed || executor == null || executor.isShutdown())
            return;
        var display = Display.getDefault();
        try
        {
            executor.execute(() -> {
                CompanyEnrichmentService.Snapshot attributes;
                LookThroughViewPresenter.Model model;
                FundOverlap overlap;
                try
                {
                    var presentStarted = TIMING ? System.nanoTime() : 0;
                    attributes = source != null ? source.companyAttributes()
                                    : CompanyEnrichmentService.Snapshot.empty();
                    model = presenter.present(current, currentGrouping, attributes);
                    overlap = FundOverlap.of(current);
                    if (TIMING)
                        timingPresent = System.nanoTime() - presentStarted;
                }
                catch (RuntimeException e)
                {
                    PortfolioPlugin.log(e);
                    return;
                }
                if (display.isDisposed())
                    return;
                display.asyncExec(() -> {
                    if (disposed || current != result || currentGrouping != grouping())
                        return;
                    companyData = companyText(attributes);
                    setTableCalculating(false);
                    this.overlap = overlap;
                    chartPane.setOverlap(overlap);
                    applyModel(model);
                    header.setUpdating(false);
                });
            });
        }
        catch (RejectedExecutionException e)
        {
            // The view is being disposed; nothing is left to present.
        }
    }

    /** Shows a presented model of the current result. */
    private void applyModel(LookThroughViewPresenter.Model model)
    {
        if (viewer == null || viewer.getControl().isDisposed() || chartPane == null || result == null)
            return;
        presented = model;
        currencyCode = model.currencyCode();
        header.setSummary(model.empty() ? null : model.summary(), model.coverage());

        var treemapStarted = TIMING ? System.nanoTime() : 0;
        chartPane.setModel(model.chart(), currencyCode,
                        LookThroughViewPresenter.contentState(true, compositionSource != null));
        var treemap = TIMING ? System.nanoTime() - treemapStarted : 0;

        var treeStarted = TIMING ? System.nanoTime() : 0;
        setRows(model.rows());
        var tree = TIMING ? System.nanoTime() - treeStarted : 0;

        updateStatus();
        logTiming(tree, treemap);
        storeCachedResult(model);
    }

    /**
     * Writes one line per presentation when the opt-in timing is on. The first
     * line of a view also carries gather and analysis; later lines are
     * grouping changes that only present and apply.
     */
    private void logTiming(long tree, long treemap)
    {
        if (!TIMING)
            return;
        timingPresentations++;
        var total = System.nanoTime() - timingStarted;
        PortfolioPlugin.info("lookthrough timing: presentation=" + timingPresentations //$NON-NLS-1$
                        + " gather=" + millis(timingGather) //$NON-NLS-1$
                        + " analysis=" + millis(timingAnalysis) //$NON-NLS-1$
                        + " present=" + millis(timingPresent) //$NON-NLS-1$
                        + " tree=" + millis(tree) //$NON-NLS-1$
                        + " treemap=" + millis(treemap) //$NON-NLS-1$
                        + " total=" + millis(total)); //$NON-NLS-1$
    }

    private static long millis(long nanos)
    {
        return nanos / 1_000_000;
    }

    private void setTableCalculating(boolean calculating)
    {
        var top = calculating ? tableMessage : tableContainer;
        if (tableStack.topControl != top)
        {
            tableStack.topControl = top;
            tableArea.layout(true);
        }
    }

    /**
     * Replaces the rows and keeps the selection, the expanded rows and the
     * top row by their stable keys. Keys that no longer exist are dropped
     * silently.
     */
    private void setRows(List<Row> rows)
    {
        var tree = viewer.getTree();
        var selected = keysOf(viewer.getStructuredSelection().toList());
        var expanded = keysOf(Arrays.asList(viewer.getExpandedElements()));
        var topItem = tree.getTopItem();
        var top = topItem != null && topItem.getData() instanceof Row row ? row.key() : null;

        // Labels are created during setInput, so the ranks must exist before:
        // companies, or groups of a grouping, ranked by the order of the total.
        var ranked = new HashMap<RowKey, Integer>();
        for (var row : rows)
        {
            if (row instanceof CompanyRow
                            || row instanceof GroupRow group && group.groupKind() == GroupKind.REGULAR)
                ranked.put(row.key(), ranked.size() + 1);
        }
        ranks = ranked;

        tree.setRedraw(false);
        try
        {
            viewer.setInput(rows);

            var byKey = new HashMap<RowKey, Row>();
            var parents = new HashMap<RowKey, RowKey>();
            index(rows, null, byKey, parents);
            rowsByKey = byKey;
            parentKeys = parents;
            var owners = new HashMap<RowKey, RowKey>();
            var groups = new HashMap<RowKey, RowKey>();
            for (var row : rows)
            {
                if (row instanceof GroupRow group)
                {
                    owners.put(group.key(), group.key());
                    group.companies().forEach(company -> indexOwners(company, group.key(), owners, groups));
                }
                else if (row instanceof CompanyRow company)
                {
                    indexOwners(company, null, owners, groups);
                }
            }
            companyKeys = owners;
            groupKeys = groups;
            viewer.setExpandedElements(expanded.stream().map(byKey::get).filter(Objects::nonNull).toArray());
            viewer.setSelection(new StructuredSelection(
                            selected.stream().map(byKey::get).filter(Objects::nonNull).toList()));

            var item = top != null ? findItem(tree.getItems(), top) : null;
            if (item != null)
                tree.setTopItem(item);
        }
        finally
        {
            tree.setRedraw(true);
        }
        showSelectionInChart();
    }

    private static void indexOwners(CompanyRow company, RowKey group, Map<RowKey, RowKey> owners,
                    Map<RowKey, RowKey> groups)
    {
        owners.put(company.key(), company.key());
        company.contributions().forEach(contribution -> owners.put(contribution.key(), company.key()));
        if (group != null)
            groups.put(company.key(), group);
    }

    /** A click in a chart selects and reveals the company or group row in the table. */
    private void selectCompany(RowKey key)
    {
        var row = rowsByKey.get(key);
        if (row == null)
            return;
        // a company of a collapsed group is only reachable through the group
        var parent = rowsByKey.get(parentKeys.get(key));
        if (parent != null)
            viewer.setExpandedState(parent, true);
        viewer.setSelection(new StructuredSelection(row), true);
        viewer.setExpandedState(row, true);
        viewer.reveal(row);
    }

    /**
     * Highlights the company of the selected row in the chart. The chart does
     * not report this back, so the two selections cannot trigger each other.
     */
    private void showSelectionInChart()
    {
        if (chartPane == null)
            return;
        var selected = viewer.getStructuredSelection().getFirstElement();
        var owner = selected instanceof Row row ? companyKeys.get(row.key()) : null;
        chartPane.setSelected(owner, owner != null ? groupKeys.get(owner) : null);
    }

    private Kind chartKind()
    {
        if (chartKind == null)
        {
            chartKind = Kind.TREEMAP;
            try
            {
                chartKind = Kind.valueOf(getPreferenceStore().getString(CHART_KEY));
            }
            catch (IllegalArgumentException | NullPointerException e)
            {
                // keep the default
            }
        }
        return chartKind;
    }

    private Grouping grouping()
    {
        if (grouping == null)
        {
            grouping = Grouping.COMPANY;
            try
            {
                grouping = Grouping.valueOf(getPreferenceStore().getString(GROUPING_KEY));
            }
            catch (IllegalArgumentException | NullPointerException e)
            {
                // keep the default
            }
        }
        return grouping;
    }

    private String groupingLabel()
    {
        return Messages.LookThroughBreakdownBy + ": " + grouping().label(); //$NON-NLS-1$
    }

    /** Groups the current result again from stored attributes; neither calculates nor requests. */
    private void selectGrouping(Grouping selected)
    {
        if (grouping() == selected)
            return;
        grouping = selected;
        getPreferenceStore().setValue(GROUPING_KEY, selected.name());
        updateCompanyColumnLabel();
        if (groupingDropDown != null)
        {
            groupingDropDown.setLabel(groupingLabel());
            groupingDropDown.setToolTip(groupingLabel());
        }
        applyResult();
    }

    private void updateCompanyColumnLabel()
    {
        if (viewer == null || viewer.getControl().isDisposed() || companyColumn == null)
            return;
        for (var treeColumn : viewer.getTree().getColumns())
        {
            if (treeColumn.getData(Column.class.getName()) == companyColumn)
                treeColumn.setText(companyColumnLabel());
        }
    }

    private void selectChart(Kind kind)
    {
        chartKind = kind;
        getPreferenceStore().setValue(CHART_KEY, kind.name());
        if (chartDropDown != null)
        {
            chartDropDown.setLabel(kind.label());
            chartDropDown.setToolTip(kind.label());
        }
        if (chartPane != null)
            chartPane.setKind(kind);
    }

    private int[] loadWeights()
    {
        try
        {
            var parts = getPreferenceStore().getString(SASH_KEY).split(","); //$NON-NLS-1$
            if (parts.length == 2)
            {
                var weights = new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
                if (weights[0] > 0 && weights[1] > 0)
                    return weights;
            }
        }
        catch (NumberFormatException | NullPointerException e)
        {
            // keep the default
        }
        return DEFAULT_WEIGHTS.clone();
    }

    private void saveWeights(int[] weights)
    {
        if (weights.length == 2 && weights[0] > 0 && weights[1] > 0)
            getPreferenceStore().setValue(SASH_KEY, weights[0] + "," + weights[1]); //$NON-NLS-1$
    }

    private static List<RowKey> keysOf(List<?> elements)
    {
        var keys = new ArrayList<RowKey>();
        for (var element : elements)
            if (element instanceof Row row)
                keys.add(row.key());
        return keys;
    }

    private static void index(List<? extends Row> rows, RowKey parent, Map<RowKey, Row> byKey,
                    Map<RowKey, RowKey> parents)
    {
        for (var row : rows)
        {
            byKey.put(row.key(), row);
            if (parent != null)
                parents.put(row.key(), parent);
            if (row instanceof GroupRow group)
                index(group.companies(), group.key(), byKey, parents);
            else if (row instanceof CompanyRow company)
                index(company.contributions(), company.key(), byKey, parents);
            else if (row instanceof CategoryRow category)
                index(category.details(), category.key(), byKey, parents);
        }
    }

    private static TreeItem findItem(TreeItem[] items, RowKey key)
    {
        for (var item : items)
        {
            if (item.getData() instanceof Row row && row.key().equals(key))
                return item;
            if (item.getExpanded())
            {
                var child = findItem(item.getItems(), key);
                if (child != null)
                    return child;
            }
        }
        return null;
    }

    /**
     * Pushes the current status into the information pane. The status is read
     * from memory only: this method never starts a refresh, so rendering,
     * resizing and recalculation cannot cause provider traffic.
     */
    private void updateStatus()
    {
        if (disposed)
            return;
        var model = statusModel;
        var source = compositionSource;
        var calculation = statusCalculation;
        if (model != null && source != null && calculation != null)
        {
            // The refresh state is captured here; the reads run in the background.
            var task = model.statusTask(keyConfigured);
            calculation.request(() -> {
                var value = task.get();
                return new StatusUpdate(source, value, value.map(item -> !item.funds().isEmpty()).orElse(false));
            });
        }
        else if (model == null)
        {
            dataStatus = Optional.empty();
            hasFunds = false;
        }
        showStatus();
    }

    /** Applies a data status read in the background, unless the source changed meanwhile. */
    private void applyStatusUpdate(StatusUpdate update)
    {
        if (disposed || update.source() != compositionSource)
            return;
        dataStatus = update.status();
        hasFunds = update.hasFunds();
        statusRead = true;
        showStatus();
        if (automaticPending && statusModel != null)
        {
            automaticPending = false;
            statusModel.refreshAutomatically();
        }
    }

    /** Shows the last data status read; never reads the stores. */
    private void showStatus()
    {
        var shares = presented != null ? presented.decomposedShares() : Map.<Map<String, String>, String>of();
        var classified = presented != null ? presented.directlyClassified() : Set.<Map<String, String>>of();
        var company = companyData;
        var assumed = presented != null ? presented.assumedCompanies() : Optional.<String>empty();
        setInformationPaneInput(dataStatus.map(
                        value -> new LookThroughDataStatusPane.Input(value, shares, classified, company, assumed))
                        .orElse(null));
        updateBanner(dataStatus);
        updateProgress();
        updateStatusRow();
        updateActionEnablement();
    }

    /** Shows the single most relevant banner. */
    private void updateBanner(Optional<LookThroughDataStatus.Model> status)
    {
        if (header == null || header.getControl().isDisposed())
            return;
        var needsCompositions = presented != null && presented.summary().needsCompositions()
                        && compositionSource != null && hasFunds;
        var facts = new LookThroughBanner.Facts(keyConfigured,
                        status.map(LookThroughDataStatus.Model::isAccessSuspended).orElse(false), needsCompositions,
                        presented != null && presented.empty());
        header.setBanner(LookThroughBanner.select(facts));
    }

    /**
     * Shows the refresh progress in the summary row. It matters most during
     * a long first pass: a broad fund needs one provider lookup per
     * constituent, so the table alone is not enough feedback.
     */
    private void updateProgress()
    {
        if (header == null || header.getControl().isDisposed())
            return;
        var model = statusModel;
        var refreshing = model != null && model.isProgressVisible() && model.total() > 0;
        var total = refreshing ? model.total() : 0;
        header.setProgress(refreshing, refreshing ? total - model.outstanding() : 0, total);
    }

    private void updateActionEnablement()
    {
        if (refreshAction != null)
            refreshAction.setEnabled(canRefresh());

        var viewToolBarManager = getViewToolBarManager();
        if (viewToolBarManager != null && viewToolBarManager.getControl() != null
                        && !viewToolBarManager.getControl().isDisposed())
            viewToolBarManager.update(true);

        var actionToolBarManager = getToolBarManager();
        if (actionToolBarManager != null && actionToolBarManager.getControl() != null
                        && !actionToolBarManager.getControl().isDisposed())
            actionToolBarManager.update(true);
    }

    /**
     * Refresh is offered when held securities can be looked up and still need
     * a composition, no refresh with visible progress is running and a key is
     * not known to be missing.
     * A quiet automatic pass does not disable it: a manual refresh supersedes
     * that pass.
     */
    private boolean canRefresh()
    {
        var source = compositionSource;
        var model = statusModel;
        return source != null && model != null && hasFunds && !model.isProgressVisible()
                        && (presented == null || presented.summary().needsCompositions())
                        && !keyConfigured.equals(Optional.of(Boolean.FALSE));
    }

    /**
     * Stops waiting for the running refresh. Results that were already
     * applied stay.
     */
    private void stopRefresh()
    {
        var model = statusModel;
        if (model == null)
            return;
        model.cancel();
        updateStatus();
    }

    @Override
    protected void addViewButtons(ToolBarManager toolBarManager)
    {
        groupingDropDown = new DropDown(groupingLabel());
        groupingDropDown.setToolTip(groupingLabel());
        groupingDropDown.setMenuListener(this::groupingMenuAboutToShow);
        toolBarManager.add(groupingDropDown);

        chartDropDown = new DropDown(chartKind().label());
        chartDropDown.setToolTip(chartKind().label());
        chartDropDown.setMenuListener(this::chartMenuAboutToShow);
        toolBarManager.add(chartDropDown);
    }

    /**
     * The refresh action sits in the action toolbar, outside the chevron
     * layout, so it stays reachable when the selectors no longer fit.
     */
    @Override
    protected void addButtons(ToolBarManager toolBarManager)
    {
        refreshAction = new SimpleAction(Messages.LookThroughRefreshAction, a -> {
            var model = statusModel;
            if (model == null)
                return;
            model.refresh();
            updateStatus();
        });
        refreshAction.setEnabled(false);
        toolBarManager.add(refreshAction);
    }

    private void groupingMenuAboutToShow(IMenuManager manager)
    {
        for (var option : Grouping.values())
        {
            var action = new SimpleAction(option.label(), a -> selectGrouping(option));
            action.setChecked(grouping() == option);
            manager.add(action);
        }
    }

    private void chartMenuAboutToShow(IMenuManager manager)
    {
        for (var kind : Kind.values())
        {
            var action = new SimpleAction(kind.label(), a -> selectChart(kind));
            action.setChecked(chartKind() == kind);
            manager.add(action);
        }
    }

    @Override
    protected void addPanePages(List<InformationPanePage> pages)
    {
        super.addPanePages(pages);
        pages.add(new LookThroughDataStatusPane());
    }

    @Override
    public void notifyModelUpdated()
    {
        recalculate(true);
    }

    /**
     * The information pane is created after the body, so the status pushed
     * while building the body is dropped. Push it again once the view is
     * complete.
     */
    @Override
    protected void notifyViewCreationCompleted()
    {
        updateStatus();
        recalculate(true);
        readCredentialState();
        automaticRefresh();
    }

    /** Matches presenter rows by their stable key, other elements by equality. */
    private static final class RowKeyComparer implements IElementComparer
    {
        @Override
        public boolean equals(Object a, Object b)
        {
            if (a instanceof Row first && b instanceof Row second)
                return first.key().equals(second.key());
            return Objects.equals(a, b);
        }

        @Override
        public int hashCode(Object element)
        {
            return element instanceof Row row ? row.key().hashCode() : Objects.hashCode(element);
        }
    }

    /** The row that contains the given row, or null. */
    private Row parentOf(Row row)
    {
        return rowsByKey.get(parentKeys.get(row.key()));
    }

    private static final class RowContentProvider implements ITreeContentProvider
    {
        private static final Object[] NO_CHILDREN = new Object[0];

        private final Function<Row, Row> parents;

        private RowContentProvider(Function<Row, Row> parents)
        {
            this.parents = parents;
        }

        @Override
        public Object[] getElements(Object inputElement)
        {
            return ((List<?>) inputElement).toArray();
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof GroupRow group)
                return group.companies().toArray();
            if (parentElement instanceof CompanyRow company)
                return company.contributions().toArray();
            if (parentElement instanceof CategoryRow category)
                return category.details().toArray();
            return NO_CHILDREN;
        }

        @Override
        public Object getParent(Object element)
        {
            return element instanceof Row row ? parents.apply(row) : null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            if (element instanceof GroupRow group)
                return !group.companies().isEmpty();
            if (element instanceof CompanyRow company)
                return !company.contributions().isEmpty();
            if (element instanceof CategoryRow category)
                return !category.details().isEmpty();
            return false;
        }
    }
}
