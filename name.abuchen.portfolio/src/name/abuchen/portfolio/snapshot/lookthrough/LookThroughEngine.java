package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Edge;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Reason;

/**
 * Deterministic calculation over precomputed evidence, with no lookup, SWT or
 * portfolio mutation. Supply one selected source/vintage per verified fund ID;
 * snapshots are never blended. Resolutions must come from evidence-backed
 * resolution, not name matching. A provenance fund must resolve to its map key.
 * Eight composition edges are permitted on each path: a leaf at edge eight is
 * usable, but a fund there cannot be expanded to edge nine. Cycles are path-local.
 * <p>
 * Verified compositions are expanded recursively. Arithmetic-usable sources with
 * unverified allocation semantics are expanded one level only and produce
 * {@link Category#UNVERIFIED_HOLDING} rows; they are displayed for transparency
 * but never count as validated coverage and are never traversed further.
 */
public final class LookThroughEngine
{
    public static final int MAX_COMPOSITION_EDGES = 8;

    public LookThroughResult calculate(SecuritiesValuation valuation,
                    Map<Security, InstrumentResolution> ownedResolutions,
                    Map<CompositionInstrument, InstrumentResolution> resolutions,
                    Map<Identifier, FundComposition> compositions)
    {
        return calculate(valuation, ownedResolutions, resolutions, compositions, Map.of());
    }

    /**
     * @param compositions
     *            verified sources, expanded recursively
     * @param unverifiedCompositions
     *            arithmetic-usable sources without verified semantics, expanded
     *            one level and labelled unverified
     */
    public LookThroughResult calculate(SecuritiesValuation valuation,
                    Map<Security, InstrumentResolution> ownedResolutions,
                    Map<CompositionInstrument, InstrumentResolution> resolutions,
                    Map<Identifier, FundComposition> compositions,
                    Map<Identifier, FundComposition> unverifiedCompositions)
    {
        var traversal = new Traversal(Map.copyOf(resolutions), Map.copyOf(compositions),
                        Map.copyOf(unverifiedCompositions));
        for (var position : valuation.positions())
        {
            var resolution = Optional.ofNullable(ownedResolutions.get(position.security()));
            var instrument = resolution.map(InstrumentResolution::source)
                            .orElseGet(() -> new CompositionInstrument(
                                            Objects.toString(position.security().getName(), ""), Map.of())); //$NON-NLS-1$
            traversal.visit(position, resolution, instrument, resolution, BigDecimal.valueOf(position.value().getAmount()),
                            List.of(), Set.of());
        }
        return new LookThroughResult(valuation, traversal.contributions);
    }

    private static final class Traversal
    {
        private final Map<CompositionInstrument, InstrumentResolution> resolutions;
        private final Map<Identifier, FundComposition> compositions;
        private final Map<Identifier, FundComposition> unverifiedCompositions;
        private final Map<Identifier, CompositionValidationResult> validations = new HashMap<>();
        private final List<LookThroughContribution> contributions = new ArrayList<>();
        private final CompositionValidator validator = new CompositionValidator();

        private Traversal(Map<CompositionInstrument, InstrumentResolution> resolutions,
                        Map<Identifier, FundComposition> compositions,
                        Map<Identifier, FundComposition> unverifiedCompositions)
        {
            this.resolutions = resolutions;
            this.compositions = compositions;
            this.unverifiedCompositions = unverifiedCompositions;
        }

        private void visit(SecuritiesValuation.Position origin, Optional<InstrumentResolution> originResolution,
                        CompositionInstrument instrument, Optional<InstrumentResolution> resolution, BigDecimal value,
                        List<Edge> path, Set<Identifier> ancestors)
        {
            if (resolution.isEmpty())
            {
                add(origin, originResolution, instrument, resolution, value, path, Category.UNRESOLVED,
                                Reason.MISSING_RESOLUTION, Optional.empty());
                return;
            }
            var resolved = resolution.orElseThrow();
            if (resolved.state() != InstrumentResolution.State.FUND)
            {
                var category = switch (resolved.state())
                {
                    case GROUPED_EQUITY -> Category.COMPANY;
                    case UNGROUPED_EQUITY -> Category.UNGROUPED_EQUITY;
                    case CASH_OR_OTHER -> Category.CASH_OR_OTHER;
                    default -> Category.UNRESOLVED;
                };
                add(origin, originResolution, instrument, resolution, value, path, category,
                                category == Category.UNRESOLVED ? Reason.UNRESOLVED_INSTRUMENT : Reason.CLASSIFIED,
                                Optional.empty());
                return;
            }

            var identity = resolved.instrument().orElseThrow();
            Reason failure = null;
            Optional<CompositionValidationResult> validation = Optional.empty();
            boolean verified = false;
            if (ancestors.contains(identity))
                failure = Reason.CYCLE;
            else if (path.size() >= MAX_COMPOSITION_EDGES)
                failure = Reason.DEPTH_LIMIT;
            else if (compositions.containsKey(identity) || unverifiedCompositions.containsKey(identity))
            {
                verified = compositions.containsKey(identity);
                var composition = verified ? compositions.get(identity) : unverifiedCompositions.get(identity);
                if (!verified && (composition.exposureBasis() == FundComposition.ExposureBasis.SYNTHETIC_COLLATERAL
                                || composition.exposureBasis() == FundComposition.ExposureBasis.DERIVATIVE_EXPOSURE))
                {
                    // Synthetic or derivative exposure is never expanded as if it
                    // were physical holdings, even when weights are arithmetic.
                    failure = Reason.UNUSABLE_COMPOSITION;
                }
                else
                {
                    var cached = validations.get(identity);
                    if (cached == null)
                    {
                        cached = verified ? validator.validate(composition)
                                        : validator.validateArithmetic(composition);
                        validations.put(identity, cached);
                    }
                    validation = Optional.of(cached);
                    if (!provenanceMatches(composition, verified, instrument, identity))
                        failure = Reason.COMPOSITION_IDENTITY_MISMATCH;
                    else if (!validation.orElseThrow().isUsable())
                        failure = Reason.UNUSABLE_COMPOSITION;
                }
            }
            else
                failure = Reason.MISSING_COMPOSITION;

            if (failure != null)
            {
                add(origin, originResolution, instrument, resolution, value, path, Category.UNRESOLVED, failure,
                                validation);
                return;
            }

            var validated = validation.orElseThrow();
            var nextAncestors = new HashSet<>(ancestors);
            nextAncestors.add(identity);
            for (var row : validated.rows())
            {
                var child = row.source().instrument();
                var childResolution = Optional.ofNullable(resolutions.get(child));
                var nextPath = new ArrayList<>(path);
                nextPath.add(new Edge(validated, row.sourceIndex(), row.weight(), childResolution));
                visit(origin, originResolution, child, childResolution, value.multiply(row.weight()), nextPath,
                                nextAncestors);
            }
            var shortfall = validated.shortfall().orElseThrow();
            if (shortfall.signum() > 0)
                add(origin, originResolution, instrument, resolution, value.multiply(shortfall), path,
                                Category.UNRESOLVED, Reason.NAV_SHORTFALL, validation);
        }

        /**
         * A verified source must resolve through its provenance fund to the same
         * instrument. An unverified source is matched by its own fund identifiers:
         * it is the identifier that the provider answered.
         */
        private boolean provenanceMatches(FundComposition composition, boolean verified,
                        CompositionInstrument instrument, Identifier identity)
        {
            if (!verified)
                return composition.provenance().fund().identifiers().equals(instrument.identifiers());
            var fund = resolutions.get(composition.provenance().fund());
            return fund != null && fund.state() == InstrumentResolution.State.FUND
                            && fund.instrument().equals(Optional.of(identity));
        }

        private void add(SecuritiesValuation.Position origin, Optional<InstrumentResolution> originResolution,
                        CompositionInstrument instrument, Optional<InstrumentResolution> resolution, BigDecimal value,
                        List<Edge> path, Category category, Reason reason,
                        Optional<CompositionValidationResult> composition)
        {
            contributions.add(new LookThroughContribution(origin, originResolution, instrument, resolution, path, value,
                            category, reason, composition));
        }
    }
}
