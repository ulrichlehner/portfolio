package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;

/**
 * Resolves descriptive attributes of the companies of a look-through result
 * outside the calculation. Scheduling never blocks. Two independent background
 * lanes work on the latest scheduled companies, so a slow register never
 * delays provider profiles:
 * <ul>
 * <li>The provider lane requests profiles in descending order of value, in
 * chunks of {@link #CHUNK} companies, each chunk followed by the revenue
 * splits of the same companies. Each request takes one unit of the shared
 * provider budget. Revenue requests, which the provider weighs higher, leave
 * room in the budget for the profiles still outstanding, so they never
 * starve profiles; revenue skipped for that reason follows once all profiles
 * are answered. Profiles that classification fetched are shared through the
 * cache and not requested again.</li>
 * <li>The register lane looks up one ISIN per request, paced by the register,
 * and only where the register adds value (see {@link #inRegisterScope}).</li>
 * </ul>
 * Every answer is visible in {@link #snapshot()} at once. Answers are written
 * to disk every {@link #FLUSH_EVERY} answers or {@link #FLUSH_INTERVAL},
 * whichever comes first, so quitting during a long pass keeps the progress.
 * Cached answers younger than {@link #REFRESH_AFTER} (revenue:
 * {@link #REVENUE_REFRESH_AFTER}) cause no request.
 * <p>
 * Failures never escape: a single failing company is skipped and tried again
 * after {@link #FAILED_RETRY_AFTER}; {@link #FAILURE_STREAK} failures in a row
 * mean the source is unavailable and the lane backs off exponentially. When the
 * budget is exhausted, the provider rate-limits or denies access, the lane
 * stops. A retry is scheduled when its time is known. Companies wait
 * ("pending") only while their lane runs or retries soon; otherwise they are
 * reported without the attribute ("unknown").
 * <p>
 * Only ISINs leave the application: no names, amounts or portfolio data.
 */
public final class CompanyEnrichmentService implements AutoCloseable
{
    public static final Duration REFRESH_AFTER = Duration.ofDays(30);
    public static final Duration REVENUE_REFRESH_AFTER = Duration.ofDays(90);
    public static final int REGISTER_BATCH = 200;

    /**
     * Register lookups are limited to this many of the largest companies. The
     * register needs one request per ISIN (about one per second), so the
     * limit bounds a first pass to minutes instead of hours while covering the
     * companies that matter for the country breakdown.
     */
    public static final int REGISTER_LIMIT = 500;

    /**
     * ISIN prefixes of jurisdictions where many companies are domiciled but
     * not headquartered. Their ISIN and often also the provider profile name
     * the domicile, so the register's headquarters country adds value.
     */
    public static final Set<String> DOMICILE_PREFIXES = Set.of("KY", "BM", "VG", "JE", "GG", "IM", "IE", "NL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                    "LU", "CW", "PA", "MH", "LR"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    static final int CHUNK = 100;
    static final int FLUSH_EVERY = 25;
    static final Duration FLUSH_INTERVAL = Duration.ofSeconds(5);
    static final int FAILURE_STREAK = 3;
    static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    static final Duration FAILED_RETRY_AFTER = Duration.ofHours(1);
    /** A lane retrying within this time still counts as loading. */
    static final Duration SOON = Duration.ofMinutes(2);

    private static final Comparator<Company> BY_VALUE = Comparator.comparing(Company::total).reversed()
                    .thenComparing(company -> company.identity().value())
                    .thenComparing(company -> company.identity().namespace());

    /** The provider's company profile; empty when the provider has none. */
    @FunctionalInterface
    public interface ProfileSource
    {
        Optional<CompanyProfile> profile(ProviderAccount account, String isin) throws IOException;
    }

    /** The provider's latest revenue by geography; empty when the provider has none. */
    @FunctionalInterface
    public interface RevenueSource
    {
        Optional<RevenueSplit> revenue(ProviderAccount account, String isin) throws IOException;
    }

    /**
     * Batched register lookup. The result contains each ISIN that was answered
     * definitively, with an empty value when no record exists; ISINs missing
     * from the result stay unknown.
     */
    @FunctionalInterface
    public interface Register
    {
        Map<String, Optional<RegisterRecord>> lookup(List<String> isins) throws IOException;
    }

    /** Runs a task once after a delay; used for retries. */
    @FunctionalInterface
    public interface Scheduler
    {
        void schedule(Runnable task, Duration delay);
    }

    /** The register asked to pause requests. */
    public static final class RegisterRateLimited extends IOException
    {
        private static final long serialVersionUID = 1L;
        private final transient Optional<Instant> retryAt;

        public RegisterRateLimited(Optional<Instant> retryAt)
        {
            this.retryAt = Objects.requireNonNull(retryAt);
        }

        public Optional<Instant> retryAt()
        {
            return retryAt;
        }
    }

    /** State of the provider data of the scheduled companies. */
    public enum State
    {
        /** Nothing scheduled or nothing to do. */
        COMPLETE,
        /** Requests are running or retry soon. */
        LOADING,
        /** No key is configured; no profile can be requested. */
        NO_KEY,
        /** The provider denied access (authentication or entitlement). */
        NO_ACCESS,
        /** The budget is exhausted or the provider asked to pause. */
        PAUSED,
        /** The provider is not reachable or keeps failing. */
        UNAVAILABLE
    }

    /**
     * How many companies with an ISIN have a provider answer, of how many,
     * and why the rest is missing; the retry time when one is known.
     */
    public record Progress(int loaded, int total, State state, Optional<Instant> retryAt)
    {
        public Progress
        {
            Objects.requireNonNull(state);
            Objects.requireNonNull(retryAt);
        }

        public static Progress none()
        {
            return new Progress(0, 0, State.COMPLETE, Optional.empty());
        }
    }

    /**
     * Attributes of the scheduled companies, the number still waiting for an
     * answer and those companies.
     */
    public record Snapshot(Map<Identifier, CompanyAttributes> attributes, int pending, Set<Identifier> waiting,
                    Map<Identifier, RevenueSplit> revenue, Set<Identifier> revenueWaiting, Progress progress)
    {
        public Snapshot
        {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            waiting = Set.copyOf(waiting);
            revenue = Collections.unmodifiableMap(new LinkedHashMap<>(revenue));
            revenueWaiting = Set.copyOf(revenueWaiting);
            Objects.requireNonNull(progress);
        }

        public Snapshot(Map<Identifier, CompanyAttributes> attributes, int pending, Set<Identifier> waiting,
                        Map<Identifier, RevenueSplit> revenue, Set<Identifier> revenueWaiting)
        {
            this(attributes, pending, waiting, revenue, revenueWaiting, Progress.none());
        }

        public Snapshot(Map<Identifier, CompanyAttributes> attributes, int pending, Set<Identifier> waiting)
        {
            this(attributes, pending, waiting, Map.of(), Set.of());
        }

        public Snapshot(Map<Identifier, CompanyAttributes> attributes, int pending)
        {
            this(attributes, pending, Set.of());
        }

        public static Snapshot empty()
        {
            return new Snapshot(Map.of(), 0);
        }
    }

    /** One background lane; its fields are guarded by the service. */
    private static final class Lane
    {
        private boolean running;
        private boolean rerun;
        private Thread thread;
        private State state = State.COMPLETE;
        private Instant retryAt = Instant.MIN;
        private boolean retryScheduled;
        private int backoffs;
    }

    /** Counts answers of one pass and decides when to write and publish them. */
    private final class Publication
    {
        private int answers;
        private Instant last = clock.instant();

        private boolean answered()
        {
            answers++;
            var now = clock.instant();
            if (answers % FLUSH_EVERY != 0 && now.isBefore(last.plus(FLUSH_INTERVAL)))
                return false;
            last = now;
            cache.flush();
            notifyListeners();
            return true;
        }
    }

    private enum Kind
    {
        PROFILE, REVENUE, REGISTER
    }

    private record Attempt(Kind kind, String isin)
    {
    }

    private final ProviderAccount account;
    private final ProfileSource profiles;
    private final RevenueSource revenue;
    private final BooleanSupplier profilesEnabled;
    private final Register register;
    private final BooleanSupplier registerEnabled;
    private final CompanyEnrichmentCache cache;
    private final CompositionRequestBudget budget;
    private final Clock clock;
    private final Executor executor;
    private final Scheduler scheduler;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final Lane providerLane = new Lane();
    private final Lane registerLane = new Lane();
    /** Companies whose last request failed individually, with the time of the failure. */
    private final Map<Attempt, Instant> failed = new HashMap<>();
    private List<Identifier> companies = List.of();
    private long generation;
    private boolean closed;
    /** Whether profiles can be requested, as seen by the last provider pass; null before the first. */
    private volatile Boolean profilesPossible;

    /**
     * @param profilesEnabled
     *            whether profile requests may run, for example only with a
     *            configured key; consulted on the background thread only
     * @param registerEnabled
     *            the user's register switch; when off, no register request
     *            is made and stored register data is not used
     */
    public CompanyEnrichmentService(ProviderAccount account, ProfileSource profiles, BooleanSupplier profilesEnabled,
                    Register register, BooleanSupplier registerEnabled, CompanyEnrichmentCache cache,
                    CompositionRequestBudget budget, Clock clock, Executor executor)
    {
        this(account, profiles, null, profilesEnabled, register, registerEnabled, cache, budget, clock, executor);
    }

    /**
     * @param revenue
     *            the revenue source, requested under the same conditions as
     *            profiles, or null for none
     */
    public CompanyEnrichmentService(ProviderAccount account, ProfileSource profiles, RevenueSource revenue,
                    BooleanSupplier profilesEnabled, Register register, BooleanSupplier registerEnabled,
                    CompanyEnrichmentCache cache, CompositionRequestBudget budget, Clock clock, Executor executor)
    {
        this(account, profiles, revenue, profilesEnabled, register, registerEnabled, cache, budget, clock, executor,
                        (task, delay) -> {
                            // No automatic retry; the next schedule continues.
                        });
    }

    /**
     * @param executor
     *            runs the two lanes; it needs two threads for the lanes to
     *            run side by side
     * @param scheduler
     *            runs retries after a backoff or pause
     */
    public CompanyEnrichmentService(ProviderAccount account, ProfileSource profiles, RevenueSource revenue,
                    BooleanSupplier profilesEnabled, Register register, BooleanSupplier registerEnabled,
                    CompanyEnrichmentCache cache, CompositionRequestBudget budget, Clock clock, Executor executor,
                    Scheduler scheduler)
    {
        this.account = Objects.requireNonNull(account);
        this.profiles = Objects.requireNonNull(profiles);
        this.revenue = revenue;
        this.profilesEnabled = Objects.requireNonNull(profilesEnabled);
        this.register = Objects.requireNonNull(register);
        this.registerEnabled = Objects.requireNonNull(registerEnabled);
        this.cache = Objects.requireNonNull(cache);
        this.budget = Objects.requireNonNull(budget);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /** Called on a background thread whenever attributes or the progress changed. */
    public void addListener(Runnable listener)
    {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Runnable listener)
    {
        listeners.remove(listener);
    }

    static Optional<String> isinOf(Identifier identity)
    {
        return InstrumentEvidence.ISIN.equals(identity.namespace())
                        && identity.value().matches("[A-Z]{2}[A-Z0-9]{9}[0-9]") //$NON-NLS-1$
                                        ? Optional.of(identity.value())
                                        : Optional.empty();
    }

    private static List<String> isinsOf(List<Identifier> identities)
    {
        return identities.stream().map(CompanyEnrichmentService::isinOf).flatMap(Optional::stream).toList();
    }

    /**
     * Makes the companies of a new result the current ones and starts both
     * lanes unless they run; a running lane continues with them. Returns
     * immediately.
     */
    public void schedule(List<Company> result)
    {
        var ordered = result.stream().sorted(BY_VALUE).map(Company::identity).distinct().toList();
        synchronized (this)
        {
            if (closed)
                return;
            companies = ordered;
        }
        start(providerLane);
        start(registerLane);
    }

    private void start(Lane lane)
    {
        synchronized (this)
        {
            if (closed || companies.isEmpty())
                return;
            if (lane.running)
            {
                lane.rerun = true;
                return;
            }
            lane.running = true;
            lane.rerun = false;
        }
        try
        {
            executor.execute(() -> run(lane));
        }
        catch (RejectedExecutionException e)
        {
            synchronized (this)
            {
                lane.running = false;
            }
        }
    }

    private void run(Lane lane)
    {
        try
        {
            synchronized (this)
            {
                lane.thread = Thread.currentThread();
            }
            while (true)
            {
                List<Identifier> current;
                long started;
                synchronized (this)
                {
                    if (closed)
                        return;
                    lane.rerun = false;
                    current = companies;
                    started = generation;
                }
                if (lane == providerLane)
                    providerPass(current, started);
                else
                    registerPass(current, started);
                cache.flush();
                synchronized (this)
                {
                    // A stop clears the rerun, a later schedule sets it again.
                    if (!lane.rerun || closed)
                        return;
                    Thread.interrupted();
                }
            }
        }
        finally
        {
            synchronized (this)
            {
                lane.running = false;
                lane.thread = null;
            }
            // An interrupt of a stopped pass must not reach the next task of the thread.
            Thread.interrupted();
            scheduleRetry(lane);
            notifyListeners();
        }
    }

    private void scheduleRetry(Lane lane)
    {
        Duration delay;
        synchronized (this)
        {
            var now = clock.instant();
            if (closed || lane.retryScheduled || !lane.retryAt.isAfter(now))
                return;
            lane.retryScheduled = true;
            delay = Duration.between(now, lane.retryAt);
        }
        try
        {
            scheduler.schedule(() -> {
                synchronized (this)
                {
                    lane.retryScheduled = false;
                }
                start(lane);
            }, delay);
        }
        catch (RuntimeException e)
        {
            synchronized (this)
            {
                lane.retryScheduled = false;
            }
        }
    }

    private synchronized boolean stopped(long started)
    {
        return closed || generation != started;
    }

    private synchronized boolean retryDue(Lane lane)
    {
        return !lane.retryAt.isAfter(clock.instant());
    }

    private synchronized void settle(Lane lane, State state, Instant retryAt)
    {
        lane.state = state;
        lane.retryAt = retryAt;
        if (state == State.COMPLETE || state == State.LOADING)
            lane.backoffs = 0;
    }

    /** Exponential backoff after a streak of failures: 1, 2, 4 ... up to 30 minutes. */
    private synchronized void backOff(Lane lane)
    {
        var delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(lane.backoffs, 10));
        if (delay.compareTo(MAX_BACKOFF) > 0)
            delay = MAX_BACKOFF;
        lane.backoffs++;
        lane.state = State.UNAVAILABLE;
        lane.retryAt = clock.instant().plus(delay);
    }

    private boolean due(Optional<? extends CompanyEnrichmentCache.Cached<?>> cached, Duration refreshAfter)
    {
        return cached.isEmpty() || !cached.get().retrievedAt().plus(refreshAfter).isAfter(clock.instant());
    }

    private synchronized boolean recentlyFailed(Attempt attempt)
    {
        var at = failed.get(attempt);
        return at != null && at.plus(FAILED_RETRY_AFTER).isAfter(clock.instant());
    }

    /**
     * Records a failed request. Returns true when the streak shows that the
     * source itself fails; its companies are then not blamed individually.
     */
    private synchronized boolean failure(Attempt attempt, List<Attempt> streak)
    {
        streak.add(attempt);
        if (streak.size() < FAILURE_STREAK)
        {
            failed.put(attempt, clock.instant());
            return false;
        }
        streak.forEach(failed::remove);
        return true;
    }

    private void providerPass(List<Identifier> current, long started)
    {
        if (!retryDue(providerLane))
            return;
        boolean enabled = profilesEnabled.getAsBoolean();
        profilesPossible = enabled;
        if (!enabled)
        {
            settle(providerLane, State.NO_KEY, Instant.MIN);
            return;
        }
        synchronized (this)
        {
            providerLane.state = State.LOADING;
        }
        var isins = isinsOf(current);
        var publication = new Publication();
        var streak = new ArrayList<Attempt>();
        for (int start = 0; start < isins.size(); start += CHUNK)
        {
            int end = Math.min(isins.size(), start + CHUNK);
            var chunk = isins.subList(start, end);
            for (var isin : chunk)
            {
                if (!request(Kind.PROFILE, isin, started, publication, streak, 0))
                    return;
            }
            if (revenue != null)
            {
                var outstanding = outstandingProfiles(isins.subList(end, isins.size()));
                for (var isin : chunk)
                {
                    if (!request(Kind.REVENUE, isin, started, publication, streak, outstanding))
                        return;
                }
            }
        }
        if (revenue != null)
        {
            // Revenue skipped to leave room for profiles; cached answers cost nothing.
            for (var isin : isins)
            {
                if (!request(Kind.REVENUE, isin, started, publication, streak, 0))
                    return;
            }
        }
        settle(providerLane, State.COMPLETE, Instant.MIN);
    }

    /** Profiles of the given ISINs that a pass would still request. */
    private long outstandingProfiles(List<String> isins)
    {
        return isins.stream().filter(isin -> due(cache.profile(account, isin), REFRESH_AFTER)
                        && !recentlyFailed(new Attempt(Kind.PROFILE, isin))).count();
    }

    /**
     * Requests one provider answer if due; returns false when the pass must
     * stop. With a headroom, the request is skipped unless that many units
     * stay available afterwards.
     */
    private boolean request(Kind kind, String isin, long started, Publication publication, List<Attempt> streak,
                    long headroom)
    {
        if (stopped(started))
            return false;
        var attempt = new Attempt(kind, isin);
        var cached = kind == Kind.PROFILE ? cache.profile(account, isin) : cache.revenue(account, isin);
        if (!due(cached, kind == Kind.PROFILE ? REFRESH_AFTER : REVENUE_REFRESH_AFTER) || recentlyFailed(attempt))
            return true;
        if (!budget.acquirePermit(account, 1, headroom))
        {
            // Skipped to leave room for profiles; asked again at the end of the pass.
            if (headroom > 0)
                return true;
            paused();
            return false;
        }
        // Captured before the request, so an answer arriving after a
        // withdrawal of local storage is kept for the session only.
        var configuration = cache.generation(account);
        try
        {
            if (kind == Kind.PROFILE)
                cache.putProfile(account, isin, profiles.profile(account, isin), configuration);
            else
                cache.putRevenue(account, isin, revenue.revenue(account, isin), configuration);
        }
        catch (EnrichmentFailure e)
        {
            budget.record(account, e.result());
            paused();
            return false;
        }
        catch (InterruptedIOException e)
        {
            return false;
        }
        catch (IOException | RuntimeException e)
        {
            if (stopped(started))
                return false;
            if (failure(attempt, streak))
            {
                backOff(providerLane);
                return false;
            }
            return true;
        }
        streak.clear();
        if (publication.answered())
            start(registerLane);
        return true;
    }

    /** The budget or the provider stopped requests; the reason decides about a retry. */
    private void paused()
    {
        if (budget.isSuspended(account))
        {
            settle(providerLane, State.NO_ACCESS, Instant.MIN);
            return;
        }
        var next = budget.nextAvailable(account, 1);
        if (next.isPresent() && next.get().isAfter(clock.instant()))
            settle(providerLane, State.PAUSED, next.get());
        else if (next.isPresent())
            backOff(providerLane);
        else
            settle(providerLane, State.PAUSED, Instant.MIN);
    }

    /**
     * Whether a register lookup adds value for the company of the given rank
     * in descending order of value. The register gives the headquarters
     * country, which matters where the ISIN and often also the provider name
     * the domicile. Among the {@link #REGISTER_LIMIT} largest companies these
     * are ISINs of {@link #DOMICILE_PREFIXES}, companies whose profile country
     * differs from the ISIN prefix (for example depositary receipts) or whose
     * profile names no country, and all companies when no profile can be
     * requested. A company whose profile is still outstanding is decided once
     * the profile arrived.
     */
    boolean inRegisterScope(String isin, int rank)
    {
        if (rank >= REGISTER_LIMIT)
            return false;
        var prefix = isin.substring(0, 2);
        if (DOMICILE_PREFIXES.contains(prefix))
            return true;
        if (Boolean.FALSE.equals(profilesPossible))
            return true;
        var profile = cache.profile(account, isin);
        if (profile.isEmpty())
            return false;
        return profile.get().value().flatMap(CompanyProfile::country).map(country -> !country.equals(prefix))
                        .orElse(true);
    }

    private void registerPass(List<Identifier> current, long started)
    {
        if (!registerEnabled.getAsBoolean())
        {
            settle(registerLane, State.COMPLETE, Instant.MIN);
            return;
        }
        if (!retryDue(registerLane))
            return;
        var isins = isinsOf(current);
        var attempted = new HashSet<String>();
        var publication = new Publication();
        var streak = new ArrayList<Attempt>();
        while (true)
        {
            if (stopped(started) || !registerEnabled.getAsBoolean())
                return;
            var next = nextRegisterLookup(isins, attempted);
            if (next.isEmpty())
                break;
            var isin = next.get();
            attempted.add(isin);
            var attempt = new Attempt(Kind.REGISTER, isin);
            try
            {
                var answer = register.lookup(List.of(isin)).get(isin);
                if (answer != null)
                    cache.putRegister(isin, answer);
                else
                    failure(attempt, new ArrayList<>());
            }
            catch (RegisterRateLimited e)
            {
                var now = clock.instant();
                settle(registerLane, State.PAUSED,
                                e.retryAt().filter(at -> at.isAfter(now)).orElseGet(() -> now.plus(FIRST_BACKOFF)));
                return;
            }
            catch (InterruptedIOException e)
            {
                return;
            }
            catch (IOException | RuntimeException e)
            {
                if (stopped(started))
                    return;
                if (failure(attempt, streak))
                {
                    backOff(registerLane);
                    return;
                }
                continue;
            }
            streak.clear();
            publication.answered();
        }
        // More companies come into scope as profiles arrive; the provider
        // lane starts this lane again when it publishes answers.
        settle(registerLane, State.COMPLETE, Instant.MIN);
    }

    private Optional<String> nextRegisterLookup(List<String> isins, Set<String> attempted)
    {
        for (int rank = 0; rank < Math.min(isins.size(), REGISTER_LIMIT); rank++)
        {
            var isin = isins.get(rank);
            if (!attempted.contains(isin) && due(cache.register(isin), REFRESH_AFTER)
                            && !recentlyFailed(new Attempt(Kind.REGISTER, isin)) && inRegisterScope(isin, rank))
                return Optional.of(isin);
        }
        return Optional.empty();
    }

    private void notifyListeners()
    {
        for (var listener : listeners)
        {
            try
            {
                listener.run();
            }
            catch (RuntimeException e)
            {
                // A failing listener must not stop enrichment.
            }
        }
    }

    private boolean active(Lane lane, Instant now)
    {
        return lane.running || lane.retryScheduled && !lane.retryAt.isAfter(now.plus(SOON));
    }

    /**
     * Attributes of the latest scheduled companies; reads only memory and the
     * register switch, never credentials, so it is cheap to call. A company
     * with an ISIN waits for its profile only while the provider lane is
     * loading or retries soon; without profile access it waits for the
     * register within its scope the same way. Otherwise a missing attribute
     * is unknown, not pending.
     */
    public Snapshot snapshot()
    {
        List<Identifier> current;
        boolean providerActive;
        boolean registerActive;
        State state;
        Instant retryAt;
        var now = clock.instant();
        synchronized (this)
        {
            current = companies;
            providerActive = active(providerLane, now);
            registerActive = active(registerLane, now);
            state = providerLane.running ? State.LOADING : providerLane.state;
            retryAt = providerLane.retryAt;
        }
        boolean withRegister = registerEnabled.getAsBoolean();
        boolean withoutProfiles = Boolean.FALSE.equals(profilesPossible);
        var attributes = new LinkedHashMap<Identifier, CompanyAttributes>();
        var waiting = new HashSet<Identifier>();
        var splits = new LinkedHashMap<Identifier, RevenueSplit>();
        var revenueWaiting = new HashSet<Identifier>();
        int pending = 0;
        int rank = 0;
        int loaded = 0;
        int total = 0;
        for (var identity : current)
        {
            var isin = isinOf(identity);
            var profile = isin.flatMap(value -> cache.profile(account, value));
            var record = withRegister ? isin.flatMap(cache::register)
                            : Optional.<CompanyEnrichmentCache.Cached<RegisterRecord>>empty();
            if (isin.isPresent())
            {
                total++;
                if (profile.isPresent())
                    loaded++;
                boolean waitsForProfile = providerActive && !withoutProfiles && profile.isEmpty();
                boolean waitsForRegister = withoutProfiles && withRegister && registerActive && record.isEmpty()
                                && rank < REGISTER_LIMIT;
                if (waitsForProfile || waitsForRegister)
                {
                    pending++;
                    waiting.add(identity);
                }
                rank++;
            }
            attributes.put(identity, CompanyAttributes.resolve(identity,
                            profile.flatMap(CompanyEnrichmentCache.Cached::value),
                            record.flatMap(CompanyEnrichmentCache.Cached::value), now));
            if (revenue != null && isin.isPresent())
            {
                var cached = cache.revenue(account, isin.get());
                if (cached.isPresent())
                    cached.get().value().ifPresent(split -> splits.put(identity, split));
                else if (providerActive && !withoutProfiles)
                    revenueWaiting.add(identity);
            }
        }
        var progress = new Progress(loaded, total, state,
                        retryAt.isAfter(now) ? Optional.of(retryAt) : Optional.<Instant>empty());
        return new Snapshot(attributes, pending, waiting, splits, revenueWaiting, progress);
    }

    /**
     * Stops the running passes after their current request and interrupts
     * waiting ones; nothing is requested until the next schedule. A stopped
     * pass writes the answers received so far on its own thread, so this
     * method does no disk access and may be called on any thread.
     */
    public void stop()
    {
        synchronized (this)
        {
            generation++;
            companies = List.of();
            for (var lane : List.of(providerLane, registerLane))
            {
                lane.rerun = false;
                if (lane.state == State.LOADING)
                    lane.state = State.COMPLETE;
                if (lane.thread != null)
                    lane.thread.interrupt();
            }
        }
        notifyListeners();
    }

    /** Stops scheduling and the running passes. */
    @Override
    public void close()
    {
        synchronized (this)
        {
            closed = true;
        }
        stop();
    }
}
