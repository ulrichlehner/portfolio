package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;

/**
 * Numerical and allocation-semantic validation only, not classification or proof
 * of completeness. The source and its provenance remain available even on
 * rejection. Unusable results expose no calculation rows or numerical shortfall:
 * the entire fund must remain unresolved. Diagnostics contain no source payload.
 */
public final class CompositionValidationResult
{
    public enum Reason
    {
        UNVERIFIED_DENOMINATOR, UNSUPPORTED_EXPOSURE_BASIS, MISSING_WEIGHT, INVALID_WEIGHT, NEGATIVE_WEIGHT,
        TOTAL_EXCEEDS_ONE
    }

    /** Empty row index denotes a snapshot-level diagnostic; indices are zero-based. */
    public record Diagnostic(Reason reason, Optional<Integer> sourceIndex)
    {
    }

    /** Separate source occurrences stay separate, including identical rows. */
    public record ValidatedRow(int sourceIndex, Constituent source, BigDecimal weight)
    {
    }

    private final FundComposition source;
    private final List<ValidatedRow> rows;
    private final Optional<BigDecimal> shortfall;
    private final List<Diagnostic> diagnostics;

    CompositionValidationResult(FundComposition source, List<ValidatedRow> rows, Optional<BigDecimal> shortfall,
                    List<Diagnostic> diagnostics)
    {
        this.source = source;
        this.rows = List.copyOf(rows);
        this.shortfall = shortfall;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public FundComposition source()
    {
        return source;
    }

    public boolean isUsable()
    {
        return diagnostics.isEmpty();
    }

    public List<ValidatedRow> rows()
    {
        return rows;
    }

    /** Exact one minus the validated sum, including zero; never display-rounded. */
    public Optional<BigDecimal> shortfall()
    {
        return shortfall;
    }

    public List<Diagnostic> diagnostics()
    {
        return diagnostics;
    }
}
