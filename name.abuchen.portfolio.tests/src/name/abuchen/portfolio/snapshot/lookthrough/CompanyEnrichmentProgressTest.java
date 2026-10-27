package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService.Progress;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService.State;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;

/**
 * Progressive enrichment: independent lanes, incremental storage, register
 * scope, backoff and stopping. Identifiers and values are invented; no
 * request leaves the test.
 */
@SuppressWarnings("nls")
public class CompanyEnrichmentProgressTest
{
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "local");

    private static final class MutableClock extends Clock
    {
        private volatile Instant now = START;

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

        private void advance(Duration duration)
        {
            now = now.plus(duration);
        }
    }

    /** Collects retries instead of running them. */
    private static final class FakeScheduler implements CompanyEnrichmentService.Scheduler
    {
        private final List<Runnable> tasks = new ArrayList<>();
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay)
        {
            tasks.add(task);
            delays.add(delay);
        }

        private void runLast()
        {
            tasks.remove(tasks.size() - 1).run();
        }
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final MutableClock clock = new MutableClock();

    private static String isin(String prefix, int index)
    {
        return prefix + String.format("%010d", index);
    }

    /** Companies with descending values: index 0 is the largest. */
    private static List<Company> companies(String prefix, int count)
    {
        var result = new ArrayList<Company>();
        for (int index = 0; index < count; index++)
            result.add(new Company(new Identifier(InstrumentEvidence.ISIN, isin(prefix, index)),
                            BigDecimal.valueOf(100000L - index), BigDecimal.ZERO, List.of()));
        return result;
    }

    private Optional<CompanyProfile> profile(String isin, String country)
    {
        return Optional.of(new CompanyProfile(isin, Optional.of(country), Optional.of("USD"),
                        Optional.of("Invented sector"), Optional.empty(), Optional.empty(), clock.instant()));
    }

    private CompositionRequestBudget budget()
    {
        return new CompositionRequestBudget(ACCOUNT.providerId(), clock, 100000, Duration.ofHours(24),
                        Duration.ofMinutes(15));
    }

    private CompanyEnrichmentCache memory()
    {
        var cache = new CompanyEnrichmentCache(null, clock);
        cache.configure(new CompositionPersistencePolicy(ACCOUNT, Permission.UNVERIFIED, Optional.empty()),
                        Optional.empty());
        return cache;
    }

    private CompanyEnrichmentService service(CompanyEnrichmentService.ProfileSource profiles,
                    BooleanSupplier profilesEnabled, CompanyEnrichmentService.Register register,
                    BooleanSupplier registerEnabled, CompanyEnrichmentCache cache,
                    java.util.concurrent.Executor executor, CompanyEnrichmentService.Scheduler scheduler)
    {
        return new CompanyEnrichmentService(ACCOUNT, profiles, null, profilesEnabled, register, registerEnabled,
                        cache, budget(), clock, executor, scheduler);
    }

    @Test
    public void testProfilesDoNotWaitForTheRegister() throws InterruptedException
    {
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            var registerStarted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var profiles = Collections.synchronizedList(new ArrayList<String>());
            var service = service((account, isin) -> {
                profiles.add(isin);
                return profile(isin, "KY");
            }, () -> true, isins -> {
                registerStarted.countDown();
                try
                {
                    // A slow register: blocks until the profiles are complete.
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IOException();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                return Map.of(isins.get(0), Optional.empty());
            }, () -> true, memory(), executor, new FakeScheduler());

            service.schedule(companies("KY", 40));
            assertTrue(registerStarted.await(10, TimeUnit.SECONDS));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (service.snapshot().progress().loaded() < 40 && System.nanoTime() < deadline)
                Thread.sleep(10);
            // All profiles arrived while the first register lookup still blocks.
            assertEquals(40, profiles.size());
            assertEquals(1, release.getCount());
            var snapshot = service.snapshot();
            assertEquals(40, snapshot.progress().loaded());
            assertEquals("Invented sector", snapshot.attributes()
                            .get(new Identifier(InstrumentEvidence.ISIN, isin("KY", 39))).sector().orElseThrow()
                            .value());
            release.countDown();
            service.close();
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAnswersAreVisibleAtOnceAndStoredIncrementally() throws IOException
    {
        var path = temporary.newFolder().toPath().toRealPath();
        var policy = new CompositionPersistencePolicy(ACCOUNT, Permission.PERMITTED, Optional.empty());
        var cache = new CompanyEnrichmentCache(path, clock);
        cache.configure(policy, Optional.empty());
        var stored = new ArrayList<Integer>();
        var visible = new ArrayList<Integer>();
        var holder = new CompanyEnrichmentService[1];
        var calls = new int[1];
        holder[0] = service((account, isin) -> {
            calls[0]++;
            // What another session would read from disk before this answer.
            var reader = new CompanyEnrichmentCache(path, clock);
            reader.configure(policy, Optional.empty());
            int count = 0;
            for (int index = 0; index < 40; index++)
                if (reader.profile(ACCOUNT, isin("XS", index)).isPresent())
                    count++;
            stored.add(count);
            visible.add(holder[0].snapshot().progress().loaded());
            if (calls[0] == 30)
                clock.advance(CompanyEnrichmentService.FLUSH_INTERVAL);
            return profile(isin, "US");
        }, () -> true, isins -> Map.of(), () -> false, cache, Runnable::run, new FakeScheduler());

        holder[0].schedule(companies("XS", 40));

        // Every answer is visible to the next snapshot.
        for (int index = 0; index < 40; index++)
            assertEquals(index, visible.get(index).intValue());
        // Written after every 25 answers, and after the flush interval.
        assertEquals(0, stored.get(CompanyEnrichmentService.FLUSH_EVERY - 1).intValue());
        assertEquals(CompanyEnrichmentService.FLUSH_EVERY, stored.get(CompanyEnrichmentService.FLUSH_EVERY).intValue());
        assertEquals(30, stored.get(30).intValue());
        assertEquals(new Progress(40, 40, State.COMPLETE, Optional.empty()), holder[0].snapshot().progress());
    }

    @Test
    public void testRegisterIsLimitedToTheLargestCompaniesWithoutProfiles()
    {
        var registered = new ArrayList<String>();
        var service = service((account, isin) -> {
            throw new AssertionError("no key");
        }, () -> false, isins -> {
            registered.addAll(isins);
            return Map.of(isins.get(0), Optional.empty());
        }, () -> true, memory(), Runnable::run, new FakeScheduler());

        service.schedule(companies("US", CompanyEnrichmentService.REGISTER_LIMIT + 50));
        assertEquals(CompanyEnrichmentService.REGISTER_LIMIT, registered.size());
        assertEquals(isin("US", 0), registered.get(0));
        assertEquals(isin("US", CompanyEnrichmentService.REGISTER_LIMIT - 1), registered.get(registered.size() - 1));
        assertEquals(State.NO_KEY, service.snapshot().progress().state());
        assertEquals(0, service.snapshot().pending());
    }

    @Test
    public void testRegisterScopeFollowsDomicileAndProfileCountry()
    {
        var countries = Map.of(isin("US", 0), "US", isin("US", 1), "TW", isin("DE", 2), "DE");
        var registered = new ArrayList<String>();
        var service = service((account, isin) -> {
            var country = countries.get(isin);
            return country == null ? Optional.empty() : profile(isin, country);
        }, () -> true, isins -> {
            registered.addAll(isins);
            return Map.of(isins.get(0), Optional.empty());
        }, () -> true, memory(), Runnable::run, new FakeScheduler());

        var companies = new ArrayList<Company>();
        companies.add(new Company(new Identifier(InstrumentEvidence.ISIN, isin("US", 0)), BigDecimal.valueOf(60),
                        BigDecimal.ZERO, List.of()));
        companies.add(new Company(new Identifier(InstrumentEvidence.ISIN, isin("US", 1)), BigDecimal.valueOf(50),
                        BigDecimal.ZERO, List.of()));
        companies.add(new Company(new Identifier(InstrumentEvidence.ISIN, isin("DE", 2)), BigDecimal.valueOf(40),
                        BigDecimal.ZERO, List.of()));
        companies.add(new Company(new Identifier(InstrumentEvidence.ISIN, isin("IE", 3)), BigDecimal.valueOf(30),
                        BigDecimal.ZERO, List.of()));
        service.schedule(companies);

        // Depositary receipt with a foreign profile country, and a domicile prefix.
        assertEquals(List.of(isin("US", 1), isin("IE", 3)), registered);
        assertFalse(service.inRegisterScope(isin("IE", 3), CompanyEnrichmentService.REGISTER_LIMIT));
    }

    @Test
    public void testUnreachableProviderBacksOffAndCompaniesBecomeUnknown()
    {
        var calls = new ArrayList<String>();
        var online = new boolean[1];
        var scheduler = new FakeScheduler();
        var service = service((account, isin) -> {
            calls.add(isin);
            if (!online[0])
                throw new IOException("offline");
            return profile(isin, "US");
        }, () -> true, isins -> Map.of(), () -> false, memory(), Runnable::run, scheduler);

        service.schedule(companies("XS", 5));
        assertEquals(CompanyEnrichmentService.FAILURE_STREAK, calls.size());
        var progress = service.snapshot().progress();
        assertEquals(State.UNAVAILABLE, progress.state());
        assertEquals(Optional.of(START.plus(Duration.ofMinutes(1))), progress.retryAt());
        assertEquals(List.of(Duration.ofMinutes(1)), scheduler.delays);
        // A retry within the next minutes keeps the companies pending.
        assertEquals(5, service.snapshot().pending());

        clock.advance(Duration.ofMinutes(1));
        scheduler.runLast();
        assertEquals(Duration.ofMinutes(2), scheduler.delays.get(1));
        clock.advance(Duration.ofMinutes(2));
        scheduler.runLast();
        assertEquals(Duration.ofMinutes(4), scheduler.delays.get(2));
        // No retry soon: unknown, not pending.
        assertEquals(0, service.snapshot().pending());
        assertEquals(State.UNAVAILABLE, service.snapshot().progress().state());
        assertEquals(3 * CompanyEnrichmentService.FAILURE_STREAK, calls.size());

        online[0] = true;
        clock.advance(Duration.ofMinutes(4));
        scheduler.runLast();
        assertEquals(new Progress(5, 5, State.COMPLETE, Optional.empty()), service.snapshot().progress());
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void testRateLimitedRegisterDoesNotStopProfiles()
    {
        var scheduler = new FakeScheduler();
        var profiles = new ArrayList<String>();
        var service = service((account, isin) -> {
            profiles.add(isin);
            return profile(isin, "KY");
        }, () -> true, isins -> {
            throw new CompanyEnrichmentService.RegisterRateLimited(
                            Optional.of(clock.instant().plus(Duration.ofSeconds(30))));
        }, () -> true, memory(), Runnable::run, scheduler);

        service.schedule(companies("KY", 3));
        assertEquals(3, profiles.size());
        assertEquals(List.of(Duration.ofSeconds(30)), scheduler.delays);
        assertEquals(State.COMPLETE, service.snapshot().progress().state());
    }

    @Test
    public void testStopEndsThePassAndKeepsTheAnswers()
    {
        var calls = new ArrayList<String>();
        var holder = new CompanyEnrichmentService[1];
        var cache = memory();
        holder[0] = service((account, isin) -> {
            calls.add(isin);
            if (calls.size() == 2)
                holder[0].stop();
            return profile(isin, "US");
        }, () -> true, isins -> Map.of(), () -> false, cache, Runnable::run, new FakeScheduler());

        var companies = companies("XS", 5);
        holder[0].schedule(companies);
        assertEquals(2, calls.size());
        assertTrue(cache.profile(ACCOUNT, isin("XS", 1)).isPresent());
        assertEquals(Progress.none(), holder[0].snapshot().progress());

        holder[0].schedule(companies);
        assertEquals(5, calls.size());
    }

    @Test
    public void testStopInterruptsAWaitingRequest() throws InterruptedException
    {
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            var started = new CountDownLatch(1);
            var ended = new CountDownLatch(1);
            var service = service((account, isin) -> {
                started.countDown();
                try
                {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                    return Optional.empty();
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }
                finally
                {
                    ended.countDown();
                }
            }, () -> true, isins -> Map.of(), () -> false, memory(), executor, new FakeScheduler());

            service.schedule(companies("XS", 3));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            service.stop();
            assertTrue(ended.await(5, TimeUnit.SECONDS));
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testIndividualFailuresAreRememberedPerCompany()
    {
        var failing = Map.of(isin("XS", 1), new IOException());
        var calls = new HashMap<String, Integer>();
        var service = service((account, isin) -> {
            calls.merge(isin, 1, Integer::sum);
            var failure = failing.get(isin);
            if (failure != null)
                throw failure;
            return profile(isin, "US");
        }, () -> true, isins -> Map.of(), () -> false, memory(), Runnable::run, new FakeScheduler());

        service.schedule(companies("XS", 4));
        service.schedule(companies("XS", 4));
        assertEquals(Integer.valueOf(1), calls.get(isin("XS", 1)));
        assertEquals(new Progress(3, 4, State.COMPLETE, Optional.empty()), service.snapshot().progress());
        assertEquals(0, service.snapshot().pending());
    }
}
