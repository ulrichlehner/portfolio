package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.Diagnostic;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.ValidatedRow;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;

/**
 * Two explicit levels of acceptance:
 * <ul>
 * <li>{@link #validate(FundComposition)} requires nonnegative, unleveraged
 * fractions of total NAV with verified physical-holdings semantics. This is the
 * only level that counts as validated coverage.</li>
 * <li>{@link #validateArithmetic(FundComposition)} checks the same numerical
 * rules but not the allocation semantics. Its rows may be displayed as an
 * unverified decomposition; they never become validated coverage and never
 * close the provider evidence gate.</li>
 * </ul>
 * All rows share the snapshot's semantics and are never merged by name or ID.
 * The only exception: rows without a security identifier are netted into one
 * unresolved row when at least one of them is negative and their net is not.
 * Neither a reconciled sum nor completeness metadata verifies the declarations.
 */
public final class CompositionValidator
{
    /**
     * Published weights are rounded decimals and their sum can exceed 100% by a
     * rounding artefact. A public UCITS fund returned 100.00000001%. Accept up
     * to one millionth above one so a complete fund is not rejected for a
     * publishing artefact. Rows are never scaled; larger overfull totals remain
     * unusable. Any further tolerance needs new evidence.
     */
    private static final BigDecimal ROUNDING_EXCESS_TOLERANCE = new BigDecimal("0.000001");

    public CompositionValidationResult validate(FundComposition source)
    {
        Objects.requireNonNull(source);
        var diagnostics = semanticsDiagnostics(source);
        return check(source, diagnostics);
    }

    /**
     * Numerical acceptance only. Semantics stay unverified; a caller must label
     * the result as such and keep coverage partial.
     */
    public CompositionValidationResult validateArithmetic(FundComposition source)
    {
        Objects.requireNonNull(source);
        return check(source, new ArrayList<>());
    }

    private List<Diagnostic> semanticsDiagnostics(FundComposition source)
    {
        var diagnostics = new ArrayList<Diagnostic>();
        if (source.denominator() != Denominator.TOTAL_FUND_NAV)
            diagnostics.add(new Diagnostic(Reason.UNVERIFIED_DENOMINATOR, Optional.empty()));
        if (source.exposureBasis() != ExposureBasis.PHYSICAL_HOLDINGS)
            diagnostics.add(new Diagnostic(Reason.UNSUPPORTED_EXPOSURE_BASIS, Optional.empty()));
        return diagnostics;
    }

    private CompositionValidationResult check(FundComposition source, List<Diagnostic> diagnostics)
    {
        boolean semanticsVerified = semanticsDiagnostics(source).isEmpty();
        List<ValidatedRow> rows = new ArrayList<>();
        var total = BigDecimal.ZERO;
        var identifierlessNet = BigDecimal.ZERO;
        boolean negativeIdentifierless = false;
        boolean allWeightsPresent = true;
        for (int index = 0; index < source.constituents().size(); index++)
        {
            var row = source.constituents().get(index);
            if (row.weight().isEmpty())
            {
                diagnostics.add(new Diagnostic(Reason.MISSING_WEIGHT, Optional.of(index)));
                allWeightsPresent = false;
                continue;
            }

            BigDecimal weight;
            try
            {
                // BigDecimal rejects malformed and nonfinite tokens alike.
                weight = new BigDecimal(row.weight().orElseThrow());
            }
            catch (NumberFormatException e)
            {
                diagnostics.add(new Diagnostic(Reason.INVALID_WEIGHT, Optional.of(index)));
                allWeightsPresent = false;
                continue;
            }

            if (isIdentifierless(row))
            {
                identifierlessNet = identifierlessNet.add(weight);
                negativeIdentifierless |= weight.signum() < 0;
            }
            else if (weight.signum() < 0)
            {
                diagnostics.add(new Diagnostic(Reason.NEGATIVE_WEIGHT, Optional.of(index)));
            }
            total = total.add(weight);
            rows.add(new ValidatedRow(index, row, weight));
        }

        // Negative rows without a security identifier (for example cash
        // balances or currency forwards) are netted against the other rows
        // without an identifier. Only a negative net rejects. The rows are
        // never classified by name, and identified rows keep their weights.
        boolean netted = false;
        if (negativeIdentifierless)
        {
            if (identifierlessNet.signum() < 0)
            {
                diagnostics.add(new Diagnostic(Reason.NEGATIVE_WEIGHT, Optional.empty()));
            }
            else
            {
                rows = netIdentifierless(rows, identifierlessNet);
                netted = true;
            }
        }

        if (allWeightsPresent && total.compareTo(BigDecimal.ONE.add(ROUNDING_EXCESS_TOLERANCE)) > 0)
            diagnostics.add(new Diagnostic(Reason.TOTAL_EXCEEDS_ONE, Optional.empty()));

        if (diagnostics.stream().anyMatch(Diagnostic::isFatal))
            return new CompositionValidationResult(source, List.of(), Optional.empty(), diagnostics,
                            semanticsVerified);
        if (netted)
            diagnostics.add(new Diagnostic(Reason.NETTED_IDENTIFIERLESS_ROWS, Optional.empty()));
        var shortfall = BigDecimal.ONE.subtract(total);
        if (shortfall.signum() < 0)
        {
            // Accepted publishing-rounding excess. Remove it proportionally so the
            // expansion reconciles exactly with the fund value. The correction is
            // at most one millionth and source rows stay unchanged; genuine
            // overfull data was rejected above and is never rescaled.
            var scale = BigDecimal.ONE.divide(total, MathContext.DECIMAL128);
            var scaled = new ArrayList<ValidatedRow>(rows.size());
            var sum = BigDecimal.ZERO;
            for (var row : rows)
            {
                var weight = row.weight().multiply(scale);
                sum = sum.add(weight);
                scaled.add(new ValidatedRow(row.sourceIndex(), row.source(), weight));
            }
            var largest = 0;
            for (int index = 1; index < scaled.size(); index++)
                if (scaled.get(index).weight().compareTo(scaled.get(largest).weight()) > 0)
                    largest = index;
            var residual = BigDecimal.ONE.subtract(sum);
            var adjusted = scaled.get(largest);
            scaled.set(largest,
                            new ValidatedRow(adjusted.sourceIndex(), adjusted.source(), adjusted.weight().add(residual)));
            rows = scaled;
            shortfall = BigDecimal.ZERO;
        }
        return new CompositionValidationResult(source, rows, Optional.of(shortfall), diagnostics,
                        semanticsVerified);
    }

    /** A row without any security identifier, independent of its label. */
    private static boolean isIdentifierless(Constituent row)
    {
        return row.instrument().identifiers().isEmpty();
    }

    /**
     * Replaces all rows without an identifier by one row with the net weight,
     * at the position of the first row with the largest positive weight, which
     * stays its source for provenance. A zero net leaves no row. The sum of
     * all rows is unchanged.
     */
    private static List<ValidatedRow> netIdentifierless(List<ValidatedRow> rows, BigDecimal net)
    {
        ValidatedRow representative = null;
        for (var row : rows)
            if (isIdentifierless(row.source()) && row.weight().signum() > 0
                            && (representative == null || row.weight().compareTo(representative.weight()) > 0))
                representative = row;

        var result = new ArrayList<ValidatedRow>(rows.size());
        for (var row : rows)
        {
            if (!isIdentifierless(row.source()))
                result.add(row);
            else if (row == representative && net.signum() > 0)
                result.add(new ValidatedRow(row.sourceIndex(), row.source(), net));
        }
        return result;
    }
}
