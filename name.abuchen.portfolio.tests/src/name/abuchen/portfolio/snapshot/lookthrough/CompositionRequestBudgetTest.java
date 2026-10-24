package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.Test;

public class CompositionRequestBudgetTest
{
    static final class MutableClock extends Clock
    {
        private Instant instant = Instant.parse("2026-01-01T12:00:00Z");

        void advance(Duration duration)
        {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant()
        {
            return instant;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final ProviderAccount first = new ProviderAccount("synthetic", "first");
    private final ProviderAccount second = new ProviderAccount("synthetic", "second");

    private CompositionRequestBudget budget(long units)
    {
        return new CompositionRequestBudget("synthetic", clock, units, Duration.ofHours(24), Duration.ofMinutes(10));
    }

    private CompositionResult result(CompositionResult.Status status, Optional<Instant> retry)
    {
        return new CompositionResult(status, Optional.empty(), retry);
    }

    @Test
    public void testWeightedProviderWideRollingAllowance()
    {
        var budget = budget(12);
        var start = clock.instant();
        assertTrue(budget.acquirePermit(first, 10));
        clock.advance(Duration.ofHours(1));
        assertTrue(budget.acquirePermit(second, 2));
        assertFalse(budget.acquirePermit(second, 1));
        assertEquals(Optional.of(start.plus(Duration.ofHours(24))), budget.nextAvailable(second, 10));
        assertEquals(Optional.of(start.plus(Duration.ofHours(25))), budget.nextAvailable(first, 12));
        clock.advance(Duration.ofHours(23));
        assertTrue(budget.acquirePermit(second, 10));
        assertFalse(budget.acquirePermit(first, 1));
        clock.advance(Duration.ofHours(1));
        assertTrue(budget.acquirePermit(first, 2));
    }

    @Test
    public void testRetryAfterProtectsAllAccountsAndCannotBeShortened()
    {
        var budget = budget(100);
        var deadline = clock.instant().plus(Duration.ofHours(2));
        budget.record(first, result(CompositionResult.Status.RATE_LIMITED, Optional.of(deadline)));
        budget.record(second, result(CompositionResult.Status.RATE_LIMITED,
                        Optional.of(clock.instant().plusSeconds(1))));
        assertFalse(budget.acquirePermit(second, 1));
        assertEquals(Optional.of(deadline), budget.nextAvailable(second, 1));
        budget.configurationChanged(first, 1);
        assertFalse(budget.acquirePermit(first, 1));
        clock.advance(Duration.ofHours(2));
        assertTrue(budget.acquirePermit(second, 1));
    }

    @Test
    public void testAuthenticationAndEntitlementSuspensionIsAccountLocal()
    {
        for (var status : new CompositionResult.Status[] { CompositionResult.Status.AUTHENTICATION_FAILED,
                        CompositionResult.Status.ENTITLEMENT_FAILED })
        {
            var budget = budget(2);
            assertTrue(budget.acquirePermit(first, 1));
            budget.record(first, result(status, Optional.empty()));
            clock.advance(Duration.ofDays(3));
            assertTrue(budget.isSuspended(first));
            assertFalse(budget.acquirePermit(first, 1));
            assertTrue(budget.acquirePermit(second, 1));
            assertFalse(budget.configurationChanged(first, 0));
            assertFalse(budget.acquirePermit(first, 1));
            assertTrue(budget.configurationChanged(first, 1));
            assertTrue(budget.acquirePermit(first, 1));
            assertFalse(budget.acquirePermit(first, 1));
        }
    }

    @Test
    public void testMissingOrExpiredRetryAfterUsesPositiveFallback()
    {
        var budget = budget(100);
        var start = clock.instant();
        budget.record(first, result(CompositionResult.Status.RATE_LIMITED, Optional.of(start.minusSeconds(1))));
        assertEquals(Optional.of(start.plus(Duration.ofMinutes(10))), budget.nextAvailable(second, 1));
        clock.advance(Duration.ofMinutes(10));
        budget.record(second, result(CompositionResult.Status.RATE_LIMITED, Optional.empty()));
        assertFalse(budget.acquirePermit(first, 1));
        clock.advance(Duration.ofMinutes(10));
        assertTrue(budget.acquirePermit(first, 1));
    }

    @Test
    public void testClockRollbackDoesNotRestoreQuota()
    {
        var budget = budget(1);
        assertTrue(budget.acquirePermit(first, 1));
        clock.advance(Duration.ofHours(-1));
        assertFalse(budget.acquirePermit(first, 1));
        clock.advance(Duration.ofHours(24));
        assertFalse(budget.acquirePermit(first, 1));
        clock.advance(Duration.ofHours(1));
        assertTrue(budget.acquirePermit(first, 1));
    }

    @Test
    public void testUnknownOrInsufficientAllowanceDoesNotInventQuota()
    {
        var budget = budget(0);
        assertFalse(budget.acquirePermit(first, 1));
        assertTrue(budget.nextAvailable(first, 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> budget.acquirePermit(first, 0));
        assertThrows(IllegalArgumentException.class,
                        () -> budget.acquirePermit(new ProviderAccount("other", "first"), 1));
        assertThrows(IllegalArgumentException.class, () -> new CompositionRequestBudget("synthetic", clock, 1,
                        Duration.ZERO, Duration.ofMinutes(1)));
    }
}
