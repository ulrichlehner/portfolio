package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Position;

@SuppressWarnings("nls")
public class FundOverlapTest
{
    private static final LocalDate DATE = LocalDate.parse("2026-01-31");
    private static final Instant RETRIEVED = Instant.parse("2026-02-01T12:00:00Z");
    private static final ProviderAccount SOURCE = new ProviderAccount("synthetic", "test");

    private final Map<Security, InstrumentResolution> owned = new HashMap<>();
    private final Map<CompositionInstrument, InstrumentResolution> resolutions = new HashMap<>();
    private final Map<Identifier, FundComposition> compositions = new HashMap<>();
    private final List<Position> positions = new ArrayList<>();

    private CompositionInstrument instrument(String id, AssetType type, String company)
    {
        var instrument = new CompositionInstrument("Synthetic " + id, Map.of("synthetic:instrument", id));
        var provenance = new Provenance(SOURCE, "record-" + id, RETRIEVED);
        var evidence = new InstrumentEvidence(instrument.identifiers(),
                        new Assertion<>(new Identifier("synthetic:instrument", id), provenance),
                        Optional.ofNullable(type).map(value -> new Assertion<>(value, provenance)),
                        Optional.ofNullable(company).map(value -> new Assertion<>(
                                        new Identifier("synthetic:company", value), provenance)));
        resolutions.put(instrument, new InstrumentResolver().resolve(instrument, List.of(evidence), ignored -> {
            throw new AssertionError("No network or enrichment during calculation");
        }, () -> false));
        return instrument;
    }

    private Security holding(String name, CompositionInstrument instrument, long amount)
    {
        var security = new Security(name, "EUR");
        positions.add(new Position(security, 1000000, Money.of("EUR", amount)));
        owned.put(security, resolutions.get(instrument));
        return security;
    }

    private Constituent row(CompositionInstrument instrument, String weight)
    {
        return new Constituent(instrument, Optional.of(weight));
    }

    private void composition(CompositionInstrument fund, Constituent... rows)
    {
        compositions.put(resolutions.get(fund).instrument().orElseThrow(),
                        new FundComposition(new CompositionProvenance(SOURCE, fund, RETRIEVED, Optional.empty(),
                                        Optional.empty()), Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS,
                                        Completeness.UNKNOWN, List.of(rows)));
    }

    private FundOverlap calculate()
    {
        long total = positions.stream().mapToLong(position -> position.value().getAmount()).sum();
        var valuation = new SecuritiesValuation(DATE, positions, List.of(), Money.of("EUR", total));
        return FundOverlap.of(new LookThroughEngine().calculate(valuation, owned, resolutions, compositions,
                        Map.of()));
    }

    private void amount(String expected, BigDecimal actual)
    {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    public void testSumOfSmallerWeightsAndSharedCount()
    {
        var x = instrument("x", AssetType.EQUITY, "company-x");
        var y = instrument("y", AssetType.EQUITY, "company-y");
        var a = instrument("fund-a", AssetType.FUND, null);
        var b = instrument("fund-b", AssetType.FUND, null);
        holding("Fund A", a, 20000);
        holding("Fund B", b, 10000);
        composition(a, row(x, "0.05"), row(y, "0.02"));
        composition(b, row(x, "0.03"), row(y, "0.04"));

        var overlap = calculate();

        assertEquals(2, overlap.funds().size());
        assertEquals("Fund A", overlap.funds().get(0).name());
        var pair = overlap.pair(0, 1);
        amount("0.05", pair.overlap());
        assertEquals(2, pair.sharedCount());
        // X contributes 0.03, Y 0.02
        assertEquals("Synthetic x", pair.shared().get(0).label());
        amount("0.03", pair.shared().get(0).overlap());
        amount("0.02", pair.shared().get(1).overlap());
        assertEquals(pair, overlap.pair(1, 0));
        assertTrue(overlap.excluded().isEmpty());
    }

    @Test
    public void testFundWithoutCompositionIsExcluded()
    {
        var x = instrument("x", AssetType.EQUITY, "company-x");
        var a = instrument("fund-a", AssetType.FUND, null);
        var b = instrument("fund-b", AssetType.FUND, null);
        var missing = instrument("fund-missing", AssetType.FUND, null);
        holding("Fund A", a, 10000);
        holding("Fund B", b, 10000);
        var excluded = holding("Fund Missing", missing, 10000);
        composition(a, row(x, "0.5"));
        composition(b, row(x, "0.25"));

        var overlap = calculate();

        assertEquals(2, overlap.funds().size());
        assertEquals(List.of(excluded), overlap.excluded());
        amount("0.25", overlap.pair(0, 1).overlap());
    }

    @Test
    public void testNestedFundCountsForTheHeldFundAndDirectHoldingsAreIgnored()
    {
        var x = instrument("x", AssetType.EQUITY, "company-x");
        var z = instrument("z", AssetType.EQUITY, "company-z");
        var inner = instrument("inner", AssetType.FUND, null);
        var outer = instrument("outer", AssetType.FUND, null);
        var other = instrument("other", AssetType.FUND, null);
        holding("Direct", x, 50000);
        holding("Outer", outer, 10000);
        holding("Other", other, 10000);
        composition(outer, row(inner, "0.5"), row(x, "0.1"), row(z, "0.2"));
        composition(inner, row(x, "0.4"));
        composition(other, row(x, "0.25"), row(z, "0.1"));

        var overlap = calculate();

        assertEquals(2, overlap.funds().size());
        // outer holds X at 0.1 + 0.5 * 0.4 = 0.3, other at 0.25; Z 0.2 against 0.1
        var pair = overlap.pair(0, 1);
        amount("0.35", pair.overlap());
        assertEquals(2, pair.sharedCount());
        amount("0.25", pair.shared().get(0).overlap());
    }

    @Test
    public void testDisjointFundsAndSingleFund()
    {
        var x = instrument("x", AssetType.EQUITY, "company-x");
        var y = instrument("y", AssetType.EQUITY, "company-y");
        var a = instrument("fund-a", AssetType.FUND, null);
        var b = instrument("fund-b", AssetType.FUND, null);
        holding("Fund A", a, 10000);
        composition(a, row(x, "0.5"));

        assertEquals(1, calculate().funds().size());

        holding("Fund B", b, 10000);
        composition(b, row(y, "0.5"));

        var pair = calculate().pair(0, 1);
        amount("0", pair.overlap());
        assertEquals(0, pair.sharedCount());
    }
}
