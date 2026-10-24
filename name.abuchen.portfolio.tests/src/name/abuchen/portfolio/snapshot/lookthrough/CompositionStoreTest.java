package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore.CacheStatus;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore.State;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;

@SuppressWarnings("nls")
public class CompositionStoreTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "local-test");
    private static final CompositionInstrument FUND = instrument("fund");
    private static final CompositionInstrument EQUITY = instrument("equity");

    private static final class MutableClock extends Clock
    {
        private Instant now = START;

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

        @Override
        public Instant instant()
        {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private static CompositionInstrument instrument(String id)
    {
        return new CompositionInstrument("Synthetic " + id, Map.of("synthetic:id", id));
    }

    private CompositionPersistencePolicy policy(ProviderAccount account, Permission permission, Duration age)
    {
        return new CompositionPersistencePolicy(account, permission, Optional.ofNullable(age));
    }

    private CompositionPersistencePolicy permitted()
    {
        return policy(ACCOUNT, Permission.PERMITTED, Duration.ofDays(2));
    }

    private FundComposition composition(ProviderAccount account, CompositionInstrument fund, String weight)
    {
        return new FundComposition(new CompositionProvenance(account, fund, START,
                        Optional.of(START.minusSeconds(600)), Optional.empty()), Denominator.TOTAL_FUND_NAV,
                        ExposureBasis.PHYSICAL_HOLDINGS, Completeness.PARTIAL,
                        List.of(new Constituent(EQUITY, Optional.of(weight))));
    }

    private CompositionResult received(FundComposition source)
    {
        return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(source), Optional.empty());
    }

    private CompositionResult failed()
    {
        return new CompositionResult(CompositionResult.Status.FAILED, Optional.empty(), Optional.empty());
    }

    private CompositionStore.Snapshot put(CompositionStore store, FundComposition source)
    {
        var ticket = store.beginRefresh(source.provenance().source(), source.provenance().fund());
        return store.complete(ticket, received(source));
    }

    private Path directory() throws IOException
    {
        return temporary.newFolder().toPath();
    }

    private Path onlyRecord(Path directory) throws IOException
    {
        try (var files = Files.list(directory))
        {
            var records = files.filter(path -> path.toString().endsWith(".composition")).toList();
            assertEquals(1, records.size());
            return records.get(0);
        }
    }

    private long files(Path directory) throws IOException
    {
        try (var paths = Files.list(directory))
        {
            return paths.count();
        }
    }

    private InstrumentResolution resolution(CompositionInstrument instrument, AssetType type, String company)
    {
        var provenance = new Provenance(ACCOUNT, "synthetic-evidence", START);
        var evidence = new InstrumentEvidence(instrument.identifiers(),
                        new Assertion<>(new Identifier("synthetic:id", instrument.identifiers().get("synthetic:id")),
                                        provenance),
                        Optional.of(new Assertion<>(type, provenance)),
                        Optional.ofNullable(company).map(value -> new Assertion<>(new Identifier("company", value),
                                        provenance)));
        return new InstrumentResolver().resolve(instrument, List.of(evidence), ignored -> {
            throw new AssertionError();
        }, () -> false);
    }

    private LookThroughResult calculate(FundComposition source)
    {
        var fund = resolution(FUND, AssetType.FUND, null);
        var equity = resolution(EQUITY, AssetType.EQUITY, "synthetic-company");
        var security = new Security("Synthetic owned fund", "EUR");
        var valuation = new SecuritiesValuation(LocalDate.of(2026, 1, 1),
                        List.of(new SecuritiesValuation.Position(security, 1000000, Money.of("EUR", 10000))), List.of(),
                        Money.of("EUR", 10000));
        return new LookThroughEngine().calculate(valuation, Map.of(security, fund), Map.of(FUND, fund, EQUITY, equity),
                        Map.of(fund.instrument().orElseThrow(), source));
    }

    @Test
    public void syntheticProviderProducesIdenticalValidationAndEngineResultsInBothModes() throws IOException
    {
        FundCompositionProvider provider = new FundCompositionProvider()
        {
            @Override
            public String getId()
            {
                return ACCOUNT.providerId();
            }

            @Override
            public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount account)
            {
                return policy(account, Permission.PERMITTED, Duration.ofDays(2));
            }

            @Override
            public CompositionResult getComposition(ProviderAccount account, CompositionInstrument fund)
            {
                return received(composition(account, fund, "0.50000000001"));
            }
        };
        var path = directory();
        try (CompositionStore session = new SessionCompositionStore(clock);
                        CompositionStore disk = new VersionedCompositionStore(path, clock))
        {
            for (var store : List.of(session, disk))
            {
                store.configure(provider.getPersistencePolicy(ACCOUNT), Optional.empty());
                store.complete(store.beginRefresh(ACCOUNT, FUND), provider.getComposition(ACCOUNT, FUND));
            }
            var memory = session.read(ACCOUNT, FUND).usableComposition().orElseThrow();
            disk.close();
            CompositionValidationResult persistent;
            try (var restarted = new VersionedCompositionStore(path, clock))
            {
                restarted.configure(permitted(), Optional.empty());
                persistent = restarted.read(ACCOUNT, FUND).usableComposition().orElseThrow();
            }
            assertEquals(memory.rows(), persistent.rows());
            assertEquals(memory.shortfall(), persistent.shortfall());
            var memoryResult = calculate(memory.source());
            var diskResult = calculate(persistent.source());
            for (var category : LookThroughContribution.Category.values())
                assertEquals(memoryResult.value(category), diskResult.value(category));
            assertEquals(memoryResult.companies().get(0).identity(), diskResult.companies().get(0).identity());
            assertEquals(memoryResult.companies().get(0).indirect(), diskResult.companies().get(0).indirect());
            assertEquals(State.SESSION_ONLY, session.read(ACCOUNT, FUND).state());
            assertEquals(State.CLOSED, disk.read(ACCOUNT, FUND).state());
        }
    }

    @Test
    public void restartOfflineLoadsOriginalProvenanceAndNoSessionDataSurvives() throws IOException
    {
        var path = directory();
        var source = composition(ACCOUNT, FUND, "0.8");
        try (var disk = new VersionedCompositionStore(path, clock))
        {
            disk.configure(permitted(), Optional.empty());
            put(disk, source);
        }
        clock.now = START.plusSeconds(3600);
        try (var disk = new VersionedCompositionStore(path, clock);
                        var session = new SessionCompositionStore(clock))
        {
            disk.configure(permitted(), Optional.empty());
            session.configure(permitted(), Optional.empty());
            assertEquals(source, disk.read(ACCOUNT, FUND).usableComposition().orElseThrow().source());
            assertTrue(session.read(ACCOUNT, FUND).usableComposition().isEmpty());
            assertTrue(disk.read(ACCOUNT, FUND).latestAttempt().isEmpty());
            assertTrue(source.provenance().holdingsEffectiveDate().isEmpty());
        }
    }

    @Test
    public void failedInvalidEmptyAndMismatchedRefreshesNeverReplaceGoodData() throws IOException
    {
        for (CompositionStore store : List.of(new SessionCompositionStore(clock),
                        new VersionedCompositionStore(directory(), clock)))
        {
            try (store)
            {
                store.configure(permitted(), Optional.empty());
                var good = composition(ACCOUNT, FUND, "0.8");
                put(store, good);
                var invalid = composition(ACCOUNT, FUND, "-0.1");
                var empty = new FundComposition(good.provenance(), good.denominator(), good.exposureBasis(),
                                good.completeness(), List.of());
                var wrong = composition(new ProviderAccount("other", "test"), FUND, "1");
                for (var attempt : List.of(failed(), received(invalid), received(empty), received(wrong),
                                received(composition(ACCOUNT, instrument("different"), "1"))))
                {
                    var result = store.complete(store.beginRefresh(ACCOUNT, FUND), attempt);
                    assertEquals(good, result.usableComposition().orElseThrow().source());
                    assertEquals(attempt.status() == CompositionResult.Status.FAILED ? attempt.status()
                                    : CompositionResult.Status.MALFORMED_RESPONSE, result.latestAttempt().orElseThrow());
                }
            }
        }
    }

    @Test
    public void validatedReplacementIsAtomicAndLeavesNoStagingFiles() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "0.5"));
            var file = onlyRecord(path);
            var old = Files.readAllBytes(file);
            put(store, composition(ACCOUNT, FUND, "0.8"));
            assertEquals(1, files(path));
            assertFalse(Arrays.equals(old, Files.readAllBytes(file)));
            assertEquals(composition(ACCOUNT, FUND, "0.8"), CompositionCacheCodec.decode(Files.readAllBytes(file)));
        }
    }

    @Test
    public void interruptedStagingIsRemovedWithoutReplacingLastCommittedSnapshot() throws IOException
    {
        var path = directory();
        var source = composition(ACCOUNT, FUND, "0.8");
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, source);
        }
        var committed = onlyRecord(path);
        var bytes = Files.readAllBytes(committed);
        var staging = path.resolve(committed.getFileName().toString().substring(0, 65)
                        + "00000000-0000-0000-0000-000000000000.pending");
        Files.write(staging, Arrays.copyOf(bytes, bytes.length / 2));
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            assertEquals(source, store.read(ACCOUNT, FUND).usableComposition().orElseThrow().source());
            assertArrayEquals(bytes, Files.readAllBytes(committed));
            assertFalse(Files.exists(staging));
            assertEquals(1, files(path));
        }
    }

    @Test
    public void portableStoreWorksWithoutSecureDirectoryStreamsOrPosixPermissions() throws IOException
    {
        var path = directory();
        var listed = new AtomicBoolean();
        var permissionsChecked = new AtomicBoolean();
        var portable = new VersionedCompositionStore.FileOperations()
        {
            @Override
            public DirectoryStream<Path> list(Path directory) throws IOException
            {
                listed.set(true);
                var delegate = Files.newDirectoryStream(directory);
                // Deliberately expose only the ordinary DirectoryStream contract.
                return new DirectoryStream<Path>()
                {
                    @Override
                    public Iterator<Path> iterator()
                    {
                        return delegate.iterator();
                    }

                    @Override
                    public void close() throws IOException
                    {
                        delegate.close();
                    }
                };
            }

            @Override
            public boolean supportsPosix(Path directory)
            {
                permissionsChecked.set(true);
                return false;
            }
        };
        var other = new ProviderAccount("synthetic-other", "local-test");
        var unrelated = path.resolve("unrelated.txt");
        Files.writeString(unrelated, "Synthetic unrelated file");
        var source = composition(ACCOUNT, FUND, "0.8");
        try (var store = new VersionedCompositionStore(path, clock, portable))
        {
            store.configure(permitted(), Optional.empty());
            store.configure(policy(other, Permission.PERMITTED, null), Optional.empty());
            assertEquals(State.PERSISTENT, put(store, source).state());
            put(store, composition(other, FUND, "0.5"));
            assertTrue(listed.get());
            assertTrue(permissionsChecked.get());
            assertEquals(3, files(path));
        }
        try (var restarted = new VersionedCompositionStore(path, clock, portable))
        {
            restarted.configure(permitted(), Optional.empty());
            restarted.configure(policy(other, Permission.PERMITTED, null), Optional.empty());
            assertEquals(source, restarted.read(ACCOUNT, FUND).usableComposition().orElseThrow().source());
            var pending = restarted.beginRefresh(ACCOUNT, FUND);
            restarted.configure(permitted(), Optional.of(false));
            restarted.complete(pending, received(composition(ACCOUNT, FUND, "0.9")));
            assertEquals(2, files(path));
            assertEquals(State.PERSISTENT, restarted.read(other, FUND).state());
            assertTrue(restarted.read(other, FUND).usableComposition().isPresent());
            assertEquals("Synthetic unrelated file", Files.readString(unrelated));
        }
    }

    @Test
    public void unsupportedAtomicMovePreservesOldRecordAndFallsBackToSession() throws IOException
    {
        var path = directory();
        var rejectMove = new AtomicBoolean();
        var operations = new VersionedCompositionStore.FileOperations()
        {
            @Override
            public void atomicReplace(Path staging, Path destination) throws IOException
            {
                if (rejectMove.get())
                    throw new AtomicMoveNotSupportedException(staging.toString(), destination.toString(),
                                    "Synthetic unsupported atomic move");
                VersionedCompositionStore.FileOperations.super.atomicReplace(staging, destination);
            }
        };
        var original = composition(ACCOUNT, FUND, "0.5");
        try (var store = new VersionedCompositionStore(path, clock, operations))
        {
            store.configure(permitted(), Optional.empty());
            put(store, original);
            var record = onlyRecord(path);
            var old = Files.readAllBytes(record);
            rejectMove.set(true);
            var latest = composition(ACCOUNT, FUND, "0.8");
            var result = put(store, latest);
            assertEquals(State.DISK_FAILURE, result.state());
            assertEquals(latest, result.usableComposition().orElseThrow().source());
            assertEquals(1, files(path));
            assertArrayEquals(old, Files.readAllBytes(record));
        }
        try (var restarted = new VersionedCompositionStore(path, clock))
        {
            restarted.configure(permitted(), Optional.empty());
            assertEquals(original, restarted.read(ACCOUNT, FUND).usableComposition().orElseThrow().source());
        }
    }

    @Test
    public void ownedLookingDirectoryIsNeverDeletedAsACacheFile() throws IOException
    {
        var path = directory();
        Path record;
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "0.8"));
            record = onlyRecord(path);
        }
        Files.delete(record);
        Files.createDirectory(record);
        var unrelated = record.resolve("unrelated.txt");
        Files.writeString(unrelated, "Synthetic directory contents");
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(policy(ACCOUNT, Permission.PROHIBITED, null), Optional.empty());
            assertEquals(State.DISK_FAILURE, store.read(ACCOUNT, FUND).state());
            assertEquals("Synthetic directory contents", Files.readString(unrelated));
        }
    }

    @Test
    public void failedDiskReplacementKeepsSessionResultAndPriorFile() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "0.5"));
            var file = onlyRecord(path);
            var old = Files.readAllBytes(file);
            // An unavailable cache root must not destroy the last committed file.
            var retained = path.resolveSibling(path.getFileName() + "-retained");
            Files.move(path, retained);
            var result = put(store, composition(ACCOUNT, FUND, "0.8"));
            assertEquals(State.DISK_FAILURE, result.state());
            assertEquals("0.8", result.usableComposition().orElseThrow().source().constituents().get(0).weight().orElseThrow());
            assertEquals(1, files(retained));
            assertArrayEquals(old, Files.readAllBytes(retained.resolve(file.getFileName())));
        }
    }

    @Test
    public void retentionExpiresAtOriginalRetrievalBoundaryEvenOnFailedRefresh() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "1"));
            clock.now = START.plus(Duration.ofDays(2)).minusNanos(1);
            assertTrue(store.read(ACCOUNT, FUND).usableComposition().isPresent());
            clock.now = clock.now.plusNanos(1);
            var result = store.complete(store.beginRefresh(ACCOUNT, FUND), failed());
            assertTrue(result.usableComposition().isEmpty());
            assertEquals(CacheStatus.EXPIRED, result.cacheStatus());
            assertEquals(0, files(path));
        }
    }

    @Test
    public void startupAndReducedRetentionDeleteExpiredEntriesAcrossAccount() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "1"));
            put(store, composition(ACCOUNT, instrument("second-fund"), "0.5"));
        }
        clock.now = START.plus(Duration.ofHours(12));
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(policy(ACCOUNT, Permission.PERMITTED, Duration.ofHours(12)), Optional.empty());
            assertEquals(0, files(path));
            assertTrue(store.read(ACCOUNT, FUND).usableComposition().isEmpty());
        }
    }

    @Test
    public void withdrawalAndReenableInvalidatePendingWritesButPreserveMemory() throws IOException
    {
        for (var permission : List.of(Permission.PROHIBITED, Permission.UNVERIFIED))
        {
            var path = directory();
            try (var store = new VersionedCompositionStore(path, clock))
            {
                store.configure(permitted(), Optional.empty());
                put(store, composition(ACCOUNT, FUND, "0.5"));
                var pending = store.beginRefresh(ACCOUNT, FUND);
                store.configure(policy(ACCOUNT, permission, null), Optional.empty());
                assertEquals(0, files(path));
                assertEquals(State.SESSION_ONLY, store.read(ACCOUNT, FUND).state());
                store.configure(permitted(), Optional.empty());
                var result = store.complete(pending, received(composition(ACCOUNT, FUND, "0.8")));
                assertTrue(result.usableComposition().isPresent());
                assertEquals(0, files(path));
                put(store, composition(ACCOUNT, FUND, "0.9"));
                assertEquals(1, files(path));
            }
        }
    }

    @Test
    public void optOutAndExplicitDeletionInvalidatePendingWrites() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "0.5"));
            var pending = store.beginRefresh(ACCOUNT, FUND);
            store.configure(permitted(), Optional.of(false));
            assertEquals(State.SESSION_ONLY, store.complete(pending, received(composition(ACCOUNT, FUND, "0.8"))).state());
            assertEquals(0, files(path));
            store.configure(permitted(), Optional.of(true));
            pending = store.beginRefresh(ACCOUNT, FUND);
            store.deleteRetained(ACCOUNT);
            store.complete(pending, received(composition(ACCOUNT, FUND, "0.9")));
            assertEquals(0, files(path));
            assertTrue(store.read(ACCOUNT, FUND).usableComposition().isPresent());
        }
    }

    @Test
    public void unknownPermissionNeverWritesEvenWithEnabledPreference() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(policy(ACCOUNT, Permission.UNVERIFIED, null), Optional.of(true));
            assertEquals(State.SESSION_ONLY, put(store, composition(ACCOUNT, FUND, "0.8")).state());
            assertEquals(0, files(path));
        }
    }

    @Test
    public void accountProviderAndFundIsolationUseHashedPaths() throws IOException
    {
        var path = directory();
        var otherAccount = new ProviderAccount(ACCOUNT.providerId(), "../../opaque/other");
        var otherProvider = new ProviderAccount("other-provider", ACCOUNT.accountId());
        try (var store = new VersionedCompositionStore(path, clock))
        {
            for (var account : List.of(ACCOUNT, otherAccount, otherProvider))
            {
                store.configure(policy(account, Permission.PERMITTED, null), Optional.empty());
                put(store, composition(account, FUND, "0.8"));
            }
            var unrelated = path.resolve("unrelated.txt");
            Files.writeString(unrelated, "Synthetic unrelated data");
            store.configure(policy(ACCOUNT, Permission.PROHIBITED, null), Optional.empty());
            assertEquals(3, files(path));
            assertEquals(State.PERSISTENT, store.read(otherAccount, FUND).state());
            assertEquals(State.PERSISTENT, store.read(otherProvider, FUND).state());
            assertTrue(store.read(otherAccount, instrument("different")).usableComposition().isEmpty());
            assertEquals("Synthetic unrelated data", Files.readString(unrelated));
            try (var paths = Files.list(path))
            {
                assertTrue(paths.noneMatch(file -> file.getFileName().toString().contains("opaque")));
            }
        }
    }

    @Test
    public void corruptChecksumSchemaAndValidationVersionsAreDiscardedOnRestart() throws IOException
    {
        for (int changedByte : List.of(0, 4, 12))
        {
            var path = directory();
            try (var store = new VersionedCompositionStore(path, clock))
            {
                store.configure(permitted(), Optional.empty());
                put(store, composition(ACCOUNT, FUND, "0.8"));
            }
            var record = onlyRecord(path);
            var bytes = Files.readAllBytes(record);
            bytes[changedByte] ^= 1;
            if (changedByte < 8)
            {
                var digest = CompositionCacheCodec.digest(Arrays.copyOf(bytes, bytes.length - 32));
                System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
            }
            Files.write(record, bytes);
            try (var store = new VersionedCompositionStore(path, clock))
            {
                store.configure(permitted(), Optional.empty());
                var result = store.read(ACCOUNT, FUND);
                assertTrue(result.usableComposition().isEmpty());
                assertEquals(CacheStatus.DISCARDED, result.cacheStatus());
                assertEquals(0, files(path));
            }
        }
    }

    @Test
    public void wrongIdentityAndInvalidDataInOtherwiseValidEnvelopeAreDiscarded() throws IOException
    {
        var path = directory();
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "0.8"));
        }
        var record = onlyRecord(path);
        for (var bad : List.of(composition(new ProviderAccount("wrong", "test"), FUND, "1"),
                        composition(ACCOUNT, instrument("wrong-fund"), "1"), composition(ACCOUNT, FUND, "1.01")))
        {
            Files.write(record, CompositionCacheCodec.encode(bad));
            try (var store = new VersionedCompositionStore(path, clock))
            {
                store.configure(permitted(), Optional.empty());
                assertTrue(store.read(ACCOUNT, FUND).usableComposition().isEmpty());
                assertEquals(0, files(path));
            }
        }
    }

    @Test
    public void futureRetrievalAndExtremeWeightsAreRejectedEqually() throws IOException
    {
        for (CompositionStore store : List.of(new SessionCompositionStore(clock),
                        new VersionedCompositionStore(directory(), clock)))
        {
            try (store)
            {
                store.configure(permitted(), Optional.empty());
                for (var weight : List.of("1e-2147483647", "NaN", "2", "-1", "0." + "1".repeat(4096)))
                    assertTrue(put(store, composition(ACCOUNT, FUND, weight)).usableComposition().isEmpty());
                clock.now = START.minusNanos(1);
                assertTrue(put(store, composition(ACCOUNT, FUND, "1")).usableComposition().isEmpty());
                clock.now = START;
            }
        }
    }

    @Test
    public void symlinkDirectoryAndRecordNeverReadOrModifyTargets() throws IOException
    {
        var outside = directory();
        var link = temporary.getRoot().toPath().resolve("cache-link");
        Files.createSymbolicLink(link, outside);
        try (var store = new VersionedCompositionStore(link, clock))
        {
            store.configure(permitted(), Optional.empty());
            assertEquals(State.DISK_FAILURE, put(store, composition(ACCOUNT, FUND, "1")).state());
            assertEquals(0, files(outside));
        }
        var path = directory();
        Path record;
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            put(store, composition(ACCOUNT, FUND, "1"));
            record = onlyRecord(path);
        }
        var target = outside.resolve("unrelated-private-file");
        Files.writeString(target, "Synthetic target must stay untouched");
        Files.delete(record);
        Files.createSymbolicLink(record, target);
        try (var store = new VersionedCompositionStore(path, clock))
        {
            store.configure(permitted(), Optional.empty());
            assertTrue(store.read(ACCOUNT, FUND).usableComposition().isEmpty());
            assertEquals("Synthetic target must stay untouched", Files.readString(target));
            assertFalse(Files.exists(record));
        }
    }

    @Test
    public void normalizedTraversalIsRejectedAndConstructorDoesNotCreateDirectory() throws IOException
    {
        var path = temporary.getRoot().toPath().resolve("missing");
        try (var ignored = new VersionedCompositionStore(path, clock))
        {
            assertFalse(Files.exists(path));
        }
        assertThrows(IllegalArgumentException.class,
                        () -> new VersionedCompositionStore(path.resolve("..").resolve("other"), clock));
    }

    @Test
    public void latestRefreshWinsAndClosedOrReusedTicketsCannotWrite() throws IOException
    {
        var path = directory();
        var store = new VersionedCompositionStore(path, clock);
        store.configure(permitted(), Optional.empty());
        var earlier = store.beginRefresh(ACCOUNT, FUND);
        var later = store.beginRefresh(ACCOUNT, FUND);
        store.complete(later, received(composition(ACCOUNT, FUND, "0.8")));
        store.complete(earlier, received(composition(ACCOUNT, FUND, "0.5")));
        assertEquals(composition(ACCOUNT, FUND, "0.8"), store.read(ACCOUNT, FUND).usableComposition().orElseThrow().source());
        assertThrows(IllegalArgumentException.class, () -> store.complete(later, failed()));
        var pending = store.beginRefresh(ACCOUNT, FUND);
        var original = Files.readAllBytes(onlyRecord(path));
        store.close();
        assertEquals(State.CLOSED, store.complete(pending, received(composition(ACCOUNT, FUND, "1"))).state());
        assertTrue(store.read(ACCOUNT, FUND).usableComposition().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(onlyRecord(path)));
    }

    @Test
    public void boundedCodecRejectsOversizedCountsAndTruncation() throws IOException
    {
        var source = composition(ACCOUNT, FUND, "1");
        var bytes = CompositionCacheCodec.encode(source);
        assertEquals(source, CompositionCacheCodec.decode(bytes));
        assertThrows(IOException.class, () -> CompositionCacheCodec.decode(Arrays.copyOf(bytes, 20)));
        // First string's byte length follows schema and validation versions.
        ByteBuffer.wrap(bytes).putInt(8, Integer.MAX_VALUE);
        var digest = CompositionCacheCodec.digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
        assertThrows(IOException.class, () -> CompositionCacheCodec.decode(bytes));
    }

    @Test
    public void duplicateRowsDecimalTextAndAllProvenanceDatesRoundTripWithoutNormalization() throws IOException
    {
        var row = new Constituent(EQUITY, Optional.of("0.250000000000000001"));
        var source = new FundComposition(new CompositionProvenance(ACCOUNT, FUND, START,
                        Optional.of(START.minusSeconds(600)), Optional.of(LocalDate.of(2025, 12, 20))),
                        Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, Completeness.UNKNOWN,
                        List.of(row, row));
        var restored = CompositionCacheCodec.decode(CompositionCacheCodec.encode(source));
        assertEquals(source, restored);
        var validation = CompositionCacheCodec.validate(restored).orElseThrow();
        assertEquals(2, validation.rows().size());
        assertEquals(new java.math.BigDecimal("0.499999999999999998"), validation.shortfall().orElseThrow());
    }

    @Test
    public void sessionCloseDiscardsMemoryAndTicketsBelongToExactlyOneStore()
    {
        try (var first = new SessionCompositionStore(clock); var second = new SessionCompositionStore(clock))
        {
            first.configure(permitted(), Optional.empty());
            second.configure(permitted(), Optional.empty());
            put(first, composition(ACCOUNT, FUND, "0.8"));
            var ticket = first.beginRefresh(ACCOUNT, FUND);
            assertThrows(IllegalArgumentException.class, () -> second.complete(ticket, failed()));
            first.close();
            assertTrue(first.complete(ticket, failed()).usableComposition().isEmpty());
            assertTrue(first.read(ACCOUNT, FUND).usableComposition().isEmpty());
            assertTrue(second.read(ACCOUNT, FUND).usableComposition().isEmpty());
        }
    }

    @Test
    public void withdrawalSynchronizesWithCommitAndThenDeletesItsPublishedData() throws Exception
    {
        var committing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var diskPresent = new AtomicBoolean();
        // Synthetic disk hooks isolate the shared commit/policy synchronization.
        try (var store = new SessionCompositionStore(clock)
        {
            @Override
            protected boolean supportsPersistence()
            {
                return true;
            }

            @Override
            protected void writeDisk(FundComposition source, CompositionPersistencePolicy policy) throws IOException
            {
                committing.countDown();
                try
                {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IOException();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException();
                }
                diskPresent.set(true);
            }

            @Override
            protected void deleteDisk(ProviderAccount account)
            {
                diskPresent.set(false);
            }
        }; var executor = Executors.newFixedThreadPool(2))
        {
            store.configure(permitted(), Optional.empty());
            var ticket = store.beginRefresh(ACCOUNT, FUND);
            var write = executor.submit(() -> store.complete(ticket, received(composition(ACCOUNT, FUND, "0.8"))));
            try
            {
                assertTrue(committing.await(5, TimeUnit.SECONDS));
                var withdraw = executor.submit(() -> store.configure(policy(ACCOUNT, Permission.PROHIBITED, null),
                                Optional.empty()));
                release.countDown();
                write.get(5, TimeUnit.SECONDS);
                withdraw.get(5, TimeUnit.SECONDS);
                assertFalse(diskPresent.get());
                assertEquals(State.SESSION_ONLY, store.read(ACCOUNT, FUND).state());
                assertTrue(store.read(ACCOUNT, FUND).usableComposition().isPresent());
            }
            finally
            {
                release.countDown();
            }
        }
    }
}
