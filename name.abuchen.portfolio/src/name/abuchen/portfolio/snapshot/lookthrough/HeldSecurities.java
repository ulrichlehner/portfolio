package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.snapshot.ClientSnapshot;

/**
 * Current security scope for the look-through view: every security with a
 * nonzero net position across all of the client's portfolios, in client order.
 * Account cash is never part of this scope. Transactions after the supplied date
 * are ignored, matching the valuation snapshot rather than the whole file.
 * <p>
 * Securities without any transaction are not "held" here and are not sent to a
 * composition provider. The view needs no prior selection.
 * <p>
 * {@link #valued} narrows the scope to the securities that the valuation keeps
 * with a positive value. A position without a price or conversion, or worth
 * less than the smallest currency unit, is only a diagnostic of the valuation.
 */
public final class HeldSecurities
{
    private HeldSecurities()
    {
    }

    public static List<Security> of(Client client, LocalDate date)
    {
        Objects.requireNonNull(client);
        Objects.requireNonNull(date);
        var shares = new HashMap<Security, Long>();
        for (var portfolio : client.getPortfolios())
            for (var transaction : portfolio.getTransactions())
            {
                var security = transaction.getSecurity();
                if (security == null || transaction.getDateTime().toLocalDate().isAfter(date))
                    continue;
                var delta = transaction.getType().isPurchase() ? transaction.getShares()
                                : -transaction.getShares();
                shares.merge(security, delta, Long::sum);
            }
        var held = new ArrayList<Security>();
        for (var security : client.getSecurities())
            if (shares.getOrDefault(security, 0L) != 0)
                held.add(security);
        return List.copyOf(held);
    }

    /**
     * The held securities that have a positive value at the date, in client
     * order. Only these are classified, requested from a provider and counted.
     */
    public static List<Security> valued(Client client, LocalDate date, ExchangeRateProviderFactory rates)
    {
        var held = of(client, date);
        if (held.isEmpty())
            return held;
        var snapshot = ClientSnapshot.create(client, new CurrencyConverterImpl(rates, client.getBaseCurrency()),
                        date);
        var valuation = new SecuritiesValuationAdapter(rates).value(snapshot, held);
        var positive = new HashSet<Security>();
        valuation.positions().forEach(position -> positive.add(position.security()));
        return held.stream().filter(positive::contains).toList();
    }
}
