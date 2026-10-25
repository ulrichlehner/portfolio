package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** A terminal allocation, in exact reporting-currency minor units, before display rounding. */
public record LookThroughContribution(SecuritiesValuation.Position origin,
                Optional<InstrumentResolution> originResolution, CompositionInstrument instrument,
                Optional<InstrumentResolution> resolution, List<Edge> path, BigDecimal value, Category category,
                Reason reason, Optional<CompositionValidationResult> composition)
{
    public enum Category
    {
        COMPANY, UNGROUPED_EQUITY, CASH_OR_OTHER, UNRESOLVED,

        /**
         * A provider-supplied fund position whose allocation semantics are not
         * verified. Shown for transparency, never counted as validated coverage.
         */
        UNVERIFIED_HOLDING
    }

    public enum Reason
    {
        CLASSIFIED, MISSING_RESOLUTION, UNRESOLVED_INSTRUMENT, MISSING_COMPOSITION, UNUSABLE_COMPOSITION,
        COMPOSITION_IDENTITY_MISMATCH, NAV_SHORTFALL, CYCLE, DEPTH_LIMIT,

        /** Arithmetic-usable provider weights without verified semantics. */
        PROVIDER_WEIGHT_UNVERIFIED
    }

    /** Source occurrence indices preserve even identical legitimate rows. */
    public record Edge(CompositionValidationResult composition, int sourceIndex, BigDecimal weight,
                    Optional<InstrumentResolution> resolution)
    {
        public CompositionProvenance provenance()
        {
            return composition.source().provenance();
        }
    }

    public LookThroughContribution
    {
        path = List.copyOf(path);
    }

    public boolean isDirect()
    {
        return path.isEmpty();
    }
}
