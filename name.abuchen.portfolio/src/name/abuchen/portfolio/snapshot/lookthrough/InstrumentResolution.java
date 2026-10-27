package name.abuchen.portfolio.snapshot.lookthrough;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;

/**
 * UNGROUPED means identified, classified equity (including depositary receipts)
 * without verified company grouping. UNRESOLVED means identity or classification
 * is missing or conflicting. Neither is verified company exposure. FUND is a
 * traversal candidate, not cash/other or an already resolved exposure: composition
 * usability, exposure basis, cycles and depth must still be checked by the engine.
 */
public record InstrumentResolution(CompositionInstrument source, State state, Reason reason,
                Optional<Identifier> instrument, Optional<AssetType> classification, Optional<Identifier> company,
                List<InstrumentEvidence> evidence, LookupStatus lookupStatus)
{
    public enum State
    {
        GROUPED_EQUITY, UNGROUPED_EQUITY, CASH_OR_OTHER, FUND, UNRESOLVED
    }

    public enum Reason
    {
        VERIFIED, MISSING_IDENTIFIERS, MISSING_IDENTITY, AMBIGUOUS_IDENTITY, UNKNOWN_CLASSIFICATION,
        AMBIGUOUS_CLASSIFICATION, MISSING_COMPANY, AMBIGUOUS_COMPANY,

        /**
         * Not verified: a constituent with an ISIN for which the classification
         * lookup found nothing, and whose name does not indicate a fund, cash,
         * a bond or a derivative, is assumed to be a listed company share.
         */
        ASSUMED_COMPANY
    }

    public enum LookupStatus
    {
        NOT_NEEDED, NO_IDENTIFIERS, BUDGET_EXHAUSTED, ATTEMPTED, FAILED
    }

    public InstrumentResolution
    {
        Objects.requireNonNull(source);
        Objects.requireNonNull(state);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(instrument);
        Objects.requireNonNull(classification);
        Objects.requireNonNull(company);
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(lookupStatus);
    }
}
