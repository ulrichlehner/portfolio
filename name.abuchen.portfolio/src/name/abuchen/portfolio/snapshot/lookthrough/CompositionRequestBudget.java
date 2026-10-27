package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared, in-memory provider admission control. Supply the same instance to all
 * consumers of a provider's allowance, including enrichment and other features.
 * Limits and request costs are caller-supplied known units, not inferred plan
 * entitlements. Admission charges the complete bounded operation up front; a
 * caller that knows how many requests the operation actually made returns the
 * unused units with {@link #release}, so the budget reflects real usage. This
 * cannot account for external clients.
 * No timers, waiting, network calls or disk writes are performed.
 */
public final class CompositionRequestBudget
{
    private record Charge(Instant at, long units)
    {
    }

    private static final class AccountState
    {
        private long configuration;
        private boolean suspended;
        private Instant retryAt = Instant.MIN;
    }

    private final String providerId;
    private final Clock clock;
    private final long capacity;
    private final Duration window;
    private final Duration failureBackoff;
    private final ArrayDeque<Charge> charges = new ArrayDeque<>();
    private final Map<ProviderAccount, AccountState> accounts = new HashMap<>();
    private long used;
    private Instant providerRetryAt = Instant.MIN;
    private Instant observedAt = Instant.MIN;

    public CompositionRequestBudget(String providerId, Clock clock, long capacity, Duration window,
                    Duration failureBackoff)
    {
        this.providerId = Objects.requireNonNull(providerId);
        this.clock = Objects.requireNonNull(clock);
        this.window = Objects.requireNonNull(window);
        this.failureBackoff = Objects.requireNonNull(failureBackoff);
        if (providerId.isBlank() || capacity < 0 || window.isNegative() || window.isZero()
                        || failureBackoff.isNegative() || failureBackoff.isZero())
            throw new IllegalArgumentException();
        this.capacity = capacity;
    }

    /** Delay used by the coordinator for an instrument-specific failure. */
    public Duration getFailureBackoff()
    {
        return failureBackoff;
    }

    private AccountState account(ProviderAccount account)
    {
        if (!providerId.equals(account.providerId()))
            throw new IllegalArgumentException();
        return accounts.computeIfAbsent(account, ignored -> new AccountState());
    }

    private Instant now()
    {
        var current = clock.instant();
        if (current.isAfter(observedAt))
            observedAt = current;
        while (!charges.isEmpty() && !charges.getFirst().at().plus(window).isAfter(observedAt))
            used -= charges.removeFirst().units();
        return observedAt;
    }

    public synchronized boolean acquirePermit(ProviderAccount account, long units)
    {
        return acquirePermit(account, units, 0);
    }

    /**
     * Admits the units only while at least the given headroom stays available
     * afterwards, so requests of lower priority leave room for other requests.
     */
    public synchronized boolean acquirePermit(ProviderAccount account, long units, long headroom)
    {
        if (units <= 0 || headroom < 0)
            throw new IllegalArgumentException();
        var state = account(account);
        var current = now();
        if (state.suspended || current.isBefore(providerRetryAt) || current.isBefore(state.retryAt)
                        || units > capacity - used - headroom)
            return false;
        charges.addLast(new Charge(current, units));
        used += units;
        return true;
    }

    /**
     * Returns reserved units that an admitted operation did not use. They are
     * taken from the oldest charges in the window, so capacity never becomes
     * available earlier than with exact accounting.
     */
    public synchronized void release(ProviderAccount account, long units)
    {
        if (units < 0)
            throw new IllegalArgumentException();
        account(account);
        now();
        var remaining = Math.min(units, used);
        used -= remaining;
        while (remaining > 0)
        {
            var oldest = charges.removeFirst();
            if (oldest.units() > remaining)
            {
                charges.addFirst(new Charge(oldest.at(), oldest.units() - remaining));
                remaining = 0;
            }
            else
            {
                remaining -= oldest.units();
            }
        }
    }

    public synchronized boolean isSuspended(ProviderAccount account)
    {
        return account(account).suspended;
    }

    /** Recheck suspension/backoff before executing work whose cost is already reserved. */
    public synchronized boolean canExecuteReserved(ProviderAccount account)
    {
        var state = account(account);
        var current = now();
        return !state.suspended && !current.isBefore(providerRetryAt) && !current.isBefore(state.retryAt);
    }

    /** Empty when no finite deadline exists (suspended or cost exceeds capacity). */
    public synchronized Optional<Instant> nextAvailable(ProviderAccount account, long units)
    {
        if (units <= 0)
            throw new IllegalArgumentException();
        var state = account(account);
        var current = now();
        if (state.suspended || units > capacity)
            return Optional.empty();
        var deadline = current;
        if (providerRetryAt.isAfter(deadline))
            deadline = providerRetryAt;
        if (state.retryAt.isAfter(deadline))
            deadline = state.retryAt;
        long remaining = used;
        for (var charge : charges)
        {
            if (units <= capacity - remaining)
                break;
            remaining -= charge.units();
            var expires = charge.at().plus(window);
            if (expires.isAfter(deadline))
                deadline = expires;
        }
        return Optional.of(deadline);
    }

    /** Rate limits protect the shared provider allowance; auth is account-local. */
    public synchronized void record(ProviderAccount account, CompositionResult result)
    {
        var state = account(account);
        var current = now();
        switch (result.status())
        {
            case AUTHENTICATION_FAILED, ENTITLEMENT_FAILED -> state.suspended = true;
            case RATE_LIMITED -> {
                var deadline = result.retryAfter().filter(value -> value.isAfter(current))
                                .orElseGet(() -> current.plus(failureBackoff));
                if (deadline.isAfter(providerRetryAt))
                    providerRetryAt = deadline;
            }
            case RECEIVED, CANCELLED, UNAVAILABLE, UNSUPPORTED -> {
                // Success cannot clear an overlapping failure or suspension.
            }
            default -> state.retryAt = current.plus(failureBackoff);
        }
    }

    /**
     * An opaque, non-secret configuration revision, changed only by configuration
     * events. Repeating the same revision does not unlock authentication retries.
     * Configuration never restores spent quota or removes provider backoff.
     */
    public synchronized boolean configurationChanged(ProviderAccount account, long revision)
    {
        var state = account(account);
        if (state.configuration == revision)
            return false;
        state.configuration = revision;
        state.suspended = false;
        state.retryAt = Instant.MIN;
        return true;
    }
}
