package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Disposition;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Refresh;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

@SuppressWarnings("nls")
public class PortfolioLensSessionTest
{
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "test");
    private static final CompositionPersistencePolicy POLICY = new CompositionPersistencePolicy(ACCOUNT,
                    CompositionPersistencePolicy.Permission.UNVERIFIED, Optional.empty());

    private static final class MutableClock extends Clock
    {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public Instant instant()
        {
            return now;
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return Clock.fixed(now, zone);
        }
    }

    private static final class QueueExecutor implements Executor
    {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command)
        {
            tasks.add(command);
        }

        void drain()
        {
            int remaining = 1000;
            while (!tasks.isEmpty())
            {
                assertTrue("Queue must terminate", remaining-- > 0);
                tasks.removeFirst().run();
            }
        }
    }

    private final MutableClock clock = new MutableClock();
    private final QueueExecutor executor = new QueueExecutor();
    private final CompositionCredentials credentials = new CompositionCredentials(new CompositionCredentials.SecureStore()
    {
        @Override
        public Optional<String> read(ProviderAccount account)
        {
            return Optional.empty();
        }

        @Override
        public void write(ProviderAccount account, String token)
        {
            throw new AssertionError("No persistent credentials in tests");
        }

        @Override
        public void remove(ProviderAccount account)
        {
        }
    });
    private final List<CompositionInstrument> calls = new ArrayList<>();
    private boolean unknownDenominator;
    private final FundCompositionProvider provider = new FundCompositionProvider()
    {
        @Override
        public String getId()
        {
            return ACCOUNT.providerId();
        }

        @Override
        public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount account)
        {
            return POLICY;
        }

        @Override
        public CompositionResult getComposition(ProviderAccount account, CompositionInstrument fund)
        {
            calls.add(fund);
            if (!credentials.getBearerToken(account).equals(Optional.of("synthetic-valid")))
                return new CompositionResult(CompositionResult.Status.AUTHENTICATION_FAILED, Optional.empty(),
                                Optional.empty());
            if (fund.identifiers().equals(instrument("missing").identifiers()))
                return new CompositionResult(CompositionResult.Status.UNAVAILABLE, Optional.empty(), Optional.empty());
            var composition = new FundComposition(new CompositionProvenance(account, fund, clock.instant(),
                            Optional.empty(), Optional.empty()),
                            unknownDenominator ? FundComposition.Denominator.UNKNOWN
                                            : FundComposition.Denominator.TOTAL_FUND_NAV,
                            FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                            List.of(new FundComposition.Constituent(instrument("child"), Optional.of("0.8"))));
            return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(composition), Optional.empty());
        }
    };
    private final PortfolioLensSession session = new PortfolioLensSession(provider,
                    (account, instrument) -> Optional.empty(), credentials, clock, executor);

    private static CompositionInstrument instrument(String id)
    {
        return new CompositionInstrument("Synthetic " + id, Map.of("synthetic:id", id));
    }

    private void configure()
    {
        credentials.replaceSession(ACCOUNT, "synthetic-valid");
        session.configure(POLICY, Optional.empty());
    }

    private Refresh refresh(String id, boolean manual)
    {
        var result = session.refresh(ACCOUNT, instrument(id), manual);
        executor.drain();
        return result.join();
    }

    @Test
    public void testUnavailableAndUnvalidatedRowsDoNotStallSelection()
    {
        configure();
        unknownDenominator = true;
        var first = session.refresh(ACCOUNT, instrument("missing"), true);
        var second = session.refresh(ACCOUNT, instrument("fund-two"), true);
        var third = session.refresh(ACCOUNT, instrument("fund-three"), true);
        executor.drain();
        assertEquals(3, calls.size());
        assertEquals(CompositionResult.Status.UNAVAILABLE,
                        first.join().snapshot().latestAttempt().orElseThrow().status());
        for (var result : List.of(second, third))
        {
            assertEquals(Disposition.COMPLETED, result.join().disposition());
            assertEquals(CompositionResult.Status.RECEIVED,
                            result.join().snapshot().latestAttempt().orElseThrow().status());
            assertTrue(result.join().snapshot().usableComposition().isEmpty());
        }
        // Unverified but arithmetically usable rows are retained for display and
        // do not set a failure backoff. A manual refresh is therefore admitted
        // immediately; the automatic pass still waits for the daily cadence.
        assertEquals(Disposition.COMPLETED, refresh("fund-two", true).disposition());
        assertEquals(4, calls.size());

        // The provider answered definitively for "missing": no second request.
        assertEquals(Disposition.NO_COMPOSITION, refresh("missing", true).disposition());
        assertEquals(4, calls.size());
    }

    @Test
    public void testWholeSelectionDrainsAndDuplicateViewsCoalesce()
    {
        configure();
        var futures = new ArrayList<CompletableFuture<Refresh>>();
        for (int index = 0; index < 7; index++)
            futures.add(session.refresh(ACCOUNT, instrument(Integer.toString(index)), true));
        assertSame(futures.get(4), session.refresh(ACCOUNT,
                        new CompositionInstrument("Renamed", instrument("4").identifiers()), true));
        assertTrue(calls.isEmpty());
        executor.drain();
        assertEquals(7, calls.size());
        for (var future : futures)
        {
            assertEquals(Disposition.COMPLETED, future.join().disposition());
            assertTrue(future.join().snapshot().usableComposition().isPresent());
        }
    }

    @Test
    public void testCorrectedCredentialsResumeAutomaticRetrieval()
    {
        session.configure(POLICY, Optional.empty());
        credentials.replaceSession(ACCOUNT, "synthetic-invalid");
        assertEquals(CompositionResult.Status.AUTHENTICATION_FAILED,
                        refresh("fund", true).snapshot().latestAttempt().orElseThrow().status());
        assertEquals(Disposition.SUSPENDED, refresh("fund", true).disposition());
        assertEquals(1, calls.size());
        credentials.replaceSession(ACCOUNT, "synthetic-valid");
        assertEquals(Disposition.COMPLETED, refresh("fund", false).disposition());
        assertEquals(2, calls.size());
    }

    @Test
    public void testInitialRetrievalCadenceManualRefreshAndMemoryOnlyReads()
    {
        session.configure(POLICY, Optional.empty());
        assertEquals(Disposition.SUSPENDED, refresh("fund", false).disposition());
        assertTrue(calls.isEmpty());
        configure();
        assertEquals(Disposition.COMPLETED, refresh("fund", false).disposition());
        for (int index = 0; index < 10; index++)
            session.read(ACCOUNT, instrument("fund"));
        clock.now = clock.now.plusSeconds(60);
        assertEquals(Disposition.CADENCE, refresh("fund", false).disposition());
        assertEquals(1, calls.size());
        clock.now = clock.now.plus(Duration.ofHours(24));
        assertEquals(Disposition.COMPLETED, refresh("fund", false).disposition());
        assertEquals(Disposition.COMPLETED, refresh("fund", true).disposition());
        assertEquals(3, calls.size());
    }

    @Test
    public void testStatusReadsDoNotInvalidatePendingStoreWrites()
    {
        configure();
        var result = session.refresh(ACCOUNT, instrument("fund"), true);
        executor.tasks.removeFirst().run();
        // Status rendering re-applies an unchanged preference while fetching.
        session.configure(POLICY, Optional.empty());
        executor.drain();
        assertTrue(result.join().snapshot().usableComposition().isPresent());
        assertTrue(session.read(ACCOUNT, instrument("fund")).usableComposition().isPresent());
    }

    @Test
    public void testUnusableSourceKeepsItsProvenanceWithoutEnteringCalculation()
    {
        configure();
        unknownDenominator = true;
        refresh("fund", true);
        var read = session.read(ACCOUNT, instrument("fund"));
        assertTrue(read.latestAttempt().orElseThrow().composition().isPresent());
        assertEquals(Optional.of(clock.instant()), read.attemptedAt());
        assertFalse(read.usableComposition().isPresent());
    }

    @Test
    public void testClassificationCacheIsAccountLocal()
    {
        configure();
        var other = new ProviderAccount(ACCOUNT.providerId(), "other");
        credentials.replaceSession(other, "synthetic-valid");
        var classifications = new AtomicInteger();
        var isolated = new PortfolioLensSession(provider, (account, instrument) -> {
            classifications.incrementAndGet();
            return Optional.empty();
        }, credentials, clock, executor);
        isolated.configure(POLICY, Optional.empty());
        isolated.configure(new CompositionPersistencePolicy(other, CompositionPersistencePolicy.Permission.UNVERIFIED,
                        Optional.empty()), Optional.empty());
        for (var account : List.of(ACCOUNT, ACCOUNT, other))
        {
            var result = isolated.refresh(account, instrument("missing"), true);
            executor.drain();
            result.join();
        }
        assertEquals(2, classifications.get());
    }

    @Test
    public void testFailedHeldClassificationCanRetryWithoutRefetchingComposition()
    {
        configure();
        var classifications = new AtomicInteger();
        var retrying = new PortfolioLensSession(provider, (account, instrument) -> {
            if (classifications.incrementAndGet() == 1)
                throw new java.io.IOException();
            return Optional.empty();
        }, credentials, clock, executor);
        retrying.configure(POLICY, Optional.empty());
        var first = retrying.refresh(ACCOUNT, instrument("missing"), true);
        executor.drain();
        assertEquals(Disposition.COMPLETED, first.join().disposition());
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        var second = retrying.refresh(ACCOUNT, instrument("missing"), true);
        executor.drain();
        assertEquals(Disposition.NO_COMPOSITION, second.join().disposition());
        assertEquals(2, classifications.get());
        assertEquals(1, calls.size());
    }

    @Test
    public void testSameIsinWithDifferentTickersReusesClassification()
    {
        configure();
        var classifications = new AtomicInteger();
        var shared = new PortfolioLensSession(provider, (account, instrument) -> {
            classifications.incrementAndGet();
            return Optional.empty();
        }, credentials, clock, executor);
        shared.configure(POLICY, Optional.empty());
        for (var ticker : List.of("FIRST", "SECOND"))
        {
            var source = new CompositionInstrument("Synthetic listing", Map.of("ISIN", "SYNTHETIC100",
                            "synthetic:ticker", ticker));
            var result = shared.refresh(ACCOUNT, source, true);
            executor.drain();
            result.join();
        }
        // One common constituent and one held ISIN, not one per listing label.
        assertEquals(2, classifications.get());
    }
}
