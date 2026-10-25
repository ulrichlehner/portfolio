package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Snapshot;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.State;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolver;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughEngine;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuationAdapter;

/**
 * Connects PP valuation and retained provider evidence to the pure calculator.
 * A provider answer is itself fund-classification evidence: it returned an ETF
 * holdings list for exactly this identifier. Arithmetic-usable answers without
 * verified semantics are passed separately and stay labelled unverified.
 */
final class PortfolioLensAnalysis
{
    LookThroughResult calculate(ClientSnapshot snapshot, ExchangeRateProviderFactory rates, List<Security> securities,
                    List<Snapshot> retained)
    {
        var valuation = new SecuritiesValuationAdapter(rates).value(snapshot, securities);
        var evidence = new ArrayList<InstrumentEvidence>();
        retained.forEach(value -> value.resolutions().stream()
                        .forEach(resolution -> evidence.addAll(resolution.evidence())));
        retained.forEach(value -> {
            value.usableComposition().ifPresent(composition -> evidence.add(
                            InstrumentEvidence.fundFromComposition(composition)));
            value.unverifiedComposition().ifPresent(composition -> evidence.add(
                            InstrumentEvidence.fundFromComposition(composition)));
        });

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
        var unverifiedCompositions = new HashMap<Identifier, FundComposition>();
        for (var data : retained)
        {
            var verified = data.usableComposition();
            var unverified = data.unverifiedComposition();
            var source = verified.or(() -> unverified);
            if (source.isEmpty())
                continue;
            var composition = source.orElseThrow();
            var fund = composition.provenance().fund();
            var resolved = resolver.resolve(fund, evidence, ignored -> List.of(), () -> false);
            resolutions.put(fund, resolved);
            if (resolved.state() == State.FUND)
            {
                var identity = resolved.instrument().orElseThrow();
                if (verified.isPresent())
                    compositions.put(identity, composition);
                else
                    unverifiedCompositions.put(identity, composition);
            }
            for (var row : composition.constituents())
                resolutions.put(row.instrument(),
                                resolver.resolve(row.instrument(), evidence, ignored -> List.of(), () -> false));
        }
        return new LookThroughEngine().calculate(valuation, Map.copyOf(owned), Map.copyOf(resolutions),
                        Map.copyOf(compositions), Map.copyOf(unverifiedCompositions));
    }
}
