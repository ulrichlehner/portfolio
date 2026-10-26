package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionStore;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Freshness;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Health;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.FundStatus;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.GlobalFacts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.Model;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughDataStatus.StorageMode;

/**
 * Synthetic-only tests of the SWT-free status model. All facts are plain
 * records with fixed dates against a fixed clock; every value uses synthetic
 * identifiers and none touches files, network or persistence.
 */
@SuppressWarnings("nls")
public class LookThroughDataStatusTest
{
    private static final Instant NOW = Instant.parse("2026-03-01T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
    private static final Instant RETRIEVAL = NOW.minus(Duration.ofHours(3));
    private static final Instant PROVIDER_REFRESHED = NOW.minus(Duration.ofHours(5));
    private static final LocalDate HOLDINGS_DATE = LocalDate.parse("2026-02-28");
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "test");
    private static final FundComposition.Completeness PARTIAL = FundComposition.Completeness.PARTIAL;
    private static final FundComposition.Denominator NAV = FundComposition.Denominator.TOTAL_FUND_NAV;

    private static final CompositionInstrument FUND = instrument("SYNTH-A");

    private static CompositionInstrument instrument(String identifier)
    {
        return new CompositionInstrument("Synthetic fund " + identifier, Map.of("synthetic:instrument", identifier));
    }

    private static FundComposition composition(Instant retrieval, Optional<Instant> providerRefreshed,
                    Optional<LocalDate> holdings, FundComposition.Completeness completeness,
                    FundComposition.Denominator denominator)
    {
        var provenance = new CompositionProvenance(ACCOUNT, FUND, retrieval, providerRefreshed, holdings);
        var constituent = new FundComposition.Constituent(instrument("SYNTH-CO"), Optional.of("1.0"));
        return new FundComposition(provenance, denominator, FundComposition.ExposureBasis.PHYSICAL_HOLDINGS,
                        completeness, List.of(constituent));
    }

    private Model model(CompositionStore.State state, CompositionPersistencePolicy.StorageMode mode,
                    CompositionPersistencePolicy.Reason reason, List<LookThroughDataStatus.FundFacts> funds)
    {
        return LookThroughDataStatus.present(CLOCK, new GlobalFacts(state, //
                        new CompositionPersistencePolicy.EffectiveMode(mode, reason)), funds);
    }

    private Model singleFundModel(Optional<FundComposition> retained, Optional<CompositionResult.Status> attempt,
                    Optional<Instant> retryAfter)
    {
        var facts = new LookThroughDataStatus.FundFacts(FUND, ACCOUNT, retained, attempt, retryAfter, false);
        return model(CompositionStore.State.SESSION_ONLY, CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of(facts));
    }

    private FundStatus first(Model model)
    {
        return model.funds().get(0);
    }

    @Test
    public void testUnvalidatedSourceShowsRetrievalWithoutBecomingRetainedData()
    {
        var preview = composition(RETRIEVAL, Optional.of(PROVIDER_REFRESHED), Optional.empty(), PARTIAL,
                        FundComposition.Denominator.UNKNOWN);
        var snapshot = new CompositionRefreshCoordinator.Snapshot(Optional.of(RETRIEVAL),
                        Optional.of(new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(preview),
                                        Optional.empty())), Optional.empty(), Optional.of(preview), List.of());
        var facts = LookThroughDataStatus.fundFacts(snapshot, ACCOUNT, FUND, false);
        assertTrue(facts.retained().isEmpty());
        var status = first(model(CompositionStore.State.SESSION_ONLY,
                        CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of(facts)));
        assertEquals(Optional.of(RETRIEVAL), status.retrieval());
        assertEquals(Optional.of(PROVIDER_REFRESHED), status.providerRefreshedAt());
        assertEquals(Optional.of(CompositionResult.Status.RECEIVED), status.latestAttempt());
        assertTrue(status.denominatorText().isPresent());
    }

    @Test
    public void testHoldingsDateUnavailableKeepsProviderRefreshAndRetrievalDistinct()
    {
        var status = first(singleFundModel(
                        Optional.of(composition(RETRIEVAL, Optional.of(PROVIDER_REFRESHED), Optional.empty(), PARTIAL,
                                        NAV)), Optional.empty(), Optional.empty()));

        assertEquals("Synthetic fund SYNTH-A", status.fundLabel());
        assertEquals("Provider synthetic, local account test", status.providerAccountText());
        assertTrue(status.holdingsEffectiveDate().isEmpty());
        assertEquals("The holdings effective date is not available.", status.holdingsText().orElseThrow());

        assertEquals(Optional.of(RETRIEVAL), status.retrieval());
        assertEquals(Optional.of(PROVIDER_REFRESHED), status.providerRefreshedAt());
        assertTrue(status.providerRefreshedText().isPresent());
        assertTrue(status.retrievalText().isPresent());
        assertFalse(status.retrievalText().equals(status.providerRefreshedText()));
        assertFalse(status.retrievalText().equals(status.holdingsText()));
        assertFalse(status.providerRefreshedText().equals(status.holdingsText()));
    }

    @Test
    public void testFreshAtExactly24HoursAndStaleOneSecondLater()
    {
        var fresh = first(singleFundModel(
                        Optional.of(composition(NOW.minus(LookThroughDataStatus.STALE_AFTER), Optional.empty(),
                                        Optional.empty(), PARTIAL, NAV)), Optional.empty(), Optional.empty()));
        assertEquals(Freshness.FRESH, fresh.freshness().orElseThrow());
        assertTrue(fresh.freshnessText().isPresent());

        var stale = first(singleFundModel(
                        Optional.of(composition(NOW.minus(LookThroughDataStatus.STALE_AFTER).minus(Duration.ofSeconds(1)),
                                        Optional.empty(), Optional.empty(), PARTIAL, NAV)), Optional.empty(),
                        Optional.empty()));
        assertEquals(Freshness.STALE, stale.freshness().orElseThrow());
        assertTrue(stale.freshnessText().isPresent());
        assertTrue(stale.freshnessText().orElseThrow().contains("24 hours"));
        assertFalse(stale.freshnessText().equals(fresh.freshnessText()));
    }

    @Test
    public void testFailedRefreshKeepsRetainedDatesAndReportsTheFailure()
    {
        var status = first(singleFundModel(
                        Optional.of(composition(RETRIEVAL, Optional.of(PROVIDER_REFRESHED), Optional.empty(), PARTIAL,
                                        NAV)), //
                        Optional.of(CompositionResult.Status.FAILED), Optional.empty()));

        assertEquals("The last retrieval attempt failed.", status.attemptText());
        assertEquals(Optional.of(CompositionResult.Status.FAILED), status.latestAttempt());
        assertEquals(Optional.of(RETRIEVAL), status.retrieval());
        assertEquals(Optional.of(PROVIDER_REFRESHED), status.providerRefreshedAt());
        assertEquals(Freshness.FRESH, status.freshness().orElseThrow());
    }

    @Test
    public void testFailedRefreshWithRetainedDataIsAWarningWithReasonAndDatesInTheTooltip()
    {
        var status = first(singleFundModel(
                        Optional.of(composition(RETRIEVAL, Optional.of(PROVIDER_REFRESHED),
                                        Optional.of(HOLDINGS_DATE), PARTIAL, NAV)), //
                        Optional.of(CompositionResult.Status.FAILED), Optional.empty()));

        assertEquals(Health.WARNING, status.health());
        assertTrue(status.retrievedDateText().isPresent());

        var tooltip = status.tooltipText();
        assertTrue(tooltip.contains("The last retrieval attempt failed."));
        assertTrue(tooltip.contains(status.retrievalText().orElseThrow()));
        assertTrue(tooltip.contains(status.providerRefreshedText().orElseThrow()));
        assertTrue(tooltip.contains(status.holdingsText().orElseThrow()));
        assertTrue(tooltip.contains(status.retrievedDateText().orElseThrow()));
        assertTrue(tooltip.contains(status.providerAccountText()));
    }

    @Test
    public void testRejectedKeyIsAnErrorAndSuspendsAccess()
    {
        var model = singleFundModel(Optional.empty(), Optional.of(CompositionResult.Status.AUTHENTICATION_FAILED),
                        Optional.empty());

        assertEquals(Health.ERROR, first(model).health());
        assertTrue(model.isAccessSuspended());

        var healthy = singleFundModel(
                        Optional.of(composition(RETRIEVAL, Optional.empty(), Optional.empty(), PARTIAL, NAV)),
                        Optional.of(CompositionResult.Status.RECEIVED), Optional.empty());
        assertEquals(Health.OK, first(healthy).health());
        assertFalse(healthy.isAccessSuspended());

        var nothing = singleFundModel(Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(Health.NONE, first(nothing).health());
    }

    @Test
    public void testStorageLineShowsTheReasonInsteadOfTheMode()
    {
        var model = singleFundModel(Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(model.reasonText().orElseThrow(), model.storageText());
    }

    @Test
    public void testEveryStatusMapsToADistinctText()
    {
        var expected = Map.of( //
                        CompositionResult.Status.RECEIVED, "Source data was retrieved from the provider. Retrieval itself is not a statement about consistency or completeness.", //
                        CompositionResult.Status.UNAVAILABLE, "The provider has no composition data for this source right now.", //
                        CompositionResult.Status.UNSUPPORTED, "The provider does not support composition data for this source.", //
                        CompositionResult.Status.MALFORMED_RESPONSE, "The last response was not captured as a consistent composition.", //
                        CompositionResult.Status.RATE_LIMITED, "The provider rate limit was reached during the last retrieval attempt.", //
                        CompositionResult.Status.AUTHENTICATION_FAILED, "Authentication failed. Reconfigure the provider key before retrying.", //
                        CompositionResult.Status.ENTITLEMENT_FAILED, "Entitlement failed. Confirm the provider permission before retrying.", //
                        CompositionResult.Status.FAILED, "The last retrieval attempt failed.", //
                        CompositionResult.Status.CANCELLED, "The last retrieval attempt was cancelled.");

        assertEquals(9, expected.size());
        var texts = new HashSet<String>();
        for (var status : CompositionResult.Status.values())
        {
            var row = first(singleFundModel(Optional.empty(), Optional.of(status), Optional.empty()));
            assertEquals(expected.get(status), row.attemptText());
            texts.add(row.attemptText());
        }
        assertEquals(9, texts.size());

        var withoutAttempt = first(singleFundModel(Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals("No retrieval attempt has been made yet.", withoutAttempt.attemptText());
    }

    @Test
    public void testSessionOnlyAndDiskFailureCarryTheRestartLimitationButPersistentDoesNot()
    {
        var session = model(CompositionStore.State.SESSION_ONLY, CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of());
        assertEquals(StorageMode.SESSION_ONLY, session.mode());
        assertTrue(session.limitationText().isPresent());
        assertEquals("Composition data is kept in memory only and discarded when Portfolio Performance exits. After restart, initial retrieval is needed again and offline fund exposure remains unresolved until data becomes available.", //
                        session.limitationText().orElseThrow());
        assertEquals(CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, session.reason());
        assertTrue(session.reasonText().isPresent());

        var failure = model(CompositionStore.State.DISK_FAILURE, CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of());
        assertEquals(StorageMode.DISK_FAILURE, failure.mode());
        assertTrue(failure.limitationText().isPresent());
        assertTrue(failure.limitationText().orElseThrow().contains("Writing composition data to disk failed"));
        assertTrue(failure.limitationText().orElseThrow().contains(
                        "After restart, initial retrieval is needed again and offline fund exposure remains unresolved until data becomes available."));

        var persistent = model(CompositionStore.State.PERSISTENT, CompositionPersistencePolicy.StorageMode.PERSISTENT,
                        CompositionPersistencePolicy.Reason.PERMITTED, List.of());
        assertEquals(StorageMode.PERSISTENT, persistent.mode());
        assertTrue(persistent.limitationText().isEmpty());
        assertTrue(persistent.reasonText().isEmpty());
    }

    @Test
    public void testClosedStoreIsDisplayedLikeASessionOnlyStore()
    {
        var closed = model(CompositionStore.State.CLOSED, CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of());
        assertEquals(StorageMode.SESSION_ONLY, closed.mode());
        assertTrue(closed.limitationText().isPresent());

        var persistent = model(CompositionStore.State.PERSISTENT, CompositionPersistencePolicy.StorageMode.PERSISTENT,
                        CompositionPersistencePolicy.Reason.PERMITTED, List.of());
        assertFalse(closed.modeText().equals(persistent.modeText()));
    }

    @Test
    public void testRetryHintAppearsWheneverTheNextAttemptIsKnown()
    {
        var retryAt = NOW.plus(Duration.ofMinutes(30));
        var rateLimited = first(singleFundModel(Optional.empty(), //
                        Optional.of(CompositionResult.Status.RATE_LIMITED), Optional.of(retryAt)));
        assertTrue(rateLimited.retryText().isPresent());
        assertTrue(rateLimited.retryText().orElseThrow().contains("Next retry is possible after"));
        assertEquals(Optional.of(retryAt), rateLimited.retryAfter());

        var rateLimitedWithoutDate = first(singleFundModel(Optional.empty(), //
                        Optional.of(CompositionResult.Status.RATE_LIMITED), Optional.empty()));
        assertTrue(rateLimitedWithoutDate.retryText().isEmpty());

        // A deferred quota or cadence has no attempt yet but still knows the date.
        var deferredWithoutAttempt = first(singleFundModel(Optional.empty(), Optional.empty(), Optional.of(retryAt)));
        assertTrue(deferredWithoutAttempt.retryText().isPresent());
        assertEquals("No retrieval attempt has been made yet.", deferredWithoutAttempt.attemptText());

        var failedWithDate = first(singleFundModel(Optional.empty(), //
                        Optional.of(CompositionResult.Status.FAILED), Optional.of(retryAt)));
        assertTrue(failedWithDate.retryText().isPresent());
    }

    @Test
    public void testSourceDeclarationDenominatorAndReportedHoldingsTexts()
    {
        var complete = composition(RETRIEVAL, Optional.empty(), Optional.of(HOLDINGS_DATE),
                        FundComposition.Completeness.COMPLETE, NAV);
        var completeStatus = first(singleFundModel(Optional.of(complete), Optional.empty(), Optional.empty()));
        assertEquals("The provider declares the composition complete.", completeStatus.sourceDataText().orElseThrow());
        assertEquals("Composition rows are allocated in percent of the total fund net asset value.", //
                        completeStatus.denominatorText().orElseThrow());
        assertTrue(completeStatus.holdingsText().orElseThrow().contains("Holdings effective as of"));
        assertTrue(completeStatus.freshnessText().isPresent());
        assertTrue(completeStatus.retrievalText().isPresent());

        var unverified = composition(RETRIEVAL, Optional.empty(), Optional.of(HOLDINGS_DATE),
                        FundComposition.Completeness.UNKNOWN, FundComposition.Denominator.UNKNOWN);
        var unverifiedStatus = first(singleFundModel(Optional.of(unverified), Optional.empty(), Optional.empty()));
        assertEquals("The provider does not declare a composition completeness.", //
                        unverifiedStatus.sourceDataText().orElseThrow());
        assertEquals("The composition denominator is not specified.", //
                        unverifiedStatus.denominatorText().orElseThrow());
    }

    @Test
    public void testWithoutRetentionOnlyTheAttemptTextIsVisible()
    {
        var status = first(singleFundModel(Optional.empty(), Optional.empty(), Optional.empty()));

        assertTrue(status.retrieval().isEmpty());
        assertTrue(status.retrievalText().isEmpty());
        assertTrue(status.providerRefreshedAt().isEmpty());
        assertTrue(status.freshness().isEmpty());
        assertTrue(status.freshnessText().isEmpty());
        assertTrue(status.sourceDataText().isEmpty());
        assertTrue(status.denominatorText().isEmpty());
        assertEquals("No retrieval attempt has been made yet.", status.attemptText());
        assertEquals("The holdings effective date is not available.", status.holdingsText().orElseThrow());
        assertFalse(status.refreshPending());
    }

    @Test
    public void testAnalysisIsAlwaysShownWithoutUploadOrMappingRequirement()
    {
        var persistent = model(CompositionStore.State.PERSISTENT, CompositionPersistencePolicy.StorageMode.PERSISTENT,
                        CompositionPersistencePolicy.Reason.PERMITTED, List.of());
        var session = model(CompositionStore.State.SESSION_ONLY, CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of());
        var failure = model(CompositionStore.State.DISK_FAILURE, CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, List.of());

        assertTrue(persistent.canShowWithoutUploadOrMapping());
        assertTrue(session.canShowWithoutUploadOrMapping());
        assertTrue(failure.canShowWithoutUploadOrMapping());
    }

    private GlobalFacts sessionOnly()
    {
        return new GlobalFacts(CompositionStore.State.SESSION_ONLY, //
                        new CompositionPersistencePolicy.EffectiveMode( //
                                        CompositionPersistencePolicy.StorageMode.SESSION_ONLY,
                                        CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED));
    }

    @Test
    public void testBuildingTheStatusModelNeverTriggersAGatewayRequest()
    {
        var gateway = new CountingGateway();
        var expected = composition(RETRIEVAL, Optional.of(PROVIDER_REFRESHED), Optional.of(HOLDINGS_DATE), PARTIAL, NAV);

        // The view wiring reads once from the memory-only store; building and
        // re-rendering the model never reads or requests again.
        var facts = LookThroughDataStatus.fundFacts(gateway.read(ACCOUNT, FUND), ACCOUNT, FUND, false);
        assertEquals(1, gateway.readCalls);
        assertEquals(0, gateway.refreshCalls);
        assertEquals(Optional.of(expected), facts.retained());

        for (var i = 0; i < 100; i++)
        {
            var row = first(LookThroughDataStatus.present(CLOCK, sessionOnly(), List.of(facts)));
            assertEquals("Synthetic fund SYNTH-A", row.fundLabel());
            assertEquals(Freshness.FRESH, row.freshness().orElseThrow());
        }

        assertEquals(0, gateway.refreshCalls);
        assertEquals(1, gateway.readCalls);
    }

    /** Counts gateway operations: a request is a refresh, a read is not. */
    private static final class CountingGateway implements LookThroughRefreshController.RefreshGateway
    {
        private int readCalls;
        private int refreshCalls;

        @Override
        public CompositionRefreshCoordinator.Snapshot read(ProviderAccount account, CompositionInstrument instrument)
        {
            readCalls++;
            var composition = composition(RETRIEVAL, Optional.of(PROVIDER_REFRESHED), Optional.of(HOLDINGS_DATE),
                            PARTIAL, NAV);
            return new CompositionRefreshCoordinator.Snapshot(Optional.of(RETRIEVAL),
                            Optional.of(new CompositionResult(CompositionResult.Status.RECEIVED,
                                            Optional.of(composition), Optional.empty())),
                            Optional.of(composition), Optional.empty(), List.of());
        }

        @Override
        public CompletableFuture<CompositionRefreshCoordinator.Refresh> refresh(ProviderAccount account,
                        CompositionInstrument instrument, boolean manual)
        {
            refreshCalls++;
            return CompletableFuture.completedFuture(new CompositionRefreshCoordinator.Refresh(
                            CompositionRefreshCoordinator.Disposition.COMPLETED,
                            new CompositionRefreshCoordinator.Snapshot(Optional.empty(), Optional.empty(),
                                            Optional.empty(), Optional.empty(), List.of()),
                            Optional.empty()));
        }
    }
}
