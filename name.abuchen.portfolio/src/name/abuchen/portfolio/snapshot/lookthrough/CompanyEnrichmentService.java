package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;

/**
 * Resolves descriptive attributes of the companies of a look-through result
 * outside the calculation. Scheduling never blocks: one background pass at a
 * time works on the latest scheduled companies, so no company is requested
 * twice concurrently. Register lookups are batched; provider profiles are
 * requested one per company in descending order of value and each request
 * takes one unit of the shared provider budget. Companies with a cached
 * answer younger than {@link #REFRESH_AFTER} cause no request. When the budget
 * is exhausted, the remaining companies stay pending until a later pass.
 * <p>
 * Only ISINs leave the application: no names, amounts or portfolio data.
 */
public final class CompanyEnrichmentService implements AutoCloseable
{
    public static final Duration REFRESH_AFTER = Duration.ofDays(30);
    public static final int REGISTER_BATCH = 200;
    private static final int FLUSH_EVERY = 25;
    private static final Duration REGISTER_BACKOFF = Duration.ofMinutes(15);
    private static final Comparator<Company> BY_VALUE = Comparator.comparing(Company::total).reversed()
                    .thenComparing(company -> company.identity().value())
                    .thenComparing(company -> company.identity().namespace());

    /** The provider's company profile; empty when the provider has none. */
    @FunctionalInterface
    public interface ProfileSource
    {
        Optional<CompanyProfile> profile(ProviderAccount account, String isin) throws IOException;
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

    /** Attributes of the scheduled companies and the number still waiting for an answer. */
    public record Snapshot(Map<Identifier, CompanyAttributes> attributes, int pending)
    {
        public Snapshot
        {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        public static Snapshot empty()
        {
            return new Snapshot(Map.of(), 0);
        }
    }

    private final ProviderAccount account;
    private final ProfileSource profiles;
    private final BooleanSupplier profilesEnabled;
    private final Register register;
    private final BooleanSupplier registerEnabled;
    private final CompanyEnrichmentCache cache;
    private final CompositionRequestBudget budget;
    private final Clock clock;
    private final Executor executor;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private List<Identifier> companies = List.of();
    private boolean running;
    private boolean rerun;
    private boolean closed;
    private Instant profileRetryAt = Instant.MIN;
    private Instant registerRetryAt = Instant.MIN;

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
        this.account = Objects.requireNonNull(account);
        this.profiles = Objects.requireNonNull(profiles);
        this.profilesEnabled = Objects.requireNonNull(profilesEnabled);
        this.register = Objects.requireNonNull(register);
        this.registerEnabled = Objects.requireNonNull(registerEnabled);
        this.cache = Objects.requireNonNull(cache);
        this.budget = Objects.requireNonNull(budget);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
    }

    /** Called on the background thread whenever attributes have changed. */
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

    /**
     * Makes the companies of a new result the current ones and starts a
     * background pass unless one is running; a running pass continues with
     * them. Returns immediately.
     */
    public void schedule(List<Company> result)
    {
        var ordered = result.stream().sorted(BY_VALUE).map(Company::identity).distinct().toList();
        synchronized (this)
        {
            if (closed)
                return;
            companies = ordered;
            if (running)
            {
                rerun = true;
                return;
            }
            running = true;
        }
        try
        {
            executor.execute(this::run);
        }
        catch (RejectedExecutionException e)
        {
            synchronized (this)
            {
                running = false;
            }
        }
    }

    private void run()
    {
        try
        {
            while (true)
            {
                List<Identifier> current;
                synchronized (this)
                {
                    rerun = false;
                    current = companies;
                }
                var isins = current.stream().map(CompanyEnrichmentService::isinOf).flatMap(Optional::stream)
                                .toList();
                registerPass(isins);
                profilePass(isins);
                cache.flush();
                notifyListeners();
                synchronized (this)
                {
                    if (!rerun || closed)
                        return;
                }
            }
        }
        finally
        {
            synchronized (this)
            {
                running = false;
            }
        }
    }

    private boolean due(Optional<? extends CompanyEnrichmentCache.Cached<?>> cached)
    {
        return cached.isEmpty() || !cached.get().retrievedAt().plus(REFRESH_AFTER).isAfter(clock.instant());
    }

    private synchronized boolean closed()
    {
        return closed;
    }

    private void registerPass(List<String> isins)
    {
        synchronized (this)
        {
            if (!registerEnabled.getAsBoolean() || registerRetryAt.isAfter(clock.instant()))
                return;
        }
        var wanted = isins.stream().filter(isin -> due(cache.register(isin))).toList();
        for (int start = 0; start < wanted.size(); start += REGISTER_BATCH)
        {
            if (closed() || !registerEnabled.getAsBoolean())
                return;
            var batch = wanted.subList(start, Math.min(wanted.size(), start + REGISTER_BATCH));
            try
            {
                var answers = register.lookup(List.copyOf(batch));
                for (var isin : batch)
                {
                    var answer = answers.get(isin);
                    if (answer != null)
                        cache.putRegister(isin, answer);
                }
            }
            catch (RegisterRateLimited e)
            {
                var retry = e.retryAt().filter(at -> at.isAfter(clock.instant()))
                                .orElseGet(() -> clock.instant().plus(Duration.ofMinutes(1)));
                synchronized (this)
                {
                    registerRetryAt = retry;
                }
                return;
            }
            catch (IOException | RuntimeException e)
            {
                synchronized (this)
                {
                    registerRetryAt = clock.instant().plus(REGISTER_BACKOFF);
                }
                return;
            }
            notifyListeners();
        }
    }

    private void profilePass(List<String> isins)
    {
        synchronized (this)
        {
            if (!profilesEnabled.getAsBoolean() || profileRetryAt.isAfter(clock.instant()))
                return;
        }
        int fetched = 0;
        for (var isin : isins)
        {
            if (closed())
                return;
            if (!due(cache.profile(account, isin)))
                continue;
            if (!budget.acquirePermit(account, 1))
                return;
            // Captured before the request, so a profile arriving after a
            // withdrawal of local storage is kept for the session only.
            var generation = cache.generation(account);
            try
            {
                cache.putProfile(account, isin, profiles.profile(account, isin), generation);
            }
            catch (EnrichmentFailure e)
            {
                budget.record(account, e.result());
                return;
            }
            catch (InterruptedIOException e)
            {
                return;
            }
            catch (IOException | RuntimeException e)
            {
                synchronized (this)
                {
                    profileRetryAt = clock.instant().plus(budget.getFailureBackoff());
                }
                return;
            }
            if (++fetched % FLUSH_EVERY == 0)
            {
                cache.flush();
                notifyListeners();
            }
        }
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

    /**
     * Attributes of the latest scheduled companies; reads only the cache and
     * the register switch. A company with an ISIN is pending until the
     * provider (and the register, if switched on) answered for it.
     */
    public Snapshot snapshot()
    {
        List<Identifier> current;
        synchronized (this)
        {
            current = companies;
        }
        boolean withRegister = registerEnabled.getAsBoolean();
        var now = clock.instant();
        var attributes = new LinkedHashMap<Identifier, CompanyAttributes>();
        int pending = 0;
        for (var identity : current)
        {
            var isin = isinOf(identity);
            var profile = isin.flatMap(value -> cache.profile(account, value));
            var record = withRegister ? isin.flatMap(cache::register)
                            : Optional.<CompanyEnrichmentCache.Cached<RegisterRecord>>empty();
            if (isin.isPresent() && (profile.isEmpty() || withRegister && record.isEmpty()))
                pending++;
            attributes.put(identity, CompanyAttributes.resolve(identity,
                            profile.flatMap(CompanyEnrichmentCache.Cached::value),
                            record.flatMap(CompanyEnrichmentCache.Cached::value), now));
        }
        return new Snapshot(attributes, pending);
    }

    /** Stops scheduling; a running pass ends after its current request. */
    @Override
    public synchronized void close()
    {
        closed = true;
    }
}
