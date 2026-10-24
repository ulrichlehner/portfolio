package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;

/**
 * In-memory implementation and common validation/retention semantics. Closing
 * discards all session data. A permitted maximum age is measured from the original
 * retrieval instant, with expiry inclusive at the boundary. Future retrieval
 * dates are rejected rather than extending retention. Policy withdrawal retains
 * permitted session analysis, but never grants runtime rights in itself.
 */
public class SessionCompositionStore implements CompositionStore
{
    private record Key(ProviderAccount account, Map<String, String> identifiers)
    {
    }

    private static final class Account
    {
        private CompositionPersistencePolicy policy;
        private boolean persistent;
        private boolean diskFailure;
        private long generation;
    }

    private static final class Entry
    {
        private CompositionValidationResult usable;
        private CompositionResult.Status attempt;
        private CacheStatus status = CacheStatus.MISS;
        private long sequence;
    }

    private static final class RefreshTicket implements Ticket
    {
        private final SessionCompositionStore owner;
        private final Key key;
        private final long generation;
        private final long sequence;
        private boolean consumed;

        private RefreshTicket(SessionCompositionStore owner, Key key, long generation, long sequence)
        {
            this.owner = owner;
            this.key = key;
            this.generation = generation;
            this.sequence = sequence;
        }
    }

    protected final Clock clock;
    private final Map<ProviderAccount, Account> accounts = new HashMap<>();
    private final Map<Key, Entry> entries = new HashMap<>();
    private boolean closed;

    public SessionCompositionStore(Clock clock)
    {
        this.clock = Objects.requireNonNull(clock);
    }

    protected boolean supportsPersistence()
    {
        return false;
    }

    @Override
    public final synchronized void configure(CompositionPersistencePolicy policy, Optional<Boolean> preference)
    {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(preference);
        if (closed)
            throw new IllegalStateException();
        var account = accounts.computeIfAbsent(policy.account(), ignored -> new Account());
        account.generation++;
        account.policy = policy;
        account.persistent = supportsPersistence() && policy.effectiveMode(preference) == StorageMode.PERSISTENT;
        account.diskFailure = false;
        try
        {
            if (account.persistent)
                pruneDisk(policy);
            else
                deleteDisk(policy.account());
        }
        catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException e)
        {
            account.diskFailure = true;
        }
        for (var item : entries.entrySet())
        {
            if (item.getKey().account().equals(policy.account()))
                expire(item.getValue(), policy);
        }
    }

    private Key key(ProviderAccount account, CompositionInstrument fund)
    {
        if (!accounts.containsKey(account) || fund.identifiers().isEmpty()
                        || fund.identifiers().entrySet().stream()
                                        .anyMatch(item -> item.getKey().isBlank() || item.getValue().isBlank()))
            throw new IllegalArgumentException();
        return new Key(account, fund.identifiers());
    }

    protected final boolean expired(FundComposition composition, CompositionPersistencePolicy policy)
    {
        var retrieved = composition.provenance().retrievedAt();
        var now = clock.instant();
        return retrieved.isAfter(now) || policy.maximumAge()
                        .map(age -> Duration.between(retrieved, now).compareTo(age) >= 0).orElse(false);
    }

    private void expire(Entry entry, CompositionPersistencePolicy policy)
    {
        if (entry.usable != null && expired(entry.usable.source(), policy))
        {
            entry.usable = null;
            entry.status = CacheStatus.EXPIRED;
        }
    }

    private Snapshot snapshot(Key key, Entry entry)
    {
        var account = accounts.get(key.account());
        var state = closed ? State.CLOSED : account.diskFailure ? State.DISK_FAILURE
                        : account.persistent ? State.PERSISTENT : State.SESSION_ONLY;
        return new Snapshot(Optional.ofNullable(entry.usable), Optional.ofNullable(entry.attempt), state, entry.status);
    }

    @Override
    public final synchronized Snapshot read(ProviderAccount account, CompositionInstrument fund)
    {
        var key = key(account, fund);
        if (closed)
            return new Snapshot(Optional.empty(), Optional.empty(), State.CLOSED, CacheStatus.MISS);
        var settings = accounts.get(account);
        var entry = entries.computeIfAbsent(key, ignored -> new Entry());
        expire(entry, settings.policy);
        if (settings.persistent && !settings.diskFailure)
        {
            try
            {
                // Also remove expired files when session memory already exists.
                pruneDisk(settings.policy);
                if (entry.usable == null)
                {
                    var loaded = readDisk(account, fund);
                    entry.status = loaded.cacheStatus();
                    entry.usable = loaded.usableComposition().orElse(null);
                    expire(entry, settings.policy);
                    if (entry.status == CacheStatus.EXPIRED)
                        pruneDisk(settings.policy);
                }
            }
            catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException e)
            {
                settings.diskFailure = true;
            }
        }
        return snapshot(key, entry);
    }

    @Override
    public final synchronized Ticket beginRefresh(ProviderAccount account, CompositionInstrument fund)
    {
        if (closed)
            throw new IllegalStateException();
        read(account, fund);
        var key = key(account, fund);
        return new RefreshTicket(this, key, accounts.get(account).generation, ++entries.get(key).sequence);
    }

    @Override
    public final synchronized Snapshot complete(Ticket ticket, CompositionResult attempt)
    {
        Objects.requireNonNull(attempt);
        if (!(ticket instanceof RefreshTicket pending) || pending.owner != this || pending.consumed)
            throw new IllegalArgumentException();
        pending.consumed = true;
        var key = pending.key;
        var settings = accounts.get(key.account());
        if (closed)
            return new Snapshot(Optional.empty(), Optional.empty(), State.CLOSED, CacheStatus.MISS);
        var fund = new CompositionInstrument("", key.identifiers()); //$NON-NLS-1$
        read(key.account(), fund);
        var entry = entries.get(key);
        if (pending.sequence != entry.sequence)
            return snapshot(key, entry);
        entry.attempt = attempt.status();
        var validated = attempt.composition().flatMap(source -> {
            if (!source.provenance().source().equals(key.account())
                            || !source.provenance().fund().identifiers().equals(key.identifiers())
                            || source.provenance().retrievedAt().isAfter(clock.instant()))
                return Optional.<CompositionValidationResult>empty();
            return CompositionCacheCodec.validate(source);
        });
        if (attempt.composition().isPresent() && validated.isEmpty())
            entry.attempt = CompositionResult.Status.MALFORMED_RESPONSE;
        if (validated.isPresent() && !expired(validated.orElseThrow().source(), settings.policy))
        {
            entry.usable = validated.orElseThrow();
            entry.status = CacheStatus.AVAILABLE;
            // Same monitor protects policy changes, deletion AND the atomic commit.
            if (settings.persistent && !settings.diskFailure && pending.generation == settings.generation)
            {
                try
                {
                    writeDisk(entry.usable.source(), settings.policy);
                    expire(entry, settings.policy);
                }
                catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException e)
                {
                    settings.diskFailure = true;
                }
            }
        }
        return snapshot(key, entry);
    }

    @Override
    public final synchronized void deleteRetained(ProviderAccount account)
    {
        var settings = Objects.requireNonNull(accounts.get(account));
        settings.generation++;
        try
        {
            deleteDisk(account);
        }
        catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException e)
        {
            settings.diskFailure = true;
        }
    }

    protected void pruneDisk(CompositionPersistencePolicy policy) throws IOException
    {
        // Session-only stores never touch the filesystem.
    }

    protected void deleteDisk(ProviderAccount account) throws IOException
    {
        // Session-only stores never touch the filesystem.
    }

    protected Snapshot readDisk(ProviderAccount account, CompositionInstrument fund) throws IOException
    {
        return new Snapshot(Optional.empty(), Optional.empty(), State.SESSION_ONLY, CacheStatus.MISS);
    }

    protected void writeDisk(FundComposition composition, CompositionPersistencePolicy policy) throws IOException
    {
        // Session-only stores never touch the filesystem.
    }

    @Override
    public final synchronized void close()
    {
        closed = true;
        entries.clear();
        // Tickets remain consumable after close, but can neither publish nor write.
    }
}
