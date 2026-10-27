package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation.Position;

/**
 * Pairwise overlap of the decomposed funds of a result: the sum, over the
 * companies held by both funds, of the smaller of the two fund weights.
 * <p>
 * The weight of a company in a fund is the value that the company receives
 * through that held fund, nested funds included, divided by the value of the
 * held fund. The contributions are exact products of the validated weights, so
 * this equals the sum of the validated weights along all paths. Value that
 * stays unresolved (shortfall, unclassified rows) is not a company and does not
 * count, so every weight is a share of the whole fund. A held fund without
 * usable composition is excluded.
 */
public final class FundOverlap
{
    /** A decomposed fund as held; the value is its share of the valued subset. */
    public record Fund(Security security, String name, BigDecimal value)
    {
    }

    /** A company held by both funds, with its weight in each and the smaller of the two. */
    public record SharedCompany(Identifier identity, String label, BigDecimal firstWeight, BigDecimal secondWeight,
                    BigDecimal overlap)
    {
    }

    /** The overlap of two funds; shared companies are ordered by overlap, largest first. */
    public record Pair(Fund first, Fund second, BigDecimal overlap, List<SharedCompany> shared)
    {
        public Pair
        {
            shared = List.copyOf(shared);
        }

        public int sharedCount()
        {
            return shared.size();
        }
    }

    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final List<Fund> funds;
    private final List<Security> excluded;
    private final Map<Long, Pair> pairs;

    private FundOverlap(List<Fund> funds, List<Security> excluded, Map<Long, Pair> pairs)
    {
        this.funds = List.copyOf(funds);
        this.excluded = List.copyOf(excluded);
        this.pairs = pairs;
    }

    private static long key(int first, int second)
    {
        return ((long) Math.min(first, second) << 32) | Math.max(first, second);
    }

    private static final class Holding
    {
        private final Set<Position> positions = new LinkedHashSet<>();
        private final Map<Identifier, BigDecimal> weights = new LinkedHashMap<>();
        private final Map<Identifier, String> labels = new HashMap<>();
        private boolean decomposed;
        private boolean fund;
    }

    public static FundOverlap of(LookThroughResult result)
    {
        var holdings = new LinkedHashMap<Security, Holding>();
        for (var contribution : result.contributions())
        {
            var holding = holdings.computeIfAbsent(contribution.origin().security(), ignored -> new Holding());
            holding.positions.add(contribution.origin());
            holding.fund |= contribution.originResolution()
                            .filter(resolution -> resolution.state() == InstrumentResolution.State.FUND).isPresent();
            if (contribution.isDirect())
                continue;
            holding.decomposed = true;
            if (contribution.category() == Category.COMPANY)
            {
                var company = contribution.resolution().orElseThrow().company().orElseThrow();
                holding.weights.merge(company, contribution.value(), BigDecimal::add);
                if (!contribution.instrument().label().isBlank())
                    holding.labels.putIfAbsent(company, contribution.instrument().label());
            }
        }

        var funds = new ArrayList<Fund>();
        var weights = new ArrayList<Map<Identifier, BigDecimal>>();
        var labels = new ArrayList<Map<Identifier, String>>();
        var excluded = new ArrayList<Security>();
        holdings.forEach((security, holding) -> {
            if (!holding.decomposed)
            {
                if (holding.fund)
                    excluded.add(security);
                return;
            }
            var value = holding.positions.stream().map(position -> BigDecimal.valueOf(position.value().getAmount()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (value.signum() <= 0)
                return;
            var fractions = new LinkedHashMap<Identifier, BigDecimal>();
            holding.weights.forEach((company, amount) -> fractions.put(company, amount.divide(value, PRECISION)));
            funds.add(new Fund(security, Objects.toString(security.getName(), ""), value)); //$NON-NLS-1$
            weights.add(fractions);
            labels.add(holding.labels);
        });

        // largest held fund first
        var order = new ArrayList<Integer>();
        for (int ii = 0; ii < funds.size(); ii++)
            order.add(ii);
        order.sort(Comparator.comparing((Integer ii) -> funds.get(ii).value()).reversed());
        var sortedFunds = new ArrayList<Fund>();
        var sortedWeights = new ArrayList<Map<Identifier, BigDecimal>>();
        var sortedLabels = new ArrayList<Map<Identifier, String>>();
        for (int ii : order)
        {
            sortedFunds.add(funds.get(ii));
            sortedWeights.add(weights.get(ii));
            sortedLabels.add(labels.get(ii));
        }

        var pairs = new HashMap<Long, Pair>();
        for (int ii = 0; ii < sortedFunds.size(); ii++)
        {
            for (int jj = ii + 1; jj < sortedFunds.size(); jj++)
            {
                var shared = new ArrayList<SharedCompany>();
                var total = BigDecimal.ZERO;
                for (var entry : sortedWeights.get(ii).entrySet())
                {
                    var other = sortedWeights.get(jj).get(entry.getKey());
                    if (other == null)
                        continue;
                    var overlap = entry.getValue().min(other);
                    var label = sortedLabels.get(ii).getOrDefault(entry.getKey(),
                                    sortedLabels.get(jj).getOrDefault(entry.getKey(), entry.getKey().value()));
                    shared.add(new SharedCompany(entry.getKey(), label, entry.getValue(), other, overlap));
                    total = total.add(overlap);
                }
                // stable: equal overlaps keep the order of the first fund's composition
                shared.sort(Comparator.comparing(SharedCompany::overlap).reversed());
                pairs.put(key(ii, jj), new Pair(sortedFunds.get(ii), sortedFunds.get(jj), total, shared));
            }
        }
        return new FundOverlap(sortedFunds, excluded, pairs);
    }

    /** The decomposed funds, the largest held fund first. */
    public List<Fund> funds()
    {
        return funds;
    }

    /** Held funds without usable composition; they are not part of the overlap. */
    public List<Security> excluded()
    {
        return excluded;
    }

    /** The overlap of two different funds by their index in {@link #funds()}; symmetric. */
    public Pair pair(int first, int second)
    {
        if (first == second)
            throw new IllegalArgumentException();
        return pairs.get(key(first, second));
    }
}
