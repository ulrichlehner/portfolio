package name.abuchen.portfolio.ui.views.lookthrough;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.HeldSecurities;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
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

        private static void apply(ProviderAccount account)
        {
            if (EulerpoolPreferencePage.ACCOUNT.equals(account))
                SESSION.configure(PROVIDER.getPersistencePolicy(account), PREFERENCES.get(account));
        }
    }

    private static final String CACHE_FOLDER = "lookthrough-cache"; //$NON-NLS-1$

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
            return Backend.SESSION;
        }

        @Override
        public GlobalFacts globalFacts()
        {
            var policy = Backend.PROVIDER.getPersistencePolicy(EulerpoolPreferencePage.ACCOUNT);
            var preference = Backend.PREFERENCES.get(EulerpoolPreferencePage.ACCOUNT);
            Backend.SESSION.configure(policy, preference);
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
        public Optional<Boolean> keyConfigured()
        {
            var mode = Backend.CREDENTIALS.state(EulerpoolPreferencePage.ACCOUNT).mode();
            return Optional.of(mode == CompositionCredentials.Mode.SECURE
                            || mode == CompositionCredentials.Mode.SESSION_ONLY);
        }
    }
}
