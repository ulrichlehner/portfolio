package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;

/**
 * The look-through scope is the whole portfolio. Synthetic clients only; no
 * provider data, credentials or real positions.
 */
@SuppressWarnings("nls")
public class HeldSecuritiesTest
{
    private static final LocalDate DATE = LocalDate.parse("2026-01-31");

    private final Client client = new Client();

    private Security security(String name)
    {
        return new SecurityBuilder("EUR").addPrice("2026-01-01", Values.Quote.factorize(10)).addTo(client);
    }

    @Test
    public void testEverySecurityWithANonzeroNetPositionIsIncluded()
    {
        var held = security("Held");
        var sold = security("Sold out");
        var untouched = security("Untouched");

        new PortfolioBuilder().buy(held, "2026-01-02", Values.Share.factorize(10), Values.Amount.factorize(100))
                        .buy(held, "2026-01-03", Values.Share.factorize(4), Values.Amount.factorize(40)).addTo(client);
        new PortfolioBuilder().buy(sold, "2026-01-02", Values.Share.factorize(5), Values.Amount.factorize(50))
                        .sell(sold, "2026-01-03", Values.Share.factorize(5), Values.Amount.factorize(50)).addTo(client);

        assertEquals(List.of(held), HeldSecurities.of(client, DATE));
        assertTrue(!HeldSecurities.of(client, DATE).contains(untouched));
    }

    @Test
    public void testNetPositionAcrossSeveralPortfoliosCounts()
    {
        var security = security("Split across portfolios");
        new PortfolioBuilder().buy(security, "2026-01-02", Values.Share.factorize(10), Values.Amount.factorize(100))
                        .addTo(client);
        new PortfolioBuilder().sell(security, "2026-01-03", Values.Share.factorize(10), Values.Amount.factorize(100))
                        .addTo(client);

        assertTrue(HeldSecurities.of(client, DATE).isEmpty());
    }

    @Test
    public void testTransactionsAfterTheValuationDateAreIgnored()
    {
        var security = security("Bought later");
        new PortfolioBuilder().buy(security, "2026-02-05", Values.Share.factorize(10), Values.Amount.factorize(100))
                        .addTo(client);

        assertTrue(HeldSecurities.of(client, DATE).isEmpty());
        assertEquals(List.of(security), HeldSecurities.of(client, LocalDate.parse("2026-02-06")));
    }

    @Test
    public void testClientOrderIsPreserved()
    {
        var first = security("First");
        var second = security("Second");
        new PortfolioBuilder().buy(first, "2026-01-02", Values.Share.factorize(1), Values.Amount.factorize(10))
                        .buy(second, "2026-01-02", Values.Share.factorize(1), Values.Amount.factorize(10))
                        .addTo(client);

        assertEquals(List.of(first, second), HeldSecurities.of(client, DATE));
    }
}
