package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coalescing scheduler for the look-through calculation. At most one
 * calculation runs on the worker and at most one waits: a newer request
 * replaces the waiting one. Every request increments a generation, and a
 * result is only delivered when no newer request arrived in the meantime, so a
 * slow calculation never overwrites a newer state.
 * <p>
 * The class is SWT-free. The view injects a worker executor for the
 * calculation and a delivery executor that runs on the user interface thread.
 */
final class LookThroughRecalculation<T>
{
    private final Executor worker;
    private final Executor delivery;
    private final Consumer<T> consumer;
    private final Consumer<RuntimeException> failure;

    private final Object lock = new Object();
    private Supplier<T> pending;
    private long pendingGeneration;
    private long generation;
    private boolean running;

    LookThroughRecalculation(Executor worker, Executor delivery, Consumer<T> consumer,
                    Consumer<RuntimeException> failure)
    {
        this.worker = Objects.requireNonNull(worker);
        this.delivery = Objects.requireNonNull(delivery);
        this.consumer = Objects.requireNonNull(consumer);
        this.failure = Objects.requireNonNull(failure);
    }

    /** Requests a calculation; it replaces a calculation that has not started. */
    void request(Supplier<T> calculation)
    {
        Objects.requireNonNull(calculation);
        synchronized (lock)
        {
            generation++;
            pending = calculation;
            pendingGeneration = generation;
            if (running)
                return;
            running = true;
        }

        try
        {
            worker.execute(this::drain);
        }
        catch (RejectedExecutionException e)
        {
            synchronized (lock)
            {
                running = false;
                pending = null;
            }
        }
    }

    /** Drops the waiting calculation and every result not yet delivered. */
    void cancel()
    {
        synchronized (lock)
        {
            generation++;
            pending = null;
        }
    }

    private boolean isCurrent(long requested)
    {
        synchronized (lock)
        {
            return requested == generation;
        }
    }

    private void drain()
    {
        while (true)
        {
            Supplier<T> calculation;
            long requested;
            synchronized (lock)
            {
                if (pending == null)
                {
                    running = false;
                    return;
                }
                calculation = pending;
                requested = pendingGeneration;
                pending = null;
            }

            T result;
            try
            {
                result = calculation.get();
            }
            catch (RuntimeException e)
            {
                failure.accept(e);
                continue;
            }

            if (!isCurrent(requested))
                continue;

            try
            {
                delivery.execute(() -> {
                    if (isCurrent(requested))
                        consumer.accept(result);
                });
            }
            catch (RejectedExecutionException e)
            {
                // The owner is gone; nothing is left to update.
            }
        }
    }
}
