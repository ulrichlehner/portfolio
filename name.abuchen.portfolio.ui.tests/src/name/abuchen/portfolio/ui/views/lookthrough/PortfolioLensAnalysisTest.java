package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.Snapshot;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolver;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

@SuppressWarnings("nls")
public class PortfolioLensAnalysisTest
{
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "test");
    private static final Instant NOW = Instant.parse("2026-01-31T12:00:00Z");
    private final Client client = new Client();
    private final ExchangeRateProviderFactory rates = new ExchangeRateProviderFactory(client)
    {
        @Override
        public List<ExchangeRateTimeSeries> getAvailableTimeSeries()
        {
            return List.of();
        }
    };

    private Security holding(String id, int shares)
    {
        var security = new SecurityBuilder("EUR").addPrice("2026-01-01", Values.Quote.factorize(10)).addTo(client);
        security.setName("Synthetic " + id);
        security.setIsin(id);
        new PortfolioBuilder().inbound_delivery(security, "2026-01-01", Values.Share.factorize(shares),
                        Values.Amount.factorize(shares * 10)).addTo(client);
        return security;
    }

    private ClientSnapshot snapshot()
    {
        return ClientSnapshot.create(client, new CurrencyConverterImpl(rates, "EUR"), LocalDate.parse("2026-01-31"));
    }

    private InstrumentResolution resolution(CompositionInstrument instrument, AssetType type)
    {
        var provenance = new Provenance(ACCOUNT, "synthetic-evidence", NOW);
        var evidence = new InstrumentEvidence(instrument.identifiers(),
                        new Assertion<>(new Identifier("synthetic", instrument.identifiers().get("ISIN")), provenance),
                        Optional.of(new Assertion<>(type, provenance)),
                        type == AssetType.EQUITY
                                        ? Optional.of(new Assertion<>(new Identifier("synthetic-company", "company"),
                                                        provenance))
                                        : Optional.empty());
        return new InstrumentResolver().resolve(instrument, List.of(evidence), ignored -> List.of(), () -> false);
    }

    @Test
    public void testCurrentSelectionProducesResultBeforeAnyProviderResponse()
    {
        var selected = holding("SYNTHETIC-ONE", 6);
        holding("SYNTHETIC-UNSELECTED", 20);
        var result = new PortfolioLensAnalysis().calculate(snapshot(), rates, List.of(selected), List.of());
        assertEquals(Money.of("EUR", 6000), result.denominator().orElseThrow());
        assertEquals(0, result.value(Category.UNRESOLVED).compareTo(new BigDecimal("6000")));
        assertEquals(1, result.contributions().size());
        assertTrue(result.companies().isEmpty());
    }

    @Test
    public void testRetainedEvidenceCombinesDirectAndIndirectExposureWithoutNormalizing()
    {
        var share = holding("SYNTHETIC-SHARE", 6);
        var fund = holding("SYNTHETIC-FUND", 4);
        var child = PortfolioLensCompositionService.instrumentOf(share);
        var parent = PortfolioLensCompositionService.instrumentOf(fund);
        var composition = new FundComposition(new CompositionProvenance(ACCOUNT, parent, NOW, Optional.empty(),
                        Optional.empty()), FundComposition.Denominator.TOTAL_FUND_NAV,
                        FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                        List.of(new FundComposition.Constituent(child, Optional.of("0.5"))));
        var retained = new Snapshot(Optional.of(NOW), Optional.empty(), Optional.of(composition),
                        List.of(resolution(child, AssetType.EQUITY), resolution(parent, AssetType.FUND)));
        var result = new PortfolioLensAnalysis().calculate(snapshot(), rates, List.of(share, fund), List.of(retained));
        assertEquals(Money.of("EUR", 10000), result.denominator().orElseThrow());
        var company = result.companies().getFirst();
        assertEquals(0, company.direct().compareTo(new BigDecimal("6000")));
        assertEquals(0, company.indirect().compareTo(new BigDecimal("2000")));
        assertEquals(0, result.value(Category.UNRESOLVED).compareTo(new BigDecimal("2000")));
        assertEquals(0, result.total().compareTo(new BigDecimal("10000")));
    }

    @Test
    public void testChangedPricesRecalculateWithoutStoredDerivedResults()
    {
        var security = holding("SYNTHETIC-SHARE", 6);
        var first = new PortfolioLensAnalysis().calculate(snapshot(), rates, List.of(security), List.of());
        security.addPrice(new SecurityPrice(LocalDate.parse("2026-01-30"),
                        Values.Quote.factorize(20)));
        var second = new PortfolioLensAnalysis().calculate(snapshot(), rates, List.of(security), List.of());
        assertEquals(Money.of("EUR", 6000), first.denominator().orElseThrow());
        assertEquals(Money.of("EUR", 12000), second.denominator().orElseThrow());
    }
}
