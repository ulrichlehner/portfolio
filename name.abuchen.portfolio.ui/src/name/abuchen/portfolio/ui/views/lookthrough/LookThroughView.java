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
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TreeItem;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.FundOverlap;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
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
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.GroupRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Row;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.RowKey;

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
    private static final int[] DEFAULT_WEIGHTS = { 2, 1 };
    /** Enrichment notifications are presented at most this often. */
    private static final Duration ENRICHMENT_UPDATE_INTERVAL = Duration.ofMillis(1500);

    private final LookThroughViewPresenter presenter = new LookThroughViewPresenter();

    private LookThroughHeader header;
    private TreeViewer viewer;
    private LookThroughChartPane chartPane;
    private Composite tableArea;
    private final StackLayout tableStack = new StackLayout();
    private Composite tableContainer;
    private Composite tableMessage;
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
    private LookThroughViewPresenter.Model presented;

    /** Empty until the credential state was read off the UI thread. */
    private Optional<Boolean> keyConfigured = Optional.empty();
    private final Listener shellActivation = event -> readCredentialState();

    private final Runnable automaticRefresh = this::automaticRefresh;
    private volatile boolean disposed;

    /** Memory-only inputs of the calculation, compared to skip recalculation. */
    private record Inputs(List<FundRef> funds, List<CompositionRefreshCoordinator.Snapshot> retained,
                    List<InstrumentEvidence> classifications)
    {
    }

    private ExecutorService calculationExecutor;
    private Shell shell;
    private LookThroughRecalculation<LookThroughResult> recalculation;
    /** Read and written on the calculation thread, reset on the user interface thread. */
    private volatile Inputs calculatedInputs;

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
        return MessageFormat.format(Messages.LookThroughViewTitle, grouping().label());
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
                        applyModel(model, false);
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
                Inputs inputs;
                try
                {
                    var funds = source.funds();
                    var retained = funds.stream().map(fund -> source.gateway().read(fund.account(), fund.fund()))
                                    .toList();
                    inputs = new Inputs(funds, retained, source.classifications());
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
                recalculation.request(() -> calculate(client, exchangeRates, source, inputs));
            });
        }
        catch (RejectedExecutionException e)
        {
            // The view is being disposed; nothing is left to calculate.
        }
    }

    private static LookThroughResult calculate(Client client, ExchangeRateProviderFactory rates,
                    LookThroughCompositionSource source, Inputs inputs)
    {
        var converter = new CurrencyConverterImpl(rates, client.getBaseCurrency());
        var snapshot = ClientSnapshot.create(client, converter, LocalDate.now());
        return new LookThroughAnalysis().calculate(snapshot, rates, source.securities(), inputs.retained(),
                        inputs.classifications());
    }

    private void automaticRefresh()
    {
        if (disposed)
            return;
        if (statusModel != null)
            statusModel.refreshAutomatically();
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

        header = new LookThroughHeader(container, this::stopRefresh, this::openProviderSettings);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header.getControl());

        // separates the header from chart and table
        createSeparator(container);

        // chart and table side by side; the weights are kept in the preferences
        var sash = new SashForm(container, SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(sash);

        // separates the view from the data status pane below
        createSeparator(container);

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
            applyCompositionSource(compositionService.sourceFor(getClient()));

        shell = container.getShell();
        shell.addListener(SWT.Activate, shellActivation);

        applyResult();
        updateStatus();
        return container;
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
        var column = new Column("instrument", Messages.LookThroughColumnCompany, SWT.NONE, 200); //$NON-NLS-1$
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
                    return label + " (" + detail.sourceText() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
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
                return null;
            }
        });
        column.setRemovable(false);
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

        // The snapshot reads memory only (no credentials, no disk), so it is
        // cheap enough here; enrichment updates are presented off this thread.
        var source = compositionSource;
        var attributes = source != null ? source.companyAttributes()
                        : CompanyEnrichmentService.Snapshot.empty();
        companyData = companyText(attributes);
        applyModel(presenter.present(result, grouping(), attributes), true);
    }

    /** Shows a presented model of the current result; the overlap depends on the result only. */
    private void applyModel(LookThroughViewPresenter.Model model, boolean newResult)
    {
        if (viewer == null || viewer.getControl().isDisposed() || chartPane == null || result == null)
            return;
        presented = model;
        currencyCode = model.currencyCode();
        header.setSummary(model.empty() ? null : model.summary(), model.coverage());
        if (newResult)
            chartPane.setOverlap(FundOverlap.of(result));
        chartPane.setModel(model.chart(), currencyCode,
                        LookThroughViewPresenter.contentState(true, compositionSource != null));
        setRows(model.rows());
        updateStatus();
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
        updateTitle(getDefaultTitle());
        if (groupingDropDown != null)
        {
            groupingDropDown.setLabel(groupingLabel());
            groupingDropDown.setToolTip(Messages.LookThroughBreakdownBy);
        }
        applyResult();
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
        var status = model != null ? model.status() : Optional.<LookThroughDataStatus.Model>empty();
        var shares = presented != null ? presented.decomposedShares() : Map.<Map<String, String>, String>of();
        var classified = presented != null ? presented.directlyClassified() : Set.<Map<String, String>>of();
        var company = companyData;
        setInformationPaneInput(status.map(
                        value -> new LookThroughDataStatusPane.Input(value, shares, classified, company))
                        .orElse(null));
        updateBanner(status);
        updateProgress();
        updateActionEnablement();
    }

    /** Shows the single most relevant banner. */
    private void updateBanner(Optional<LookThroughDataStatus.Model> status)
    {
        if (header == null || header.getControl().isDisposed())
            return;
        var source = compositionSource;
        var needsCompositions = presented != null && presented.summary().needsCompositions() && source != null
                        && !source.funds().isEmpty();
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

        var toolBarManager = getViewToolBarManager();
        if (toolBarManager != null && toolBarManager.getControl() != null
                        && !toolBarManager.getControl().isDisposed())
            toolBarManager.update(true);
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
        return source != null && model != null && !source.funds().isEmpty() && !model.isProgressVisible()
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
        groupingDropDown.setToolTip(Messages.LookThroughBreakdownBy);
        groupingDropDown.setMenuListener(this::groupingMenuAboutToShow);
        toolBarManager.add(groupingDropDown);

        chartDropDown = new DropDown(chartKind().label());
        chartDropDown.setToolTip(chartKind().label());
        chartDropDown.setMenuListener(this::chartMenuAboutToShow);
        toolBarManager.add(chartDropDown);

        refreshAction = new SimpleAction(Messages.LookThroughRefreshAction, a -> {
            var model = statusModel;
            if (model == null)
                return;
            model.refresh();
            updateStatus();
        });
        refreshAction.setEnabled(canRefresh());
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
