package name.abuchen.portfolio.ui.views.lookthrough;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.GlobalFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Model;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.Outcome;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.Result;

/**
 * SWT-free state of the look-through data status: the current facts, the
 * cancellable manual refresh and the display model derived from both.
 * <p>
 * Rendering is always memory-only. {@link #status()} reads the retained
 * compositions through the gateway {@code read} operation and never submits a
 * request, so a redraw, a resize or a repeated call cannot cause provider
 * traffic. Manual refresh and automatic eligibility passes start or join a
 * coordinated request once per fund.
 * <p>
 * The status keeps the last refresh outcome per fund, so a failed attempt stays
 * visible next to the retained dates it did not replace. A newer refresh or a
 * cancellation supersedes an older one: outcomes of a superseded call are
 * ignored and never overwrite the newer facts.
 * <p>
 * Threading: every method is called on the thread of the injected executor,
 * which is the user interface thread in production. Outcome callbacks arrive on
 * that same executor, and the change listener is invoked on it as well, never on
 * a provider completion thread and never while refreshing is being started.
 * <p>
 * The listener is notified only when the observable state changed: the
 * refreshing flag and fund set, the outstanding count or the last refresh
 * entries. An automatic eligibility pass stays quiet until one of its outcomes
 * changes an entry, so a periodic check that retrieves nothing never causes a
 * notification.
 */
public final class LookThroughStatusModel
{
    /**
     * Supplies the facts currently worth showing: the global storage facts and
     * the funds of the current portfolio. An empty result means that no
     * look-through data is available for display, for example before the first
     * client input arrives.
     */
    @FunctionalInterface
    public interface Source
    {
        Optional<Snapshot> current();
    }

    /** One display snapshot: global storage facts plus the held funds. */
    public record Snapshot(GlobalFacts global, List<FundRef> funds)
    {
        public Snapshot
        {
            Objects.requireNonNull(global);
            funds = List.copyOf(funds);
        }
    }

    private final Clock clock;
    private final RefreshGateway gateway;
    private final Executor executor;
    private final Source source;
    private final LookThroughRefreshController controller;

    private final Map<FundRef, CompositionRefreshCoordinator.Refresh> lastRefreshes = new HashMap<>();
    private volatile Set<FundRef> refreshingFunds = Set.of();
    private Object callToken;
    private Object dispatchingToken;
    private final List<Result> earlyResults = new ArrayList<>();
    private volatile int outstanding;
    private boolean quiet;
    private State notifiedState;
    private Runnable changeListener = () -> {
    };

    public LookThroughStatusModel(Clock clock, RefreshGateway gateway, Executor executor, Source source)
    {
        this.clock = Objects.requireNonNull(clock);
        this.gateway = Objects.requireNonNull(gateway);
        this.executor = Objects.requireNonNull(executor);
        this.source = Objects.requireNonNull(source);
        this.controller = new LookThroughRefreshController(gateway, executor);
        this.notifiedState = observableState();
    }

    /**
     * The state that a notification reports. A quiet automatic pass is not
     * part of it until it changed an entry.
     */
    private record State(boolean refreshing, Set<FundRef> refreshingFunds, int outstanding,
                    Map<FundRef, CompositionRefreshCoordinator.Refresh> lastRefreshes)
    {
    }

    /** Registers the listener notified on the executor after every change. */
    public void setChangeListener(Runnable changeListener)
    {
        this.changeListener = Objects.requireNonNull(changeListener);
    }

    /** True while a manual refresh is started and not yet finished. */
    public boolean isRefreshing()
    {
        return callToken != null;
    }

    /** Number of funds whose outcome is still outstanding in the running call. */
    public int outstanding()
    {
        return outstanding;
    }

    /**
     * True while a refresh is running that the user should see: a manual
     * refresh, or an automatic pass after one of its outcomes changed an
     * entry. A quiet automatic pass that has not retrieved anything is not
     * shown as progress.
     */
    public boolean isProgressVisible()
    {
        return callToken != null && !quiet;
    }

    /** Number of funds in the running refresh call. */
    public int total()
    {
        return refreshingFunds.size();
    }

    /**
     * Starts one manual refresh of the current funds and returns the number of
     * outstanding outcomes. It returns zero, without any request, when nothing
     * is displayed or stored data is unavailable. A refresh that is already
     * running is superseded, not stacked.
     */
    public int refresh()
    {
        return refresh(true);
    }

    public int refreshAutomatically()
    {
        return isRefreshing() ? 0 : refresh(false);
    }

    private int refresh(boolean manual)
    {
        var current = source.current();
        if (current.isEmpty())
            return 0;

        var funds = current.orElseThrow().funds();
        if (funds.isEmpty())
            return 0;

        var token = new Object();
        callToken = token;
        quiet = !manual;
        refreshingFunds = Set.copyOf(new HashSet<>(funds));

        int expected;
        dispatchingToken = token;
        try
        {
            expected = controller.refresh(funds, manual, () -> callToken != token, result -> arrived(token, result));
        }
        finally
        {
            dispatchingToken = null;
        }

        outstanding = expected;

        var early = List.copyOf(earlyResults);
        earlyResults.clear();
        for (var result : early)
            apply(token, result);

        notifyChanged();
        return expected;
    }

    /**
     * Stops waiting for the running refresh. No later outcome of that call is
     * applied; the requests already handed to the coordinator continue there.
     */
    public void cancel()
    {
        if (callToken == null)
            return;
        callToken = null;
        quiet = false;
        refreshingFunds = Set.of();
        outstanding = 0;
        notifyChanged();
    }

    /**
     * Builds the display model from the retained compositions and the latest
     * attempts. This performs memory-only reads and never a request.
     */
    public Optional<Model> status()
    {
        return statusTask().get();
    }

    /**
     * Captures the refresh state on the executor thread and returns the task
     * that builds the display model from it. The task only reads facts and
     * the retained compositions, which may wait for the store, so the view
     * runs it off the user interface thread. It never submits a request.
     */
    public Supplier<Optional<Model>> statusTask()
    {
        return statusTask(Optional.empty());
    }

    /**
     * Like {@link #statusTask()}, with the credential state that the caller
     * read off the user interface thread. Empty means unknown, which never
     * selects a terminal state.
     */
    public Supplier<Optional<Model>> statusTask(Optional<Boolean> keyConfigured)
    {
        Objects.requireNonNull(keyConfigured);
        var refreshing = isProgressVisible();
        var pendingFunds = refreshingFunds;
        var refreshes = Map.copyOf(lastRefreshes);
        return () -> {
            var current = source.current();
            if (current.isEmpty())
                return Optional.empty();

            var snapshot = current.orElseThrow();
            var facts = new ArrayList<FundFacts>(snapshot.funds().size());
            for (var fund : snapshot.funds())
            {
                var pending = refreshing && pendingFunds.contains(fund);
                var refresh = refreshes.get(fund);
                if (refresh != null)
                    facts.add(LookThroughDataStatus.fundFacts(refresh, fund.account(), fund.fund(), pending));
                else
                    facts.add(LookThroughDataStatus.fundFacts(gateway.read(fund.account(), fund.fund()),
                                    fund.account(), fund.fund(), pending));
            }

            return Optional.of(LookThroughDataStatus.present(clock, snapshot.global(), facts, keyConfigured));
        };
    }

    /** Records one outcome, unless it belongs to a superseded call. */
    private void arrived(Object token, Result result)
    {
        // A direct executor delivers while the refresh call is still running;
        // buffer those outcomes so the counter is already initialised.
        if (dispatchingToken == token)
        {
            earlyResults.add(result);
            return;
        }
        apply(token, result);
    }

    private void apply(Object token, Result result)
    {
        if (token != callToken)
            return;

        var refresh = result.refresh();
        if (result.outcome() == Outcome.COMPLETED && refresh.isPresent())
        {
            var previous = lastRefreshes.put(result.fund(), refresh.orElseThrow());
            // An automatic pass becomes visible once it changed an entry.
            if (!refresh.orElseThrow().equals(previous))
                quiet = false;
        }

        outstanding = Math.max(0, outstanding - 1);
        if (outstanding == 0)
        {
            callToken = null;
            quiet = false;
            refreshingFunds = Set.of();
        }
        notifyChanged();
    }

    private State observableState()
    {
        if (callToken == null || quiet)
            return new State(false, Set.of(), 0, Map.copyOf(lastRefreshes));
        return new State(true, refreshingFunds, outstanding, Map.copyOf(lastRefreshes));
    }

    /** Notifies the listener unless the observable state is unchanged. */
    private void notifyChanged()
    {
        var state = observableState();
        if (state.equals(notifiedState))
            return;
        notifiedState = state;

        try
        {
            executor.execute(changeListener);
        }
        catch (RejectedExecutionException e)
        {
            // The owning view is gone; nothing is left to update.
        }
    }
}
