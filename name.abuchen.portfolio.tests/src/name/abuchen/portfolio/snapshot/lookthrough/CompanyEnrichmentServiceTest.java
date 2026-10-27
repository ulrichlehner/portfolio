package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes.Source;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;

/** Identifiers and values are invented. */
@SuppressWarnings("nls")
public class CompanyEnrichmentServiceTest
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
    private final List<String> profileCalls = new ArrayList<>();
    private final List<List<String>> registerCalls = new ArrayList<>();
    private final Map<String, IOException> profileFailures = new HashMap<>();
    private boolean registerEnabled;
    private boolean profilesEnabled = true;

    private static Company company(String isin, String value)
    {
        return new Company(new Identifier(InstrumentEvidence.ISIN, isin), new BigDecimal(value), BigDecimal.ZERO,
                        List.of());
    }

    private static final List<Company> COMPANIES = List.of(company(SMALL, "10"), company(LARGE, "30"),
                    company(MEDIUM, "20"), new Company(new Identifier("synthetic:id", "no-isin"), BigDecimal.ONE,
                                    BigDecimal.ZERO, List.of()));

    private CompanyEnrichmentService service(CompanyEnrichmentCache cache, CompositionRequestBudget budget)
    {
        return new CompanyEnrichmentService(ACCOUNT, (account, isin) -> {
            profileCalls.add(isin);
            var failure = profileFailures.get(isin);
            if (failure != null)
                throw failure;
            if (isin.equals(MEDIUM))
                return Optional.empty();
            return Optional.of(new CompanyProfile(isin, Optional.of("US"), Optional.of("USD"),
                            Optional.of("Invented sector"), Optional.empty(), Optional.empty(), clock.instant()));
        }, () -> profilesEnabled, isins -> {
            registerCalls.add(isins);
            var result = new HashMap<String, Optional<RegisterRecord>>();
            for (var isin : isins)
                result.put(isin, isin.equals(LARGE)
                                ? Optional.of(new RegisterRecord(isin, "SYNTHETICLEI00000001", Optional.of("HK"),
                                                Optional.of("KY"), clock.instant()))
                                : Optional.empty());
            return result;
        }, () -> registerEnabled, cache, budget, clock, Runnable::run);
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
    public void testLargestCompaniesFirstWithinBudgetAndRestStaysPending()
    {
        var budget = budget(2);
        var service = service(memory(), budget);
        service.schedule(COMPANIES);
        assertEquals(List.of(LARGE, MEDIUM), profileCalls);
        var snapshot = service.snapshot();
        assertEquals(1, snapshot.pending());
        assertEquals(Set.of(new Identifier(InstrumentEvidence.ISIN, SMALL)), snapshot.waiting());
        var small = snapshot.attributes().get(new Identifier(InstrumentEvidence.ISIN, SMALL));
        assertTrue(small.sector().isEmpty());
        assertEquals(Source.ISIN_PREFIX, small.country().orElseThrow().source());
        var large = snapshot.attributes().get(new Identifier(InstrumentEvidence.ISIN, LARGE));
        assertEquals("Invented sector", large.sector().orElseThrow().value());
        assertEquals(Source.PROVIDER, large.country().orElseThrow().source());
        // A company without ISIN is reported, but never requested or pending.
        assertTrue(snapshot.attributes().get(new Identifier("synthetic:id", "no-isin")).country().isEmpty());

        // Enrichment continues when budget is available again.
        clock.now = clock.now.plus(Duration.ofHours(25));
        service.schedule(COMPANIES);
        assertEquals(List.of(LARGE, MEDIUM, SMALL), profileCalls);
        assertEquals(0, service.snapshot().pending());
    }

    @Test
    public void testCachedAnswersAreNotRequestedAgainUntilThirtyDays()
    {
        var service = service(memory(), budget(100));
        service.schedule(COMPANIES);
        assertEquals(3, profileCalls.size());
        // A "no profile" answer is cached as well.
        service.schedule(COMPANIES);
        assertEquals(3, profileCalls.size());

        clock.now = clock.now.plus(Duration.ofDays(29));
        service.schedule(COMPANIES);
        assertEquals(3, profileCalls.size());
        clock.now = clock.now.plus(Duration.ofDays(1));
        service.schedule(COMPANIES);
        assertEquals(6, profileCalls.size());
    }

    @Test
    public void testRegisterIsBatchedWinsCountryAndCanBeSwitchedOff()
    {
        registerEnabled = true;
        var service = service(memory(), budget(100));
        service.schedule(COMPANIES);
        assertEquals(List.of(List.of(LARGE, MEDIUM, SMALL)), registerCalls);
        var large = service.snapshot().attributes().get(new Identifier(InstrumentEvidence.ISIN, LARGE));
        assertEquals("HK", large.country().orElseThrow().value());
        assertEquals(Source.REGISTER, large.country().orElseThrow().source());

        service.schedule(COMPANIES);
        assertEquals(1, registerCalls.size());

        registerEnabled = false;
        clock.now = clock.now.plus(Duration.ofDays(31));
        service.schedule(COMPANIES);
        assertEquals(1, registerCalls.size());
        large = service.snapshot().attributes().get(new Identifier(InstrumentEvidence.ISIN, LARGE));
        assertEquals(Source.PROVIDER, large.country().orElseThrow().source());
    }

    @Test
    public void testCompaniesAreNotPendingWithoutProfileAccess()
    {
        profilesEnabled = false;
        var service = service(memory(), budget(100));
        service.schedule(COMPANIES);
        assertTrue(profileCalls.isEmpty());
        assertEquals(0, service.snapshot().pending());
        assertTrue(service.snapshot().waiting().isEmpty());
    }

    @Test
    public void testProviderFailureIsRecordedInTheSharedBudgetAndStopsThePass()
    {
        var budget = budget(100);
        profileFailures.put(LARGE, new EnrichmentFailure(new CompositionResult(CompositionResult.Status.ENTITLEMENT_FAILED,
                        Optional.empty(), Optional.empty())));
        var service = service(memory(), budget);
        service.schedule(COMPANIES);
        assertEquals(List.of(LARGE), profileCalls);
        assertTrue(budget.isSuspended(ACCOUNT));
        assertEquals(3, service.snapshot().pending());

        // Other failures back off without suspending the account.
        var other = budget(100);
        profileFailures.put(LARGE, new IOException());
        profileCalls.clear();
        var retry = service(memory(), other);
        retry.schedule(COMPANIES);
        retry.schedule(COMPANIES);
        assertEquals(List.of(LARGE), profileCalls);
        assertFalse(other.isSuspended(ACCOUNT));
    }

    @Test
    public void testRestartWithStoredDataNeedsNoRequests() throws IOException
    {
        registerEnabled = true;
        var path = temporary.newFolder().toPath().toRealPath();
        var policy = new CompositionPersistencePolicy(ACCOUNT, Permission.PERMITTED, Optional.empty());
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy, Optional.empty());
        service(cache, budget(100)).schedule(COMPANIES);
        assertEquals(3, profileCalls.size());
        assertEquals(1, registerCalls.size());

        clock.now = clock.now.plus(Duration.ofDays(1));
        var restarted = new CompanyEnrichmentCache(path, clock);
        restarted.configure(policy, Optional.empty());
        var service = service(restarted, budget(100));
        service.schedule(COMPANIES);
        assertEquals(3, profileCalls.size());
        assertEquals(1, registerCalls.size());
        assertEquals(0, service.snapshot().pending());
        assertEquals("HK", service.snapshot().attributes().get(new Identifier(InstrumentEvidence.ISIN, LARGE))
                        .country().orElseThrow().value());
    }
}
