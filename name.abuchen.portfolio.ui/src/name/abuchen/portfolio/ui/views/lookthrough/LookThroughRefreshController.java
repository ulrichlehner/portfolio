package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/**
 * Cancellable, coalescing manual refresh dispatch for the look-through status.
 * The controller is SWT-free: it never touches Display, jobs or other UI
 * classes, and it holds no data itself. Outcomes reach the caller only through
 * the supplied listener; the same listener receives one outcome per fund per
 * refresh call, at most.
 * <p>
 * Threading: {@link #refresh(List, Monitor, Listener)} may be called from any
 * thread. The gateway is invoked on the calling thread while the internal lock
 * is held, so gateway implementations must not block for long and must never
 * call back into this controller. The injected executor is used only to
 * marshal completion: the listener is invoked on that executor, never on the
 * thread completing the gateway future and never while the internal lock is
 * held. The monitor is consulted on the calling thread when the request is
 * queued and on the marshalling thread when the future has completed.
 * <p>
 * Cancellation is cooperative: it is checked before a request is queued (a
 * cancelled request never reaches the gateway) and again after completion (a
 * cancelled wait is reported as {@code CANCELLED} without applying any
 * result). Nothing interrupts work already handed to the coordinator; that
 * matches its own contract that closing is not an interrupt of an already
 * executing provider call.
 * <p>
 * Requests coalesce per account and identifier map, regardless of the fund
 * label: while one request is in flight, further refreshes of the same fund
 * join it and never issue a second gateway call. Each request carries a
 * generation; a newer refresh of the same fund makes the outcome of the older
 * request stale, and that request is reported as {@code CANCELLED} so the
 * caller always learns how a call ended. Mere rendering
 * never triggers requests: building {@link LookThroughDataStatus} needs only
 * the memory-only {@code read} of the gateway, never this refresh method.
 * <p>
 * Manual refresh bypasses only cadence, never quota, backoff or suspension.
 * Automatic eligibility passes use the same path with manual=false. The owning
 * view schedules those passes independently of rendering.
 */
public final class LookThroughRefreshController
{
    public enum Outcome
    {
        COMPLETED, CANCELLED, FAILED
    }

    /** One fund to refresh: provider account and fund instrument. */
    public record FundRef(ProviderAccount account, CompositionInstrument fund)
    {
        public FundRef
        {
            Objects.requireNonNull(account);
            Objects.requireNonNull(fund);
        }
    }

    /**
     * Isolates the controller from the final composition refresh coordinator.
     * The {@code read} method is memory-only and never causes a request; the
     * {@code refresh} method starts or joins the coordinated refresh and
     * enforces quota, backoff, suspension and cadence itself.
     */
    public interface RefreshGateway
    {
        CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument fund);

        CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument fund, boolean manual);
    }

    /** Production adapter that delegates to the final coordinator. */
    public static final class CoordinatorGateway implements RefreshGateway
    {
        private final CompositionRefreshCoordinator coordinator;

        public CoordinatorGateway(CompositionRefreshCoordinator coordinator)
        {
            this.coordinator = Objects.requireNonNull(coordinator);
        }

        @Override
        public CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument fund)
        {
            return coordinator.read(account, fund);
        }

        @Override
        public CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument fund, boolean manual)
        {
            return coordinator.refresh(account, fund, manual);
        }
    }

    /** Cooperative cancellation flag, checked before and after completion. */
    @FunctionalInterface
    public interface Monitor
    {
        boolean isCancelled();
    }

    /** Receives at most one outcome per fund per refresh call. */
    @FunctionalInterface
    public interface Listener
    {
        void updated(Result result);
    }

    /**
     * One fund outcome. {@code CANCELLED} carries no result; {@code FAILED}
     * carries none either because coordinator failures keep nothing to apply;
     * {@code COMPLETED} carries the refresh result with its disposition, so a
     * deferred or suspended outcome stays distinguishable from fresh data.
     */
    public record Result(FundRef fund, Outcome outcome, Optional<CompositionRefreshCoordinator.Refresh> refresh)
    {
        public Result
        {
            Objects.requireNonNull(fund);
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(refresh);
        }
    }

    private record FundKey(ProviderAccount account, Map<String, String> identifiers)
    {
        private FundKey
        {
            identifiers = Map.copyOf(identifiers);
        }
    }

    private record Waiting(int generation, FundRef ref, Monitor monitor, Listener listener)
    {
    }

    private static final class Pending
    {
        private final CompletableFuture<CompositionRefreshCoordinator.Refresh> future;
        private final List<Waiting> waitings = new ArrayList<>();

        private Pending(CompletableFuture<CompositionRefreshCoordinator.Refresh> future)
        {
            this.future = future;
        }
    }

    private final RefreshGateway gateway;
    private final Executor marshal;

    private final Object lock = new Object();
    private final Map<FundKey, Pending> pending = new HashMap<>();
    private int generation;

    public LookThroughRefreshController(RefreshGateway gateway, Executor marshal)
    {
        this.gateway = Objects.requireNonNull(gateway);
        this.marshal = Objects.requireNonNull(marshal);
    }

    /**
     * Refreshes the given funds. Duplicate funds are joined into one request;
     * each waiting request still reports once. Transparently handing funds
     * without identifiers is refused per fund with {@code FAILED} because the
     * coordinator assigns no request to an unidentified source.
     * <p>
     * Returns the number of outcomes that will be delivered to this listener
     * for this call, so a caller can track when the call has fully ended. Every
     * distinct fund of the list contributes exactly one outcome, either
     * immediately or after completion.
     */
    public int refresh(List<FundRef> funds, Monitor monitor, Listener listener)
    {
        return refresh(funds, true, monitor, listener);
    }

    public int refresh(List<FundRef> funds, boolean manual, Monitor monitor, Listener listener)
    {
        Objects.requireNonNull(funds);
        Objects.requireNonNull(monitor);
        Objects.requireNonNull(listener);

        var immediate = new ArrayList<Result>();
        int expected = 0;

        synchronized (lock)
        {
            var requested = ++generation;

            for (var fund : new LinkedHashSet<>(funds))
            {
                expected++;

                if (monitor.isCancelled())
                {
                    immediate.add(new Result(fund, Outcome.CANCELLED, Optional.empty()));
                    continue;
                }

                if (fund.fund().identifiers().isEmpty())
                {
                    immediate.add(new Result(fund, Outcome.FAILED, Optional.empty()));
                    continue;
                }

                var key = new FundKey(fund.account(), fund.fund().identifiers());
                var current = pending.get(key);
                if (current != null)
                {
                    current.waitings.add(new Waiting(requested, fund, monitor, listener));
                    continue;
                }

                CompletableFuture<CompositionRefreshCoordinator.Refresh> future;
                try
                {
                    future = gateway.refresh(fund.account(), fund.fund(), manual);
                }
                catch (RuntimeException e)
                {
                    immediate.add(new Result(fund, Outcome.FAILED, Optional.empty()));
                    continue;
                }

                var entry = new Pending(future);
                entry.waitings.add(new Waiting(requested, fund, monitor, listener));
                pending.put(key, entry);
                future.handle((value, error) -> {
                    marshal.execute(() -> complete(key, entry, value));
                    return null;
                });
            }
        }

        for (var result : immediate)
            deliver(listener, result);

        return expected;
    }

    private void complete(FundKey key, Pending entry, CompositionRefreshCoordinator.Refresh value)
    {
        List<Waiting> waitings;
        synchronized (lock)
        {
            if (pending.get(key) != entry)
                return;
            waitings = List.copyOf(entry.waitings);
            pending.remove(key);
        }

        // The newest generation wins; an older waiting for the same fund is
        // superseded, applies nothing and is reported as CANCELLED.
        var applied = waitings.stream().mapToInt(Waiting::generation).max().orElseThrow();

        for (var waiting : waitings)
        {
            if (waiting.monitor().isCancelled() || waiting.generation() < applied)
            {
                deliver(waiting.listener(), new Result(waiting.ref(), Outcome.CANCELLED, Optional.empty()));
                continue;
            }

            if (value == null)
                deliver(waiting.listener(), new Result(waiting.ref(), Outcome.FAILED, Optional.empty()));
            else
                deliver(waiting.listener(), new Result(waiting.ref(), Outcome.COMPLETED, Optional.of(value)));
        }
    }

    /**
     * Marshals one outcome onto the executor. Rejections from an executor
     * that is already shutting down drop the delivery; they never propagate
     * into coordinator completion threads.
     */
    private void deliver(Listener listener, Result result)
    {
        try
        {
            marshal.execute(() -> listener.updated(result));
        }
        catch (RejectedExecutionException e)
        {
            // Delivery is dropped because the owning context is gone.
        }
    }
}
