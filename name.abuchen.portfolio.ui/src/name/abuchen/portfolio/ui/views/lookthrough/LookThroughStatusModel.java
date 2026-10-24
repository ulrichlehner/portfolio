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
 */
public final class LookThroughStatusModel
{
    /**
     * Supplies the facts currently worth showing: the global storage facts and
     * the funds of the current selection. An empty result means that no
     * look-through data is available for display, for example before the first
     * client input arrives.
     */
    @FunctionalInterface
    public interface Source
    {
        Optional<Snapshot> current();
    }

    /** One display snapshot: global storage facts plus the selected funds. */
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
    private Set<FundRef> refreshingFunds = Set.of();
    private Object callToken;
    private Object dispatchingToken;
    private final List<Result> earlyResults = new ArrayList<>();
    private int outstanding;
    private Runnable changeListener = () -> {
    };

    public LookThroughStatusModel(Clock clock, RefreshGateway gateway, Executor executor, Source source)
    {
        this.clock = Objects.requireNonNull(clock);
        this.gateway = Objects.requireNonNull(gateway);
        this.executor = Objects.requireNonNull(executor);
        this.source = Objects.requireNonNull(source);
        this.controller = new LookThroughRefreshController(gateway, executor);
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
        var current = source.current();
        if (current.isEmpty())
            return Optional.empty();

        var snapshot = current.orElseThrow();
        var refreshing = isRefreshing();
        var facts = new ArrayList<FundFacts>(snapshot.funds().size());
        for (var fund : snapshot.funds())
        {
            var pending = refreshing && refreshingFunds.contains(fund);
            var refresh = lastRefreshes.get(fund);
            if (refresh != null)
                facts.add(LookThroughDataStatus.fundFacts(refresh, fund.account(), fund.fund(), pending));
            else
                facts.add(LookThroughDataStatus.fundFacts(gateway.read(fund.account(), fund.fund()), fund.account(),
                                fund.fund(), pending));
        }

        return Optional.of(LookThroughDataStatus.present(clock, snapshot.global(), facts));
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
            lastRefreshes.put(result.fund(), refresh.orElseThrow());

        outstanding = Math.max(0, outstanding - 1);
        if (outstanding == 0)
        {
            callToken = null;
            refreshingFunds = Set.of();
        }
        notifyChanged();
    }

    private void notifyChanged()
    {
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
