package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;

/**
 * The memory-only inputs of one breakdown calculation: the held funds, their
 * retained compositions and the current classification evidence. Compared to
 * skip a recalculation when nothing changed, and stored with a cached result
 * to describe what that result was calculated from.
 */
record LookThroughInputs(List<FundRef> funds, List<CompositionRefreshCoordinator.Snapshot> retained,
                List<InstrumentEvidence> classifications)
{
    LookThroughInputs
    {
        Objects.requireNonNull(funds);
        Objects.requireNonNull(retained);
        Objects.requireNonNull(classifications);
        funds = List.copyOf(funds);
        retained = List.copyOf(retained);
        classifications = List.copyOf(classifications);
    }
}
