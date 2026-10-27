package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class LookThroughUpdateThrottleTest
{
    private static final Duration INTERVAL = Duration.ofMillis(1500);

    private static final class MutableClock extends Clock
    {
        private Instant now = Instant.parse("2026-01-01T12:00:00Z"); //$NON-NLS-1$

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private int updates;

    private LookThroughUpdateThrottle throttle()
    {
        return new LookThroughUpdateThrottle(clock, INTERVAL, (task, delay) -> {
            tasks.add(task);
            delays.add(delay);
        }, () -> updates++);
    }

    private void runScheduled()
    {
        tasks.remove(0).run();
    }

    @Test
    public void testFirstRequestRunsAtOnceAndBurstsAreMerged()
    {
        var throttle = throttle();
        throttle.request();
        throttle.request();
        throttle.request();
        assertEquals(List.of(Duration.ZERO), delays);

        runScheduled();
        assertEquals(1, updates);
        assertTrue(tasks.isEmpty());
    }

    @Test
    public void testAtMostOneUpdatePerInterval()
    {
        var throttle = throttle();
        throttle.request();
        runScheduled();

        // A notification shortly after an update waits for the rest of the interval.
        clock.now = clock.now.plusMillis(500);
        for (int index = 0; index < 100; index++)
            throttle.request();
        assertEquals(List.of(Duration.ZERO, Duration.ofMillis(1000)), delays);

        clock.now = clock.now.plusMillis(1000);
        runScheduled();
        assertEquals(2, updates);

        // After a quiet period the next update is immediate again.
        clock.now = clock.now.plusSeconds(10);
        throttle.request();
        assertEquals(Duration.ZERO, delays.get(2));
    }

    @Test
    public void testRequestDuringAnUpdateSchedulesTheNextOne()
    {
        var holder = new LookThroughUpdateThrottle[1];
        var nested = new int[1];
        holder[0] = new LookThroughUpdateThrottle(clock, INTERVAL, (task, delay) -> {
            tasks.add(task);
            delays.add(delay);
        }, () -> {
            if (nested[0]++ == 0)
                holder[0].request();
        });
        holder[0].request();
        runScheduled();
        assertEquals(List.of(Duration.ZERO, INTERVAL), delays);
    }
}
