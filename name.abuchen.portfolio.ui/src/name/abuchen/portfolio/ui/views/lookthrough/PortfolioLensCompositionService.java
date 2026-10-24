package name.abuchen.portfolio.ui.views.lookthrough;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRequestBudget;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.snapshot.lookthrough.SessionCompositionStore;
import name.abuchen.portfolio.ui.preferences.EclipseCompositionPersistencePreferences;
import name.abuchen.portfolio.ui.preferences.EulerpoolPreferencePage;
import name.abuchen.portfolio.ui.selection.SelectionService;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.GlobalFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;

/**
 * Application wiring of the composition backend for Portfolio Lens. It creates
 * one session per client with the discovered Eulerpool adapter, the shared
 * request budget, the refresh coordinator and a session-only composition store,
 * and exposes it to the view as a {@link LookThroughCompositionSource}.
 * <p>
 * Only explicitly selected securities are candidates for a refresh, and only
 * their ISIN leaves the application. The portfolio model has no fund
 * classification, so unselected holdings are never sent: classification evidence
 * stays the open gate of task 1.3 and no name or ISIN is guessed into a fund.
 * <p>
 * Real-data disk persistence stays disabled: the provider reports unverified
 * persistence permission, so the effective mode is session only regardless of the
 * local preference, and the store keeps compositions in memory until exit. The
 * session store is the read source, so the status and any later calculation see
 * the same retained data that a refresh wrote.
 * <p>
 * The request budget is a conservative caller-supplied placeholder, not a
 * published provider allowance: 100 requests per rolling 24 hours with a 15
 * minute failure backoff. Enrichment lookups stay at zero because no verified
 * instrument evidence service exists yet, so a fund is never resolved from an
 * unverified source. Sessions are shared by client across view parts, because a
 * second part must not double the provider traffic.
 */
@Creatable
@Singleton
public final class PortfolioLensCompositionService
{
    private static final String ISIN = "ISIN"; //$NON-NLS-1$

    private static final long REQUESTS_PER_WINDOW = 100;
    private static final Duration REQUEST_WINDOW = Duration.ofHours(24);
    private static final Duration FAILURE_BACKOFF = Duration.ofMinutes(15);
    private static final int MAX_PENDING_REQUESTS = 2;

    private static final ProviderAccount ACCOUNT = EulerpoolPreferencePage.ACCOUNT;

    private static final Map<Client, Session> SESSIONS = Collections.synchronizedMap(new WeakHashMap<>());

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "PortfolioLens-composition"); //$NON-NLS-1$
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    private SelectionService selectionService;

    private final CompositionCredentials credentials = CompositionCredentials.local();
    private final CompositionPersistencePreferences preferences = new EclipseCompositionPersistencePreferences();

    /**
     * Returns the source for the given client, or {@code null} without a client.
     * Every call re-applies the current persistence policy and preference, so a
     * preference change takes effect on the next status build.
     */
    public LookThroughCompositionSource sourceFor(Client client)
    {
        if (client == null)
            return null;

        Session session;
        synchronized (SESSIONS)
        {
            session = SESSIONS.computeIfAbsent(client, ignored -> new Session(credentials));
        }
        session.configure(preferences.get(ACCOUNT));
        return new SessionSource(session, client);
    }

    /**
     * Maps selected securities to refresh candidates. A security without an ISIN
     * cannot be identified by the adapter and is left out; duplicates keep the
     * first label. This is the only place where portfolio data turns into a
     * provider request identifier.
     */
    static List<FundRef> fundsOf(List<Security> securities)
    {
        Objects.requireNonNull(securities);
        var byIsin = new LinkedHashMap<String, FundRef>();
        for (var security : securities)
        {
            var isin = security.getIsin();
            if (isin == null || isin.isBlank())
                continue;
            var identifier = isin.trim();
            var label = Objects.toString(security.getName(), ""); //$NON-NLS-1$
            byIsin.putIfAbsent(identifier,
                            new FundRef(ACCOUNT, new CompositionInstrument(label, Map.of(ISIN, identifier))));
        }
        return List.copyOf(byIsin.values());
    }

    /** One application-lifetime backend session for a client. */
    private static final class Session
    {
        private final CompositionPersistencePolicy policy;
        private final SessionCompositionStore store;
        private final RefreshGateway gateway;

        private Session(CompositionCredentials credentials)
        {
            var clock = Clock.systemUTC();
            var provider = new EulerpoolFundCompositionProvider(account -> credentials.getBearerToken(account));
            this.policy = provider.getPersistencePolicy(ACCOUNT);
            this.store = new SessionCompositionStore(clock);
            var budget = new CompositionRequestBudget(provider.getId(), clock, REQUESTS_PER_WINDOW, REQUEST_WINDOW,
                            FAILURE_BACKOFF);
            var coordinator = new CompositionRefreshCoordinator(provider, budget, clock, EXECUTOR, 1, 1, 0,
                            MAX_PENDING_REQUESTS, Session::noEvidence);
            this.gateway = new StoreGateway(store, coordinator);
        }

        /** No verified evidence source is configured; never resolve from guesses. */
        private static List<InstrumentEvidence> noEvidence(ProviderAccount account, CompositionInstrument fund)
        {
            return List.of();
        }

        private void configure(Optional<Boolean> persistencePreference)
        {
            store.configure(policy, persistencePreference);
        }
    }

    /** The source handed to the view; every method is memory-only. */
    private final class SessionSource implements LookThroughCompositionSource
    {
        private final Session session;
        private final Client client;

        private SessionSource(Session session, Client client)
        {
            this.session = session;
            this.client = client;
        }

        @Override
        public RefreshGateway gateway()
        {
            return session.gateway;
        }

        @Override
        public GlobalFacts globalFacts()
        {
            // The session store never writes to disk, so its state is session only
            // by construction; the effective mode adds the policy reason.
            return new GlobalFacts(CompositionStore.State.SESSION_ONLY,
                            session.policy.effectiveModeDetail(preferences.get(ACCOUNT)));
        }

        @Override
        public List<FundRef> funds()
        {
            var selection = selectionService;
            if (selection == null)
                return List.of();
            return selection.getSelection(client).map(value -> fundsOf(value.getSecurities())).orElse(List.of());
        }
    }

    /**
     * Reads retained compositions from the store and keeps it up to date with
     * every coordinated refresh. Reads never request data.
     */
    private static final class StoreGateway implements RefreshGateway
    {
        private final SessionCompositionStore store;
        private final CompositionRefreshCoordinator coordinator;

        private StoreGateway(SessionCompositionStore store, CompositionRefreshCoordinator coordinator)
        {
            this.store = store;
            this.coordinator = coordinator;
        }

        @Override
        public CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument fund)
        {
            var stored = store.read(account, fund);
            return new CompositionRefreshCoordinator.Snapshot(Optional.empty(), attempt(stored),
                            stored.usableComposition().map(CompositionValidationResult::source), List.of());
        }

        private static Optional<CompositionResult> attempt(CompositionStore.Snapshot stored)
        {
            var status = stored.latestAttempt();
            if (status.isEmpty())
                return Optional.empty();
            if (status.orElseThrow() == CompositionResult.Status.RECEIVED)
                return stored.usableComposition().map(usable -> new CompositionResult(
                                CompositionResult.Status.RECEIVED, Optional.of(usable.source()), Optional.empty()));
            return Optional.of(new CompositionResult(status.orElseThrow(), Optional.empty(), Optional.empty()));
        }

        @Override
        public CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument fund, boolean manual)
        {
            // The store requires the ticket before the asynchronous work starts and
            // the actual attempt, never a fallback, when it ends.
            var ticket = store.beginRefresh(account, fund);
            return coordinator.refresh(account, fund, manual).thenApply(refresh -> {
                refresh.snapshot().latestAttempt().ifPresent(attempt -> complete(ticket, attempt));
                return refresh;
            });
        }

        private void complete(CompositionStore.Ticket ticket, CompositionResult attempt)
        {
            try
            {
                store.complete(ticket, attempt);
            }
            catch (RuntimeException e)
            {
                // A stale ticket or a closed store must not replace the attempt
                // result the caller sees; the retained data stays unchanged.
            }
        }
    }
}
