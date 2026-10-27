package name.abuchen.portfolio.ui.views.lookthrough;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.online.impl.GleifCompanyRegister;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentCache;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.HeldSecurities;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.preferences.EclipseCompositionPersistencePreferences;
import name.abuchen.portfolio.ui.preferences.EulerpoolPreferencePage;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.GlobalFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;

/**
 * Application wiring with one provider budget/session across clients and views.
 * The scope is every security with a nonzero net position across the client's
 * portfolios, not an external selection: the view is a portfolio x-ray and needs
 * no prior selection step. Only held identifiers are sent; holding does not
 * assert fund or equity classification. Unverified evidence stays unverified in
 * the calculation and never becomes validated coverage. Real provider data stays
 * in memory unless the user confirmed local storage for the account and kept
 * the persistence preference on; it is then stored in PP's workspace state area.
 */
@Creatable
@Singleton
public final class LookThroughCompositionService
{
    private static final class Backend
    {
        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "lookthrough-composition"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        private static final CompositionCredentials CREDENTIALS = DevelopmentCredentials
                        .install(CompositionCredentials.local(), EulerpoolPreferencePage.ACCOUNT);
        private static final CompositionPersistencePreferences PREFERENCES = new EclipseCompositionPersistencePreferences();
        private static final EulerpoolFundCompositionProvider PROVIDER = new EulerpoolFundCompositionProvider(
                        account -> CREDENTIALS.getBearerToken(account), PREFERENCES::isLocalStorageConfirmed);
        private static final LookThroughSession SESSION = new LookThroughSession(PROVIDER, PROVIDER,
                        CREDENTIALS, Clock.systemUTC(), EXECUTOR, LookThroughSession.storeIn(stateDirectory(),
                                        Clock.systemUTC()));
        private static final CompanyEnrichmentCache COMPANIES = new CompanyEnrichmentCache(
                        privateDirectory(COMPANY_FOLDER), Clock.systemUTC());

        static
        {
            // Register data is openly licensed and always cached; profiles
            // follow the account's storage settings like compositions. Two
            // threads: the provider lane and the slower register lane run
            // side by side; retries are scheduled on the same pool.
            var sequence = new AtomicInteger();
            var enrichment = Executors.newScheduledThreadPool(2, runnable -> {
                var thread = new Thread(runnable, "lookthrough-enrichment-" + sequence.incrementAndGet()); //$NON-NLS-1$
                thread.setDaemon(true);
                return thread;
            });
            SESSION.enableEnrichment(EulerpoolPreferencePage.ACCOUNT, PROVIDER::profile,
                            PROVIDER::revenueRegions, new GleifCompanyRegister(),
                            PREFERENCES::isRegisterLookupEnabled, COMPANIES, enrichment,
                            (task, delay) -> enrichment.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS));
            // Stored register data is read once in the background, never by
            // the first snapshot on the user interface thread.
            enrichment.execute(COMPANIES::preload);
        }

        static
        {
            // Changed settings apply at once; withdrawn data is deleted without
            // waiting for the Breakdown view. Keys are known to the preferences
            // only after a first read.
            PREFERENCES.addListener(Backend::apply);
            apply(EulerpoolPreferencePage.ACCOUNT);
        }

        private static Path stateDirectory()
        {
            var plugin = PortfolioPlugin.getDefault();
            return plugin == null ? null : plugin.getStateLocation().toFile().toPath().resolve(CACHE_FOLDER);
        }

        /** The private cache directory in the state area, or null for memory-only caching. */
        private static Path privateDirectory(String folder)
        {
            var plugin = PortfolioPlugin.getDefault();
            if (plugin == null)
                return null;
            try
            {
                var directory = plugin.getStateLocation().toFile().toPath().resolve(folder);
                Files.createDirectories(directory);
                if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
                    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); //$NON-NLS-1$
                return directory.toRealPath();
            }
            catch (IOException | RuntimeException e)
            {
                PortfolioPlugin.log(e);
                return null;
            }
        }

        private static void apply(ProviderAccount account)
        {
            if (EulerpoolPreferencePage.ACCOUNT.equals(account))
                configure(PROVIDER.getPersistencePolicy(account), PREFERENCES.get(account));
        }

        private static void configure(CompositionPersistencePolicy policy, Optional<Boolean> preference)
        {
            SESSION.configure(policy, preference);
            COMPANIES.configure(policy, preference);
        }
    }

    /**
     * Starts the backend lazily: the first call reads and prunes the stores,
     * which happens on the thread that calls first, never when a view only
     * keeps the gateway.
     */
    private static final RefreshGateway GATEWAY = new RefreshGateway()
    {
        @Override
        public CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument fund)
        {
            return Backend.SESSION.read(account, fund);
        }

        @Override
        public CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument fund, boolean manual)
        {
            return Backend.SESSION.refresh(account, fund, manual);
        }
    };

    private static final Executor STARTUP = runnable -> {
        var thread = new Thread(runnable, "lookthrough-startup"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    };

    private static final String CACHE_FOLDER = "lookthrough-cache"; //$NON-NLS-1$
    private static final String COMPANY_FOLDER = "lookthrough-companies"; //$NON-NLS-1$

    /** Applies the stored provider settings to the shared session, deleting data of withdrawn storage. */
    public static void reconfigure()
    {
        Backend.apply(EulerpoolPreferencePage.ACCOUNT);
    }

    public LookThroughCompositionSource sourceFor(Client client)
    {
        return client == null ? null : new SessionSource(client);
    }

    static CompositionInstrument instrumentOf(Security security)
    {
        var isin = security.getIsin();
        return new CompositionInstrument(Objects.toString(security.getName(), ""), //$NON-NLS-1$
                        isin == null || isin.isBlank() ? Map.of() : Map.of("ISIN", isin.trim())); //$NON-NLS-1$
    }

    static List<FundRef> fundsOf(List<Security> securities)
    {
        var byIsin = new LinkedHashMap<String, FundRef>();
        for (var security : securities)
        {
            var instrument = instrumentOf(security);
            var isin = instrument.identifiers().get("ISIN"); //$NON-NLS-1$
            if (isin != null)
                byIsin.putIfAbsent(isin, new FundRef(EulerpoolPreferencePage.ACCOUNT, instrument));
        }
        return List.copyOf(byIsin.values());
    }

    private final class SessionSource implements LookThroughCompositionSource
    {
        private final Client client;

        private SessionSource(Client client)
        {
            this.client = client;
        }

        @Override
        public RefreshGateway gateway()
        {
            return GATEWAY;
        }

        @Override
        public GlobalFacts globalFacts()
        {
            var policy = Backend.PROVIDER.getPersistencePolicy(EulerpoolPreferencePage.ACCOUNT);
            var preference = Backend.PREFERENCES.get(EulerpoolPreferencePage.ACCOUNT);
            Backend.configure(policy, preference);
            var effective = policy.effectiveModeDetail(preference);
            return new GlobalFacts(effective.mode() == StorageMode.PERSISTENT ? CompositionStore.State.PERSISTENT
                            : CompositionStore.State.SESSION_ONLY, effective);
        }

        @Override
        public List<Security> securities()
        {
            return HeldSecurities.of(client, LocalDate.now());
        }

        @Override
        public List<FundRef> funds()
        {
            return fundsOf(securities());
        }

        @Override
        public List<InstrumentEvidence> classifications()
        {
            return Backend.SESSION.classifications();
        }

        @Override
        public void enrich(LookThroughResult result)
        {
            Backend.SESSION.enrich(result.companies());
        }

        @Override
        public void stopEnrichment()
        {
            Backend.SESSION.stopEnrichment();
        }

        @Override
        public CompanyEnrichmentService.Snapshot companyAttributes()
        {
            return Backend.SESSION.companyAttributes();
        }

        @Override
        public Runnable addEnrichmentListener(Runnable listener)
        {
            // Registering starts the backend, which reads the stores; never on
            // the user interface thread that creates the view.
            var registration = CompletableFuture
                            .supplyAsync(() -> Backend.SESSION.addEnrichmentListener(listener), STARTUP);
            return () -> registration.thenAccept(Runnable::run);
        }

        @Override
        public Optional<Boolean> keyConfigured()
        {
            var mode = Backend.CREDENTIALS.state(EulerpoolPreferencePage.ACCOUNT).mode();
            return Optional.of(mode == CompositionCredentials.Mode.SECURE
                            || mode == CompositionCredentials.Mode.SESSION_ONLY);
        }
    }
}
