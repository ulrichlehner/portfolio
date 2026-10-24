package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;

/**
 * Exact allocations of the valued subset. Reconciliation is not proof of complete
 * economic coverage. Valuation exclusions and composition/identity evidence stay
 * inspectable; freshness is independent. No percentage is inferred when the
 * selected positive-securities denominator is unknown or zero.
 */
public final class LookThroughResult
{
    public record Company(Identifier identity, BigDecimal direct, BigDecimal indirect,
                    List<LookThroughContribution> contributions)
    {
        public Company
        {
            contributions = List.copyOf(contributions);
        }

        public BigDecimal total()
        {
            return direct.add(indirect);
        }
    }

    private final SecuritiesValuation valuation;
    private final List<LookThroughContribution> contributions;
    private final List<Company> companies;

    LookThroughResult(SecuritiesValuation valuation, List<LookThroughContribution> contributions)
    {
        this.valuation = valuation;
        this.contributions = List.copyOf(contributions);
        Map<Identifier, List<LookThroughContribution>> grouped = new LinkedHashMap<>();
        for (var contribution : contributions)
        {
            if (contribution.category() == Category.COMPANY)
                grouped.computeIfAbsent(contribution.resolution().orElseThrow().company().orElseThrow(),
                                ignored -> new ArrayList<>()).add(contribution);
        }
        var result = new ArrayList<Company>();
        grouped.forEach((identity, rows) -> {
            var direct = BigDecimal.ZERO;
            var indirect = BigDecimal.ZERO;
            for (var row : rows)
            {
                if (row.isDirect())
                    direct = direct.add(row.value());
                else
                    indirect = indirect.add(row.value());
            }
            result.add(new Company(identity, direct, indirect, rows));
        });
        companies = List.copyOf(result);
        if (total().compareTo(BigDecimal.valueOf(valuation.valuedSubset().getAmount())) != 0)
            throw new IllegalArgumentException();
    }

    public SecuritiesValuation valuation()
    {
        return valuation;
    }

    public Optional<Money> denominator()
    {
        return valuation.denominator();
    }

    /** Depth-first source order, with each snapshot's residual following its rows. */
    public List<LookThroughContribution> contributions()
    {
        return contributions;
    }

    /** First occurrence order; identifiers, never labels, define company groups. */
    public List<Company> companies()
    {
        return companies;
    }

    public BigDecimal value(Category category)
    {
        return contributions.stream().filter(row -> row.category() == category).map(LookThroughContribution::value)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal total()
    {
        return contributions.stream().map(LookThroughContribution::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
