package name.abuchen.portfolio.ui.views.lookthrough;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Coalesces frequent change notifications into at most one update per
 * interval. Requests from any thread that arrive while an update is
 * scheduled are merged into it; the first request after a quiet period runs
 * at once, later ones wait until the interval since the last update passed.
 * <p>
 * The class is SWT-free. The view injects a scheduler that runs the update
 * after a delay on the user interface thread.
 */
final class LookThroughUpdateThrottle
{
    /** Runs a task once after a delay. */
    @FunctionalInterface
    interface Scheduler
    {
        void schedule(Runnable task, Duration delay);
    }

    private final Clock clock;
    private final Duration interval;
    private final Scheduler scheduler;
    private final Runnable update;

    private boolean scheduled;
    private Instant last = Instant.MIN;

    LookThroughUpdateThrottle(Clock clock, Duration interval, Scheduler scheduler, Runnable update)
    {
        this.clock = Objects.requireNonNull(clock);
        this.interval = Objects.requireNonNull(interval);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.update = Objects.requireNonNull(update);
    }

    /** Requests an update; returns at once. */
    void request()
    {
        Duration delay;
        synchronized (this)
        {
            if (scheduled)
                return;
            scheduled = true;
            var now = clock.instant();
            var next = last.equals(Instant.MIN) ? now : last.plus(interval);
            delay = next.isAfter(now) ? Duration.between(now, next) : Duration.ZERO;
        }
        try
        {
            scheduler.schedule(this::run, delay);
        }
        catch (RuntimeException e)
        {
            synchronized (this)
            {
                scheduled = false;
            }
        }
    }

    private void run()
    {
        synchronized (this)
        {
            scheduled = false;
            last = clock.instant();
        }
        update.run();
    }
}
