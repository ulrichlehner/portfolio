package name.abuchen.portfolio.snapshot.lookthrough;

import java.util.Optional;

/**
 * Application-owned composition storage, independent of portfolio serialization.
 * Configure each account before use. A refresh must capture a ticket BEFORE its
 * asynchronous work starts, and complete that same ticket with the actual attempt
 * result (not the coordinator's last-valid fallback). Reads never fetch data.
 * Coalesced coordinator futures must share one store ticket; do not create a
 * ticket for each redraw/subscriber. Wire account policy/preference changes to
 * configure immediately, not merely when the next network operation finishes.
 * Configure performs startup cleanup; reads and refresh completion enforce
 * retention again. There is no internal timer. The application's eligibility
 * pass must read active funds even when refresh is deferred by quota/offline use.
 * DISK_FAILURE preserves permitted session analysis and blocks further disk use
 * until configure explicitly retries the filesystem (including failed cleanup).
 * Supply read().usableComposition().source() to the existing engine, with the
 * same evidence-backed resolutions in either mode. Enrichment evidence and
 * calculated results are deliberately not serialized by this composition store.
 */
public interface CompositionStore extends AutoCloseable
{
    enum State
    {
        SESSION_ONLY, PERSISTENT, DISK_FAILURE, CLOSED
    }

    enum CacheStatus
    {
        MISS, AVAILABLE, EXPIRED, DISCARDED
    }

    record Snapshot(Optional<CompositionValidationResult> usableComposition,
                    Optional<CompositionResult.Status> latestAttempt, State state, CacheStatus cacheStatus)
    {
    }

    /** Opaque, single-use refresh capability bound to one store and fund. */
    interface Ticket
    {
    }

    void configure(CompositionPersistencePolicy policy, Optional<Boolean> persistencePreference);

    Snapshot read(ProviderAccount account, CompositionInstrument fund);

    Ticket beginRefresh(ProviderAccount account, CompositionInstrument fund);

    Snapshot complete(Ticket ticket, CompositionResult attempt);

    /** Deletes only this account's disk data and invalidates pending disk writes. */
    void deleteRetained(ProviderAccount account);

    @Override
    void close();
}
