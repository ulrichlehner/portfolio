package name.abuchen.portfolio.ui.views.lookthrough;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.CompositionCredentials.Mode;
import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentCache;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
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
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolver;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.snapshot.lookthrough.SessionCompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.SessionCompositionStore.NoComposition;
import name.abuchen.portfolio.snapshot.lookthrough.VersionedCompositionStore;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;

/**
 * One provider session shared across clients. Serial admission drains the held
 * portfolio without overflowing the coordinator's bounded transport capacity. Queued and
 * running requests coalesce by account and identifiers, independent of labels.
 * Credential storage and all request admission run off the UI thread.
 */
final class LookThroughSession implements RefreshGateway
{
    private record Key(ProviderAccount account, Map<String, String> identifiers)
    {
    }

    private record Configuration(CompositionPersistencePolicy policy, Optional<Boolean> preference)
    {
    }

    private final CompositionCredentials credentials;
    private final Clock clock;
    private final Executor executor;
    private final SessionCompositionStore store;
    private final CompositionRefreshCoordinator coordinator;
    private final InstrumentClassifier classifier;
    private final CompositionRequestBudget budget;
    private final long classificationCost;
    private final Map<Key, CompletableFuture<List<InstrumentEvidence>>> evidenceCache = new HashMap<>();
    /** Identifiers known to have no stored answer, so reads do not repeat disk lookups. */
    private final Set<Key> storeMisses = new HashSet<>();
    /** Stored answers without composition per fund, read from the store once per configuration. */
    private final Map<Key, Optional<NoComposition>> noCompositions = new HashMap<>();
    private final Map<Key, CompletableFuture<Refresh>> pending = new HashMap<>();
    private final Map<ProviderAccount, Configuration> configurations = new HashMap<>();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private volatile CompanyEnrichmentService enrichment;
    private final List<Runnable> enrichmentListeners = new CopyOnWriteArrayList<>();

    LookThroughSession(FundCompositionProvider provider, InstrumentClassifier classifier,
                    CompositionCredentials credentials, Clock clock, Executor executor)
    {
        this(provider, classifier, credentials, clock, executor, new SessionCompositionStore(clock));
    }

    /** The store keeps compositions and classifications on disk only while its effective mode is persistent. */
    LookThroughSession(FundCompositionProvider provider, InstrumentClassifier classifier,
                    CompositionCredentials credentials, Clock clock, Executor executor,
                    SessionCompositionStore store)
    {
        this.credentials = credentials;
        this.clock = clock;
        this.executor = executor;
        this.classifier = classifier;
        this.store = store;
        // Conservative application budget, not a statement of plan entitlement.
        // Reserve the adapter's worst-case request count, including fallbacks.
        this.classificationCost = classifier instanceof EulerpoolFundCompositionProvider
                        ? EulerpoolFundCompositionProvider.CLASSIFICATION_REQUEST_COST : 1;
        this.budget = new CompositionRequestBudget(provider.getId(), clock, 20000, Duration.ofHours(24),
                        Duration.ofMinutes(15));
        this.coordinator = new CompositionRefreshCoordinator(provider, budget, clock, executor,
                        classificationExecutor(), 1, classificationCost, 5000, 1, this::classifyCached);
    }

    /**
     * Versioned store in the given private cache directory; session-only memory
     * when the directory is missing or cannot be prepared. The store writes
     * nothing until the account's effective mode is persistent.
     */
    static SessionCompositionStore storeIn(Path directory, Clock clock)
    {
        if (directory == null)
            return new SessionCompositionStore(clock);
        try
        {
            Files.createDirectories(directory);
            if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); //$NON-NLS-1$
            // The store refuses directories reached through links.
            return new VersionedCompositionStore(directory.toRealPath(), clock);
        }
        catch (IOException | RuntimeException e)
        {
            PortfolioPlugin.log(e);
            return new SessionCompositionStore(clock);
        }
    }

    private static Executor classificationExecutor()
    {
        var sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            var thread = new Thread(task, "lookthrough-classification-" + sequence.incrementAndGet()); //$NON-NLS-1$
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
                // Captured before the lookup, so an answer arriving after a
                // withdrawal of local storage is not written.
                var generation = store.generation(account);
                var stored = store.readEvidence(account, key.identifiers());
                if (stored.isPresent())
                {
                    future.complete(stored.get());
                }
                else
                {
                    var evidence = classifier.classify(account, instrument).map(List::of).orElseGet(List::of);
                    store.writeEvidence(account, key.identifiers(), evidence, generation);
                    future.complete(evidence);
                }
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

    /** Loads a stored classification answer into the session cache; returns whether one is known. */
    private boolean restoreEvidence(ProviderAccount account, CompositionInstrument instrument)
    {
        var key = classificationKey(account, instrument);
        synchronized (evidenceCache)
        {
            if (evidenceCache.containsKey(key))
                return true;
            if (storeMisses.contains(key))
                return false;
        }
        var stored = store.readEvidence(account, key.identifiers());
        synchronized (evidenceCache)
        {
            if (stored.isEmpty())
            {
                storeMisses.add(key);
                return false;
            }
            evidenceCache.putIfAbsent(key, CompletableFuture.completedFuture(stored.get()));
            return true;
        }
    }

    /** Resolutions of a stored composition from stored classifications, without any provider request. */
    private List<InstrumentResolution> storedResolutions(ProviderAccount account, FundComposition composition)
    {
        var resolver = new InstrumentResolver();
        var resolutions = new ArrayList<InstrumentResolution>();
        for (var row : composition.constituents())
        {
            var instrument = row.instrument();
            restoreEvidence(account, instrument);
            List<InstrumentEvidence> known = List.of();
            synchronized (evidenceCache)
            {
                var future = evidenceCache.get(classificationKey(account, instrument));
                if (future != null && future.isDone() && !future.isCompletedExceptionally())
                    known = future.join();
            }
            resolutions.add(resolver.resolve(instrument, known, ignored -> List.of(), () -> false));
        }
        return List.copyOf(resolutions);
    }

    synchronized void configure(CompositionPersistencePolicy policy, Optional<Boolean> preference)
    {
        var configuration = new Configuration(policy, preference);
        if (!configuration.equals(configurations.put(policy.account(), configuration)))
        {
            store.configure(policy, preference);
            synchronized (evidenceCache)
            {
                storeMisses.clear();
                noCompositions.clear();
            }
        }
    }

    /** The stored provider answer that the fund has no composition, if known. */
    private Optional<NoComposition> storedNoComposition(ProviderAccount account, CompositionInstrument fund)
    {
        var key = new Key(account, fund.identifiers());
        synchronized (evidenceCache)
        {
            var known = noCompositions.get(key);
            if (known != null)
                return known;
        }
        var stored = store.readNoComposition(account, fund.identifiers());
        synchronized (evidenceCache)
        {
            noCompositions.put(key, stored);
        }
        return stored;
    }

    @Override
    public Snapshot read(ProviderAccount account, CompositionInstrument fund)
    {
        var latest = coordinator.read(account, fund);
        var retained = store.read(account, fund);
        var resolutions = latest.resolutions();
        // After a restart a stored composition is shown from stored classifications
        // until a refresh replaces them.
        if (resolutions.isEmpty() && latest.latestAttempt().isEmpty() && retained.usableComposition().isPresent())
        {
            restoreEvidence(account, fund);
            resolutions = storedResolutions(account, retained.usableComposition().get().source());
        }
        // A stored answer without composition shows as such after a restart.
        if (latest.latestAttempt().isEmpty() && latest.attemptedAt().isEmpty()
                        && retained.usableComposition().isEmpty())
        {
            var absent = storedNoComposition(account, fund);
            if (absent.isPresent())
                return new Snapshot(Optional.of(absent.get().storedAt()),
                                Optional.of(new CompositionResult(absent.get().status(), Optional.empty(),
                                                Optional.empty())),
                                Optional.empty(), latest.unverifiedComposition(), resolutions);
        }
        // Keep source previews and attempt dates even when validation rejected
        // the payload. Only the store decides whether data is usable/retained.
        return new Snapshot(latest.attemptedAt(), latest.latestAttempt(),
                        retained.usableComposition().map(CompositionValidationResult::source),
                        latest.unverifiedComposition(), resolutions);
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

        // A composition stored within the last cadence interval is current
        // enough: an automatic pass after a restart does not request it again.
        if (!manual)
        {
            var untouched = coordinator.read(account, fund).attemptedAt().isEmpty();
            var stored = untouched ? store.read(account, fund).usableComposition()
                            : Optional.<CompositionValidationResult>empty();
            if (stored.isPresent())
            {
                var next = stored.get().source().provenance().retrievedAt().plus(Duration.ofHours(24));
                if (next.isAfter(clock.instant()))
                {
                    restoreEvidence(account, fund);
                    return CompletableFuture.completedFuture(
                                    new Refresh(Disposition.CADENCE, read(account, fund), Optional.of(next)));
                }
            }
            // The same applies to a stored answer without composition, such
            // as for a directly held share.
            var absent = untouched && stored.isEmpty() ? storedNoComposition(account, fund)
                            : Optional.<NoComposition>empty();
            if (absent.isPresent() && absent.get().storedAt().plus(Duration.ofHours(24)).isAfter(clock.instant()))
            {
                classifyHeld(account, fund);
                return CompletableFuture.completedFuture(
                                new Refresh(Disposition.NO_COMPOSITION, read(account, fund), Optional.empty()));
            }
        }

        var ticket = store.beginRefresh(account, fund);
        // Captured before the request, so an answer arriving after a
        // withdrawal of local storage is not written.
        var generation = store.generation(account);
        return coordinator.refresh(account, fund, manual).thenApply(refresh -> {
            if (refresh.disposition() == Disposition.COMPLETED
                            || refresh.disposition() == Disposition.NO_COMPOSITION)
                classifyHeld(account, fund);
            var status = refresh.snapshot().latestAttempt().map(CompositionResult::status);
            if (refresh.disposition() == Disposition.COMPLETED && status.isPresent()
                            && (status.get() == CompositionResult.Status.UNAVAILABLE
                                            || status.get() == CompositionResult.Status.UNSUPPORTED))
            {
                store.writeNoComposition(account, fund.identifiers(), status.get(), generation);
                synchronized (evidenceCache)
                {
                    noCompositions.remove(new Key(account, fund.identifiers()));
                }
            }
            // Deferred reads are not new attempts and must not rewrite the store.
            if (refresh.disposition() == Disposition.COMPLETED)
                refresh.snapshot().latestAttempt().ifPresent(attempt -> store.complete(ticket, attempt));
            return new Refresh(refresh.disposition(), read(account, fund), refresh.retryAt());
        });
    }

    /**
     * Classifies the held instrument itself (fund, stock or bond) so a direct
     * holding resolves to an issuer, not to an unresolved row.
     */
    private void classifyHeld(ProviderAccount account, CompositionInstrument fund)
    {
        try
        {
            // A stored answer needs no provider request and no budget.
            boolean cached = restoreEvidence(account, fund);
            if (cached || budget.acquirePermit(account, classificationCost))
                classifyCached(account, fund);
        }
        catch (IOException e)
        {
            // A failed classification does not discard the composition or
            // make the refresh fail; it remains uncached for the next pass.
            budget.record(account, e instanceof CompositionRefreshCoordinator.EnrichmentFailure failure
                            ? failure.result() : new CompositionResult(CompositionResult.Status.FAILED,
                                            Optional.empty(), Optional.empty()));
        }
    }

    /**
     * Enables company enrichment with this session's provider budget, so
     * profile requests and composition requests share one allowance. Profile
     * requests run only while a key is configured for the account.
     */
    CompanyEnrichmentService enableEnrichment(ProviderAccount account, CompanyEnrichmentService.ProfileSource profiles,
                    CompanyEnrichmentService.Register register, BooleanSupplier registerEnabled,
                    CompanyEnrichmentCache cache, Executor enrichmentExecutor)
    {
        var service = new CompanyEnrichmentService(account, profiles, () -> {
            var mode = credentials.state(account).mode();
            return mode == Mode.SECURE || mode == Mode.SESSION_ONLY;
        }, register, registerEnabled, cache, budget, clock, enrichmentExecutor);
        service.addListener(this::notifyEnrichmentListeners);
        enrichment = service;
        return service;
    }

    /**
     * Calls the listener on a background thread whenever company attributes
     * changed. Returns the action that removes the listener.
     */
    Runnable addEnrichmentListener(Runnable listener)
    {
        enrichmentListeners.add(listener);
        return () -> enrichmentListeners.remove(listener);
    }

    private void notifyEnrichmentListeners()
    {
        for (var listener : enrichmentListeners)
            listener.run();
    }

    /**
     * Schedules the enrichment of a result's companies in the background. It
     * neither blocks nor changes the calculation; companies with fresh cached
     * attributes cause no request.
     */
    void enrich(List<LookThroughResult.Company> companies)
    {
        var service = enrichment;
        if (service != null)
            service.schedule(companies);
    }

    /** Company attributes and pending count of the latest scheduled result; memory-only. */
    CompanyEnrichmentService.Snapshot companyAttributes()
    {
        var service = enrichment;
        return service == null ? CompanyEnrichmentService.Snapshot.empty() : service.snapshot();
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
