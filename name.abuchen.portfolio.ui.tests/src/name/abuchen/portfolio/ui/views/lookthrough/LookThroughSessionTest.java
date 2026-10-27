package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentCache;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyProfile;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Disposition;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Refresh;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentClassifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.snapshot.lookthrough.VersionedCompositionStore;

@SuppressWarnings("nls")
public class LookThroughSessionTest
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
    /** The constituent has an ISIN, like a listed share of a real response. */
    private boolean listedChild;
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
                            List.of(new FundComposition.Constituent(listedChild
                                            ? new CompositionInstrument("Synthetic Holdings", Map.of("ISIN", "QS0000000017"))
                                            : instrument("child"), Optional.of("0.8"))));
            return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(composition), Optional.empty());
        }
    };
    private final LookThroughSession session = new LookThroughSession(provider,
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
        var isolated = new LookThroughSession(provider, (account, instrument) -> {
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
        var retrying = new LookThroughSession(provider, (account, instrument) -> {
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
        var shared = new LookThroughSession(provider, (account, instrument) -> {
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

    private static final CompositionPersistencePolicy PERMITTED = new CompositionPersistencePolicy(ACCOUNT,
                    CompositionPersistencePolicy.Permission.PERMITTED, Optional.empty());

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private LookThroughSession persistent(Path directory, InstrumentClassifier classifier,
                    CompositionPersistencePolicy policy)
    {
        var restarted = new LookThroughSession(provider, classifier, credentials, clock, executor,
                        new VersionedCompositionStore(directory, clock));
        restarted.configure(policy, Optional.empty());
        return restarted;
    }

    private static long files(Path directory) throws IOException
    {
        try (var paths = Files.list(directory))
        {
            return paths.count();
        }
    }

    @Test
    public void testConfirmedAccountReadsStoredCompositionAfterRestartWithoutProviderCalls() throws IOException
    {
        var directory = temporary.newFolder().toPath().toRealPath();
        credentials.replaceSession(ACCOUNT, "synthetic-valid");
        var classifications = new AtomicInteger();
        InstrumentClassifier classifier = (account, instrument) -> {
            classifications.incrementAndGet();
            var id = instrument.identifiers().get("synthetic:id");
            var provenance = new InstrumentEvidence.Provenance(account, "synthetic", clock.instant());
            return Optional.of(new InstrumentEvidence(instrument.identifiers(),
                            new InstrumentEvidence.Assertion<>(new InstrumentEvidence.Identifier("synthetic:id", id),
                                            provenance),
                            Optional.of(new InstrumentEvidence.Assertion<>(InstrumentEvidence.AssetType.EQUITY,
                                            provenance)),
                            Optional.empty()));
        };

        var first = persistent(directory, classifier, PERMITTED);
        var initial = first.refresh(ACCOUNT, instrument("fund"), true);
        executor.drain();
        assertEquals(Disposition.COMPLETED, initial.join().disposition());
        assertEquals(1, calls.size());
        // Held fund and its constituent were classified once each.
        assertEquals(2, classifications.get());

        clock.now = clock.now.plus(Duration.ofHours(1));
        var second = persistent(directory, classifier, PERMITTED);
        var read = second.read(ACCOUNT, instrument("fund"));
        assertTrue(read.usableComposition().isPresent());
        assertEquals(1, read.resolutions().size());
        assertEquals(2, second.classifications().size());

        // An automatic pass after the restart neither requests nor classifies again.
        var automatic = second.refresh(ACCOUNT, instrument("fund"), false);
        executor.drain();
        assertEquals(Disposition.CADENCE, automatic.join().disposition());
        assertTrue(automatic.join().snapshot().usableComposition().isPresent());
        assertEquals(1, calls.size());
        assertEquals(2, classifications.get());

        // After the cadence interval the composition is requested again.
        clock.now = clock.now.plus(Duration.ofHours(24));
        var due = second.refresh(ACCOUNT, instrument("fund"), false);
        executor.drain();
        assertEquals(Disposition.COMPLETED, due.join().disposition());
        assertEquals(2, calls.size());
    }

    /**
     * Real wiring: the provider declares neither denominator nor exposure
     * basis, so its composition is unverified. It is still stored, used after
     * a restart without a request, and its unclassified listed constituent is
     * assumed to be a company.
     */
    @Test
    public void testUndeclaredProviderCompositionIsStoredAndUsedAfterRestart() throws IOException
    {
        var directory = temporary.newFolder().toPath().toRealPath();
        credentials.replaceSession(ACCOUNT, "synthetic-valid");
        unknownDenominator = true;
        listedChild = true;
        InstrumentClassifier classifier = (account, instrument) -> Optional.empty();

        var first = persistent(directory, classifier, PERMITTED);
        var initial = first.refresh(ACCOUNT, instrument("fund"), true);
        executor.drain();
        assertEquals(Disposition.COMPLETED, initial.join().disposition());
        assertTrue(initial.join().snapshot().unverifiedComposition().isPresent());
        assertEquals(1, compositionFiles(directory));
        assertEquals(1, calls.size());

        clock.now = clock.now.plus(Duration.ofHours(1));
        var second = persistent(directory, classifier, PERMITTED);
        var read = second.read(ACCOUNT, instrument("fund"));
        assertTrue(read.usableComposition().isEmpty());
        assertTrue(read.unverifiedComposition().isPresent());
        assertEquals(1, read.resolutions().size());
        assertEquals(InstrumentResolution.Reason.ASSUMED_COMPANY, read.resolutions().getFirst().reason());

        var automatic = second.refresh(ACCOUNT, instrument("fund"), false);
        executor.drain();
        assertEquals(Disposition.CADENCE, automatic.join().disposition());
        assertTrue(automatic.join().snapshot().unverifiedComposition().isPresent());
        assertEquals(1, calls.size());
    }

    private static long compositionFiles(Path directory) throws IOException
    {
        try (var paths = Files.list(directory))
        {
            return paths.filter(path -> path.toString().endsWith(".composition")).count();
        }
    }

    @Test
    public void testUnverifiedAccountStoresNothingAndWithdrawalRemovesCacheContents() throws IOException
    {
        var directory = temporary.newFolder().toPath().toRealPath();
        credentials.replaceSession(ACCOUNT, "synthetic-valid");
        InstrumentClassifier classifier = (account, instrument) -> Optional.empty();

        var unverified = persistent(directory, classifier, POLICY);
        var result = unverified.refresh(ACCOUNT, instrument("fund"), true);
        executor.drain();
        result.join();
        assertEquals(0, files(directory));

        var confirmed = persistent(directory, classifier, PERMITTED);
        clock.now = clock.now.plus(Duration.ofDays(2));
        var stored = confirmed.refresh(ACCOUNT, instrument("fund"), true);
        executor.drain();
        stored.join();
        assertTrue(files(directory) > 0);

        confirmed.configure(POLICY, Optional.empty());
        assertEquals(0, files(directory));
        // Analysis continues from memory for this session.
        assertTrue(confirmed.read(ACCOUNT, instrument("fund")).usableComposition().isPresent());
        assertEquals(0, files(directory));

        var restarted = persistent(directory, classifier, PERMITTED);
        assertTrue(restarted.read(ACCOUNT, instrument("fund")).usableComposition().isEmpty());
    }

    @Test
    public void testStoredAnswerWithoutCompositionNeedsNoRequestAfterRestart() throws IOException
    {
        var directory = temporary.newFolder().toPath().toRealPath();
        credentials.replaceSession(ACCOUNT, "synthetic-valid");
        InstrumentClassifier classifier = (account, instrument) -> Optional.empty();

        var first = persistent(directory, classifier, PERMITTED);
        var initial = first.refresh(ACCOUNT, instrument("missing"), false);
        executor.drain();
        assertEquals(Disposition.COMPLETED, initial.join().disposition());
        assertEquals(1, calls.size());

        clock.now = clock.now.plus(Duration.ofHours(1));
        var second = persistent(directory, classifier, PERMITTED);
        assertEquals(CompositionResult.Status.UNAVAILABLE,
                        second.read(ACCOUNT, instrument("missing")).latestAttempt().orElseThrow().status());
        // An automatic pass after the restart makes no provider request.
        var automatic = second.refresh(ACCOUNT, instrument("missing"), false);
        executor.drain();
        assertEquals(Disposition.NO_COMPOSITION, automatic.join().disposition());
        assertEquals(CompositionResult.Status.UNAVAILABLE,
                        automatic.join().snapshot().latestAttempt().orElseThrow().status());
        assertEquals(1, calls.size());

        // After the cadence interval the instrument is asked once again.
        clock.now = clock.now.plus(Duration.ofHours(24));
        var third = persistent(directory, classifier, PERMITTED);
        var due = third.refresh(ACCOUNT, instrument("missing"), false);
        executor.drain();
        assertEquals(Disposition.COMPLETED, due.join().disposition());
        assertEquals(2, calls.size());

        // Withdrawal removes the stored answer; the next restart asks again.
        third.configure(POLICY, Optional.empty());
        assertEquals(0, files(directory));
        var fourth = persistent(directory, classifier, PERMITTED);
        assertTrue(fourth.read(ACCOUNT, instrument("missing")).latestAttempt().isEmpty());
        var again = fourth.refresh(ACCOUNT, instrument("missing"), false);
        executor.drain();
        assertEquals(Disposition.COMPLETED, again.join().disposition());
        assertEquals(3, calls.size());
    }

    @Test
    public void testEnrichmentRunsAfterResultsOnlyWithKeyAndExposesAttributes()
    {
        var requested = new ArrayList<String>();
        var cache = new CompanyEnrichmentCache(null, clock);
        cache.configure(POLICY, Optional.empty());
        session.enableEnrichment(ACCOUNT, (account, isin) -> {
            requested.add(isin);
            return Optional.of(new CompanyProfile(isin, Optional.of("US"), Optional.of("USD"),
                            Optional.of("Invented sector"), Optional.empty(), Optional.empty(), clock.instant()));
        }, isins -> {
            throw new AssertionError("register switched off");
        }, () -> false, cache, Runnable::run);
        var identity = new InstrumentEvidence.Identifier(InstrumentEvidence.ISIN, "XX0000000017");
        var companies = List.of(new LookThroughResult.Company(identity, java.math.BigDecimal.TEN,
                        java.math.BigDecimal.ZERO, List.of()));

        // Without a key nothing is requested, and the company is not left pending.
        session.enrich(companies);
        assertTrue(requested.isEmpty());
        assertEquals(0, session.companyAttributes().pending());

        configure();
        session.enrich(companies);
        assertEquals(List.of("XX0000000017"), requested);
        var attributes = session.companyAttributes();
        assertEquals(0, attributes.pending());
        assertEquals("Invented sector", attributes.attributes().get(identity).sector().orElseThrow().value());

        // A fresh cached profile is not requested again and no composition request was made.
        session.enrich(companies);
        assertEquals(1, requested.size());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void testConstituentProfileIsSharedWithEnrichmentAcrossOverlappingFunds()
    {
        var account = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "test");
        var policy = new CompositionPersistencePolicy(account, CompositionPersistencePolicy.Permission.UNVERIFIED,
                        Optional.empty());
        var constituent = new CompositionInstrument("Invented company", Map.of("ISIN", "XX0000000017"));
        var funds = new FundCompositionProvider()
        {
            @Override
            public String getId()
            {
                return EulerpoolFundCompositionProvider.ID;
            }

            @Override
            public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount requested)
            {
                return policy;
            }

            @Override
            public CompositionResult getComposition(ProviderAccount requested, CompositionInstrument fund)
            {
                calls.add(fund);
                var composition = new FundComposition(new CompositionProvenance(requested, fund, clock.instant(),
                                Optional.empty(), Optional.empty()), FundComposition.Denominator.TOTAL_FUND_NAV,
                                FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                                List.of(new FundComposition.Constituent(constituent, Optional.of("0.8"))));
                return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(composition),
                                Optional.empty());
            }
        };
        var paths = new ArrayList<String>();
        var classifier = new EulerpoolFundCompositionProvider(requested -> Optional.of("synthetic-token"),
                        (uri, authorization) -> {
                            synchronized (paths)
                            {
                                paths.add(uri.getPath());
                            }
                            if (uri.getPath().equals("/api/1/equity/profile/XX0000000017"))
                                return new EulerpoolFundCompositionProvider.Response(200,
                                                "{\"isin\":\"XX0000000017\",\"sector\":\"Invented sector\"}",
                                                Optional.empty());
                            return new EulerpoolFundCompositionProvider.Response(404, "", Optional.empty());
                        }, clock);
        var shared = new LookThroughSession(funds, classifier, credentials, clock, executor);
        shared.configure(policy, Optional.empty());
        var cache = new CompanyEnrichmentCache(null, clock);
        cache.configure(policy, Optional.empty());
        var profiles = new ArrayList<String>();
        shared.enableEnrichment(account, (requested, isin) -> {
            profiles.add(isin);
            return Optional.empty();
        }, isins -> Map.of(), () -> false, cache, Runnable::run);
        credentials.replaceSession(account, "synthetic-valid");

        // Two funds hold the same company; held funds without ISIN need no classification.
        for (var id : List.of("first", "second"))
        {
            var result = shared.refresh(account, instrument(id), true);
            executor.drain();
            assertEquals(Disposition.COMPLETED, result.join().disposition());
        }
        assertEquals(List.of("/api/1/equity/profile/XX0000000017"), paths);

        // Enrichment uses the profile that classification fetched.
        var identity = new InstrumentEvidence.Identifier(InstrumentEvidence.ISIN, "XX0000000017");
        shared.enrich(List.of(new LookThroughResult.Company(identity, java.math.BigDecimal.TEN,
                        java.math.BigDecimal.ZERO, List.of())));
        assertTrue(profiles.isEmpty());
        assertEquals("Invented sector",
                        shared.companyAttributes().attributes().get(identity).sector().orElseThrow().value());
    }
}
