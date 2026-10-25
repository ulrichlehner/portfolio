package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.Test;

import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Disposition;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRequestBudgetTest.MutableClock;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;

public class CompositionRefreshCoordinatorTest
{
    private static final class QueueExecutor implements Executor
    {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command)
        {
            tasks.add(command);
        }

        void runAll()
        {
            while (!tasks.isEmpty())
                tasks.removeFirst().run();
        }
    }

    private final MutableClock clock = new MutableClock();
    private final QueueExecutor executor = new QueueExecutor();
    private final ProviderAccount account = new ProviderAccount("synthetic", "first");
    private final ProviderAccount other = new ProviderAccount("synthetic", "second");
    private final CompositionInstrument fund = instrument("fund");
    private final AtomicInteger calls = new AtomicInteger();
    private final CompositionRequestBudget budget = new CompositionRequestBudget("synthetic", clock, 100,
                    Duration.ofHours(24), Duration.ofMinutes(10));
    private BiFunction<ProviderAccount, CompositionInstrument, CompositionResult> response = this::received;

    private final FundCompositionProvider provider = new FundCompositionProvider()
    {
        @Override
        public String getId()
        {
            return "synthetic";
        }

        @Override
        public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount requested)
        {
            throw new AssertionError("Refresh must not activate storage");
        }

        @Override
        public CompositionResult getComposition(ProviderAccount requested, CompositionInstrument requestedFund)
        {
            calls.incrementAndGet();
            return response.apply(requested, requestedFund);
        }
    };

    private CompositionInstrument instrument(String id)
    {
        return new CompositionInstrument("Synthetic", Map.of("synthetic:id", id));
    }

    private CompositionResult failed(CompositionResult.Status status)
    {
        return new CompositionResult(status, Optional.empty(), Optional.empty());
    }

    private CompositionResult received(ProviderAccount requested, CompositionInstrument requestedFund)
    {
        var composition = new FundComposition(new CompositionProvenance(requested, requestedFund, clock.instant(),
                        Optional.empty(), Optional.empty()), Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS,
                        Completeness.PARTIAL,
                        List.of(new Constituent(instrument("one"), Optional.of("0.4")),
                                        new Constituent(instrument("two"), Optional.of("0.3")),
                                        new Constituent(instrument("three"), Optional.of("0.2"))));
        return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(composition), Optional.empty());
    }

    private CompositionRefreshCoordinator coordinator()
    {
        return new CompositionRefreshCoordinator(provider, budget, clock, executor, 2, 1, 0, 2,
                        (requested, source) -> {
                            throw new AssertionError("No enrichment configured");
                        });
    }

    @Test
    public void testConstructionReadAndRedrawNeverScheduleRequests()
    {
        try (var coordinator = coordinator())
        {
            for (int i = 0; i < 100; i++)
                assertTrue(coordinator.read(account, fund).latestAttempt().isEmpty());
            assertTrue(executor.tasks.isEmpty());
            assertEquals(0, calls.get());
            var request = coordinator.refresh(account, fund, false);
            assertFalse(request.isDone());
            executor.runAll();
            assertEquals(Disposition.COMPLETED, request.join().disposition());
            for (int i = 0; i < 100; i++)
                assertTrue(coordinator.read(account, fund).usableComposition().isPresent());
            assertEquals(1, calls.get());
            clock.advance(Duration.ofDays(10));
            executor.runAll();
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void testCoalescesLabelsAndManualWithAutomaticButNotAccounts()
    {
        try (var coordinator = coordinator())
        {
            var first = coordinator.refresh(account, fund, false);
            var alias = new CompositionInstrument("Different label", fund.identifiers());
            assertSame(first, coordinator.refresh(account, alias, true));
            var second = coordinator.refresh(other, fund, false);
            executor.runAll();
            assertEquals(2, calls.get());
            assertEquals(account, first.join().snapshot().usableComposition().orElseThrow().provenance().source());
            assertEquals(other, second.join().snapshot().usableComposition().orElseThrow().provenance().source());
        }
    }

    @Test
    public void testCoalescesWhileProviderIsExecuting()
    {
        try (var coordinator = coordinator())
        {
            var inFlight = coordinator.refresh(account, fund, false);
            response = (requested, source) -> {
                assertSame(inFlight, coordinator.refresh(account, fund, true));
                return received(requested, source);
            };
            executor.runAll();
            assertEquals(Disposition.COMPLETED, inFlight.join().disposition());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void testRollingCadenceIncludesFailedAndManualAttempts()
    {
        try (var coordinator = coordinator())
        {
            response = (requested, source) -> failed(CompositionResult.Status.FAILED);
            var start = clock.instant();
            coordinator.refresh(account, fund, false);
            executor.runAll();
            clock.advance(Duration.ofMinutes(10));
            assertEquals(Disposition.CADENCE, coordinator.refresh(account, fund, false).join().disposition());
            assertEquals(Optional.of(start.plus(Duration.ofHours(24))),
                            coordinator.refresh(account, fund, false).join().retryAt());
            response = this::received;
            var manual = coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(Disposition.COMPLETED, manual.join().disposition());
            clock.advance(Duration.ofHours(24).minusSeconds(1));
            assertEquals(Disposition.CADENCE, coordinator.refresh(account, fund, false).join().disposition());
            clock.advance(Duration.ofSeconds(1));
            coordinator.refresh(account, fund, false);
            executor.runAll();
            assertEquals(3, calls.get());
        }
    }

    @Test
    public void testManualRefreshCannotBypassRetryAfterOrTransientBackoff()
    {
        try (var coordinator = coordinator())
        {
            var deadline = clock.instant().plus(Duration.ofHours(2));
            response = (requested, source) -> new CompositionResult(CompositionResult.Status.RATE_LIMITED,
                            Optional.empty(), Optional.of(deadline));
            coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(Disposition.DEFERRED, coordinator.refresh(other, fund, true).join().disposition());
            assertEquals(Optional.of(deadline), coordinator.refresh(account, fund, true).join().retryAt());
            assertEquals(1, calls.get());
            clock.advance(Duration.ofHours(2));
            response = (requested, source) -> failed(CompositionResult.Status.FAILED);
            coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(Disposition.DEFERRED, coordinator.refresh(account, fund, true).join().disposition());
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void testAuthAndEntitlementRequireActualConfigurationChange()
    {
        for (var status : new CompositionResult.Status[] { CompositionResult.Status.AUTHENTICATION_FAILED,
                        CompositionResult.Status.ENTITLEMENT_FAILED })
        {
            try (var coordinator = coordinator())
            {
                var tested = new ProviderAccount("synthetic", status.name());
                response = (requested, source) -> failed(status);
                coordinator.refresh(tested, fund, true);
                executor.runAll();
                clock.advance(Duration.ofDays(2));
                assertEquals(Disposition.SUSPENDED, coordinator.refresh(tested, fund, true).join().disposition());
                coordinator.configurationChanged(tested, 0);
                assertEquals(Disposition.SUSPENDED, coordinator.refresh(tested, fund, true).join().disposition());
                coordinator.configurationChanged(tested, status.ordinal());
                response = this::received;
                var request = coordinator.refresh(tested, fund, true);
                executor.runAll();
                assertEquals(Disposition.COMPLETED, request.join().disposition());
            }
        }
    }

    @Test
    public void testQueuedWorkRechecksProviderBackoff()
    {
        try (var coordinator = coordinator())
        {
            response = (requested, source) -> new CompositionResult(CompositionResult.Status.RATE_LIMITED,
                            Optional.empty(), Optional.of(clock.instant().plusSeconds(30)));
            coordinator.refresh(account, fund, true);
            var second = coordinator.refresh(other, fund, true);
            executor.runAll();
            assertEquals(Disposition.DEFERRED, second.join().disposition());
            assertTrue(second.join().snapshot().attemptedAt().isEmpty());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void testMissingInstrumentDoesNotDelayOtherFunds()
    {
        try (var coordinator = coordinator())
        {
            response = (requested, source) -> failed(CompositionResult.Status.UNAVAILABLE);
            coordinator.refresh(account, fund, true);
            executor.runAll();
            var repeated = coordinator.refresh(account, fund, true).join();
            assertEquals(Disposition.NO_COMPOSITION, repeated.disposition());
            assertTrue(repeated.retryAt().isEmpty());
            assertEquals(1, calls.get());
            response = this::received;
            var next = coordinator.refresh(account, instrument("other-fund"), true);
            executor.runAll();
            assertEquals(Disposition.COMPLETED, next.join().disposition());
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void testFailureAndInvalidSnapshotsKeepPriorUsableDataAndDates()
    {
        try (var coordinator = coordinator())
        {
            coordinator.refresh(account, fund, true);
            executor.runAll();
            var prior = coordinator.read(account, fund).usableComposition().orElseThrow();
            clock.advance(Duration.ofHours(1));
            response = (requested, source) -> failed(CompositionResult.Status.FAILED);
            coordinator.refresh(account, fund, true);
            executor.runAll();
            assertSame(prior, coordinator.read(account, fund).usableComposition().orElseThrow());
            assertEquals(CompositionResult.Status.FAILED,
                            coordinator.read(account, fund).latestAttempt().orElseThrow().status());
            clock.advance(Duration.ofMinutes(10));
            response = (requested, source) -> {
                var valid = received(requested, source).composition().orElseThrow();
                var invalid = new FundComposition(valid.provenance(), Denominator.UNKNOWN, valid.exposureBasis(),
                                valid.completeness(), valid.constituents());
                return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(invalid), Optional.empty());
            };
            coordinator.refresh(account, fund, true);
            executor.runAll();
            assertSame(prior, coordinator.read(account, fund).usableComposition().orElseThrow());
            assertEquals(Denominator.UNKNOWN, coordinator.read(account, fund).latestAttempt().orElseThrow()
                            .composition().orElseThrow().denominator());
            assertEquals(Disposition.DEFERRED, coordinator.refresh(account, fund, true).join().disposition());
            response = this::received;
            var next = coordinator.refresh(account, instrument("other-fund"), true);
            executor.runAll();
            assertEquals(Disposition.COMPLETED, next.join().disposition());
        }
    }

    @Test
    public void testEnrichmentIsBoundedAndSharesCompositionBudget()
    {
        var lookups = new AtomicInteger();
        var limited = new CompositionRequestBudget("synthetic", clock, 4, Duration.ofHours(24), Duration.ofMinutes(1));
        try (var coordinator = new CompositionRefreshCoordinator(provider, limited, clock, executor, 2, 1, 1, 2,
                        (requested, source) -> {
                            lookups.incrementAndGet();
                            var provenance = new Provenance(requested, "synthetic evidence", clock.instant());
                            return List.of(new InstrumentEvidence(source.identifiers(),
                                            new Assertion<>(new Identifier("synthetic:id",
                                                            source.identifiers().get("synthetic:id")), provenance),
                                            Optional.of(new Assertion<>(AssetType.OTHER_NON_EQUITY, provenance)),
                                            Optional.empty()));
                        }))
        {
            coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(1, lookups.get());
            assertEquals(3, coordinator.read(account, fund).resolutions().size());
            assertEquals(InstrumentResolution.State.CASH_OR_OTHER,
                            coordinator.read(account, fund).resolutions().getFirst().state());
            assertEquals(InstrumentResolution.LookupStatus.BUDGET_EXHAUSTED,
                            coordinator.read(account, fund).resolutions().get(1).lookupStatus());
            assertEquals(Disposition.DEFERRED, coordinator.refresh(other, fund, true).join().disposition());
            assertEquals(1, calls.get());
            clock.advance(Duration.ofHours(24));
            coordinator.refresh(account, fund, false);
            executor.runAll();
            assertEquals(2, lookups.get());
            assertEquals(InstrumentResolution.LookupStatus.NOT_NEEDED,
                            coordinator.read(account, fund).resolutions().getFirst().lookupStatus());
            assertEquals(InstrumentResolution.State.CASH_OR_OTHER,
                            coordinator.read(account, fund).resolutions().get(1).state());
        }
    }

    @Test
    public void testEnrichmentAuthenticationFailureSuspendsFurtherLookupsAndRefresh()
    {
        var lookups = new AtomicInteger();
        try (var coordinator = new CompositionRefreshCoordinator(provider, budget, clock, executor, 2, 1, 10, 2,
                        (requested, source) -> {
                            lookups.incrementAndGet();
                            throw new EnrichmentFailure(failed(CompositionResult.Status.AUTHENTICATION_FAILED));
                        }))
        {
            coordinator.refresh(account, fund, false);
            executor.runAll();
            assertEquals(1, lookups.get());
            assertEquals(Disposition.SUSPENDED, coordinator.refresh(account, fund, true).join().disposition());
            assertTrue(coordinator.read(account, fund).usableComposition().isPresent());
        }
    }

    @Test
    public void testEnrichmentRetryAfterIsSharedWithOtherAccounts()
    {
        var lookups = new AtomicInteger();
        var deadline = clock.instant().plus(Duration.ofHours(2));
        try (var coordinator = new CompositionRefreshCoordinator(provider, budget, clock, executor, 2, 1, 10, 2,
                        (requested, source) -> {
                            lookups.incrementAndGet();
                            throw new EnrichmentFailure(new CompositionResult(CompositionResult.Status.RATE_LIMITED,
                                            Optional.empty(), Optional.of(deadline)));
                        }))
        {
            coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(1, lookups.get());
            assertEquals(Optional.of(deadline), coordinator.refresh(other, fund, true).join().retryAt());
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void testDuplicateRowsDoNotMultiplyUnsuccessfulEnrichment()
    {
        var lookups = new AtomicInteger();
        response = (requested, source) -> {
            var original = received(requested, source).composition().orElseThrow();
            var row = new Constituent(instrument("duplicate"), Optional.of("0.2"));
            var duplicate = new FundComposition(original.provenance(), original.denominator(), original.exposureBasis(),
                            original.completeness(), List.of(row, row, row));
            return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(duplicate), Optional.empty());
        };
        try (var coordinator = new CompositionRefreshCoordinator(provider, budget, clock, executor, 2, 1, 10, 2,
                        (requested, source) -> {
                            lookups.incrementAndGet();
                            return List.of();
                        }))
        {
            var refresh = coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(1, lookups.get());
            assertEquals(3, refresh.join().snapshot().resolutions().size());
            assertEquals(3, refresh.join().snapshot().usableComposition().orElseThrow().constituents().size());
        }
    }

    @Test
    public void testUnexpectedProviderFailureIsSanitizedAndDoesNotSpin()
    {
        try (var coordinator = coordinator())
        {
            response = (requested, source) -> {
                throw new IllegalStateException("Synthetic transport message must not escape");
            };
            var refresh = coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(CompositionResult.Status.FAILED,
                            refresh.join().snapshot().latestAttempt().orElseThrow().status());
            assertEquals(Disposition.DEFERRED, coordinator.refresh(account, fund, true).join().disposition());
            assertEquals(1, calls.get());
            assertTrue(executor.tasks.isEmpty());
        }
    }

    @Test
    public void testWrongAccountSnapshotCannotCrossAccountBoundary()
    {
        try (var coordinator = coordinator())
        {
            response = (requested, source) -> received(other, source);
            var refresh = coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(CompositionResult.Status.MALFORMED_RESPONSE,
                            refresh.join().snapshot().latestAttempt().orElseThrow().status());
            assertTrue(refresh.join().snapshot().usableComposition().isEmpty());
        }
    }

    @Test
    public void testCloseCancelsQueuedWorkAndDiscardsLateCompletion()
    {
        var coordinator = coordinator();
        var queued = coordinator.refresh(account, fund, false);
        coordinator.close();
        executor.runAll();
        assertEquals(0, calls.get());
        assertEquals(Disposition.CLOSED, queued.join().disposition());
        assertEquals(Disposition.CLOSED, coordinator.refresh(account, fund, true).join().disposition());

        var running = coordinator();
        response = (requested, source) -> {
            running.close();
            return received(requested, source);
        };
        var late = running.refresh(account, fund, false);
        executor.runAll();
        assertEquals(Disposition.CLOSED, late.join().disposition());
        assertTrue(running.read(account, fund).latestAttempt().isEmpty());
        assertTrue(executor.tasks.isEmpty());
    }

    @Test
    public void testBoundedQueueAndExecutorRejectionDoNotConsumeQuotaOrCadence()
    {
        try (var coordinator = coordinator())
        {
            coordinator.refresh(account, fund, true);
            coordinator.refresh(other, fund, true);
            assertEquals(Disposition.BUSY,
                            coordinator.refresh(account, instrument("another fund"), true).join().disposition());
        }
        executor.runAll();
        try (var rejected = new CompositionRefreshCoordinator(provider, budget, clock, command -> {
            throw new RejectedExecutionException();
        }, 2, 1, 0, 1, (requested, source) -> List.of()))
        {
            assertEquals(Disposition.BUSY, rejected.refresh(account, fund, false).join().disposition());
            assertEquals(Disposition.BUSY, rejected.refresh(account, fund, false).join().disposition());
            assertTrue(rejected.read(account, fund).attemptedAt().isEmpty());
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void testConfigurationChangeInvalidatesQueuedAndLateResponses()
    {
        try (var coordinator = coordinator())
        {
            var queued = coordinator.refresh(account, fund, true);
            coordinator.configurationChanged(account, 1);
            executor.runAll();
            assertEquals(Disposition.CONFIGURATION_CHANGED, queued.join().disposition());
            assertEquals(0, calls.get());
            response = (requested, source) -> {
                coordinator.configurationChanged(account, 2);
                return failed(CompositionResult.Status.AUTHENTICATION_FAILED);
            };
            var late = coordinator.refresh(account, fund, true);
            executor.runAll();
            assertEquals(Disposition.CONFIGURATION_CHANGED, late.join().disposition());
            assertFalse(budget.isSuspended(account));
            assertTrue(coordinator.read(account, fund).latestAttempt().isEmpty());
        }
    }
}
