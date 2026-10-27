package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Position;

@SuppressWarnings("nls")
public class LookThroughEngineTest
{
    private static final LocalDate DATE = LocalDate.parse("2026-01-31");
    private static final Instant RETRIEVED = Instant.parse("2026-02-01T12:00:00Z");
    private static final ProviderAccount SOURCE = new ProviderAccount("synthetic", "test");

    private final Map<Security, InstrumentResolution> owned = new HashMap<>();
    private final Map<CompositionInstrument, InstrumentResolution> resolutions = new HashMap<>();
    private final Map<Identifier, FundComposition> compositions = new HashMap<>();
    private final Map<Identifier, FundComposition> unverifiedCompositions = new HashMap<>();
    private final List<Position> positions = new ArrayList<>();

    private Identifier identity(String id)
    {
        return new Identifier("synthetic:instrument", id);
    }

    private CompositionInstrument instrument(String id, AssetType type, String company)
    {
        return instrument(id, id, type, company);
    }

    private CompositionInstrument instrument(String id, String verifiedId, AssetType type, String company)
    {
        var instrument = new CompositionInstrument("Synthetic " + id, Map.of("synthetic:instrument", id));
        var provenance = new Provenance(SOURCE, "record-" + id, RETRIEVED);
        var evidence = new InstrumentEvidence(instrument.identifiers(), new Assertion<>(identity(verifiedId), provenance),
                        Optional.ofNullable(type).map(value -> new Assertion<>(value, provenance)),
                        Optional.ofNullable(company).map(value -> new Assertion<>(new Identifier("synthetic:company", value),
                                        provenance)));
        resolutions.put(instrument, new InstrumentResolver().resolve(instrument, List.of(evidence), ignored -> {
            throw new AssertionError("No network or enrichment during calculation");
        }, () -> false));
        return instrument;
    }

    private Security holding(CompositionInstrument instrument, long amount)
    {
        var security = new Security("Owned synthetic", "EUR");
        security.setIsin("SYNTHETIC-NOT-AN-ISIN");
        positions.add(new Position(security, 1000000, Money.of("EUR", amount)));
        owned.put(security, resolutions.get(instrument));
        return security;
    }

    private Constituent row(CompositionInstrument instrument, String weight)
    {
        return new Constituent(instrument, Optional.of(weight));
    }

    private FundComposition composition(CompositionInstrument fund, Constituent... rows)
    {
        return composition(fund, Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, rows);
    }

    private FundComposition composition(CompositionInstrument fund, Denominator denominator, ExposureBasis basis,
                    Constituent... rows)
    {
        var result = new FundComposition(new CompositionProvenance(SOURCE, fund, RETRIEVED, Optional.empty(),
                        Optional.empty()), denominator, basis, Completeness.UNKNOWN, List.of(rows));
        compositions.put(resolutions.get(fund).instrument().orElseThrow(), result);
        return result;
    }

    private SecuritiesValuation valuation(List<SecuritiesValuation.Diagnostic> diagnostics)
    {
        long total = positions.stream().mapToLong(position -> position.value().getAmount()).sum();
        return new SecuritiesValuation(DATE, positions, diagnostics, Money.of("EUR", total));
    }

    private FundComposition unverifiedComposition(CompositionInstrument fund, Constituent... rows)
    {
        var result = new FundComposition(new CompositionProvenance(SOURCE, fund, RETRIEVED, Optional.empty(),
                        Optional.empty()), Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.UNKNOWN,
                        List.of(rows));
        unverifiedCompositions.put(resolutions.get(fund).instrument().orElseThrow(), result);
        return result;
    }

    private LookThroughResult calculate()
    {
        return new LookThroughEngine().calculate(valuation(List.of()), owned, resolutions, compositions,
                        unverifiedCompositions);
    }

    private void amount(String expected, BigDecimal actual)
    {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private void reconciles(LookThroughResult result)
    {
        var total = BigDecimal.ZERO;
        for (var category : Category.values())
            total = total.add(result.value(category));
        amount(Long.toString(result.valuation().valuedSubset().getAmount()), total);
        amount(total.toPlainString(), result.total());
    }

    @Test
    public void testDirectAndTwoFundsOverlapPreservesDuplicateRowsWithoutParentDoubleCount()
    {
        var direct = instrument("direct-class", AssetType.EQUITY, "issuer");
        var otherClass = instrument("other-class", AssetType.EQUITY, "issuer");
        var first = instrument("first-fund", AssetType.FUND, null);
        var second = instrument("second-fund", AssetType.FUND, null);
        var directSecurity = holding(direct, 6000);
        holding(first, 2000);
        holding(second, 2000);
        var duplicate = row(otherClass, "0.25");
        var firstSnapshot = composition(first, duplicate, duplicate, row(direct, "0.5"));
        var secondSnapshot = composition(second, row(otherClass, "1"));

        var result = calculate();

        reconciles(result);
        assertEquals(1, result.companies().size());
        var company = result.companies().get(0);
        amount("6000", company.direct());
        amount("4000", company.indirect());
        amount("10000", company.total());
        assertEquals(5, company.contributions().size());
        assertSame(directSecurity, company.contributions().get(0).origin().security());
        assertTrue(company.contributions().get(0).isDirect());
        var firstPath = company.contributions().get(1).path();
        var duplicatePath = company.contributions().get(2).path();
        assertEquals(0, firstPath.get(0).sourceIndex());
        assertEquals(1, duplicatePath.get(0).sourceIndex());
        assertSame(firstSnapshot, firstPath.get(0).composition().source());
        assertSame(secondSnapshot, company.contributions().get(4).path().get(0).composition().source());
        assertTrue(firstPath.get(0).provenance().holdingsEffectiveDate().isEmpty());
        assertTrue(firstPath.get(0).provenance().providerRefreshedAt().isEmpty());
        assertEquals(Completeness.UNKNOWN, firstPath.get(0).composition().source().completeness());
        amount("0", result.value(Category.UNRESOLVED));
        assertEquals("Owned synthetic", directSecurity.getName());
        assertEquals("SYNTHETIC-NOT-AN-ISIN", directSecurity.getIsin());
        assertThrows(UnsupportedOperationException.class, () -> result.contributions().clear());
        assertThrows(UnsupportedOperationException.class, () -> company.contributions().clear());
        assertThrows(UnsupportedOperationException.class, () -> firstPath.clear());
    }

    @Test
    public void testSpecificationSixtyDirectFortyHalfValidatedIsExactlyEightyTwenty()
    {
        var share = instrument("share", AssetType.EQUITY, "issuer");
        var fund = instrument("fund", AssetType.FUND, null);
        holding(share, 6000);
        holding(fund, 4000);
        composition(fund, row(share, "0.5"));

        var result = calculate();

        amount("8000", result.value(Category.COMPANY));
        amount("2000", result.value(Category.UNRESOLVED));
        assertEquals(Money.of("EUR", 10000), result.denominator().orElseThrow());
        assertEquals(Reason.NAV_SHORTFALL, result.contributions().get(2).reason());
        amount("0.5", result.contributions().get(2).composition().orElseThrow().shortfall().orElseThrow());
        reconciles(result);
    }

    @Test
    public void testAllFourCategoriesAndUnknownTypesAndIdentities()
    {
        var fund = instrument("fund", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        var ungrouped = instrument("ungrouped", AssetType.EQUITY, null);
        var cash = instrument("cash", AssetType.CASH, null);
        var bond = instrument("bond", AssetType.OTHER_NON_EQUITY, null);
        var unknown = instrument("identifier-only", null, "not-evidence-of-equity");
        var unidentified = new CompositionInstrument("Unidentified synthetic", Map.of());
        resolutions.put(unidentified, new InstrumentResolver().resolve(unidentified, List.of(), ignored -> List.of(),
                        () -> false));
        holding(fund, 10000);
        composition(fund, row(share, "0.2"), row(ungrouped, "0.2"), row(cash, "0.1"), row(bond, "0.1"),
                        row(unknown, "0.2"), row(unidentified, "0.1"));

        var result = calculate();

        amount("2000", result.value(Category.COMPANY));
        amount("2000", result.value(Category.UNGROUPED_EQUITY));
        amount("2000", result.value(Category.CASH_OR_OTHER));
        amount("4000", result.value(Category.UNRESOLVED));
        assertEquals(InstrumentResolution.Reason.UNKNOWN_CLASSIFICATION,
                        result.contributions().get(4).resolution().orElseThrow().reason());
        assertEquals(InstrumentResolution.Reason.MISSING_IDENTIFIERS,
                        result.contributions().get(5).resolution().orElseThrow().reason());
        assertEquals(unidentified.label(), result.contributions().get(5).instrument().label());
        reconciles(result);
    }

    @Test
    public void testMissingCompositionAndMissingPrecomputedResolutionRemainUnresolved()
    {
        var fund = instrument("fund", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        var missingSecurity = holding(share, 4000);
        owned.remove(missingSecurity);
        holding(fund, 6000);

        var result = calculate();

        amount("10000", result.value(Category.UNRESOLVED));
        assertEquals(Reason.MISSING_RESOLUTION, result.contributions().get(0).reason());
        assertEquals(Reason.MISSING_COMPOSITION, result.contributions().get(1).reason());
        assertTrue(result.companies().isEmpty());
        reconciles(result);
    }

    @Test
    public void testUnusableSnapshotsKeepEntireValueAndDiagnosticsWithoutInventedShortfall()
    {
        var fund = instrument("fund", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        holding(fund, 10000);
        var unusable = List.of(
                        composition(fund, Denominator.UNKNOWN, ExposureBasis.PHYSICAL_HOLDINGS, row(share, "1")),
                        composition(fund, Denominator.REPORTED_HOLDINGS, ExposureBasis.PHYSICAL_HOLDINGS, row(share, "1")),
                        composition(fund, Denominator.TOTAL_FUND_NAV, ExposureBasis.SYNTHETIC_COLLATERAL, row(share, "1")),
                        composition(fund, Denominator.TOTAL_FUND_NAV, ExposureBasis.UNKNOWN, row(share, "1")),
                        composition(fund, row(share, "1.01")), composition(fund, row(share, "-0.1")),
                        composition(fund, row(share, "NaN")));

        for (var snapshot : unusable)
        {
            compositions.put(identity("fund"), snapshot);
            var result = calculate();
            amount("10000", result.value(Category.UNRESOLVED));
            assertEquals(1, result.contributions().size());
            var contribution = result.contributions().get(0);
            assertEquals(Reason.UNUSABLE_COMPOSITION, contribution.reason());
            var validation = contribution.composition().orElseThrow();
            assertSame(snapshot, validation.source());
            assertFalse(validation.diagnostics().isEmpty());
            assertTrue(validation.shortfall().isEmpty());
            assertTrue(result.companies().isEmpty());
            reconciles(result);
        }
    }

    @Test
    public void testTinyShortfallIsNotRoundedAwayAndWeightsAreNotNormalized()
    {
        var fund = instrument("fund", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        holding(fund, 1);
        composition(fund, row(share, "0.999999999999999999999999999999"));

        var result = calculate();

        amount("0.000000000000000000000000000001", result.value(Category.UNRESOLVED));
        amount("0.999999999999999999999999999999", result.value(Category.COMPANY));
        assertEquals(2, result.contributions().size());
        reconciles(result);
    }

    @Test
    public void testNestedUnusableFundAndMissingRowResolutionOnlyAffectTheirBranches()
    {
        var root = instrument("root", AssetType.FUND, null);
        var child = instrument("synthetic-child", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        var missing = new CompositionInstrument("Missing synthetic row", Map.of());
        holding(root, 10000);
        composition(root, row(share, "0.5"), row(child, "0.3"), row(missing, "0.2"));
        composition(child, Denominator.TOTAL_FUND_NAV, ExposureBasis.SYNTHETIC_COLLATERAL, row(share, "1"));

        var result = calculate();

        amount("5000", result.value(Category.COMPANY));
        amount("5000", result.value(Category.UNRESOLVED));
        assertEquals(Reason.UNUSABLE_COMPOSITION, result.contributions().get(1).reason());
        amount("3000", result.contributions().get(1).value());
        assertEquals(Reason.MISSING_RESOLUTION, result.contributions().get(2).reason());
        assertEquals(missing, result.contributions().get(2).instrument());
        reconciles(result);
    }

    @Test
    public void testEightEdgeLeafUsableNinthEdgeOnlyAffectedBranchUnresolved()
    {
        var share = instrument("share", AssetType.EQUITY, "issuer");
        var funds = new ArrayList<CompositionInstrument>();
        for (int index = 0; index < 9; index++)
            funds.add(instrument("fund-" + index, AssetType.FUND, null));
        holding(funds.get(0), 10000);
        for (int index = 0; index < 7; index++)
            composition(funds.get(index), row(funds.get(index + 1), "1"));
        composition(funds.get(7), row(share, "0.6"), row(funds.get(8), "0.4"));
        composition(funds.get(8), row(share, "1"));

        var result = calculate();

        amount("6000", result.value(Category.COMPANY));
        amount("4000", result.value(Category.UNRESOLVED));
        assertEquals(8, result.contributions().get(0).path().size());
        assertEquals(8, result.contributions().get(1).path().size());
        assertEquals(Reason.DEPTH_LIMIT, result.contributions().get(1).reason());
        reconciles(result);
    }

    @Test
    public void testCyclesUseVerifiedIdentityAndOnlyStopCurrentBranch()
    {
        var root = instrument("root", AssetType.FUND, null);
        var alias = instrument("different-source-id", "root", AssetType.FUND, null);
        var child = instrument("child", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        holding(root, 10000);
        composition(root, row(child, "0.5"), row(share, "0.5"));
        composition(child, row(alias, "0.4"), row(share, "0.6"));

        var result = calculate();

        amount("8000", result.value(Category.COMPANY));
        amount("2000", result.value(Category.UNRESOLVED));
        assertEquals(Reason.CYCLE, result.contributions().get(0).reason());
        assertEquals(alias, result.contributions().get(0).instrument());
        assertEquals(2, result.contributions().get(0).path().size());
        reconciles(result);
    }

    @Test
    public void testRepeatedNestedFundOnIndependentPathsAndOwnedPositionIsNotGlobalCycle()
    {
        var root = instrument("root", AssetType.FUND, null);
        var child = instrument("child", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        holding(root, 6000);
        holding(child, 4000);
        composition(root, row(child, "0.4"), row(child, "0.6"));
        composition(child, row(share, "0.5"));

        var result = calculate();

        amount("5000", result.value(Category.COMPANY));
        amount("5000", result.value(Category.UNRESOLVED));
        assertEquals(3, result.companies().get(0).contributions().size());
        amount("1200", result.companies().get(0).contributions().get(0).value());
        amount("1800", result.companies().get(0).contributions().get(1).value());
        amount("2000", result.companies().get(0).contributions().get(2).value());
        reconciles(result);
    }

    @Test
    public void testSelectedSnapshotIsUsedWholeAndWrongFundCannotBeSubstituted()
    {
        var fund = instrument("fund", AssetType.FUND, null);
        var other = instrument("other-fund", AssetType.FUND, null);
        var share = instrument("share", AssetType.EQUITY, "issuer");
        holding(fund, 10000);
        composition(fund, row(share, "0.8"));
        var selected = new FundComposition(new CompositionProvenance(new ProviderAccount("second-source", "test"),
                        fund, RETRIEVED.plusSeconds(1), Optional.of(RETRIEVED), Optional.of(DATE)),
                        Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, Completeness.PARTIAL,
                        List.of(row(share, "0.3")));
        compositions.put(identity("fund"), selected);

        var result = calculate();

        amount("3000", result.value(Category.COMPANY));
        amount("7000", result.value(Category.UNRESOLVED));
        assertSame(selected.provenance(), result.contributions().get(0).path().get(0).provenance());
        assertEquals(Optional.of(DATE), result.contributions().get(0).path().get(0).provenance().holdingsEffectiveDate());
        var wrongFund = composition(other, row(share, "1"));
        compositions.put(identity("fund"), wrongFund);
        var mismatch = calculate();
        amount("10000", mismatch.value(Category.UNRESOLVED));
        assertEquals(Reason.COMPOSITION_IDENTITY_MISMATCH, mismatch.contributions().get(0).reason());
        reconciles(mismatch);
    }

    @Test
    public void testMissingValuationDenominatorAndExcludedShortAreRetained()
    {
        holding(instrument("share", AssetType.EQUITY, "issuer"), 6000);
        var missing = new Security("Missing synthetic", "USD");
        var shortPosition = new Security("Short synthetic", "EUR");
        var diagnostics = List.of(new SecuritiesValuation.Diagnostic(missing,
                        SecuritiesValuation.Reason.MISSING_CONVERSION), new SecuritiesValuation.Diagnostic(shortPosition,
                                        SecuritiesValuation.Reason.UNSUPPORTED_NEGATIVE_POSITION));
        var valuation = valuation(diagnostics);

        var result = new LookThroughEngine().calculate(valuation, owned, resolutions, compositions);

        amount("6000", result.value(Category.COMPANY));
        assertTrue(result.denominator().isEmpty());
        assertSame(valuation, result.valuation());
        assertEquals(diagnostics, result.valuation().diagnostics());
        assertFalse(result.valuation().isEntireSelectionSupported());
        reconciles(result);
    }

    @Test
    public void testEmptySelectionDoesNotInventPercentages()
    {
        var result = calculate();
        amount("0", result.total());
        assertTrue(result.denominator().isEmpty());
        assertTrue(result.contributions().isEmpty());
        reconciles(result);
    }

    @Test
    public void testUnverifiedWeightsExpandIntoClassifiedIssuers()
    {
        var fund = instrument("unverified-fund", AssetType.FUND, null);
        var issuer = instrument("issuer", AssetType.EQUITY, "SYNTH-CO");
        holding(fund, 10000);
        unverifiedComposition(fund, row(issuer, "0.6"), row(issuer, "0.4"));

        var result = calculate();

        reconciles(result);
        assertEquals(1, result.companies().size());
        amount("10000", result.value(Category.COMPANY));
        amount("0", result.value(Category.UNRESOLVED));
        assertTrue(result.value(Category.UNVERIFIED_HOLDING).signum() == 0);
        assertTrue(result.contributions().stream()
                        .allMatch(row -> row.category() == Category.COMPANY && !row.isDirect()));
    }

    @Test
    public void testUnverifiedShortfallStaysUnresolvedAndIsNeverNormalized()
    {
        var fund = instrument("unverified-short", AssetType.FUND, null);
        var issuer = instrument("issuer", AssetType.EQUITY, "SYNTH-CO");
        holding(fund, 10000);
        unverifiedComposition(fund, row(issuer, "0.75"));

        var result = calculate();

        reconciles(result);
        amount("7500", result.value(Category.COMPANY));
        amount("2500", result.value(Category.UNRESOLVED));
        assertEquals(Reason.NAV_SHORTFALL, result.contributions().getLast().reason());
    }

    @Test
    public void testArithmeticallyInvalidUnverifiedSourceStaysEntirelyUnresolved()
    {
        var fund = instrument("unverified-overfull", AssetType.FUND, null);
        var issuer = instrument("issuer", AssetType.EQUITY, "SYNTH-CO");
        holding(fund, 10000);
        unverifiedComposition(fund, row(issuer, "0.9"), row(issuer, "0.2"));

        var result = calculate();

        reconciles(result);
        amount("0", result.value(Category.COMPANY));
        amount("10000", result.value(Category.UNRESOLVED));
        assertEquals(Reason.UNUSABLE_COMPOSITION, result.contributions().getFirst().reason());
    }

    @Test
    public void testRoundingExcessCompositionReconcilesExactly()
    {
        var fund = instrument("rounding-fund", AssetType.FUND, null);
        var first = instrument("first", AssetType.EQUITY, "CO-FIRST");
        var second = instrument("second", AssetType.EQUITY, "CO-SECOND");
        holding(fund, 10000);
        // Published weights of a real fund can sum to just above 100 percent.
        unverifiedComposition(fund, row(first, "0.6"), row(second, "0.4000000001"));

        var result = calculate();

        reconciles(result);
        amount("10000", result.value(Category.COMPANY));
        amount("0", result.value(Category.UNRESOLVED));
    }

    @Test
    public void testBroadFundWithIdentifierlessRowsReconcilesAtRealisticSize()
    {
        var fund = instrument("broad-fund", AssetType.FUND, null);
        holding(fund, 1_000_000);
        var rows = new ArrayList<Constituent>();
        // 1250 identified equities plus 16 cash-like rows without identifiers,
        // the shape of a broad global fund. Weights are exact decimals.
        for (int index = 0; index < 1250; index++)
            rows.add(row(instrument("equity-" + index, AssetType.EQUITY, "CO-" + index), "0.00079936"));
        for (int index = 0; index < 16; index++)
            rows.add(new Constituent(new CompositionInstrument("Synthetic cash " + index, Map.of()), Optional.of("0.00005")));
        composition(fund, rows.toArray(Constituent[]::new));

        var result = calculate();

        reconciles(result);
        assertEquals(1266, result.contributions().size());
        assertEquals(1250, result.companies().size());
        amount("999200", result.value(Category.COMPANY));
        // Missing identifiers stay unresolved: they are not invented as cash.
        amount("800", result.value(Category.UNRESOLVED));
        amount("0", result.value(Category.CASH_OR_OTHER));
        assertEquals(16, result.contributions().stream()
                        .filter(contribution -> contribution.reason() == Reason.MISSING_RESOLUTION
                                        && contribution.resolution().isEmpty())
                        .count());
    }

    @Test
    public void testNegativeRowsWithoutIdentifierAreNettedIntoUnresolvedRemainder()
    {
        var fund = instrument("netted-fund", AssetType.FUND, null);
        var first = instrument("first-share", AssetType.EQUITY, "CO-1");
        var second = instrument("second-share", AssetType.EQUITY, "CO-2");
        var cash = new CompositionInstrument("Synthetic cash", Map.of());
        var forward = new CompositionInstrument("Synthetic forward", Map.of());
        for (var unidentified : List.of(cash, forward))
            resolutions.put(unidentified, new InstrumentResolver().resolve(unidentified, List.of(),
                            ignored -> List.of(), () -> false));
        holding(fund, 1_000_000);
        var source = composition(fund, row(first, "0.6"), row(cash, "0.0005"), row(second, "0.39965"),
                        row(forward, "-0.00015"));

        var result = calculate();

        reconciles(result);
        amount("1000000", result.total());
        assertEquals(2, result.companies().size());
        amount("600000", result.companies().get(0).total());
        amount("399650", result.companies().get(1).total());
        amount("999650", result.value(Category.COMPANY));
        amount("350", result.value(Category.UNRESOLVED));
        amount("0", result.value(Category.CASH_OR_OTHER));
        assertEquals(3, result.contributions().size());
        var netted = result.contributions().get(1);
        assertEquals(Category.UNRESOLVED, netted.category());
        assertSame(cash, netted.instrument());
        amount("350", netted.value());
        assertEquals(1, netted.path().get(0).sourceIndex());
        assertTrue(netted.path().get(0).composition().diagnostics().contains(new CompositionValidationResult.Diagnostic(
                        CompositionValidationResult.Reason.NETTED_IDENTIFIERLESS_ROWS, Optional.empty())));
        assertEquals(4, source.constituents().size());
    }
}
