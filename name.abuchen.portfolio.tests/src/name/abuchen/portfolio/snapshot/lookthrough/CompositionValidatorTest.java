package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.Diagnostic;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;

public class CompositionValidatorTest
{
    private final CompositionValidator validator = new CompositionValidator();

    private FundComposition snapshot(Denominator denominator, ExposureBasis basis, Completeness completeness,
                    List<Constituent> rows)
    {
        var fund = new CompositionInstrument("Synthetic fund", Map.of("test", "fund"));
        var provenance = new CompositionProvenance(new ProviderAccount("synthetic", "test"), fund,
                        Instant.parse("2026-01-02T12:00:00Z"), Optional.empty(), Optional.empty());
        return new FundComposition(provenance, denominator, basis, completeness, rows);
    }

    private Constituent row(String weight)
    {
        return new Constituent(new CompositionInstrument("Synthetic constituent", Map.of("test", "instrument")),
                        Optional.ofNullable(weight));
    }

    private FundComposition snapshot(String... weights)
    {
        var rows = new ArrayList<Constituent>();
        for (var weight : weights)
            rows.add(row(weight));
        return snapshot(Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, Completeness.UNKNOWN, rows);
    }

    private void assertRejected(CompositionValidationResult result, Reason reason, Optional<Integer> index)
    {
        assertFalse(result.isUsable());
        assertTrue(result.rows().isEmpty());
        assertTrue(result.shortfall().isEmpty());
        assertTrue(result.diagnostics().contains(new Diagnostic(reason, index)));
    }

    @Test
    public void testArithmeticAcceptanceKeepsUnknownSemanticsUnverified()
    {
        var source = snapshot(Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.UNKNOWN,
                        List.of(row("0.30"), row("0.50")));

        var strict = validator.validate(source);
        assertFalse(strict.isUsable());
        assertFalse(strict.semanticsVerified());

        var arithmetic = validator.validateArithmetic(source);
        assertTrue(arithmetic.isUsable());
        assertFalse(arithmetic.semanticsVerified());
        assertSame(source, arithmetic.source());
        assertEquals(2, arithmetic.rows().size());
        assertEquals(new BigDecimal("0.20"), arithmetic.shortfall().orElseThrow());
    }

    @Test
    public void testVerifiedSemanticsAreReportedIndependentlyOfArithmetic()
    {
        var verified = snapshot("0.30", "0.50");
        assertTrue(validator.validate(verified).semanticsVerified());
        assertTrue(validator.validateArithmetic(verified).semanticsVerified());

        var overfull = snapshot("0.70", "0.50");
        assertFalse(validator.validate(overfull).isUsable());
        assertTrue(validator.validate(overfull).semanticsVerified());
    }

    @Test
    public void testArithmeticRejectsMissingAndNegativeWeightsRegardlessOfSemantics()
    {
        var missing = snapshot(Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.UNKNOWN,
                        List.of(row(null)));
        assertFalse(validator.validateArithmetic(missing).isUsable());

        var negative = snapshot(Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.UNKNOWN,
                        List.of(row("-0.10")));
        assertFalse(validator.validateArithmetic(negative).isUsable());
    }

    @Test
    public void testPartialFractionsAreNotNormalized()
    {
        var source = snapshot("0.30", "0.50");
        var result = validator.validate(source);

        assertTrue(result.isUsable());
        assertTrue(result.diagnostics().isEmpty());
        assertSame(source, result.source());
        assertSame(source.provenance(), result.source().provenance());
        assertEquals(new BigDecimal("0.20"), result.shortfall().orElseThrow());
        assertEquals(new BigDecimal("0.30"), result.rows().get(0).weight());
        assertEquals(new BigDecimal("0.50"), result.rows().get(1).weight());
        assertEquals(Completeness.UNKNOWN, result.source().completeness());
    }

    @Test
    public void testTiniestPositiveShortfallIsRetainedExactly()
    {
        var result = validator.validate(snapshot("0.9999999999999999999999999999999999999999"));

        assertTrue(result.isUsable());
        assertEquals(new BigDecimal("0.0000000000000000000000000000000000000001"),
                        result.shortfall().orElseThrow());
        assertTrue(result.shortfall().orElseThrow().signum() > 0);
    }

    @Test
    public void testDecimalAdditionZeroAndScientificNotationAreExact()
    {
        var result = validator.validate(snapshot("0.1", "0.2", "7E-1", "0", "-0.00"));

        assertTrue(result.isUsable());
        assertEquals(0, result.shortfall().orElseThrow().signum());
        assertEquals(5, result.rows().size());
        assertEquals(new BigDecimal("7E-1"), result.rows().get(2).weight());
    }

    @Test
    public void testDuplicatesStayOrderedAndContributeSeparately()
    {
        var duplicate = row("0.3");
        var sameName = new Constituent(new CompositionInstrument(duplicate.instrument().label(),
                        Map.of("test", "different-instrument")), Optional.of("0.1"));
        var source = snapshot(Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, Completeness.PARTIAL,
                        List.of(duplicate, sameName, duplicate, row("0.2")));
        var result = validator.validate(source);

        assertTrue(result.isUsable());
        assertEquals(4, result.rows().size());
        assertEquals(new BigDecimal("0.1"), result.shortfall().orElseThrow());
        for (int index = 0; index < result.rows().size(); index++)
        {
            assertEquals(index, result.rows().get(index).sourceIndex());
            assertSame(source.constituents().get(index), result.rows().get(index).source());
        }
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
    }

    @Test
    public void testNormalizedTopHoldingsAndUnknownDenominatorAreRejectedEvenAtOne()
    {
        for (var denominator : List.of(Denominator.UNKNOWN, Denominator.REPORTED_HOLDINGS))
        {
            var source = snapshot(denominator, ExposureBasis.PHYSICAL_HOLDINGS, Completeness.COMPLETE,
                            List.of(row("0.6"), row("0.4")));
            var result = validator.validate(source);
            assertRejected(result, Reason.UNVERIFIED_DENOMINATOR, Optional.empty());
            assertSame(source, result.source());
        }
    }

    @Test
    public void testUnsupportedExposureSemanticsAreRejectedWithoutDeduplicating()
    {
        for (var basis : List.of(ExposureBasis.UNKNOWN, ExposureBasis.SYNTHETIC_COLLATERAL,
                        ExposureBasis.DERIVATIVE_EXPOSURE))
        {
            var duplicate = row("0.5");
            var source = snapshot(Denominator.TOTAL_FUND_NAV, basis, Completeness.COMPLETE,
                            List.of(duplicate, duplicate));
            var result = validator.validate(source);
            assertRejected(result, Reason.UNSUPPORTED_EXPOSURE_BASIS, Optional.empty());
            assertEquals(List.of(duplicate, duplicate), result.source().constituents());
        }
    }

    @Test
    public void testMissingMalformedAndNonfiniteWeightsRejectWholeSnapshot()
    {
        var invalid = new ArrayList<String>();
        invalid.add(null);
        invalid.addAll(List.of("", " ", "malformed", "NaN", "Infinity", "+Infinity", "-Infinity", "0,2", "20%"));
        for (var token : invalid)
        {
            var source = snapshot("0.4", token, "0.2");
            var result = validator.validate(source);
            assertRejected(result, token == null ? Reason.MISSING_WEIGHT : Reason.INVALID_WEIGHT, Optional.of(1));
            assertSame(source, result.source());
            assertEquals(Optional.ofNullable(token), result.source().constituents().get(1).weight());
        }
    }

    @Test
    public void testNegativeRowsAreNotDroppedEvenWhenSumIsOne()
    {
        var source = snapshot("0.7", "-0.2", "0.5");
        var result = validator.validate(source);

        assertRejected(result, Reason.NEGATIVE_WEIGHT, Optional.of(1));
        assertEquals(source.constituents(), result.source().constituents());
        assertRejected(validator.validate(snapshot("1", "-1E-40")), Reason.NEGATIVE_WEIGHT, Optional.of(1));
    }

    @Test
    public void testOverfullTotalsAreRejectedWithoutToleranceOrNormalization()
    {
        for (var source : List.of(snapshot("0.6", "0.5"), snapshot("0.6", "0.6"), snapshot("1", "0.0001"),
                        snapshot("1.1")))
        {
            var result = validator.validate(source);
            assertRejected(result, Reason.TOTAL_EXCEEDS_ONE, Optional.empty());
            assertSame(source, result.source());
        }
    }

    @Test
    public void testProviderRoundingExcessIsAcceptedWithoutNormalization()
    {
        // A public UCITS fund published weights summing to 100.00000001%.
        var source = snapshot("0.5", "0.5", "0.0000000001");
        var result = validator.validateArithmetic(source);

        assertTrue(result.isUsable());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(3, result.rows().size());
        assertEquals(new BigDecimal("0.5"), result.rows().get(0).weight());
        assertEquals(new BigDecimal("0.0000000001"), result.rows().get(2).weight());
        assertEquals(BigDecimal.ZERO, result.shortfall().orElseThrow());
    }

    @Test
    public void testAllProblemsRemainAvailableForDiagnostics()
    {
        var source = snapshot(Denominator.REPORTED_HOLDINGS, ExposureBasis.SYNTHETIC_COLLATERAL,
                        Completeness.UNKNOWN, List.of(row(null), row("NaN"), row("-0.2")));
        var result = validator.validate(source);

        assertRejected(result, Reason.UNVERIFIED_DENOMINATOR, Optional.empty());
        assertRejected(result, Reason.UNSUPPORTED_EXPOSURE_BASIS, Optional.empty());
        assertRejected(result, Reason.MISSING_WEIGHT, Optional.of(0));
        assertRejected(result, Reason.INVALID_WEIGHT, Optional.of(1));
        assertRejected(result, Reason.NEGATIVE_WEIGHT, Optional.of(2));
        assertEquals(5, result.diagnostics().size());
        assertSame(source, result.source());
    }

    @Test
    public void testEmptyRowsLeaveEntireNavUnresolved()
    {
        var result = validator.validate(snapshot());

        assertTrue(result.isUsable());
        assertTrue(result.rows().isEmpty());
        assertEquals(BigDecimal.ONE, result.shortfall().orElseThrow());
        assertEquals(Completeness.UNKNOWN, result.source().completeness());
        assertRejected(validator.validate(snapshot(Denominator.UNKNOWN, ExposureBasis.PHYSICAL_HOLDINGS,
                        Completeness.UNKNOWN, List.of())), Reason.UNVERIFIED_DENOMINATOR, Optional.empty());
    }

    @Test
    public void testCompletenessDeclarationCannotHideShortfall()
    {
        var source = snapshot(Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, Completeness.COMPLETE,
                        List.of(row("0.8")));
        var result = validator.validate(source);

        assertTrue(result.isUsable());
        assertEquals(new BigDecimal("0.2"), result.shortfall().orElseThrow());
        assertEquals(Completeness.COMPLETE, result.source().completeness());
    }

    @Test
    public void testReconciledSumDoesNotEstablishCompletenessOrIdentity()
    {
        for (var completeness : Completeness.values())
        {
            var unidentified = new Constituent(new CompositionInstrument("Unknown asset", Map.of()), Optional.of("1"));
            var source = snapshot(Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS, completeness,
                            List.of(unidentified));
            var result = validator.validate(source);

            assertTrue(result.isUsable());
            assertEquals(0, result.shortfall().orElseThrow().signum());
            assertEquals(completeness, result.source().completeness());
            assertTrue(result.rows().getFirst().source().instrument().identifiers().isEmpty());
        }
    }
}
