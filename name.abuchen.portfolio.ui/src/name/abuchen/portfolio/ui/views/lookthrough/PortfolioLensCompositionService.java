package name.abuchen.portfolio.ui.views.lookthrough;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.HeldSecurities;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
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
 * in memory while persistence permission is unverified.
 */
@Creatable
@Singleton
public final class PortfolioLensCompositionService
{
    private static final class Backend
    {
        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "PortfolioLens-composition"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        private static final EulerpoolFundCompositionProvider PROVIDER = new EulerpoolFundCompositionProvider(
                        account -> CompositionCredentials.local().getBearerToken(account));
        private static final PortfolioLensSession SESSION = new PortfolioLensSession(PROVIDER, PROVIDER,
                        CompositionCredentials.local(), Clock.systemUTC(), EXECUTOR);
    }

    private final CompositionPersistencePreferences preferences = new EclipseCompositionPersistencePreferences();

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
            var preference = preferences.get(EulerpoolPreferencePage.ACCOUNT);
            Backend.SESSION.configure(policy, preference);
            return new GlobalFacts(CompositionStore.State.SESSION_ONLY, policy.effectiveModeDetail(preference));
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
    }
}
