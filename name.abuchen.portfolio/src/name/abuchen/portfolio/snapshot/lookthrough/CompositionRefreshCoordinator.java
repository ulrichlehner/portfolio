package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import name.abuchen.portfolio.online.FundCompositionProvider;

/**
 * Application-lifetime, session-only refresh coordination for one provider.
 * Construction and read never submit work. The application explicitly invokes
 * refresh on initial analysis, an automatic eligibility pass or a manual action,
 * never on redraw. There is no internal timer or retry loop. Deferred work needs
 * a later explicit invocation while the application is alive.
 *
 * Automatic cadence is 24 rolling hours from each admitted composition attempt,
 * including failed and manual attempts, not completion or source dates. Manual
 * refresh bypasses only cadence, never quota, backoff or suspension. Identical
 * account/identifier maps coalesce regardless of display label. Configure one
 * coordinator per provider and share its budget with every provider consumer.
 * An instrument that the provider answers without a usable composition is not
 * requested again in the same session: the portfolio model has no fund
 * classification, so one probe per identifier replaces guessing.
 *
 * Costs must cover all requests in each injected bounded operation (including
 * identifier lookup). Enrichment admits at most maxEnrichmentLookups operations
 * per refresh through InstrumentResolver's acquirePermit contract. Only usable
 * validated snapshots are enriched. Prior usable data keeps its original dates
 * after retrieval/validation failures; source previews remain separate. This is
 * not the retention/persistence store. UI jobs and interruptible transport are
 * separate concerns: close prevents queued requests and further enrichment,
 * discards late responses and clears memory, but cannot interrupt an already
 * executing synchronous provider call. The injected executor remains caller-owned.
 */
public final class CompositionRefreshCoordinator implements AutoCloseable
{
    public enum Disposition
    {
        COMPLETED, CADENCE, DEFERRED, SUSPENDED, BUSY, CLOSED, CONFIGURATION_CHANGED,

        /**
         * The provider already answered that this instrument has no usable
         * composition. It is not asked again in this session.
         */
        NO_COMPOSITION
    }

    public record Snapshot(Optional<Instant> attemptedAt, Optional<CompositionResult> latestAttempt,
                    Optional<FundComposition> usableComposition, List<InstrumentResolution> resolutions)
    {
        public Snapshot
        {
            Objects.requireNonNull(attemptedAt);
            Objects.requireNonNull(latestAttempt);
            Objects.requireNonNull(usableComposition);
            resolutions = List.copyOf(resolutions);
        }
    }

    public record Refresh(Disposition disposition, Snapshot snapshot, Optional<Instant> retryAt)
    {
    }

    @FunctionalInterface
    public interface EnrichmentLookup
    {
        List<InstrumentEvidence> lookup(ProviderAccount account, CompositionInstrument instrument) throws IOException;
    }

    /** Optional sanitized failure signal from an enrichment transport. */
    public static final class EnrichmentFailure extends IOException
    {
        private static final long serialVersionUID = 1L;
        private final CompositionResult result;

        public EnrichmentFailure(CompositionResult result)
        {
            Objects.requireNonNull(result);
            if (result.status() == CompositionResult.Status.RECEIVED)
                throw new IllegalArgumentException();
            this.result = result;
        }
    }

    private record Key(ProviderAccount account, Map<String, String> identifiers)
    {
    }

    private static final class Entry
    {
        private Snapshot snapshot = emptySnapshot();
        private Instant retryAt = Instant.MIN;
        /** The provider gave a definitive answer without a usable composition. */
        private boolean noComposition;
        private CompletableFuture<Refresh> pending;
    }

    private final FundCompositionProvider provider;
    private final CompositionRequestBudget budget;
    private final Clock clock;
    private final Executor executor;
    private final long compositionCost;
    private final long enrichmentCost;
    private final int maxEnrichmentLookups;
    private final int maxPending;
    private final EnrichmentLookup lookup;
    private final Map<Key, Entry> entries = new HashMap<>();
    private boolean closed;
    private int pendingCount;

    public CompositionRefreshCoordinator(FundCompositionProvider provider, CompositionRequestBudget budget,
                    Clock clock, Executor executor, long compositionCost, long enrichmentCost,
                    int maxEnrichmentLookups, int maxPending, EnrichmentLookup lookup)
    {
        this.provider = Objects.requireNonNull(provider);
        this.budget = Objects.requireNonNull(budget);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.lookup = Objects.requireNonNull(lookup);
        if (compositionCost <= 0 || enrichmentCost <= 0 || maxEnrichmentLookups < 0 || maxPending <= 0)
            throw new IllegalArgumentException();
        this.compositionCost = compositionCost;
        this.enrichmentCost = enrichmentCost;
        this.maxEnrichmentLookups = maxEnrichmentLookups;
        this.maxPending = maxPending;
    }

    private static Snapshot emptySnapshot()
    {
        return new Snapshot(Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    private Key key(ProviderAccount account, CompositionInstrument fund)
    {
        if (!provider.getId().equals(account.providerId()) || fund.identifiers().isEmpty()
                        || fund.identifiers().entrySet().stream()
                                        .anyMatch(item -> item.getKey().isBlank() || item.getValue().isBlank()))
            throw new IllegalArgumentException();
        return new Key(account, fund.identifiers());
    }

    public synchronized Snapshot read(ProviderAccount account, CompositionInstrument fund)
    {
        var entry = entries.get(key(account, fund));
        return entry == null ? emptySnapshot() : entry.snapshot;
    }

    public synchronized CompletableFuture<Refresh> refresh(ProviderAccount account, CompositionInstrument fund,
                    boolean manual)
    {
        var key = key(account, fund);
        if (closed)
            return CompletableFuture.completedFuture(new Refresh(Disposition.CLOSED, emptySnapshot(), Optional.empty()));
        var entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (entry.pending != null)
            return entry.pending;
        var deferred = deferred(account, entry, manual);
        if (deferred.isPresent())
            return CompletableFuture.completedFuture(deferred.orElseThrow());
        if (pendingCount >= maxPending)
            return CompletableFuture.completedFuture(new Refresh(Disposition.BUSY, entry.snapshot, Optional.empty()));
        var future = new CompletableFuture<Refresh>();
        entry.pending = future;
        pendingCount++;
        try
        {
            executor.execute(() -> retrieve(key, fund, entry, future, manual));
        }
        catch (RejectedExecutionException e)
        {
            finish(entry, future, new Refresh(Disposition.BUSY, entry.snapshot, Optional.empty()));
        }
        return future;
    }

    private Optional<Refresh> deferred(ProviderAccount account, Entry entry, boolean manual)
    {
        if (entry.noComposition)
            return Optional.of(new Refresh(Disposition.NO_COMPOSITION, entry.snapshot, Optional.empty()));
        if (budget.isSuspended(account))
            return Optional.of(new Refresh(Disposition.SUSPENDED, entry.snapshot, Optional.empty()));
        var next = budget.nextAvailable(account, compositionCost);
        if (next.isEmpty() || next.orElseThrow().isAfter(clock.instant()))
            return Optional.of(new Refresh(Disposition.DEFERRED, entry.snapshot, next));
        if (entry.retryAt.isAfter(clock.instant()))
            return Optional.of(new Refresh(Disposition.DEFERRED, entry.snapshot, Optional.of(entry.retryAt)));
        var cadence = entry.snapshot.attemptedAt().map(value -> value.plus(Duration.ofHours(24)));
        if (!manual && cadence.filter(value -> value.isAfter(clock.instant())).isPresent())
            return Optional.of(new Refresh(Disposition.CADENCE, entry.snapshot, cadence));
        return Optional.empty();
    }

    private boolean active(Entry entry, CompletableFuture<Refresh> future)
    {
        return !closed && entry.pending == future;
    }

    private void retrieve(Key key, CompositionInstrument fund, Entry entry, CompletableFuture<Refresh> future,
                    boolean manual)
    {
        synchronized (this)
        {
            if (!active(entry, future))
                return;
            var deferred = deferred(key.account(), entry, manual);
            if (deferred.isPresent() || !budget.acquirePermit(key.account(), compositionCost))
            {
                finish(entry, future, deferred.orElseGet(() -> new Refresh(Disposition.DEFERRED, entry.snapshot,
                                budget.nextAvailable(key.account(), compositionCost))));
                return;
            }
            entry.snapshot = new Snapshot(Optional.of(clock.instant()), entry.snapshot.latestAttempt(),
                            entry.snapshot.usableComposition(), entry.snapshot.resolutions());
        }

        CompositionResult result;
        try
        {
            result = Objects.requireNonNull(provider.getComposition(key.account(), fund));
            if (result.composition().filter(value -> !value.provenance().source().equals(key.account())
                            || !value.provenance().fund().identifiers().equals(key.identifiers())).isPresent())
                result = failure(CompositionResult.Status.MALFORMED_RESPONSE);
        }
        catch (RuntimeException e)
        {
            // Do not retain exception messages or provider payloads.
            result = failure(CompositionResult.Status.FAILED);
        }

        synchronized (this)
        {
            if (!active(entry, future))
                return;
            budget.record(key.account(), result);
        }
        var usable = result.composition().filter(value -> new CompositionValidator().validate(value).isUsable());
        synchronized (this)
        {
            if (active(entry, future))
            {
                // A provider answer without any usable composition is definitive
                // for this session: ask the instrument once and skip it
                // afterwards, so a stock never costs a request per refresh. It
                // concerns this instrument only, never the account's other funds.
                boolean noComposition = result.status() == CompositionResult.Status.UNAVAILABLE
                                || result.status() == CompositionResult.Status.UNSUPPORTED;
                entry.noComposition = noComposition;
                boolean unusableComposition = !noComposition && result.composition().isPresent() && usable.isEmpty();
                entry.retryAt = unusableComposition ? clock.instant().plus(budget.getFailureBackoff())
                                : Instant.MIN;
            }
        }
        var resolutions = usable.map(value -> enrich(key.account(), value, entry, future));
        synchronized (this)
        {
            if (!active(entry, future))
                return;
            entry.snapshot = new Snapshot(entry.snapshot.attemptedAt(), Optional.of(result),
                            usable.isPresent() ? usable : entry.snapshot.usableComposition(),
                            resolutions.orElse(entry.snapshot.resolutions()));
            finish(entry, future, new Refresh(Disposition.COMPLETED, entry.snapshot, Optional.empty()));
        }
    }

    private List<InstrumentResolution> enrich(ProviderAccount account, FundComposition composition, Entry entry,
                    CompletableFuture<Refresh> future)
    {
        var resolver = new InstrumentResolver();
        var resolved = new ArrayList<InstrumentResolution>();
        var evidence = new HashMap<Map<String, String>, List<InstrumentEvidence>>();
        synchronized (this)
        {
            for (var previous : entry.snapshot.resolutions())
                evidence.put(previous.source().identifiers(), previous.evidence());
        }
        var attempted = new HashSet<Map<String, String>>();
        int[] permits = { 0 };
        for (var row : composition.constituents())
        {
            synchronized (this)
            {
                if (!active(entry, future))
                    return List.of();
            }
            var source = row.instrument();
            var resolution = resolver.resolve(source, evidence.getOrDefault(source.identifiers(), List.of()), item -> {
                try
                {
                    return List.copyOf(lookup.lookup(account, item));
                }
                catch (IOException | RuntimeException e)
                {
                    synchronized (this)
                    {
                        if (active(entry, future))
                            budget.record(account, e instanceof EnrichmentFailure failure ? failure.result
                                            : failure(CompositionResult.Status.FAILED));
                    }
                    throw new IOException();
                }
            }, () -> {
                synchronized (this)
                {
                    if (!active(entry, future) || permits[0] >= maxEnrichmentLookups
                                    || !attempted.add(source.identifiers())
                                    || !budget.acquirePermit(account, enrichmentCost))
                        return false;
                    permits[0]++;
                    return true;
                }
            });
            evidence.put(source.identifiers(), resolution.evidence());
            resolved.add(resolution);
        }
        return List.copyOf(resolved);
    }

    private static CompositionResult failure(CompositionResult.Status status)
    {
        return new CompositionResult(status, Optional.empty(), Optional.empty());
    }

    private void finish(Entry entry, CompletableFuture<Refresh> future, Refresh result)
    {
        entry.pending = null;
        pendingCount--;
        future.complete(result);
    }

    public synchronized void configurationChanged(ProviderAccount account, long revision)
    {
        if (closed || !budget.configurationChanged(account, revision))
            return;
        for (var item : List.copyOf(entries.entrySet()))
        {
            if (!item.getKey().account().equals(account))
                continue;
            var entry = item.getValue();
            if (entry.snapshot.latestAttempt().map(CompositionResult::status)
                            .filter(status -> status == CompositionResult.Status.AUTHENTICATION_FAILED
                                            || status == CompositionResult.Status.ENTITLEMENT_FAILED).isPresent())
                entry.snapshot = new Snapshot(Optional.empty(), entry.snapshot.latestAttempt(),
                                entry.snapshot.usableComposition(), entry.snapshot.resolutions());
            if (entry.pending != null)
                finish(entry, entry.pending,
                                new Refresh(Disposition.CONFIGURATION_CHANGED, entry.snapshot, Optional.empty()));
        }
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        for (var entry : entries.values())
        {
            if (entry.pending != null)
                finish(entry, entry.pending, new Refresh(Disposition.CLOSED, emptySnapshot(), Optional.empty()));
        }
        entries.clear();
    }
}
