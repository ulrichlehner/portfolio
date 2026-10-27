package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Snapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
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
final class LookThroughAnalysis
{
    LookThroughResult calculate(ClientSnapshot snapshot, ExchangeRateProviderFactory rates, List<Security> securities,
                    List<Snapshot> retained, List<InstrumentEvidence> heldClassifications)
    {
        var valuation = new SecuritiesValuationAdapter(rates).value(snapshot, securities);
        var evidence = new ArrayList<InstrumentEvidence>(heldClassifications);
        retained.forEach(value -> value.resolutions().stream()
                        .forEach(resolution -> evidence.addAll(resolution.evidence())));
        retained.forEach(value -> source(value).ifPresent(composition -> evidence.add(
                        InstrumentEvidence.fundFromComposition(composition))));

        var resolver = new InstrumentResolver();
        var lookups = lookups(retained);
        var resolutions = new HashMap<CompositionInstrument, InstrumentResolution>();
        var owned = new HashMap<Security, InstrumentResolution>();
        for (var position : valuation.positions())
        {
            var instrument = LookThroughCompositionService.instrumentOf(position.security());
            var resolved = resolver.resolve(instrument, evidence, ignored -> List.of(), () -> false);
            resolutions.put(instrument, resolved);
            owned.put(position.security(), resolved);
        }

        var compositions = new HashMap<Identifier, FundComposition>();
        var unverifiedCompositions = new HashMap<Identifier, FundComposition>();
        for (var data : retained)
        {
            var verified = data.usableComposition();
            var supplied = source(data);
            if (supplied.isEmpty())
                continue;
            var composition = supplied.orElseThrow();
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
            {
                var resolution = resolver.resolve(row.instrument(), evidence, ignored -> List.of(), () -> false);
                var lookup = lookups.get(row.instrument().identifiers());
                resolutions.put(row.instrument(), lookup != null && isOpen(resolution) ? lookup : resolution);
            }
        }
        return new LookThroughEngine().calculate(valuation, Map.copyOf(owned), Map.copyOf(resolutions),
                        Map.copyOf(compositions), Map.copyOf(unverifiedCompositions));
    }

    /**
     * One line of counts for the log: distinct constituents resolved, assumed
     * to be companies and unresolved by reason, and by lookup status. Never
     * names, identifiers or amounts.
     */
    static String diagnostics(LookThroughResult result)
    {
        var distinct = new HashMap<Map<String, String>, InstrumentResolution>();
        for (var contribution : result.contributions())
            contribution.resolution()
                            .ifPresent(value -> distinct.putIfAbsent(contribution.instrument().identifiers(), value));
        int resolved = 0;
        int assumed = 0;
        var reasons = new TreeMap<InstrumentResolution.Reason, Integer>();
        var statuses = new TreeMap<InstrumentResolution.LookupStatus, Integer>();
        for (var value : distinct.values())
        {
            if (value.reason() == InstrumentResolution.Reason.ASSUMED_COMPANY)
                assumed++;
            else if (value.state() == State.UNRESOLVED)
                reasons.merge(value.reason(), 1, Integer::sum);
            else
                resolved++;
            statuses.merge(value.lookupStatus(), 1, Integer::sum);
        }
        return "Breakdown classification: resolved=" + resolved + ", assumed=" + assumed + ", unresolved by reason "
                        + reasons + ", lookup status " + statuses;
    }

    /**
     * The outcome of the classification lookup of each constituent, kept by
     * the refresh. Evidence alone cannot tell an answered lookup from one that
     * failed or was not made, nor carries the assumption of a company.
     */
    private Map<Map<String, String>, InstrumentResolution> lookups(List<Snapshot> retained)
    {
        var result = new HashMap<Map<String, String>, InstrumentResolution>();
        for (var value : retained)
        {
            for (var resolution : value.resolutions())
            {
                var key = resolution.source().identifiers();
                var previous = result.get(key);
                if (previous == null || rank(resolution) > rank(previous))
                    result.put(key, resolution);
            }
        }
        return result;
    }

    private static int rank(InstrumentResolution resolution)
    {
        if (resolution.reason() == InstrumentResolution.Reason.ASSUMED_COMPANY)
            return 3;
        return switch (resolution.lookupStatus())
        {
            case ATTEMPTED -> 2;
            case FAILED -> 1;
            default -> 0;
        };
    }

    /** Without evidence of its own, only the outcome of the lookup tells why. */
    private static boolean isOpen(InstrumentResolution resolution)
    {
        return resolution.state() == State.UNRESOLVED
                        && (resolution.reason() == InstrumentResolution.Reason.MISSING_IDENTITY
                                        || resolution.reason() == InstrumentResolution.Reason.UNKNOWN_CLASSIFICATION);
    }

    private Optional<FundComposition> source(Snapshot data)
    {
        // Preserve invalid source metadata too: the engine must distinguish an
        // unusable composition from one the provider never supplied. An older
        // retained usable source still takes precedence over a failed refresh.
        return data.usableComposition().or(data::unverifiedComposition)
                        .or(() -> data.latestAttempt().flatMap(CompositionResult::composition));
    }
}
