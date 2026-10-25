package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

/**
 * Current security scope for the look-through view: every security with a
 * nonzero net position across all of the client's portfolios, in client order.
 * Account cash is never part of this scope. Transactions after the supplied date
 * are ignored, matching the valuation snapshot rather than the whole file.
 * <p>
 * Securities without any transaction are not "held" here and are not sent to a
 * composition provider. The view needs no prior selection.
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
}
