package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.Diagnostic;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidationResult.ValidatedRow;
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
 * Neither a reconciled sum nor completeness metadata verifies the declarations.
 */
public final class CompositionValidator
{
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
        var rows = new ArrayList<ValidatedRow>();
        var total = BigDecimal.ZERO;
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

            if (weight.signum() < 0)
                diagnostics.add(new Diagnostic(Reason.NEGATIVE_WEIGHT, Optional.of(index)));
            total = total.add(weight);
            rows.add(new ValidatedRow(index, row, weight));
        }

        if (allWeightsPresent && total.compareTo(BigDecimal.ONE) > 0)
            diagnostics.add(new Diagnostic(Reason.TOTAL_EXCEEDS_ONE, Optional.empty()));

        if (!diagnostics.isEmpty())
            return new CompositionValidationResult(source, List.of(), Optional.empty(), diagnostics,
                            semanticsVerified);
        return new CompositionValidationResult(source, rows, Optional.of(BigDecimal.ONE.subtract(total)), diagnostics,
                        semanticsVerified);
    }
}
