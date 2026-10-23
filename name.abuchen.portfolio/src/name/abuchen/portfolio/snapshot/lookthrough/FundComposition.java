package name.abuchen.portfolio.snapshot.lookthrough;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable source snapshot, not a validated economic decomposition. Rows retain
 * order and duplicates. Semantic declarations require provider evidence; a sum
 * of one does not establish completeness. Unknown dates remain empty in the
 * provenance rather than being inferred from retrieval time.
 */
public record FundComposition(CompositionProvenance provenance, Denominator denominator, ExposureBasis exposureBasis,
                Completeness completeness, List<Constituent> constituents)
{
    public enum Denominator
    {
        TOTAL_FUND_NAV, REPORTED_HOLDINGS, UNKNOWN
    }

    public enum ExposureBasis
    {
        PHYSICAL_HOLDINGS, SYNTHETIC_COLLATERAL, DERIVATIVE_EXPOSURE, UNKNOWN
    }

    public enum Completeness
    {
        COMPLETE, PARTIAL, UNKNOWN
    }

    /**
     * Weight is a decimal fraction (one represents 100%), never a binary floating
     * point value. Keep the original decimal text, signed values and malformed
     * or nonfinite tokens for validation. Empty means missing. Adapters converting
     * percentages must use decimal arithmetic without rounding or normalization.
     */
    public record Constituent(CompositionInstrument instrument, Optional<String> weight)
    {
        public Constituent
        {
            Objects.requireNonNull(instrument);
            Objects.requireNonNull(weight);
        }
    }

    public FundComposition
    {
        Objects.requireNonNull(provenance);
        Objects.requireNonNull(denominator);
        Objects.requireNonNull(exposureBasis);
        Objects.requireNonNull(completeness);
        constituents = List.copyOf(constituents);
    }
}
