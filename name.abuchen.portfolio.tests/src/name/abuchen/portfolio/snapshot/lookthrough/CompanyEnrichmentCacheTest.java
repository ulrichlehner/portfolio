package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;

/** Identifiers and values are invented. */
@SuppressWarnings("nls")
public class CompanyEnrichmentCacheTest
{
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "local");
    private static final String ISIN = "KY0000000001";
    private static final String OTHER = "XX0000000002";

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
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final MutableClock clock = new MutableClock();

    private static CompositionPersistencePolicy policy(Permission permission, Duration age)
    {
        return new CompositionPersistencePolicy(ACCOUNT, permission, Optional.ofNullable(age));
    }

    private CompanyProfile profile(String isin)
    {
        return new CompanyProfile(isin, Optional.of("US"), Optional.of("USD"), Optional.of("Invented sector"),
                        Optional.empty(), Optional.of(new BigDecimal("12.25")), clock.instant());
    }

    private RegisterRecord register(String isin)
    {
        return new RegisterRecord(isin, "SYNTHETICLEI00000001", Optional.of("HK"), Optional.of("KY"),
                        clock.instant());
    }

    private Path directory() throws IOException
    {
        return temporary.newFolder().toPath().toRealPath();
    }

    private static long files(Path directory, String prefix) throws IOException
    {
        try (var paths = Files.list(directory))
        {
            return paths.filter(path -> path.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    public void testRegisterAndPermittedProfilesRoundTripIncludingNotFound() throws IOException
    {
        var path = directory();
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy(Permission.PERMITTED, null), Optional.empty());
        cache.putRegister(ISIN, Optional.of(register(ISIN)));
        cache.putRegister(OTHER, Optional.empty());
        cache.putProfile(ACCOUNT, ISIN, Optional.of(profile(ISIN)), cache.generation(ACCOUNT));
        cache.putProfile(ACCOUNT, OTHER, Optional.empty(), cache.generation(ACCOUNT));
        cache.flush();
        assertEquals(1, files(path, "register"));
        assertEquals(1, files(path, "profiles-"));

        clock.now = START.plusSeconds(3600);
        var restarted = new CompanyEnrichmentCache(path, clock);
        restarted.configure(policy(Permission.PERMITTED, null), Optional.empty());
        assertEquals(Optional.of(new RegisterRecord(ISIN, "SYNTHETICLEI00000001", Optional.of("HK"),
                        Optional.of("KY"), START)), restarted.register(ISIN).orElseThrow().value());
        assertEquals(START, restarted.register(OTHER).orElseThrow().retrievedAt());
        assertTrue(restarted.register(OTHER).orElseThrow().value().isEmpty());
        var stored = restarted.profile(ACCOUNT, ISIN).orElseThrow().value().orElseThrow();
        assertEquals(Optional.of("US"), stored.country());
        assertEquals(Optional.of(new BigDecimal("12.25")), stored.marketCapMillions());
        assertTrue(stored.industry().isEmpty());
        assertTrue(restarted.profile(ACCOUNT, OTHER).orElseThrow().value().isEmpty());
    }

    @Test
    public void testUnverifiedProfilesStayInMemoryWhileRegisterIsStored() throws IOException
    {
        var path = directory();
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy(Permission.UNVERIFIED, null), Optional.of(true));
        cache.putRegister(ISIN, Optional.of(register(ISIN)));
        cache.putProfile(ACCOUNT, ISIN, Optional.of(profile(ISIN)), cache.generation(ACCOUNT));
        cache.flush();
        assertTrue(cache.profile(ACCOUNT, ISIN).isPresent());
        assertEquals(1, files(path, "register"));
        assertEquals(0, files(path, "profiles-"));

        var restarted = new CompanyEnrichmentCache(path, clock);
        restarted.configure(policy(Permission.UNVERIFIED, null), Optional.of(true));
        assertTrue(restarted.register(ISIN).isPresent());
        assertTrue(restarted.profile(ACCOUNT, ISIN).isEmpty());
    }

    @Test
    public void testWithdrawalDeletesProfilesKeepsRegisterAndStaleRequestsCannotStore() throws IOException
    {
        var path = directory();
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy(Permission.PERMITTED, null), Optional.empty());
        cache.putRegister(ISIN, Optional.of(register(ISIN)));
        cache.putProfile(ACCOUNT, ISIN, Optional.of(profile(ISIN)), cache.generation(ACCOUNT));
        cache.flush();
        assertEquals(1, files(path, "profiles-"));

        // A request started before the withdrawal answers afterwards.
        var stale = cache.generation(ACCOUNT);
        cache.configure(policy(Permission.PERMITTED, null), Optional.of(false));
        assertEquals(0, files(path, "profiles-"));
        assertEquals(1, files(path, "register"));
        // The session keeps its profiles in memory.
        assertTrue(cache.profile(ACCOUNT, ISIN).isPresent());

        cache.configure(policy(Permission.PERMITTED, null), Optional.empty());
        cache.putProfile(ACCOUNT, OTHER, Optional.of(profile(OTHER)), stale);
        cache.flush();
        // Neither the late answer nor the session-only profile reach the disk.
        var restarted = new CompanyEnrichmentCache(path, clock);
        restarted.configure(policy(Permission.PERMITTED, null), Optional.empty());
        assertTrue(restarted.profile(ACCOUNT, ISIN).isEmpty());
        assertTrue(restarted.profile(ACCOUNT, OTHER).isEmpty());
        assertTrue(restarted.register(ISIN).isPresent());

        cache.putProfile(ACCOUNT, OTHER, Optional.of(profile(OTHER)), cache.generation(ACCOUNT));
        cache.flush();
        var again = new CompanyEnrichmentCache(path, clock);
        again.configure(policy(Permission.PERMITTED, null), Optional.empty());
        assertTrue(again.profile(ACCOUNT, OTHER).isPresent());
        assertFalse(again.profile(ACCOUNT, ISIN).isPresent());
    }

    @Test
    public void testMaximumAgeExpiresProfilesAndCorruptFilesAreDropped() throws IOException
    {
        var path = directory();
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy(Permission.PERMITTED, Duration.ofDays(1)), Optional.empty());
        cache.putProfile(ACCOUNT, ISIN, Optional.of(profile(ISIN)), cache.generation(ACCOUNT));
        cache.putRegister(ISIN, Optional.of(register(ISIN)));
        cache.flush();
        clock.now = START.plus(Duration.ofDays(2));
        assertTrue(cache.profile(ACCOUNT, ISIN).isEmpty());

        try (var paths = Files.list(path))
        {
            var file = paths.filter(item -> item.getFileName().toString().startsWith("register")).findFirst()
                            .orElseThrow();
            var bytes = Files.readAllBytes(file);
            bytes[8] ^= 1;
            Files.write(file, bytes);
        }
        var restarted = new CompanyEnrichmentCache(path, clock);
        assertTrue(restarted.register(ISIN).isEmpty());
        assertEquals(0, files(path, "register"));
    }

    @Test
    public void testWithoutDirectoryEverythingStaysInMemory()
    {
        var cache = new CompanyEnrichmentCache(null, clock);
        cache.configure(policy(Permission.PERMITTED, null), Optional.empty());
        cache.putRegister(ISIN, Optional.of(register(ISIN)));
        cache.putProfile(ACCOUNT, ISIN, Optional.of(profile(ISIN)), cache.generation(ACCOUNT));
        cache.flush();
        assertTrue(cache.register(ISIN).isPresent());
        assertTrue(cache.profile(ACCOUNT, ISIN).isPresent());
    }

    private RevenueSplit revenue(String isin)
    {
        return RevenueSplit.of(isin, java.time.LocalDate.parse("2025-06-30"),
                        java.util.List.of(new RevenueSplit.Entry("United States", "country_US", new BigDecimal("54")),
                                        new RevenueSplit.Entry("Europe", "srt_EuropeMember", new BigDecimal("36"))),
                        clock.instant()).orElseThrow();
    }

    @Test
    public void testRevenueRoundTripWithdrawalAndSessionOnly() throws IOException
    {
        var path = directory();
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy(Permission.PERMITTED, null), Optional.empty());
        cache.putRevenue(ACCOUNT, ISIN, Optional.of(revenue(ISIN)), cache.generation(ACCOUNT));
        cache.putRevenue(ACCOUNT, OTHER, Optional.empty(), cache.generation(ACCOUNT));
        cache.flush();
        assertEquals(1, files(path, "revenue-"));

        var restarted = new CompanyEnrichmentCache(path, clock);
        restarted.configure(policy(Permission.PERMITTED, null), Optional.empty());
        assertEquals(Optional.of(revenue(ISIN)), restarted.revenue(ACCOUNT, ISIN).orElseThrow().value());
        assertTrue(restarted.revenue(ACCOUNT, OTHER).orElseThrow().value().isEmpty());
        assertTrue(restarted.profile(ACCOUNT, ISIN).isEmpty());

        // Withdrawing storage deletes the file; a late answer and the session copy stay in memory.
        var stale = cache.generation(ACCOUNT);
        cache.configure(policy(Permission.PERMITTED, null), Optional.of(false));
        assertEquals(0, files(path, "revenue-"));
        assertTrue(cache.revenue(ACCOUNT, ISIN).isPresent());
        cache.configure(policy(Permission.PERMITTED, null), Optional.empty());
        cache.putRevenue(ACCOUNT, "XX0000000099", Optional.empty(), stale);
        cache.flush();
        var again = new CompanyEnrichmentCache(path, clock);
        again.configure(policy(Permission.PERMITTED, null), Optional.empty());
        assertTrue(again.revenue(ACCOUNT, ISIN).isEmpty());
        assertTrue(again.revenue(ACCOUNT, "XX0000000099").isEmpty());

        // Unverified storage without preference keeps splits for the session only.
        var memory = new CompanyEnrichmentCache(path, clock);
        memory.configure(policy(Permission.UNVERIFIED, null), Optional.empty());
        memory.putRevenue(ACCOUNT, ISIN, Optional.of(revenue(ISIN)), memory.generation(ACCOUNT));
        memory.flush();
        assertEquals(0, files(path, "revenue-"));
        assertTrue(memory.revenue(ACCOUNT, ISIN).isPresent());
    }

    @Test
    public void testRevenueFollowsMaximumAge() throws IOException
    {
        var cache = new CompanyEnrichmentCache(directory(), clock);
        cache.configure(policy(Permission.PERMITTED, Duration.ofDays(10)), Optional.empty());
        cache.putRevenue(ACCOUNT, ISIN, Optional.of(revenue(ISIN)), cache.generation(ACCOUNT));
        clock.now = START.plus(Duration.ofDays(11));
        assertTrue(cache.revenue(ACCOUNT, ISIN).isEmpty());
    }
}
