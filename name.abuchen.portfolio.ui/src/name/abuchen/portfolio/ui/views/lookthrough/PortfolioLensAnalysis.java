package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Snapshot;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.State;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolver;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughEngine;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuationAdapter;

/** Connects PP valuation and retained provider evidence to the pure calculator. */
final class PortfolioLensAnalysis
{
    LookThroughResult calculate(ClientSnapshot snapshot, ExchangeRateProviderFactory rates, List<Security> selection,
                    List<Snapshot> retained)
    {
        var valuation = new SecuritiesValuationAdapter(rates).value(snapshot, selection);
        var evidence = retained.stream().flatMap(value -> value.resolutions().stream())
                        .flatMap(value -> value.evidence().stream()).distinct().toList();
        var resolver = new InstrumentResolver();
        var resolutions = new HashMap<CompositionInstrument, InstrumentResolution>();
        var owned = new HashMap<Security, InstrumentResolution>();
        for (var position : valuation.positions())
        {
            var instrument = PortfolioLensCompositionService.instrumentOf(position.security());
            var resolved = resolver.resolve(instrument, evidence, ignored -> List.of(), () -> false);
            resolutions.put(instrument, resolved);
            owned.put(position.security(), resolved);
        }

        var compositions = new HashMap<Identifier, FundComposition>();
        for (var data : retained)
        {
            if (data.usableComposition().isEmpty())
                continue;
            var composition = data.usableComposition().orElseThrow();
            var fund = composition.provenance().fund();
            var resolved = resolver.resolve(fund, evidence, ignored -> List.of(), () -> false);
            resolutions.put(fund, resolved);
            if (resolved.state() == State.FUND)
                compositions.put(resolved.instrument().orElseThrow(), composition);
            for (var row : composition.constituents())
                resolutions.put(row.instrument(),
                                resolver.resolve(row.instrument(), evidence, ignored -> List.of(), () -> false));
        }
        return new LookThroughEngine().calculate(valuation, Map.copyOf(owned), Map.copyOf(resolutions),
                        Map.copyOf(compositions));
    }
}
