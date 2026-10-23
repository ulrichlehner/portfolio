package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.money.impl.ExchangeRateTimeSeriesImpl;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Diagnostic;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Reason;

@SuppressWarnings("nls")
public class SecuritiesValuationAdapterTest
{
    private static final String TRADE_DATE = "2026-01-02";
    private static final LocalDate DATE = LocalDate.parse("2026-01-31");

    private final Client client = new Client();

    private Security security(String currency, double price)
    {
        return new SecurityBuilder(currency).addPrice(TRADE_DATE, Values.Quote.factorize(price)).addTo(client);
    }

    private void holding(Security security, double shares, double amount)
    {
        new PortfolioBuilder().inbound_delivery(security, TRADE_DATE, Values.Share.factorize(shares),
                        Values.Amount.factorize(amount)).addTo(client);
    }

    // The real factory still resolves inverse/chained routes. Its inputs are
    // exclusively synthetic: no bundled online provider or live rates are used.
    private ExchangeRateProviderFactory factory(ExchangeRateTimeSeries... series)
    {
        return new ExchangeRateProviderFactory(client)
        {
            @Override
            public List<ExchangeRateTimeSeries> getAvailableTimeSeries()
            {
                return List.of(series);
            }
        };
    }

    private ExchangeRateTimeSeriesImpl series(String base, String term, String rate)
    {
        var series = new ExchangeRateTimeSeriesImpl();
        series.setBaseCurrency(base);
        series.setTermCurrency(term);
        if (rate != null)
            series.addRate(new ExchangeRate(LocalDate.parse(TRADE_DATE), new BigDecimal(rate)));
        return series;
    }

    private SecuritiesValuation value(ExchangeRateProviderFactory factory, Security... selection)
    {
        var snapshot = ClientSnapshot.create(client, new CurrencyConverterImpl(factory, "EUR"), DATE);
        return new SecuritiesValuationAdapter(factory).value(snapshot, List.of(selection));
    }

    @Test
    public void testCashClosedPositionsAndUnselectedSecuritiesAreOutsideDenominator()
    {
        var selected = security("EUR", 25);
        var other = security("EUR", 500);
        var closed = security("EUR", 80);
        holding(selected, 4, 100);
        holding(other, 2, 1000);
        new PortfolioBuilder().buyPrice(closed, TRADE_DATE, 2, 80)
                        .sellPrice(closed, "2026-01-03", 2, 80).addTo(client);
        new AccountBuilder().deposit_(TRADE_DATE, Values.Amount.factorize(9000)).addTo(client);
        // Even unconvertible account cash must not invalidate securities scope.
        new AccountBuilder("USD").deposit_(TRADE_DATE, Values.Amount.factorize(7000)).addTo(client);

        var result = value(factory(), selected, closed, selected);

        assertEquals(Money.of("EUR", 10000), result.denominator().orElseThrow());
        assertEquals(1, result.positions().size());
        assertEquals(selected, result.positions().get(0).security());
        assertEquals(DATE, result.date());
        assertTrue(result.isEntireSelectionSupported());
    }

    @Test
    public void testShortExcludedAndDisclosedWithoutReducingPositiveDenominator()
    {
        var positive = security("EUR", 50);
        var negative = security("EUR", 30);
        holding(positive, 2, 100);
        new PortfolioBuilder().sellPrice(negative, TRADE_DATE, 3, 30).addTo(client);

        var result = value(factory(), positive, negative);

        assertEquals(Money.of("EUR", 10000), result.denominator().orElseThrow());
        assertEquals(List.of(new Diagnostic(negative, Reason.UNSUPPORTED_NEGATIVE_POSITION)), result.diagnostics());
        assertTrue(result.isValuationComplete());
        assertFalse(result.isEntireSelectionSupported());
    }

    @Test
    public void testEmptySelectionHasNoPercentageDenominator()
    {
        var security = security("EUR", 100);
        holding(security, 1, 100);
        var result = value(factory());

        assertTrue(result.denominator().isEmpty());
        assertTrue(result.positions().isEmpty());
        assertEquals(Money.of("EUR", 0), result.valuedSubset());
        assertTrue(result.isValuationComplete());
    }

    @Test
    public void testNoHeldPositionsHasNoPercentageDenominator()
    {
        var result = value(factory(), security("EUR", 100));

        assertTrue(result.denominator().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    public void testMissingPriceIsNotSubstitutedWithZero()
    {
        var priced = security("EUR", 20);
        var missing = new SecurityBuilder().addTo(client);
        holding(priced, 3, 60);
        holding(missing, 4, 0);

        var result = value(factory(), priced, missing);

        assertEquals(Money.of("EUR", 6000), result.valuedSubset());
        assertEquals(1, result.positions().size());
        assertEquals(List.of(new Diagnostic(missing, Reason.MISSING_VALUATION)), result.diagnostics());
        assertTrue(result.denominator().isEmpty());
        assertFalse(result.isValuationComplete());
        assertFalse(result.isEntireSelectionSupported());
    }

    @Test
    public void testSnapshotTransactionPriceFallbackIsRetained()
    {
        var security = new SecurityBuilder().addTo(client);
        holding(security, 4, 120);

        var result = value(factory(), security);

        assertEquals(Money.of("EUR", 12000), result.denominator().orElseThrow());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    public void testMissingFxRouteDoesNotBecomeOneToOne()
    {
        var domestic = security("EUR", 20);
        var foreign = security("USD", 40);
        holding(domestic, 3, 60);
        holding(foreign, 2, 80);

        var result = value(factory(), domestic, foreign);

        assertEquals(Money.of("EUR", 6000), result.valuedSubset());
        assertEquals(List.of(new Diagnostic(foreign, Reason.MISSING_CONVERSION)), result.diagnostics());
        assertTrue(result.denominator().isEmpty());
    }

    @Test
    public void testExistingButEmptyFxSeriesIsMissingConversion()
    {
        var foreign = security("USD", 40);
        holding(foreign, 2, 80);

        var result = value(factory(series("USD", "EUR", null)), foreign);

        assertEquals(List.of(new Diagnostic(foreign, Reason.MISSING_CONVERSION)), result.diagnostics());
        assertTrue(result.positions().isEmpty());
        assertTrue(result.denominator().isEmpty());
    }

    @Test
    public void testGenuineOneToOneFxIsNotRejected()
    {
        var foreign = security("USD", 40);
        holding(foreign, 2, 80);

        var result = value(factory(series("USD", "EUR", "1")), foreign);

        assertEquals(Money.of("EUR", 8000), result.denominator().orElseThrow());
    }

    @Test
    public void testNonpositiveFxIsNotAUsableValuation()
    {
        var foreign = security("USD", 40);
        holding(foreign, 2, 80);

        for (var rate : List.of("0", "-0.75"))
        {
            var result = value(factory(series("USD", "EUR", rate)), foreign);

            assertEquals(List.of(new Diagnostic(foreign, Reason.MISSING_CONVERSION)), result.diagnostics());
            assertTrue(result.denominator().isEmpty());
            assertTrue(result.positions().isEmpty());
        }
    }

    @Test
    public void testInverseChainedConversionUsesSnapshotConverter()
    {
        var foreign = security("USD", 40);
        holding(foreign, 2, 80);
        var rates = factory(series("GBP", "USD", "2"), series("GBP", "EUR", "1.5"));

        var result = value(rates, foreign);

        assertEquals(Money.of("EUR", 6000), result.denominator().orElseThrow());
    }

    @Test
    public void testMissingOrEmptyChainedFxComponentWithholdsDenominator()
    {
        var domestic = security("EUR", 50);
        var foreign = security("USD", 40);
        holding(domestic, 2, 100);
        holding(foreign, 2, 80);

        var missing = factory(series("GBP", "USD", "2"));
        var empty = factory(series("GBP", "USD", "2"), series("GBP", "EUR", null));
        for (var rates : List.of(missing, empty))
        {
            var result = value(rates, domestic, foreign);

            assertEquals(List.of(new Diagnostic(foreign, Reason.MISSING_CONVERSION)), result.diagnostics());
            assertEquals(Money.of("EUR", 10000), result.valuedSubset());
            assertEquals(1, result.positions().size());
            assertTrue(result.denominator().isEmpty());
            assertFalse(result.isValuationComplete());
        }
    }

    @Test
    public void testInvalidHistoricalFxDoesNotMasqueradeAsNegativeOrMissingPrice()
    {
        var domestic = security("EUR", 50);
        var unquoted = new SecurityBuilder().addTo(client);
        holding(domestic, 2, 100);
        var portfolio = new PortfolioBuilder().inbound_delivery(unquoted, TRADE_DATE,
                        Values.Share.factorize(2), Values.Amount.factorize(80)).addTo(client);
        portfolio.getTransactions().get(0).setCurrencyCode("USD");

        for (var rate : List.of("-0.75", "0"))
        {
            var result = value(factory(series("USD", "EUR", rate)), domestic, unquoted);

            assertEquals(List.of(new Diagnostic(unquoted, Reason.MISSING_CONVERSION)), result.diagnostics());
            assertEquals(Money.of("EUR", 10000), result.valuedSubset());
            assertEquals(1, result.positions().size());
            assertTrue(result.denominator().isEmpty());
            assertFalse(result.isValuationComplete());
            assertFalse(result.isEntireSelectionSupported());
        }
    }

    @Test
    public void testTransactionPriceFallbackAlsoRequiresItsHistoricalFx()
    {
        // Reporting in EUR alone needs no FX, but the fallback price was derived
        // from a USD transaction and must not inherit PP's silent 1:1 fallback.
        var security = new SecurityBuilder().addTo(client);
        var portfolio = new PortfolioBuilder().inbound_delivery(security, TRADE_DATE,
                        Values.Share.factorize(2), Values.Amount.factorize(80)).addTo(client);
        portfolio.getTransactions().get(0).setCurrencyCode("USD");

        var missing = value(factory(), security);
        assertEquals(List.of(new Diagnostic(security, Reason.MISSING_CONVERSION)), missing.diagnostics());
        assertTrue(missing.denominator().isEmpty());

        var converted = value(factory(series("USD", "EUR", "0.75")), security);
        assertEquals(Money.of("EUR", 6000), converted.denominator().orElseThrow());
    }

    @Test
    public void testTransactionForexUnitProvidesFallbackPriceWithoutMarketFx()
    {
        var security = new SecurityBuilder().addTo(client);
        var gross = new Unit(Unit.Type.GROSS_VALUE, Money.of("USD", 8000), Money.of("EUR", 6000),
                        new BigDecimal("1.3333333333"));
        new PortfolioBuilder().inbound_delivery(security, TRADE_DATE, Values.Share.factorize(2), gross).addTo(client);

        var result = value(factory(), security);

        assertEquals(Money.of("EUR", 6000), result.denominator().orElseThrow());
    }

    @Test
    public void testJointSnapshotAvoidsPerPortfolioRoundingAndDuplicateSelection()
    {
        var security = security("EUR", 1);
        holding(security, 0.005, 0);
        holding(security, 0.005, 0);

        var result = value(factory(), security, security);

        assertEquals(1, result.positions().size());
        assertEquals(Values.Share.factorize(0.01), result.positions().get(0).shares());
        assertEquals(Money.of("EUR", 1), result.denominator().orElseThrow());
    }

    @Test
    public void testConversionUsesSnapshotDateAndPpHalfDownRounding()
    {
        var foreign = security("USD", 0.01);
        holding(foreign, 3, 0.03);
        var series = series("USD", "EUR", "0.25");
        series.addRate(new ExchangeRate(DATE, new BigDecimal("0.5")));
        series.addRate(new ExchangeRate(DATE.plusDays(1), new BigDecimal("0.75")));

        var result = value(factory(series), foreign);

        // USD 0.03 * 0.5 = EUR 0.015, rounded by PP to EUR 0.01.
        assertEquals(Money.of("EUR", 1), result.denominator().orElseThrow());
    }
}
