package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
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
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundStatus;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.GlobalFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Model;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;

/**
 * Synthetic-only tests of the status state machine. A fake gateway and a direct
 * executor replace the coordinator and the user interface thread; there are no
 * threads, files, network calls or persistence.
 */
@SuppressWarnings("nls")
public class LookThroughStatusModelTest
{
    private static final Instant NOW = Instant.parse("2026-03-01T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "test");
    private static final FundRef FUND_A = new FundRef(ACCOUNT, instrument("SYNTH-A"));
    private static final FundRef FUND_B = new FundRef(ACCOUNT, instrument("SYNTH-B"));

    private static CompositionInstrument instrument(String identifier)
    {
        return new CompositionInstrument("Synthetic fund " + identifier, Map.of("synthetic:instrument", identifier));
    }

    private static GlobalFacts globalFacts()
    {
        return new GlobalFacts(CompositionStore.State.SESSION_ONLY,
                        new CompositionPersistencePolicy.EffectiveMode(
                                        CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED));
    }

    private static CompositionRefreshCoordinator.Snapshot snapshot(Optional<FundComposition> retained,
                    Optional<CompositionResult.Status> attempt)
    {
        return new CompositionRefreshCoordinator.Snapshot(Optional.of(NOW.minus(Duration.ofHours(2))), //
                        attempt.map(status -> new CompositionResult(status, retained, Optional.empty())), retained,
                        List.of());
    }

    private static FundComposition composition(FundRef fund, Instant retrievedAt)
    {
        var provenance = new CompositionProvenance(ACCOUNT, fund.fund(), retrievedAt, Optional.empty(),
                        Optional.empty());
        var constituent = new FundComposition.Constituent(instrument("SYNTH-CO"), Optional.of("1.0"));
        return new FundComposition(provenance, FundComposition.Denominator.TOTAL_FUND_NAV,
                        FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                        List.of(constituent));
    }

    /** A source whose current snapshot the test sets, or clears, itself. */
    private static final class SourceStub implements LookThroughStatusModel.Source
    {
        private LookThroughStatusModel.Snapshot snapshot = new LookThroughStatusModel.Snapshot(globalFacts(),
                        List.of(FUND_A));

        @Override
        public Optional<LookThroughStatusModel.Snapshot> current()
        {
            return Optional.ofNullable(snapshot);
        }
    }

    /** Counts reads and requests, and lets the test complete a request. */
    private static final class RecordingGateway implements LookThroughRefreshController.RefreshGateway
    {
        private final Map<FundRef, CompletableFuture<CompositionRefreshCoordinator.Refresh>> futures = new HashMap<>();
        private final List<FundRef> refreshes = new ArrayList<>();
        private final List<FundRef> reads = new ArrayList<>();

        @Override
        public CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument fund)
        {
            var ref = new FundRef(account, fund);
            reads.add(ref);
            return snapshot(Optional.of(composition(ref, NOW.minus(Duration.ofHours(3)))), Optional.empty());
        }

        @Override
        public CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument fund, boolean manual)
        {
            var ref = new FundRef(account, fund);
            refreshes.add(ref);
            var future = new CompletableFuture<CompositionRefreshCoordinator.Refresh>();
            futures.put(ref, future);
            return future;
        }

        private void complete(FundRef fund, CompositionRefreshCoordinator.Refresh value)
        {
            futures.get(fund).complete(value);
        }

        private int refreshCalls()
        {
            return refreshes.size();
        }
    }

    private LookThroughStatusModel model(RecordingGateway gateway, LookThroughStatusModel.Source source)
    {
        return new LookThroughStatusModel(CLOCK, gateway, Runnable::run, source);
    }

    private static FundStatus first(Model model)
    {
        return model.funds().get(0);
    }

    @Test
    public void testRenderingWithoutDataYieldsNothingAndRequestsNothing()
    {
        var gateway = new RecordingGateway();
        var source = new SourceStub();
        source.snapshot = null;
        var model = model(gateway, source);

        assertTrue(model.status().isEmpty());
        assertEquals(0, model.refresh());
        assertEquals(0, gateway.reads.size());
        assertEquals(0, gateway.refreshCalls());
        assertFalse(model.isRefreshing());
    }

    @Test
    public void testRepeatedRenderingOnlyReadsAndNeverRequests()
    {
        var gateway = new RecordingGateway();
        var model = model(gateway, new SourceStub());

        for (var i = 0; i < 50; i++)
        {
            var status = model.status().orElseThrow();
            assertEquals(1, status.funds().size());
            assertEquals("Synthetic fund SYNTH-A", first(status).fundLabel());
        }

        assertEquals(0, gateway.refreshCalls());
        assertEquals(50, gateway.reads.size());
    }

    @Test
    public void testRefreshRequestsEachFundOnceAndAppliesTheOutcome()
    {
        var gateway = new RecordingGateway();
        var source = new SourceStub();
        source.snapshot = new LookThroughStatusModel.Snapshot(globalFacts(), List.of(FUND_A, FUND_B));
        var model = model(gateway, source);

        assertEquals(2, model.refresh());
        assertTrue(model.isRefreshing());
        assertEquals(List.of(FUND_A, FUND_B), gateway.refreshes);

        var retained = Optional.of(composition(FUND_A, NOW.minus(Duration.ofMinutes(5))));
        gateway.complete(FUND_A, new CompositionRefreshCoordinator.Refresh(
                        CompositionRefreshCoordinator.Disposition.COMPLETED, snapshot(retained,
                                        Optional.of(CompositionResult.Status.RECEIVED)),
                        Optional.empty()));

        assertTrue(model.isRefreshing());
        var afterFirst = first(model.status().orElseThrow());
        assertEquals(Optional.of(CompositionResult.Status.RECEIVED), afterFirst.latestAttempt());
        assertTrue(afterFirst.refreshPending());

        gateway.complete(FUND_B, new CompositionRefreshCoordinator.Refresh(
                        CompositionRefreshCoordinator.Disposition.COMPLETED, snapshot(Optional.empty(),
                                        Optional.of(CompositionResult.Status.UNAVAILABLE)),
                        Optional.empty()));

        assertFalse(model.isRefreshing());
        var status = model.status().orElseThrow();
        assertEquals("The provider has no composition data for this source right now.",
                        status.funds().get(1).attemptText());
        assertFalse(status.funds().get(1).refreshPending());
    }

    @Test
    public void testRefreshWithoutFundsRequestsNothing()
    {
        var gateway = new RecordingGateway();
        var source = new SourceStub();
        source.snapshot = new LookThroughStatusModel.Snapshot(globalFacts(), List.of());
        var model = model(gateway, source);

        assertEquals(0, model.refresh());
        assertFalse(model.isRefreshing());
        assertEquals(0, gateway.refreshCalls());
    }

    @Test
    public void testCancellationStopsWaitingAndIgnoresLateOutcomes()
    {
        var gateway = new RecordingGateway();
        var model = model(gateway, new SourceStub());

        assertEquals(1, model.refresh());
        assertTrue(model.isRefreshing());

        model.cancel();
        assertFalse(model.isRefreshing());

        var retained = Optional.of(composition(FUND_A, NOW.minus(Duration.ofMinutes(5))));
        gateway.complete(FUND_A, new CompositionRefreshCoordinator.Refresh(
                        CompositionRefreshCoordinator.Disposition.COMPLETED, snapshot(retained,
                                        Optional.of(CompositionResult.Status.RECEIVED)),
                        Optional.empty()));

        // The superseded outcome is not applied: the retained read stays visible.
        var status = first(model.status().orElseThrow());
        assertTrue(status.latestAttempt().isEmpty());
        assertEquals("No retrieval attempt has been made yet.", status.attemptText());
        assertEquals(Optional.of(NOW.minus(Duration.ofHours(3))), status.retrieval());
        assertFalse(model.isRefreshing());
    }

    @Test
    public void testRateLimitedRetryStaysVisibleAfterTheAttempt()
    {
        var gateway = new RecordingGateway();
        var model = model(gateway, new SourceStub());
        var retryAt = NOW.plus(Duration.ofMinutes(30));

        assertEquals(1, model.refresh());
        gateway.complete(FUND_A, new CompositionRefreshCoordinator.Refresh(
                        CompositionRefreshCoordinator.Disposition.COMPLETED, snapshot(Optional.empty(),
                                        Optional.of(CompositionResult.Status.RATE_LIMITED)),
                        Optional.of(retryAt)));

        var status = first(model.status().orElseThrow());
        assertEquals(Optional.of(CompositionResult.Status.RATE_LIMITED), status.latestAttempt());
        assertEquals(Optional.of(retryAt), status.retryAfter());
        assertTrue(status.retryText().isPresent());
        assertFalse(model.isRefreshing());
    }

    @Test
    public void testChangeListenerIsNotifiedPerOutcomeAndWhenIdle()
    {
        var gateway = new RecordingGateway();
        var source = new SourceStub();
        source.snapshot = new LookThroughStatusModel.Snapshot(globalFacts(), List.of(FUND_A, FUND_B));
        var model = model(gateway, source);
        var notifications = new ArrayList<Boolean>();
        model.setChangeListener(() -> notifications.add(model.isRefreshing()));

        assertEquals(2, model.refresh());
        gateway.complete(FUND_A, new CompositionRefreshCoordinator.Refresh(
                        CompositionRefreshCoordinator.Disposition.COMPLETED,
                        snapshot(Optional.empty(), Optional.of(CompositionResult.Status.UNAVAILABLE)),
                        Optional.empty()));
        gateway.complete(FUND_B, new CompositionRefreshCoordinator.Refresh(
                        CompositionRefreshCoordinator.Disposition.COMPLETED,
                        snapshot(Optional.empty(), Optional.of(CompositionResult.Status.UNAVAILABLE)),
                        Optional.empty()));

        assertEquals(List.of(true, true, false), notifications);
        assertFalse(notifications.get(notifications.size() - 1));
    }
}
