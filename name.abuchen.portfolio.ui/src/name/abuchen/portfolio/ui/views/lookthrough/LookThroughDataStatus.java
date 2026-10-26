package name.abuchen.portfolio.ui.views.lookthrough;

import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;

/**
 * SWT-free status model for the look-through data status. It converts provided
 * facts into display rows: one row per fund with provenance dates, freshness,
 * source declaration and the latest retrieval attempt, and one global row with
 * the effective storage mode, the persistence reason and the restart limitation
 * for session-only or failed disk storage. The model performs no retrieval, no
 * file, network or persistence access of its own; callers obtain the facts from
 * memory-only reads of the composition store, never through refresh.
 * <p>
 * Freshness is a display fact, not a validity claim: a retrieval within
 * {@link #STALE_AFTER} of the supplied clock is shown as fresh, otherwise
 * stale. A recent provider refresh or a recent retrieval never implies a
 * recent holdings date, and the holdings effective date is never substituted
 * when the provider does not report one; provider refresh and local retrieval
 * stay visible and distinct instead.
 * <p>
 * Excluded positions are deliberately not encoded again here. They stay with
 * the existing {@link LookThroughViewPresenter}, which renders every valuation
 * exclusion as an excluded row from the same source of truth, so these values
 * exist in one place only.
 * <p>
 * The analysis is always shown without any upload or mapping step: the status
 * model encodes that as {@code canShowWithoutUploadOrMapping}, a fixed fact of
 * this display because the composition request carries fund identifiers only,
 * never portfolio data, and no mapping flow exists.
 * <p>
 * No text of this model contains secrets, tokens or key material. Provider and
 * local account identifiers may appear as plain facts.
 */
public final class LookThroughDataStatus
{
    /**
     * A retrieval at most this old relative to the clock is displayed as fresh;
     * anything older is displayed as stale. This is display wording only and
     * never decides whether calculations may run. The stale text key
     * {@code LookThroughStatusStale} must stay consistent with this constant.
     */
    public static final Duration STALE_AFTER = Duration.ofHours(24);

    public enum Freshness
    {
        FRESH, STALE
    }

    public enum StorageMode
    {
        PERSISTENT, SESSION_ONLY, DISK_FAILURE
    }

    /**
     * Condensed state of one fund row for the status icon. The details stay
     * available in the row tooltip.
     */
    public enum Health
    {
        /** Usable data and no failed attempt since. */
        OK,
        /** A refresh failed, but earlier usable data is still used. */
        WARNING,
        /** Access suspended, or the received data cannot be used. */
        ERROR,
        /** A refresh of this fund is running. */
        PENDING,
        /** Nothing to report, for example before the first attempt. */
        NONE
    }

    /** Global facts: the composition store state and the policy-built mode. */
    public record GlobalFacts(CompositionStore.State storeState,
                    CompositionPersistencePolicy.EffectiveMode effectiveMode)
    {
        public GlobalFacts
        {
            Objects.requireNonNull(storeState);
            Objects.requireNonNull(effectiveMode);
        }
    }

    /**
     * Per-fund facts for one status row. The retained composition, when
     * present, is the same retained valid source that calculations use; its
     * provenance dates are the displayed dates, never replaced by attempt
     * dates. {@code retryAfter} comes from the refresh coordination, not from
     * a plain read.
     */
    public record FundFacts(CompositionInstrument fund, ProviderAccount account, Optional<FundComposition> retained,
                    Optional<CompositionResult.Status> latestAttempt, Optional<Instant> retryAfter,
                    boolean refreshPending, Optional<FundComposition> sourcePreview,
                    Optional<FundComposition> unverifiedComposition)
    {
        public FundFacts(CompositionInstrument fund, ProviderAccount account, Optional<FundComposition> retained,
                        Optional<CompositionResult.Status> latestAttempt, Optional<Instant> retryAfter,
                        boolean refreshPending)
        {
            this(fund, account, retained, latestAttempt, retryAfter, refreshPending, Optional.empty());
        }

        public FundFacts(CompositionInstrument fund, ProviderAccount account, Optional<FundComposition> retained,
                        Optional<CompositionResult.Status> latestAttempt, Optional<Instant> retryAfter,
                        boolean refreshPending, Optional<FundComposition> sourcePreview)
        {
            this(fund, account, retained, latestAttempt, retryAfter, refreshPending, sourcePreview, Optional.empty());
        }

        public FundFacts
        {
            Objects.requireNonNull(fund);
            Objects.requireNonNull(account);
            Objects.requireNonNull(retained);
            Objects.requireNonNull(sourcePreview);
            Objects.requireNonNull(unverifiedComposition);
            Objects.requireNonNull(latestAttempt);
            Objects.requireNonNull(retryAfter);
        }
    }

    /**
     * One fund row: facts plus all display texts, ready for the view. The
     * fund reference is the stable key of the row across updates.
     */
    public record FundStatus(String fundLabel, String providerAccountText, Optional<Instant> retrieval,
                    Optional<String> retrievalText, Optional<Instant> providerRefreshedAt,
                    Optional<String> providerRefreshedText, Optional<LocalDate> holdingsEffectiveDate,
                    Optional<String> holdingsText, Optional<Freshness> freshness, Optional<Duration> age,
                    Optional<String> freshnessText, Optional<String> sourceDataText, Optional<String> denominatorText,
                    Optional<CompositionResult.Status> latestAttempt, String attemptText, Optional<Instant> retryAfter,
                    Optional<String> retryText, boolean refreshPending, FundRef reference, Health health,
                    Optional<String> retrievedDateText)
    {
        public FundStatus
        {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(health);
            Objects.requireNonNull(retrievedDateText);
            Objects.requireNonNull(fundLabel);
            Objects.requireNonNull(providerAccountText);
            Objects.requireNonNull(retrieval);
            Objects.requireNonNull(retrievalText);
            Objects.requireNonNull(providerRefreshedAt);
            Objects.requireNonNull(providerRefreshedText);
            Objects.requireNonNull(holdingsEffectiveDate);
            Objects.requireNonNull(holdingsText);
            Objects.requireNonNull(freshness);
            Objects.requireNonNull(age);
            Objects.requireNonNull(freshnessText);
            Objects.requireNonNull(sourceDataText);
            Objects.requireNonNull(denominatorText);
            Objects.requireNonNull(latestAttempt);
            Objects.requireNonNull(attemptText);
            Objects.requireNonNull(retryAfter);
            Objects.requireNonNull(retryText);
        }

        /**
         * All details of the row as one text, one fact per line: the pane
         * shows only the fund, the status, the retrieval date and the share,
         * so the remaining facts must stay reachable here.
         */
        public String tooltipText()
        {
            var lines = new ArrayList<String>();
            lines.add(fundLabel);
            if (refreshPending)
                lines.add(Messages.LookThroughStatusPendingRefresh);
            lines.add(attemptText);
            retryText.ifPresent(lines::add);
            retrievalText.ifPresent(lines::add);
            freshnessText.ifPresent(lines::add);
            providerRefreshedText.ifPresent(lines::add);
            holdingsText.ifPresent(lines::add);
            sourceDataText.ifPresent(lines::add);
            denominatorText.ifPresent(lines::add);
            lines.add(providerAccountText);
            return String.join("\n", lines); //$NON-NLS-1$
        }
    }

    /** The complete status: global row, fund rows and the local-render fact. */
    public record Model(StorageMode mode, String modeText, CompositionPersistencePolicy.Reason reason,
                    Optional<String> reasonText, Optional<String> limitationText, List<FundStatus> funds,
                    boolean canShowWithoutUploadOrMapping)
    {
        public Model
        {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(modeText);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(reasonText);
            Objects.requireNonNull(limitationText);
            funds = List.copyOf(funds);
        }

        /**
         * The single storage line of the pane: the persistence reason when one
         * applies, because it already names the mode, otherwise the mode.
         */
        public String storageText()
        {
            return reasonText.orElse(modeText);
        }

        /** True when the provider rejected the key or its permission. */
        public boolean isAccessSuspended()
        {
            return funds.stream().anyMatch(status -> status.latestAttempt().filter(LookThroughDataStatus::isSuspension)
                            .isPresent());
        }
    }

    private LookThroughDataStatus()
    {
    }

    /**
     * Builds the status model from facts only. The clock is needed for the
     * freshness display and for formatting the instant facts in the local
     * zone of the clock.
     */
    public static Model present(Clock clock, GlobalFacts global, Collection<FundFacts> funds)
    {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(global);
        Objects.requireNonNull(funds);

        var mode = storageMode(global.storeState());
        var reason = global.effectiveMode().reason();

        var fundStatuses = funds.stream().map(fund -> status(clock, fund)).toList();

        return new Model(mode, modeText(mode), reason, reasonText(reason), limitationText(mode), fundStatuses, true);
    }

    /**
     * Converts a memory-only coordinator read into per-fund facts. A plain
     * read carries no retry instant; callers that own a refresh outcome use
     * {@link #fundFacts(CompositionRefreshCoordinator.Refresh, ProviderAccount, CompositionInstrument, boolean)}
     * instead.
     */
    public static FundFacts fundFacts(CompositionRefreshCoordinator.Snapshot snapshot, ProviderAccount account,
                    CompositionInstrument fund, boolean refreshPending)
    {
        return fundFacts(snapshot, account, fund, Optional.empty(), refreshPending);
    }

    /**
     * Converts a refresh outcome into per-fund facts, keeping the retained
     * valid source and its dates visible alongside the attempt status; a
     * failed refresh never replaces the retained dates with an empty result.
     */
    public static FundFacts fundFacts(CompositionRefreshCoordinator.Refresh refresh, ProviderAccount account,
                    CompositionInstrument fund, boolean refreshPending)
    {
        return fundFacts(refresh.snapshot(), account, fund, refresh.retryAt(), refreshPending);
    }

    private static FundFacts fundFacts(CompositionRefreshCoordinator.Snapshot snapshot, ProviderAccount account,
                    CompositionInstrument fund, Optional<Instant> retryAfter, boolean refreshPending)
    {
        Objects.requireNonNull(snapshot);
        return new FundFacts(fund, account, //
                        snapshot.usableComposition(), //
                        snapshot.latestAttempt().map(CompositionResult::status), //
                        retryAfter, refreshPending,
                        snapshot.latestAttempt().flatMap(CompositionResult::composition),
                        snapshot.unverifiedComposition());
    }

    private static StorageMode storageMode(CompositionStore.State storeState)
    {
        return switch (storeState)
        {
            case PERSISTENT -> StorageMode.PERSISTENT;
            case SESSION_ONLY -> StorageMode.SESSION_ONLY;
            // A closed store retains nothing beyond what memory still keeps;
            // the session-only limitation describes that display state.
            case CLOSED -> StorageMode.SESSION_ONLY;
            case DISK_FAILURE -> StorageMode.DISK_FAILURE;
            default -> throw new IllegalArgumentException(storeState.name());
        };
    }

    private static String modeText(StorageMode mode)
    {
        return switch (mode)
        {
            // The wording is shared with the persistence preference control so
            // the same mode is never described differently in two places.
            case PERSISTENT -> Messages.PrefEulerpoolPersistenceModePersistent;
            case SESSION_ONLY -> Messages.PrefEulerpoolPersistenceModeSessionOnly;
            case DISK_FAILURE -> Messages.LookThroughStatusModeDiskFailure;
            default -> throw new IllegalArgumentException(mode.name());
        };
    }

    private static Optional<String> reasonText(CompositionPersistencePolicy.Reason reason)
    {
        return switch (reason)
        {
            case USER_OPT_OUT -> Optional.of(Messages.PrefEulerpoolPersistenceReasonUserOptOut);
            case POLICY_PROHIBITED -> Optional.of(Messages.PrefEulerpoolPersistenceReasonPolicyProhibited);
            case POLICY_UNVERIFIED -> Optional.of(Messages.PrefEulerpoolPersistenceReasonPolicyUnverified);
            case PERMITTED -> Optional.empty();
            default -> throw new IllegalArgumentException(reason.name());
        };
    }

    private static Optional<String> limitationText(StorageMode mode)
    {
        return switch (mode)
        {
            case SESSION_ONLY -> Optional.of(Messages.LookThroughStatusLimitationSessionOnly);
            case DISK_FAILURE -> Optional.of(Messages.LookThroughStatusLimitationDiskFailure);
            case PERSISTENT -> Optional.empty();
            default -> throw new IllegalArgumentException(mode.name());
        };
    }

    private static FundStatus status(Clock clock, FundFacts facts)
    {
        var providerAccountText = MessageFormat.format(Messages.LookThroughStatusProviderAccount, //
                        facts.account().providerId(), facts.account().accountId());

        // Prefer retained calculation data. If none is usable yet, show the
        // received source's own provenance and declarations without admitting
        // it to the exposure calculation.
        var displayed = facts.retained().or(facts::sourcePreview);
        var retrieval = displayed.map(FundComposition::provenance) //
                        .map(CompositionProvenance::retrievedAt);
        var providerRefreshedAt = displayed.map(FundComposition::provenance) //
                        .flatMap(CompositionProvenance::providerRefreshedAt);
        var providerRefreshedText = optionalInstantText(clock, providerRefreshedAt,
                        Messages.LookThroughStatusProviderRefreshedUnavailable,
                        Messages.LookThroughStatusProviderRefreshedDate);

        var holdingsEffectiveDate = displayed.map(FundComposition::provenance) //
                        .flatMap(CompositionProvenance::holdingsEffectiveDate);
        var holdingsText = Optional.of(holdingsEffectiveDate //
                        .map(date -> MessageFormat.format(Messages.LookThroughStatusHoldingsDate, //
                                        Values.Date.format(date))) //
                        .orElse(Messages.LookThroughStatusHoldingsUnavailable));

        var freshness = freshness(clock, retrieval);
        var age = age(clock, retrieval);
        var freshnessText = freshness.map(value -> ageText(value, age.orElse(Duration.ZERO)));

        var sourceDataText = displayed //
                        .map(FundComposition::completeness) //
                        .map(LookThroughDataStatus::sourceDataText);
        var denominatorText = displayed //
                        .map(FundComposition::denominator) //
                        .map(LookThroughDataStatus::denominatorText);

        var latestAttempt = facts.latestAttempt();
        var attemptText = latestAttempt.isPresent() //
                        ? attemptText(latestAttempt.orElseThrow()) //
                        : Messages.LookThroughStatusNoAttempt;
        var retrievalText = retrieval //
                        .map(instant -> MessageFormat.format(Messages.LookThroughStatusRetrievalDate, //
                                        Values.DateTime.format(LocalDateTime.ofInstant(instant, clock.getZone()))));
        var retryText = retryText(clock, facts);
        var retrievedDateText = retrieval.map(
                        instant -> Values.DateTime.format(LocalDateTime.ofInstant(instant, clock.getZone())));

        return new FundStatus(facts.fund().label(), providerAccountText, retrieval, retrievalText, providerRefreshedAt,
                        providerRefreshedText, holdingsEffectiveDate, holdingsText, freshness, age, freshnessText,
                        sourceDataText, denominatorText, latestAttempt, attemptText, facts.retryAfter(), retryText,
                        facts.refreshPending(), new FundRef(facts.account(), facts.fund()), health(facts),
                        retrievedDateText);
    }

    private static boolean isSuspension(CompositionResult.Status status)
    {
        return status == CompositionResult.Status.AUTHENTICATION_FAILED
                        || status == CompositionResult.Status.ENTITLEMENT_FAILED;
    }

    /**
     * A provider answer without composition (for example for a directly held
     * share) is not a problem and gets no icon. Failures that a later retry
     * may fix are warnings; unusable data and suspended access are errors.
     */
    private static Health health(FundFacts facts)
    {
        if (facts.refreshPending())
            return Health.PENDING;

        var attempt = facts.latestAttempt();
        if (attempt.filter(LookThroughDataStatus::isSuspension).isPresent())
            return Health.ERROR;

        // Arithmetic-usable data without verified semantics is still used by
        // the calculation, so it counts as data here.
        if (facts.retained().or(facts::unverifiedComposition).isPresent())
        {
            var failed = attempt.filter(status -> status != CompositionResult.Status.RECEIVED
                            && status != CompositionResult.Status.CANCELLED).isPresent();
            return failed ? Health.WARNING : Health.OK;
        }

        if (facts.sourcePreview().isPresent())
            return Health.ERROR;

        return switch (attempt.orElse(CompositionResult.Status.CANCELLED))
        {
            case MALFORMED_RESPONSE -> Health.ERROR;
            case FAILED, RATE_LIMITED -> Health.WARNING;
            default -> Health.NONE;
        };
    }

    /**
     * The age is a display duration measured from the local retrieval to now;
     * a negative comparison result from clock skew is treated as an age of
     * zero and stays fresh.
     */
    private static Optional<Duration> age(Clock clock, Optional<Instant> retrieval)
    {
        return retrieval.map(instant -> {
            var age = Duration.between(instant, clock.instant());
            return age.isNegative() ? Duration.ZERO : age;
        });
    }

    private static Optional<Freshness> freshness(Clock clock, Optional<Instant> retrieval)
    {
        return age(clock, retrieval) //
                        .map(value -> value.compareTo(STALE_AFTER) <= 0 ? Freshness.FRESH : Freshness.STALE);
    }

    private static String ageText(Freshness freshness, Duration age)
    {
        var ageText = MessageFormat.format(Messages.LookThroughStatusAgeFormatter, age.toHours());
        return switch (freshness)
        {
            case FRESH -> MessageFormat.format(Messages.LookThroughStatusFresh, ageText);
            case STALE -> MessageFormat.format(Messages.LookThroughStatusStale, ageText);
            default -> throw new IllegalArgumentException(freshness.name());
        };
    }

    /** Formats the date-time fact, or reports the fact as unavailable. */
    private static Optional<String> optionalInstantText(Clock clock, Optional<Instant> value, String emptyText,
                    String presentPattern)
    {
        if (value.isEmpty())
            return Optional.of(emptyText);
        return value.map(instant -> MessageFormat.format(presentPattern, //
                        Values.DateTime.format(LocalDateTime.ofInstant(instant, clock.getZone()))));
    }

    /**
     * The retry hint is shown whenever the coordination knows the next possible
     * attempt, for example after a rate limit, a deferred quota or the automatic
     * cadence. It does not depend on the last attempt having produced a status.
     */
    private static Optional<String> retryText(Clock clock, FundFacts facts)
    {
        return facts.retryAfter() //
                        .map(at -> MessageFormat.format(Messages.LookThroughStatusRetryHint, //
                                        Values.DateTime.format(LocalDateTime.ofInstant(at, clock.getZone()))));
    }

    private static String attemptText(CompositionResult.Status status)
    {
        return switch (status)
        {
            case RECEIVED -> Messages.LookThroughStatusAttemptReceived;
            case UNAVAILABLE -> Messages.LookThroughStatusAttemptUnavailable;
            case UNSUPPORTED -> Messages.LookThroughStatusAttemptUnsupported;
            case MALFORMED_RESPONSE -> Messages.LookThroughStatusAttemptMalformedResponse;
            case RATE_LIMITED -> Messages.LookThroughStatusAttemptRateLimited;
            case AUTHENTICATION_FAILED -> Messages.LookThroughStatusAttemptAuthenticationFailed;
            case ENTITLEMENT_FAILED -> Messages.LookThroughStatusAttemptEntitlementFailed;
            case FAILED -> Messages.LookThroughStatusAttemptFailed;
            case CANCELLED -> Messages.LookThroughStatusAttemptCancelled;
            default -> throw new IllegalArgumentException(status.name());
        };
    }

    private static String sourceDataText(FundComposition.Completeness completeness)
    {
        return switch (completeness)
        {
            case COMPLETE -> Messages.LookThroughStatusSourceDataComplete;
            case PARTIAL -> Messages.LookThroughStatusSourceDataPartial;
            case UNKNOWN -> Messages.LookThroughStatusSourceDataUnknown;
            default -> throw new IllegalArgumentException(completeness.name());
        };
    }

    private static String denominatorText(FundComposition.Denominator denominator)
    {
        return switch (denominator)
        {
            case TOTAL_FUND_NAV -> Messages.LookThroughStatusSourceDenominatorTotalFundNav;
            case REPORTED_HOLDINGS -> Messages.LookThroughStatusSourceDenominatorReportedHoldings;
            case UNKNOWN -> Messages.LookThroughStatusSourceDenominatorUnknown;
            default -> throw new IllegalArgumentException(denominator.name());
        };
    }
}
