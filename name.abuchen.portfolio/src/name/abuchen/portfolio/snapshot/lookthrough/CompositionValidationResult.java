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
 * <p>
 * A result can be arithmetically usable while its allocation semantics stay
 * unverified. Such a source may be displayed as an unverified decomposition, but
 * it is never validated coverage: neither its rows nor a reconciled sum prove
 * the denominator, the exposure basis or completeness.
 */
public final class CompositionValidationResult
{
    public enum Reason
    {
        UNVERIFIED_DENOMINATOR, UNSUPPORTED_EXPOSURE_BASIS, MISSING_WEIGHT, INVALID_WEIGHT, NEGATIVE_WEIGHT,
        TOTAL_EXCEEDS_ONE,
        /**
         * Informational: rows without a security identifier, at least one of
         * them negative, were replaced by one nonnegative netted row that stays
         * unresolved. The source rows are unchanged.
         */
        NETTED_IDENTIFIERLESS_ROWS;

        /** A fatal reason makes the result unusable. */
        public boolean isFatal()
        {
            return this != NETTED_IDENTIFIERLESS_ROWS;
        }
    }

    /** Empty row index denotes a snapshot-level diagnostic; indices are zero-based. */
    public record Diagnostic(Reason reason, Optional<Integer> sourceIndex)
    {
        public boolean isFatal()
        {
            return reason.isFatal();
        }
    }

    /** Separate source occurrences stay separate, including identical rows. */
    public record ValidatedRow(int sourceIndex, Constituent source, BigDecimal weight)
    {
    }

    private final FundComposition source;
    private final List<ValidatedRow> rows;
    private final Optional<BigDecimal> shortfall;
    private final List<Diagnostic> diagnostics;
    private final boolean semanticsVerified;

    CompositionValidationResult(FundComposition source, List<ValidatedRow> rows, Optional<BigDecimal> shortfall,
                    List<Diagnostic> diagnostics, boolean semanticsVerified)
    {
        this.source = source;
        this.rows = List.copyOf(rows);
        this.shortfall = shortfall;
        this.diagnostics = List.copyOf(diagnostics);
        this.semanticsVerified = semanticsVerified;
    }

    public FundComposition source()
    {
        return source;
    }

    /** Usable when no diagnostic is fatal; informational ones are allowed. */
    public boolean isUsable()
    {
        return diagnostics.stream().noneMatch(Diagnostic::isFatal);
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

    /** All diagnostics, including informational ones. */
    public List<Diagnostic> diagnostics()
    {
        return diagnostics;
    }

    /** Only the diagnostics that make the result unusable. */
    public List<Diagnostic> fatalDiagnostics()
    {
        return diagnostics.stream().filter(Diagnostic::isFatal).toList();
    }

    /**
     * True when the provider declarations allow treating the rows as validated
     * fractions of total fund NAV with physical-holdings semantics. Independent
     * of the arithmetic outcome; only an affirmative declaration counts.
     */
    public boolean semanticsVerified()
    {
        return semanticsVerified;
    }
}
