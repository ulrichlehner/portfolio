package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundStatus;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.GlobalFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Model;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.Listener;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.Monitor;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.Outcome;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.Result;

/**
 * Synthetic-only tests of the SWT-free refresh controller. A fake gateway
 * replaces the final coordinator, the executor is direct, cancellation flags
 * are simple booleans; there are no threads, files, network calls or
 * persistence.
 */
@SuppressWarnings("nls")
public class LookThroughRefreshControllerTest
{
    private static final Instant NOW = Instant.parse("2026-03-01T16:00:00Z");
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "test");
    private static final CompositionInstrument FUND_A = instrument("SYNTH-A");
    private static final CompositionInstrument FUND_B = instrument("SYNTH-B");

    private static CompositionInstrument instrument(String identifier)
    {
        return new CompositionInstrument("Synthetic fund " + identifier, Map.of("synthetic:instrument", identifier));
    }

    private static CompositionRefreshCoordinator.Snapshot emptySnapshot()
    {
        return new CompositionRefreshCoordinator.Snapshot(Optional.empty(), Optional.empty(), Optional.empty(),
                        List.of());
    }

    private static CompositionRefreshCoordinator.Refresh emptyRefresh()
    {
        return new CompositionRefreshCoordinator.Refresh(CompositionRefreshCoordinator.Disposition.COMPLETED,
                        emptySnapshot(), Optional.empty());
    }

    private static CompositionRefreshCoordinator.Refresh failedRefreshKeepingRetained()
    {
        var provenance = new CompositionProvenance(ACCOUNT, FUND_B, NOW.minusSeconds(7200), Optional.empty(),
                        Optional.empty());
        var constituent = new FundComposition.Constituent(instrument("SYNTH-CO"), Optional.of("1.0"));
        var retained = new FundComposition(provenance, FundComposition.Denominator.TOTAL_FUND_NAV,
                        FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                        List.of(constituent));
        var snapshot = new CompositionRefreshCoordinator.Snapshot( //
                        Optional.of(NOW.minusSeconds(60)), //
                        Optional.of(new CompositionResult(CompositionResult.Status.FAILED, Optional.empty(),
                                        Optional.empty())), //
                        Optional.of(retained), //
                        List.of());
        return new CompositionRefreshCoordinator.Refresh(CompositionRefreshCoordinator.Disposition.COMPLETED, snapshot,
                        Optional.empty());
    }

    private FundRef ref(CompositionInstrument fund)
    {
        return new FundRef(ACCOUNT, fund);
    }

    private LookThroughRefreshController controller(RecordingGateway gateway)
    {
        return new LookThroughRefreshController(gateway, Runnable::run);
    }

    private GatewayRefresh singleRefresh(LookThroughRefreshController controller, RecordingGateway gateway,
                    Monitor monitor, Listener listener, FundRef fund)
    {
        controller.refresh(List.of(fund), monitor, listener);
        assertEquals(1, gateway.calls.size());
        return gateway.calls.get(0);
    }

    @Test
    public void testCancelledMonitorBeforeStartIssuesNoGatewayRequest()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        controller.refresh(List.of(ref(FUND_A)), () -> true, listener);

        assertEquals(0, gateway.calls.size());
        assertEquals(1, listener.results.size());
        var result = listener.results.get(0);
        assertEquals(Outcome.CANCELLED, result.outcome());
        assertEquals(ref(FUND_A), result.fund());
        assertTrue(result.refresh().isEmpty());
    }

    @Test
    public void testCancelledMonitorDuringFlightAppliesNothingAndReportsCancelled()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var monitor = new MutableMonitor();
        var listener = new CapturingListener();

        controller.refresh(List.of(ref(FUND_B)), monitor, listener);
        assertEquals(1, gateway.calls.size());

        monitor.cancelled = true;
        gateway.complete(ref(FUND_B), emptyRefresh());

        assertEquals(1, gateway.calls.size());
        assertEquals(1, listener.results.size());
        var result = listener.results.get(0);
        assertEquals(Outcome.CANCELLED, result.outcome());
        assertTrue(result.refresh().isEmpty());
    }

    @Test
    public void testTwoRefreshesOfTheSameFundIssueExactlyOneGatewayRequest()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        controller.refresh(List.of(ref(FUND_A)), () -> false, listener);
        controller.refresh(List.of(ref(FUND_A)), () -> false, listener);

        assertEquals(1, gateway.calls.size());

        gateway.complete(ref(FUND_A), emptyRefresh());

        // One gateway call, one outcome per call: the older one is superseded.
        assertEquals(2, listener.results.size());
        assertEquals(Outcome.CANCELLED, listener.results.get(0).outcome());
        assertEquals(Outcome.COMPLETED, listener.results.get(1).outcome());
    }

    @Test
    public void testNewerRefreshSupersedesTheOlderOutcomeWhichIsReportedCancelled()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var olderListener = new CapturingListener();
        var newerListener = new CapturingListener();

        assertEquals(1, controller.refresh(List.of(ref(FUND_A)), () -> false, olderListener));
        assertEquals(1, controller.refresh(List.of(ref(FUND_A)), () -> false, newerListener));

        assertEquals(1, gateway.calls.size());

        gateway.complete(ref(FUND_A), emptyRefresh());

        // Both calls end with exactly one outcome; only the newest applies.
        assertEquals(1, olderListener.results.size());
        assertEquals(Outcome.CANCELLED, olderListener.results.get(0).outcome());
        assertTrue(olderListener.results.get(0).refresh().isEmpty());
        assertEquals(1, newerListener.results.size());
        assertEquals(Outcome.COMPLETED, newerListener.results.get(0).outcome());
    }

    @Test
    public void testRefreshReturnsTheNumberOfOutcomesItWillDeliver()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        assertEquals(2, controller.refresh(List.of(ref(FUND_A), ref(FUND_B)), () -> false, listener));
        assertEquals(1, controller.refresh(List.of(ref(FUND_B)), () -> false, listener));
        assertEquals(0, controller.refresh(List.of(), () -> false, listener));

        gateway.complete(ref(FUND_A), emptyRefresh());
        gateway.complete(ref(FUND_B), emptyRefresh());

        assertEquals(3, listener.results.size());
    }

    @Test
    public void testFailedGatewayCompletionReportsFailureWithoutResult()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        controller.refresh(List.of(ref(FUND_B)), () -> false, listener);
        gateway.fail(ref(FUND_B));

        assertEquals(1, listener.results.size());
        var result = listener.results.get(0);
        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals(ref(FUND_B), result.fund());
        assertTrue(result.refresh().isEmpty());
    }

    @Test
    public void testFailedRefreshKeepsTheRetainedSnapshotDatesAndReportsTheFailure()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        controller.refresh(List.of(ref(FUND_B)), () -> false, listener);
        gateway.complete(ref(FUND_B), failedRefreshKeepingRetained());

        var result = listener.results.get(0);
        assertEquals(Outcome.COMPLETED, result.outcome());
        var delivered = result.refresh().orElseThrow();

        var facts = LookThroughDataStatus.fundFacts(delivered, ACCOUNT, FUND_B, false);
        var row = firstStatus(present(facts));

        assertEquals("The last retrieval attempt failed.", row.attemptText());
        assertTrue(row.retrieval().isPresent());
        assertTrue(row.retrievalText().isPresent());
        // The retained provenance dates survive the failed attempt unchanged.
        assertEquals(NOW.minusSeconds(7200), row.retrieval().orElseThrow());
        assertTrue(row.providerRefreshedAt().isEmpty());
        assertEquals("The holdings effective date is not available.", row.holdingsText().orElseThrow());
    }

    @Test
    public void testDistinctFundsIssueSeparateGatewayRequestsAndBothDeliver()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        controller.refresh(List.of(ref(FUND_A), ref(FUND_B)), () -> false, listener);

        assertEquals(2, gateway.calls.size());

        gateway.complete(ref(FUND_A), emptyRefresh());
        gateway.complete(ref(FUND_B), emptyRefresh());

        assertEquals(2, listener.results.size());
        assertEquals(List.of(ref(FUND_A), ref(FUND_B)), listener.results.stream().map(Result::fund).toList());
        assertTrue(listener.results.stream().allMatch(result -> result.outcome() == Outcome.COMPLETED));
    }

    @Test
    public void testUnidentifiedFundIsRefusedWithoutGatewayRequest()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        var unidentified = new CompositionInstrument("Synthetic fund unidentified", Map.of());
        controller.refresh(List.of(new FundRef(ACCOUNT, unidentified)), () -> false, listener);

        assertEquals(0, gateway.calls.size());
        assertEquals(1, listener.results.size());
        assertEquals(Outcome.FAILED, listener.results.get(0).outcome());
        assertTrue(listener.results.get(0).refresh().isEmpty());
    }

    @Test
    public void testManualRefreshIsAlwaysRequestedManuallyAndCompletesWithTheCoordinatorDisposition()
    {
        var gateway = new RecordingGateway();
        var controller = controller(gateway);
        var listener = new CapturingListener();

        var delivered = singleRefresh(controller, gateway, () -> false, listener, ref(FUND_A));
        assertEquals(Boolean.TRUE, delivered.manual);

        gateway.complete(ref(FUND_A), emptyRefresh());

        var result = listener.results.get(0);
        assertEquals(Outcome.COMPLETED, result.outcome());
        assertEquals(CompositionRefreshCoordinator.Disposition.COMPLETED, result.refresh().orElseThrow().disposition());
    }

    private Model present(FundFacts facts)
    {
        return LookThroughDataStatus.present(Clock.fixed(NOW, ZoneId.of("UTC")), new GlobalFacts(
                        CompositionStore.State.SESSION_ONLY,
                        new CompositionPersistencePolicy.EffectiveMode(
                                        CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED)),
                        List.of(facts));
    }

    private FundStatus firstStatus(Model model)
    {
        return model.funds().get(0);
    }

    /** Records gateway operations; completions are manual and synchronous. */
    private static final class RecordingGateway implements RefreshGateway
    {
        private final List<GatewayRefresh> calls = new ArrayList<>();
        private final Map<FundRef, CompletableFuture<CompositionRefreshCoordinator.Refresh>> futures = new HashMap<>();

        @Override
        public CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument fund)
        {
            return emptySnapshot();
        }

        @Override
        public CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument fund, boolean manual)
        {
            var future = new CompletableFuture<CompositionRefreshCoordinator.Refresh>();
            futures.put(new FundRef(account, fund), future);
            calls.add(new GatewayRefresh(new FundRef(account, fund), manual));
            return future;
        }

        void complete(FundRef fund, CompositionRefreshCoordinator.Refresh value)
        {
            futures.get(fund).complete(value);
        }

        void fail(FundRef fund)
        {
            futures.get(fund).completeExceptionally(new IllegalStateException("synthetic gateway failure"));
        }
    }

    private record GatewayRefresh(FundRef fund, boolean manual)
    {
    }

    /** A cancellation flag that a test flips at a chosen moment. */
    private static final class MutableMonitor implements Monitor
    {
        private boolean cancelled;

        @Override
        public boolean isCancelled()
        {
            return cancelled;
        }
    }

    private static final class CapturingListener implements Listener
    {
        private final List<Result> results = new ArrayList<>();

        @Override
        public void updated(Result result)
        {
            results.add(result);
        }
    }
}
