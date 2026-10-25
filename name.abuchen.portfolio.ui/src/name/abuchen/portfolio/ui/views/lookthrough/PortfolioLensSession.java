package name.abuchen.portfolio.ui.views.lookthrough;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.CompositionCredentials.Mode;
import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Disposition;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Refresh;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Snapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRequestBudget;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentClassifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.snapshot.lookthrough.SessionCompositionStore;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;

/**
 * One provider session shared across clients. Serial admission drains the held
 * portfolio without overflowing the coordinator's bounded transport capacity. Queued and
 * running requests coalesce by account and identifiers, independent of labels.
 * Credential storage and all request admission run off the UI thread.
 */
final class PortfolioLensSession implements RefreshGateway
{
    private record Key(ProviderAccount account, Map<String, String> identifiers)
    {
    }

    private record Configuration(CompositionPersistencePolicy policy, Optional<Boolean> preference)
    {
    }

    private final CompositionCredentials credentials;
    private final Executor executor;
    private final SessionCompositionStore store;
    private final CompositionRefreshCoordinator coordinator;
    private final InstrumentClassifier classifier;
    private final CompositionRequestBudget budget;
    private final long classificationCost;
    private final Map<Key, CompletableFuture<List<InstrumentEvidence>>> evidenceCache = new HashMap<>();
    private final Map<Key, CompletableFuture<Refresh>> pending = new HashMap<>();
    private final Map<ProviderAccount, Configuration> configurations = new HashMap<>();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    PortfolioLensSession(FundCompositionProvider provider, InstrumentClassifier classifier,
                    CompositionCredentials credentials, Clock clock, Executor executor)
    {
        this.credentials = credentials;
        this.executor = executor;
        this.classifier = classifier;
        this.store = new SessionCompositionStore(clock);
        // Conservative application budget, not a statement of plan entitlement.
        // Reserve the adapter's worst-case request count, including fallbacks.
        this.classificationCost = classifier instanceof EulerpoolFundCompositionProvider
                        ? EulerpoolFundCompositionProvider.CLASSIFICATION_REQUEST_COST : 1;
        this.budget = new CompositionRequestBudget(provider.getId(), clock, 20000, Duration.ofHours(24),
                        Duration.ofMinutes(15));
        this.coordinator = new CompositionRefreshCoordinator(provider, budget, clock, executor,
                        classificationExecutor(), 1, classificationCost, 5000, 1, this::classifyCached);
    }

    private static Executor classificationExecutor()
    {
        var sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            var thread = new Thread(task, "portfolio-lens-classification-" + sequence.incrementAndGet()); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(8, factory);
    }

    /**
     * Session-wide classification cache: one provider answer per identifier, so
     * overlapping funds share their constituents without repeated lookups.
     */
    private List<InstrumentEvidence> classifyCached(ProviderAccount account,
                    CompositionInstrument instrument) throws IOException
    {
        var key = classificationKey(account, instrument);
        var created = new CompletableFuture<List<InstrumentEvidence>>();
        CompletableFuture<List<InstrumentEvidence>> future;
        synchronized (evidenceCache)
        {
            future = evidenceCache.get(key);
            if (future == null)
            {
                future = created;
                evidenceCache.put(key, future);
            }
        }
        if (future == created)
        {
            try
            {
                future.complete(classifier.classify(account, instrument).map(List::of).orElseGet(List::of));
            }
            catch (IOException e)
            {
                synchronized (evidenceCache)
                {
                    evidenceCache.remove(key, future);
                }
                future.completeExceptionally(e);
            }
            catch (RuntimeException e)
            {
                synchronized (evidenceCache)
                {
                    evidenceCache.remove(key, future);
                }
                future.completeExceptionally(e);
            }
        }
        try
        {
            return future.get();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        catch (ExecutionException e)
        {
            if (e.getCause() instanceof IOException io)
                throw io;
            if (e.getCause() instanceof RuntimeException runtime)
                throw runtime;
            throw new IOException(e.getCause());
        }
    }

    private Key classificationKey(ProviderAccount account, CompositionInstrument instrument)
    {
        var isin = instrument.identifiers().get(InstrumentEvidence.ISIN);
        return new Key(account, isin == null || isin.isBlank() ? instrument.identifiers()
                        : Map.of(InstrumentEvidence.ISIN, isin));
    }

    synchronized void configure(CompositionPersistencePolicy policy, Optional<Boolean> preference)
    {
        var configuration = new Configuration(policy, preference);
        if (!configuration.equals(configurations.put(policy.account(), configuration)))
            store.configure(policy, preference);
    }

    @Override
    public Snapshot read(ProviderAccount account, CompositionInstrument fund)
    {
        var latest = coordinator.read(account, fund);
        var retained = store.read(account, fund);
        // Keep source previews and attempt dates even when validation rejected
        // the payload. Only the store decides whether data is usable/retained.
        return new Snapshot(latest.attemptedAt(), latest.latestAttempt(),
                        retained.usableComposition().map(CompositionValidationResult::source),
                        latest.unverifiedComposition(), latest.resolutions());
    }

    @Override
    public synchronized CompletableFuture<Refresh> refresh(ProviderAccount account, CompositionInstrument fund,
                    boolean manual)
    {
        var key = new Key(account, fund.identifiers());
        var existing = pending.get(key);
        if (existing != null)
            return existing;

        var result = new CompletableFuture<Refresh>();
        pending.put(key, result);
        var work = tail.thenComposeAsync(ignored -> retrieve(account, fund, manual), executor);
        // Even failures must allow the next distinct fund to be admitted.
        tail = work.handle((value, failure) -> null);
        work.whenComplete((value, failure) -> {
            synchronized (this)
            {
                pending.remove(key, result);
            }
            if (failure != null)
                result.completeExceptionally(failure);
            else
                result.complete(value);
        });
        return result;
    }

    private CompletableFuture<Refresh> retrieve(ProviderAccount account, CompositionInstrument fund, boolean manual)
    {
        var state = credentials.state(account);
        coordinator.configurationChanged(account, state.revision());
        if (!manual && state.mode() != Mode.SECURE && state.mode() != Mode.SESSION_ONLY)
            return CompletableFuture.completedFuture(new Refresh(Disposition.SUSPENDED, read(account, fund),
                            Optional.empty()));

        var ticket = store.beginRefresh(account, fund);
        return coordinator.refresh(account, fund, manual).thenApply(refresh -> {
            // Classify the held instrument itself (fund, stock or bond) so a
            // direct holding resolves to an issuer, not to an unresolved row.
            try
            {
                if (refresh.disposition() == Disposition.COMPLETED
                                || refresh.disposition() == Disposition.NO_COMPOSITION)
                {
                    boolean cached;
                    synchronized (evidenceCache)
                    {
                        cached = evidenceCache.containsKey(classificationKey(account, fund));
                    }
                    if (cached || budget.acquirePermit(account, classificationCost))
                        classifyCached(account, fund);
                }
            }
            catch (IOException e)
            {
                // A failed classification does not discard the composition or
                // make the refresh fail; it remains uncached for the next pass.
                budget.record(account, e instanceof CompositionRefreshCoordinator.EnrichmentFailure failure
                                ? failure.result() : new CompositionResult(CompositionResult.Status.FAILED,
                                                Optional.empty(), Optional.empty()));
            }
            // Deferred reads are not new attempts and must not rewrite the store.
            if (refresh.disposition() == Disposition.COMPLETED)
                refresh.snapshot().latestAttempt().ifPresent(attempt -> store.complete(ticket, attempt));
            return new Refresh(refresh.disposition(), read(account, fund), refresh.retryAt());
        });
    }

    /** Cached classification of held instruments and constituents, exposed to the analysis. */
    List<InstrumentEvidence> classifications()
    {
        synchronized (evidenceCache)
        {
            return evidenceCache.values().stream().filter(CompletableFuture::isDone)
                            .filter(future -> !future.isCompletedExceptionally())
                            .flatMap(future -> future.join().stream()).toList();
        }
    }
}
