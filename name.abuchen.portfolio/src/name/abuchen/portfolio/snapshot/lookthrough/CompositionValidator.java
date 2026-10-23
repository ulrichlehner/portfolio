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
 * Accepts only nonnegative, unleveraged fractions of total NAV with verified
 * physical-holdings semantics. Provider declarations require independent
 * evidence; neither a reconciled sum nor completeness metadata verifies them.
 * All rows share the snapshot's semantics and are never merged by name or ID.
 */
public final class CompositionValidator
{
    public CompositionValidationResult validate(FundComposition source)
    {
        Objects.requireNonNull(source);
        var diagnostics = new ArrayList<Diagnostic>();
        if (source.denominator() != Denominator.TOTAL_FUND_NAV)
            diagnostics.add(new Diagnostic(Reason.UNVERIFIED_DENOMINATOR, Optional.empty()));
        if (source.exposureBasis() != ExposureBasis.PHYSICAL_HOLDINGS)
            diagnostics.add(new Diagnostic(Reason.UNSUPPORTED_EXPOSURE_BASIS, Optional.empty()));

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
            return new CompositionValidationResult(source, List.of(), Optional.empty(), diagnostics);
        return new CompositionValidationResult(source, rows, Optional.of(BigDecimal.ONE.subtract(total)), diagnostics);
    }
}
