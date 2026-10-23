package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.MonetaryException;
import name.abuchen.portfolio.money.MutableMoney;
import name.abuchen.portfolio.money.impl.EmptyExchangeRateTimeSeries;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Diagnostic;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Position;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Reason;

/**
 * Uses the joint snapshot's net security positions and PP's monetary rounding.
 * The factory must be the one backing the snapshot's currency converter. It is
 * needed because CurrencyConverter alone conceals missing FX with a 1:1 fallback.
 * No account valuations or account-inclusive percentages are accessed.
 */
public final class SecuritiesValuationAdapter
{
    private final ExchangeRateProviderFactory rates;

    public SecuritiesValuationAdapter(ExchangeRateProviderFactory rates)
    {
        this.rates = Objects.requireNonNull(rates);
    }

    public SecuritiesValuation value(ClientSnapshot snapshot, Collection<Security> selectedSecurities)
    {
        var selected = new HashSet<>(selectedSecurities);
        var positions = new ArrayList<Position>();
        var diagnostics = new ArrayList<Diagnostic>();
        var total = MutableMoney.of(snapshot.getCurrencyCode());

        for (var position : snapshot.getJointPortfolio().getPositions())
        {
            var security = position.getSecurity();
            if (!selected.contains(security) || position.getShares() == 0)
                continue;

            if (position.getShares() < 0)
            {
                diagnostics.add(new Diagnostic(security, Reason.UNSUPPORTED_NEGATIVE_POSITION));
                continue;
            }
            if (security.getCurrencyCode() == null)
            {
                diagnostics.add(new Diagnostic(security, Reason.MISSING_VALUATION));
                continue;
            }

            try
            {
                // Validate the fallback's FX before interpreting its sign or
                // zero value: either may be an artefact of an invalid rate.
                if (!hasFallbackConversion(snapshot, security))
                {
                    diagnostics.add(new Diagnostic(security, Reason.MISSING_CONVERSION));
                    continue;
                }
                if (position.getPrice().getValue() < 0)
                {
                    diagnostics.add(new Diagnostic(security, Reason.UNSUPPORTED_NEGATIVE_POSITION));
                    continue;
                }
                if (position.getPrice().getValue() == 0)
                {
                    diagnostics.add(new Diagnostic(security, Reason.MISSING_VALUATION));
                    continue;
                }
                if (!hasRate(security.getCurrencyCode(), snapshot.getCurrencyCode(), snapshot.getTime()))
                {
                    diagnostics.add(new Diagnostic(security, Reason.MISSING_CONVERSION));
                    continue;
                }

                var value = snapshot.getCurrencyConverter().convert(snapshot.getTime(), position.calculateValue());
                if (value.getAmount() < 0)
                {
                    diagnostics.add(new Diagnostic(security, Reason.MISSING_CONVERSION));
                }
                else if (value.getAmount() > 0)
                {
                    positions.add(new Position(security, position.getShares(), value));
                    total.add(value);
                }
            }
            catch (MonetaryException | ArithmeticException e)
            {
                diagnostics.add(new Diagnostic(security, Reason.MISSING_CONVERSION));
            }
        }

        return new SecuritiesValuation(snapshot.getTime(), positions, diagnostics, total.toMoney());
    }

    // PortfolioSnapshot may derive its price from the latest transaction. Check
    // that this earlier conversion did not silently use a missing-rate fallback.
    private boolean hasFallbackConversion(ClientSnapshot snapshot, Security security)
    {
        if (security.getSecurityPrice(snapshot.getTime()).getValue() != 0)
            return true;

        var last = snapshot.getJointPortfolio().getPortfolio().getTransactions().stream()
                        .filter(t -> t.getSecurity().equals(security)
                                        && !t.getDateTime().toLocalDate().isAfter(snapshot.getTime()))
                        .sorted(Transaction.BY_DATE.reversed()).findFirst();

        if (last.isEmpty())
            return false;

        var transaction = last.orElseThrow();
        return transaction.getUnit(Unit.Type.GROSS_VALUE).isPresent()
                        || hasRate(transaction.getCurrencyCode(), security.getCurrencyCode(),
                                        transaction.getDateTime().toLocalDate());
    }

    private boolean hasRate(String base, String term, LocalDate date)
    {
        if (term.equals(base))
            return true;
        var series = rates.getTimeSeries(base, term);
        return series != null && hasRealRates(series, date);
    }

    private boolean hasRealRates(ExchangeRateTimeSeries series, LocalDate date)
    {
        if (series instanceof EmptyExchangeRateTimeSeries)
            return false;
        if (!series.getComposition().stream().allMatch(s -> hasRealRates(s, date)))
            return false;
        return series.lookupRate(date).filter(r -> r.getValue().signum() > 0).isPresent();
    }
}
