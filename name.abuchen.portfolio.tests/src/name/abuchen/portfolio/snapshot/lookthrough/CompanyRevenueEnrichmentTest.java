package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;

/** Identifiers and values are invented. */
@SuppressWarnings("nls")
public class CompanyRevenueEnrichmentTest
{
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "local");
    private static final String SMALL = "US0000000001";
    private static final String LARGE = "KY0000000002";
    private static final String MEDIUM = "XX0000000003";

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
    private final List<String> events = new ArrayList<>();
    private final Map<String, IOException> failures = new HashMap<>();

    private static Company company(String isin, String value)
    {
        return new Company(new Identifier(InstrumentEvidence.ISIN, isin), new BigDecimal(value), BigDecimal.ZERO,
                        List.of());
    }

    private static final List<Company> COMPANIES = List.of(company(SMALL, "10"), company(LARGE, "30"),
                    company(MEDIUM, "20"));

    private CompanyEnrichmentService service(CompanyEnrichmentCache cache, CompositionRequestBudget budget)
    {
        return new CompanyEnrichmentService(ACCOUNT, (account, isin) -> {
            events.add("profile " + isin);
            return Optional.empty();
        }, (account, isin) -> {
            events.add("revenue " + isin);
            var failure = failures.get(isin);
            if (failure != null)
                throw failure;
            if (isin.equals(MEDIUM))
                return Optional.empty();
            return RevenueSplit.of(isin, LocalDate.parse("2025-06-30"),
                            List.of(new RevenueSplit.Entry("United States", "country_US", new BigDecimal("60")),
                                            new RevenueSplit.Entry("Europe", "srt_EuropeMember", new BigDecimal("40"))),
                            clock.instant());
        }, () -> true, isins -> Map.of(), () -> false, cache, budget, clock, Runnable::run);
    }

    private CompositionRequestBudget budget(long capacity)
    {
        return new CompositionRequestBudget(ACCOUNT.providerId(), clock, capacity, Duration.ofHours(24),
                        Duration.ofMinutes(15));
    }

    private CompanyEnrichmentCache memory()
    {
        var cache = new CompanyEnrichmentCache(null, clock);
        cache.configure(new CompositionPersistencePolicy(ACCOUNT, Permission.UNVERIFIED, Optional.empty()),
                        Optional.empty());
        return cache;
    }

    @Test
    public void testRevenueFollowsProfilesInOrderOfValueWithinBudget()
    {
        var service = service(memory(), budget(5));
        service.schedule(COMPANIES);
        assertEquals(List.of("profile " + LARGE, "profile " + MEDIUM, "profile " + SMALL, "revenue " + LARGE,
                        "revenue " + MEDIUM), events);
        var snapshot = service.snapshot();
        var large = new Identifier(InstrumentEvidence.ISIN, LARGE);
        // The budget is back only tomorrow, so the rest is not pending.
        assertEquals(Set.of(), snapshot.revenueWaiting());
        assertEquals(Set.of(large), snapshot.revenue().keySet());
        assertEquals(0, new BigDecimal("60").compareTo(snapshot.revenue().get(large).percentOf(
                        new RevenueSplit.Member(RevenueSplit.Kind.COUNTRY, "US"))));

        // Continues with the rest when the budget is available again; "no data" is an answer.
        clock.now = clock.now.plus(Duration.ofHours(25));
        service.schedule(COMPANIES);
        assertEquals("revenue " + SMALL, events.get(events.size() - 1));
        assertTrue(service.snapshot().revenueWaiting().isEmpty());
        assertEquals(2, service.snapshot().revenue().size());
    }

    @Test
    public void testRevenueLeavesRoomForTheProfilesOfLaterChunks()
    {
        var many = new ArrayList<Company>();
        for (int index = 0; index < 150; index++)
            many.add(company(String.format("XX%010d", index), String.valueOf(1000 - index)));
        var service = service(memory(), budget(160));
        service.schedule(many);
        // Every profile is answered; revenue took only what the profiles did not need.
        assertEquals(150, events.stream().filter(event -> event.startsWith("profile")).count());
        assertEquals(10, events.stream().filter(event -> event.startsWith("revenue")).count());
        assertEquals(150, service.snapshot().progress().loaded());

        // Skipped revenue follows once the budget is back.
        clock.now = clock.now.plus(Duration.ofHours(25));
        service.schedule(many);
        assertEquals(150, events.stream().filter(event -> event.startsWith("revenue")).count());
        assertEquals(150, events.stream().filter(event -> event.startsWith("profile")).count());
    }

    @Test
    public void testRevenueIsCachedForNinetyDaysAndNotFoundToo()
    {
        var service = service(memory(), budget(1000));
        service.schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());

        clock.now = clock.now.plus(Duration.ofDays(89));
        service.schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());

        clock.now = clock.now.plus(Duration.ofDays(1));
        service.schedule(COMPANIES);
        assertEquals(6, events.stream().filter(event -> event.startsWith("revenue")).count());
    }

    @Test
    public void testAFailingCompanyIsSkippedAndRepeatedFailuresStopThePass()
    {
        failures.put(LARGE, new IOException());
        var service = service(memory(), budget(1000));
        service.schedule(COMPANIES);
        service.schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());
        assertTrue(service.snapshot().revenueWaiting().isEmpty());

        // Three failures in a row: the provider is unavailable and the pass backs off.
        events.clear();
        failures.put(MEDIUM, new IOException());
        failures.put(SMALL, new IOException());
        var failing = service(memory(), budget(1000));
        failing.schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());
        assertEquals(CompanyEnrichmentService.State.UNAVAILABLE, failing.snapshot().progress().state());
        failing.schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());
    }

    @Test
    public void testRestartWithinNinetyDaysNeedsNoRequestsAndWithdrawalKeepsSessionData() throws IOException
    {
        var path = temporary.newFolder().toPath().toRealPath();
        var policy = new CompositionPersistencePolicy(ACCOUNT, Permission.PERMITTED, Optional.empty());
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy, Optional.empty());
        service(cache, budget(1000)).schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());

        clock.now = clock.now.plus(Duration.ofDays(60));
        var restarted = new CompanyEnrichmentCache(path, clock);
        restarted.configure(policy, Optional.empty());
        var service = service(restarted, budget(1000));
        service.schedule(COMPANIES);
        assertEquals(3, events.stream().filter(event -> event.startsWith("revenue")).count());
        assertEquals(2, service.snapshot().revenue().size());

        // Withdrawn storage: nothing is read back after a restart, answers stay in the session.
        restarted.configure(policy, Optional.of(false));
        var withdrawn = new CompanyEnrichmentCache(path, clock);
        withdrawn.configure(policy, Optional.empty());
        assertTrue(withdrawn.revenue(ACCOUNT, LARGE).isEmpty());
        assertEquals(2, service.snapshot().revenue().size());
    }

    @Test
    public void testServiceWithoutRevenueSourceReportsNoRevenue()
    {
        var service = new CompanyEnrichmentService(ACCOUNT, (account, isin) -> Optional.empty(), () -> true,
                        isins -> Map.of(), () -> false, memory(), budget(10), clock, Runnable::run);
        service.schedule(COMPANIES);
        assertTrue(service.snapshot().revenue().isEmpty());
        assertTrue(service.snapshot().revenueWaiting().isEmpty());
    }
}
