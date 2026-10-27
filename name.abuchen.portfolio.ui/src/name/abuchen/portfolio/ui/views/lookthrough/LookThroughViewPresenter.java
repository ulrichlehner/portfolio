package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes.Source;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;
import name.abuchen.portfolio.snapshot.lookthrough.RevenueSplit;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughGrouping.Group;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughGrouping.Grouping;

/**
 * Converts the exact look-through result into rows for display. The presenter is
 * SWT-free, performs no retrieval and persists nothing, so all presentation rules
 * stay testable and the view remains thin.
 * <p>
 * Shares are display values only. They never decide whether coverage is complete:
 * even a single minor unit of unresolved value keeps the result partial although
 * every displayed share may round to a full 100 percent.
 * <p>
 * Unverified provider weights are shown as their own category with per-position
 * detail rows. They are visible for transparency and never described as verified
 * company exposure.
 */
public final class LookThroughViewPresenter
{
    /** Display precision of a share: one ten-thousandth of a percent. */
    private static final int SHARE_SCALE = 6;
    private static final BigDecimal CONCENTRATION_THRESHOLD = new BigDecimal("0.10"); //$NON-NLS-1$

    private static final Comparator<Company> BY_TOTAL_THEN_IDENTIFIER = Comparator //
                    .comparing(Company::total).reversed() //
                    .thenComparing(company -> company.identity().value()) //
                    .thenComparing(company -> company.identity().namespace());

    private static final Comparator<DetailRow> BY_DETAIL_VALUE = Comparator //
                    .comparing(DetailRow::value).reversed() //
                    .thenComparing(DetailRow::label);

    public enum Completeness
    {
        COMPLETE, PARTIAL
    }

    public enum RowKind
    {
        COMPANY, CONTRIBUTION, CATEGORY, DETAIL, EXCLUDED, GROUP
    }

    /** Whether a group collects regular values, unknown values or companies that still wait for enrichment. */
    public enum GroupKind
    {
        REGULAR, UNKNOWN, PENDING
    }

    /**
     * Stable identity of a row across recalculations. Equal results produce
     * equal keys, and the key of a nested row starts with the key of its
     * parent, so the view can keep selection and expansion when it replaces
     * the immutable rows.
     */
    public record RowKey(List<Object> parts)
    {
        public RowKey
        {
            parts = List.copyOf(parts);
        }

        static RowKey of(Object... parts)
        {
            return new RowKey(List.of(parts));
        }

        RowKey child(Object... more)
        {
            var combined = new ArrayList<Object>(parts);
            combined.addAll(List.of(more));
            return new RowKey(combined);
        }
    }

    /**
     * Hands out keys within one presentation. A repeated key gets an
     * occurrence number, so identical rows still have distinct keys.
     */
    private static final class Keys
    {
        private final Map<RowKey, Integer> occurrences = new HashMap<>();

        private RowKey unique(RowKey key)
        {
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            return occurrence == 1 ? key : key.child(occurrence);
        }
    }

    public sealed interface Row permits CompanyRow, ContributionRow, CategoryRow, DetailRow, ExcludedRow, GroupRow
    {
        RowKey key();

        RowKind kind();

        String label();
    }

    /** Aggregated verified exposure of one company identity. */
    public record CompanyRow(RowKey key, String label, BigDecimal direct, BigDecimal indirect, BigDecimal total,
                    Optional<BigDecimal> share, Optional<String> shareText, List<ContributionRow> contributions,
                    Optional<String> detail) implements Row
    {
        public CompanyRow
        {
            contributions = List.copyOf(contributions);
        }

        @Override
        public RowKind kind()
        {
            return RowKind.COMPANY;
        }
    }

    /**
     * The companies of one group of a grouping, with their summed values. The
     * key is stable for the group, so selection and expansion survive when
     * enrichment moves companies between groups.
     */
    public record GroupRow(RowKey key, Grouping grouping, GroupKind groupKind, String label, int count,
                    BigDecimal direct, BigDecimal indirect, BigDecimal total, Optional<BigDecimal> share,
                    Optional<String> shareText, List<CompanyRow> companies) implements Row
    {
        public GroupRow
        {
            companies = List.copyOf(companies);
        }

        @Override
        public RowKind kind()
        {
            return RowKind.GROUP;
        }
    }

    /** One traceable source of a company row: direct holding or fund path. */
    public record ContributionRow(RowKey key, String label, String origin, String path, String sourceText, boolean direct,
                    BigDecimal value, Optional<BigDecimal> share, Optional<String> shareText) implements Row
    {
        @Override
        public RowKind kind()
        {
            return RowKind.CONTRIBUTION;
        }
    }

    /**
     * One position inside a non-company coverage category. The note states why
     * the position is not verified company exposure.
     */
    public record DetailRow(RowKey key, String label, String sourceText, String note, boolean direct, BigDecimal value,
                    Optional<BigDecimal> share, Optional<String> shareText) implements Row
    {
        @Override
        public RowKind kind()
        {
            return RowKind.DETAIL;
        }
    }

    /** Always-present aggregate for a non-company coverage category. */
    public record CategoryRow(RowKey key, Category category, String label, BigDecimal value, Optional<BigDecimal> share,
                    Optional<String> shareText, List<DetailRow> details) implements Row
    {
        public CategoryRow
        {
            details = List.copyOf(details);
        }

        @Override
        public RowKind kind()
        {
            return RowKind.CATEGORY;
        }
    }

    /** A selected position that the valuation excluded and therefore never valued. */
    public record ExcludedRow(RowKey key, String label, SecuritiesValuation.Reason exclusion, String reasonText) implements Row
    {
        @Override
        public RowKind kind()
        {
            return RowKind.EXCLUDED;
        }
    }

    /**
     * Key figures shown above the table. Percentages are absent whenever the
     * denominator is missing; the values stay visible. The indicator text
     * collects every notice of the result as paragraphs, so no disclosure
     * needs a permanent label above the table.
     */
    public record Summary(String asOfText, String valueText, Optional<BigDecimal> resolvedShare,
                    Optional<String> resolvedText, Optional<BigDecimal> topTenShare, Optional<String> topTenText,
                    int decomposedFunds, int heldFunds, Optional<String> fundsText, boolean needsCompositions,
                    boolean attention, String indicatorText, Optional<BigDecimal> effectiveCompanies,
                    Optional<String> effectiveText, Optional<Largest> largest)
    {
    }

    /** The largest company with its share of the portfolio value. */
    public record Largest(String name, BigDecimal share, String shareText)
    {
    }

    public enum SegmentKind
    {
        COMPANIES, UNGROUPED_EQUITY, CASH_OR_OTHER, UNVERIFIED, UNRESOLVED
    }

    /**
     * One part of the coverage bar. The fraction is the exact share of the
     * denominator; {@code nonzero} tells the renderer to keep the segment
     * visible even when its proportional width rounds to nothing.
     */
    public record Segment(SegmentKind kind, String label, BigDecimal value, String valueText, BigDecimal fraction,
                    String shareText, boolean nonzero)
    {
    }

    /** Split of the securities value into the coverage categories. */
    public record Coverage(List<Segment> segments)
    {
        public Coverage
        {
            segments = List.copyOf(segments);
        }

        /** The segments for the legend: categories without a value are left out. */
        public List<Segment> legend()
        {
            return segments.stream().filter(Segment::nonzero).toList();
        }

        /**
         * Distributes the available width proportionally. A nonzero segment
         * gets at least the minimum width, taken from the widest segments, so
         * a tiny unresolved remainder never disappears through rounding.
         * Segments with a zero value get no width.
         */
        public int[] widths(int totalWidth, int minimumWidth)
        {
            var widths = new int[segments.size()];
            if (totalWidth <= 0)
                return widths;

            int used = 0;
            for (int ii = 0; ii < widths.length; ii++)
            {
                var segment = segments.get(ii);
                if (!segment.nonzero())
                    continue;
                int width = segment.fraction().multiply(BigDecimal.valueOf(totalWidth))
                                .setScale(0, RoundingMode.HALF_UP).intValue();
                widths[ii] = Math.max(minimumWidth, width);
                used += widths[ii];
            }

            // Give or take the rounding difference from the widest segment,
            // without pushing any nonzero segment below the minimum.
            int difference = totalWidth - used;
            while (difference != 0)
            {
                int widest = -1;
                for (int ii = 0; ii < widths.length; ii++)
                    if (widths[ii] > 0 && (widest < 0 || widths[ii] > widths[widest]))
                        widest = ii;
                if (widest < 0)
                    break;
                int change = difference > 0 ? difference : Math.max(difference, minimumWidth - widths[widest]);
                if (change == 0)
                    break;
                widths[widest] += change;
                difference -= change;
            }
            return widths;
        }
    }

    public record Model(LocalDate valuationDate, String currencyCode, String scopeText, Optional<Money> denominator,
                    Optional<String> denominatorText, Completeness completeness, String completenessText,
                    Optional<String> unverifiedNotice, boolean empty, List<Row> rows, Summary summary,
                    Optional<Coverage> coverage, Map<Map<String, String>, String> decomposedShares,
                    Set<Map<String, String>> directlyClassified, Grouping grouping)
    {
        public Model
        {
            rows = List.copyOf(rows);
            decomposedShares = Map.copyOf(decomposedShares);
            directlyClassified = Set.copyOf(directlyClassified);
        }

        /** The company charts, derived from the company rows only. */
        public ChartModel chart()
        {
            return ChartModel.of(rows);
        }
    }

    /**
     * One company of the charts, with its direct and indirect part. The key is
     * the key of the company row, so a chart element selects that row.
     */
    public record ChartCompany(RowKey key, String label, BigDecimal direct, BigDecimal indirect, BigDecimal total,
                    Optional<String> shareText, Optional<String> detail)
    {
        /** Part of the company exposure that is held directly, from 0 to 1. */
        public double directFraction()
        {
            if (total.signum() <= 0)
                return 0;
            return Math.max(0, Math.min(1, direct.divide(total, SHARE_SCALE, RoundingMode.HALF_UP).doubleValue()));
        }
    }

    /** One group of the charts with its companies, largest first. */
    public record ChartGroup(ChartCompany group, List<ChartCompany> companies)
    {
        public ChartGroup
        {
            companies = List.copyOf(companies);
        }
    }

    /**
     * Companies for the charts, largest first. Coverage categories, unresolved
     * value and excluded positions are not part of the charts.
     */
    public record ChartModel(List<ChartCompany> companies, List<ChartGroup> groups)
    {
        /** Number of companies in the bar chart. */
        public static final int TOP_COMPANIES = 20;

        public ChartModel
        {
            companies = List.copyOf(companies);
            groups = List.copyOf(groups);
        }

        public ChartModel(List<ChartCompany> companies)
        {
            this(companies, List.of());
        }

        /** Whether the charts show groups with their companies nested inside. */
        public boolean isGrouped()
        {
            return !groups.isEmpty();
        }

        static ChartModel of(List<Row> rows)
        {
            var groupRows = rows.stream().filter(GroupRow.class::isInstance).map(GroupRow.class::cast)
                            .filter(group -> group.total().signum() > 0).toList();
            if (!groupRows.isEmpty())
            {
                var groups = groupRows.stream().sorted(Comparator.comparing(GroupRow::total).reversed())
                                .map(group -> new ChartGroup(chartOf(group.key(), group.label(), group.direct(),
                                                group.indirect(), group.total(), group.shareText(), Optional.empty()),
                                                companiesOf(group.companies())))
                                .toList();
                var all = groups.stream().flatMap(group -> group.companies().stream())
                                .sorted(Comparator.comparing(ChartCompany::total).reversed()).toList();
                return new ChartModel(all, groups);
            }
            return new ChartModel(companiesOf(rows.stream().filter(CompanyRow.class::isInstance)
                            .map(CompanyRow.class::cast).toList()));
        }

        private static ChartCompany chartOf(RowKey key, String label, BigDecimal direct, BigDecimal indirect,
                        BigDecimal total, Optional<String> shareText, Optional<String> detail)
        {
            return new ChartCompany(key, label, direct, indirect, total, shareText, detail);
        }

        private static List<ChartCompany> companiesOf(List<CompanyRow> companies)
        {
            return companies.stream().filter(company -> company.total().signum() > 0)
                            .sorted(Comparator.comparing(CompanyRow::total).reversed())
                            .map(company -> chartOf(company.key(), company.label(), company.direct(),
                                            company.indirect(), company.total(), percentText(company.share()),
                                            company.detail()))
                            .toList();
        }

        public boolean isEmpty()
        {
            return companies.isEmpty();
        }

        /** The largest companies, or the largest groups, for the bar chart. */
        public List<ChartCompany> top()
        {
            var shown = isGrouped() ? groups.stream().map(ChartGroup::group).toList() : companies;
            return shown.subList(0, Math.min(TOP_COMPANIES, shown.size()));
        }
    }

    public Model present(LookThroughResult result)
    {
        return present(result, Grouping.COMPANY, CompanyEnrichmentService.Snapshot.empty());
    }

    /**
     * Presents the result grouped by an attribute of its companies. The
     * attributes are the memory-only snapshot of the enrichment; companies
     * without the attribute form the unknown group and companies that still
     * wait for enrichment the pending group. Company grouping lists the
     * companies without groups.
     */
    public Model present(LookThroughResult result, Grouping grouping, CompanyEnrichmentService.Snapshot attributes)
    {
        Objects.requireNonNull(result);
        Objects.requireNonNull(grouping);
        Objects.requireNonNull(attributes);

        var denominator = result.denominator();
        var denominatorAmount = denominator.map(value -> BigDecimal.valueOf(value.getAmount()));
        var valuation = result.valuation();
        var empty = valuation.positions().isEmpty() && valuation.diagnostics().isEmpty();

        var keys = new Keys();
        var rows = new ArrayList<Row>();
        var classifier = new LookThroughGrouping();
        var companyRows = new LinkedHashMap<Identifier, CompanyRow>();
        result.companies().stream().sorted(BY_TOTAL_THEN_IDENTIFIER).forEach(company -> companyRows.put(
                        company.identity(), companyRow(keys, company, denominatorAmount,
                                        detail(classifier, grouping, attributes.attributes().get(company.identity())))));
        var revenueNotice = Optional.<String>empty();
        if (grouping == Grouping.COMPANY)
            rows.addAll(companyRows.values());
        else if (grouping == Grouping.REVENUE_REGION)
        {
            rows.addAll(revenueRows(keys, classifier, companyRows, attributes, denominatorAmount));
            revenueNotice = Optional.of(revenueNotice(companyRows, attributes));
        }
        else
            rows.addAll(groupRows(keys, classifier, grouping, companyRows, attributes, denominatorAmount));

        // The coverage categories are rendered even when zero: their absence
        // would look like complete coverage of everything that was not resolved.
        // Unverified provider positions get their own category, and only when
        // present, so a portfolio without them stays uncluttered. An empty
        // portfolio shows the explicit empty state instead of zero rows.
        if (!empty)
        {
            rows.add(categoryRow(keys, result, Category.UNGROUPED_EQUITY, denominatorAmount));
            rows.add(categoryRow(keys, result, Category.CASH_OR_OTHER, denominatorAmount));
            rows.add(categoryRow(keys, result, Category.UNRESOLVED, denominatorAmount));
        }

        for (var diagnostic : valuation.diagnostics())
        {
            var label = Objects.toString(diagnostic.security().getName(), ""); //$NON-NLS-1$
            rows.add(new ExcludedRow(keys.unique(RowKey.of(RowKind.EXCLUDED, label, diagnostic.reason())), label,
                            diagnostic.reason(), exclusionText(diagnostic.reason())));
        }

        var completeness = completeness(result, denominator);
        var scopeText = MessageFormat.format(Messages.LookThroughLabelScope, Values.Date.format(valuation.date()));
        var denominatorText = denominator.map(value -> MessageFormat.format(Messages.LookThroughLabelDenominator,
                        Values.Money.format(value)));
        var completenessText = completeness == Completeness.COMPLETE ? Messages.LookThroughLabelComplete
                        : Messages.LookThroughLabelPartial;
        var unverifiedNotice = hasUnverifiedAllocationSemantics(result)
                        ? Optional.of(Messages.LookThroughLabelUnverifiedNotice)
                        : Optional.<String>empty();

        var funds = new FundCounts(result);
        var notices = new ArrayList<String>();
        notices.add(scopeText);
        notices.add(denominatorText.orElse(Messages.LookThroughLabelNoDenominator));
        exclusionNotice(valuation).ifPresent(notices::add);
        unverifiedNotice.ifPresent(notices::add);
        revenueNotice.ifPresent(notices::add);
        notices.add(completenessText);

        var largest = companyRows.values().stream().findFirst()
                        .filter(row -> row.share().isPresent()).map(row -> new Largest(row.label(),
                                        row.share().orElseThrow(), Values.Percent2.format(row.share().orElseThrow().doubleValue())));
        var concentrated = largest.filter(value -> value.share().compareTo(CONCENTRATION_THRESHOLD) > 0);
        concentrated.ifPresent(value -> notices.add(MessageFormat.format(Messages.LookThroughNoticeConcentration,
                        value.name(), value.shareText())));
        var effective = denominator.isPresent() ? effectiveCompanies(result) : Optional.<BigDecimal>empty();

        var open = result.value(Category.UNRESOLVED).add(result.value(Category.UNVERIFIED_HOLDING));
        var resolvedShare = share(open, denominatorAmount).map(BigDecimal.ONE::subtract);
        var topTenShare = topTenShare(result, denominatorAmount);
        var summary = new Summary(MessageFormat.format(Messages.LookThroughSummaryAsOf,
                        Values.Date.format(valuation.date())), valueText(valuation), resolvedShare,
                        resolvedShare.map(value -> boundedShareText(Values.Percent2, value, value.signum() > 0,
                                        open.signum() != 0)),
                        topTenShare, topTenShare.map(value -> Values.Percent2.format(value.doubleValue())),
                        funds.decomposed(), funds.held(),
                        funds.held() > 0 ? Optional.of(MessageFormat.format(Messages.LookThroughSummaryOf,
                                        funds.decomposed(), funds.held())) : Optional.empty(),
                        funds.held() > 0 || funds.unclassified(),
                        denominator.isEmpty() || !valuation.diagnostics().isEmpty() || unverifiedNotice.isPresent()
                                        || concentrated.isPresent(),
                        String.join("\n\n", notices), effective, effective.map(LookThroughViewPresenter::effectiveText), //$NON-NLS-1$
                        largest);

        return new Model(valuation.date(), valuation.valuedSubset().getCurrencyCode(), scopeText, denominator,
                        denominatorText, completeness, completenessText, unverifiedNotice, empty, rows, summary,
                        empty ? Optional.empty() : coverage(result, denominatorAmount), funds.shares(),
                        directlyClassified(result), grouping);
    }

    private List<GroupRow> groupRows(Keys keys, LookThroughGrouping classifier, Grouping grouping,
                    Map<Identifier, CompanyRow> companies, CompanyEnrichmentService.Snapshot attributes,
                    Optional<BigDecimal> denominator)
    {
        var regular = new LinkedHashMap<String, List<CompanyRow>>();
        var groups = new HashMap<String, Group>();
        var unknown = new ArrayList<CompanyRow>();
        var pending = new ArrayList<CompanyRow>();
        companies.forEach((identity, row) -> {
            var known = attributes.attributes().get(identity);
            var group = known == null ? Optional.<Group>empty() : classifier.groupOf(grouping, known);
            if (group.isPresent())
            {
                groups.putIfAbsent(group.get().code(), group.get());
                regular.computeIfAbsent(group.get().code(), code -> new ArrayList<>()).add(row);
            }
            else if (attributes.waiting().contains(identity))
            {
                pending.add(row);
            }
            else
            {
                unknown.add(row);
            }
        });

        var rows = new ArrayList<GroupRow>();
        regular.forEach((code, members) -> rows.add(groupRow(keys, grouping, GroupKind.REGULAR, code,
                        groups.get(code).label(), members, denominator)));
        rows.sort(Comparator.comparing(GroupRow::total).reversed().thenComparing(GroupRow::label));
        if (!unknown.isEmpty())
            rows.add(groupRow(keys, grouping, GroupKind.UNKNOWN, "~unknown", //$NON-NLS-1$
                            MessageFormat.format(Messages.LookThroughGroupUnknown, unknown.size()), unknown,
                            denominator));
        if (!pending.isEmpty())
            rows.add(groupRow(keys, grouping, GroupKind.PENDING, "~pending", //$NON-NLS-1$
                            MessageFormat.format(Messages.LookThroughGroupPending, pending.size()), pending,
                            denominator));
        return rows;
    }

    /**
     * Splits every company over the members of its revenue split. A company
     * appears in each group with its allocated part, with direct and indirect
     * parts and contributions scaled by the same fraction, so the groups add up
     * to the company exposure. Companies without a split form the group "No
     * revenue data", those still waiting for one the pending group.
     */
    private List<GroupRow> revenueRows(Keys keys, LookThroughGrouping classifier, Map<Identifier, CompanyRow> companies,
                    CompanyEnrichmentService.Snapshot attributes, Optional<BigDecimal> denominator)
    {
        var regular = new LinkedHashMap<RevenueSplit.Member, List<CompanyRow>>();
        var unknown = new ArrayList<CompanyRow>();
        var pending = new ArrayList<CompanyRow>();
        companies.forEach((identity, row) -> {
            var split = attributes.revenue().get(identity);
            if (split != null)
            {
                for (var share : split.shares())
                    regular.computeIfAbsent(share.member(), member -> new ArrayList<>())
                                    .add(allocated(keys, row, split, share, denominator));
            }
            else if (attributes.revenueWaiting().contains(identity))
            {
                pending.add(row);
            }
            else
            {
                // no revenue data: the headquarters country stands in for it
                var country = headquarters(attributes, identity);
                if (country.isPresent())
                    regular.computeIfAbsent(new RevenueSplit.Member(RevenueSplit.Kind.COUNTRY, country.get()),
                                    member -> new ArrayList<>()).add(fallback(keys, row, country.get(), denominator));
                else
                    unknown.add(row);
            }
        });

        var rows = new ArrayList<GroupRow>();
        regular.forEach((member, members) -> {
            members.sort(Comparator.comparing(CompanyRow::total).reversed().thenComparing(CompanyRow::label));
            var group = classifier.revenueGroup(member);
            rows.add(groupRow(keys, Grouping.REVENUE_REGION, GroupKind.REGULAR, group.code(), group.label(), members,
                            denominator));
        });
        rows.sort(Comparator.comparing(GroupRow::total).reversed().thenComparing(GroupRow::label));
        if (!unknown.isEmpty())
            rows.add(groupRow(keys, Grouping.REVENUE_REGION, GroupKind.UNKNOWN, "~unknown", //$NON-NLS-1$
                            MessageFormat.format(Messages.LookThroughGroupNoRevenue, unknown.size()), unknown,
                            denominator));
        if (!pending.isEmpty())
            rows.add(groupRow(keys, Grouping.REVENUE_REGION, GroupKind.PENDING, "~pending", //$NON-NLS-1$
                            MessageFormat.format(Messages.LookThroughGroupPending, pending.size()), pending,
                            denominator));
        return rows;
    }

    /** The part of a company that one member of its revenue split gets; the key is unique per member. */
    private CompanyRow allocated(Keys keys, CompanyRow row, RevenueSplit split, RevenueSplit.Share share,
                    Optional<BigDecimal> denominator)
    {
        var percent = Values.Percent2.format(share.percent().movePointLeft(2).doubleValue());
        var period = Values.Date.format(split.period());
        var detail = split.incomplete()
                        ? MessageFormat.format(Messages.LookThroughDetailRevenueScaled, percent, period,
                                        Values.Percent2.format(split.reportedSum().movePointLeft(2).doubleValue()))
                        : MessageFormat.format(Messages.LookThroughDetailRevenue, percent, period);
        return allocated(keys, row, share.member(), share.percent().movePointLeft(2), detail, denominator);
    }

    /** A company without revenue data, allocated completely to its headquarters country. */
    private CompanyRow fallback(Keys keys, CompanyRow row, String country, Optional<BigDecimal> denominator)
    {
        var detail = MessageFormat.format(Messages.LookThroughDetailRevenueFallback,
                        Values.Percent2.format(1d));
        return allocated(keys, row, new RevenueSplit.Member(RevenueSplit.Kind.COUNTRY, country), BigDecimal.ONE,
                        detail, denominator);
    }

    private static Optional<String> headquarters(CompanyEnrichmentService.Snapshot attributes, Identifier identity)
    {
        var known = attributes.attributes().get(identity);
        return known == null ? Optional.empty() : known.country().map(CompanyAttributes.Attribute::value);
    }

    private CompanyRow allocated(Keys keys, CompanyRow row, RevenueSplit.Member member, BigDecimal fraction,
                    String detail, Optional<BigDecimal> denominator)
    {
        // the fractions of a company add up to exactly 1, so the parts add up to the exact amount
        var key = keys.unique(row.key().child("revenue", member.key())); //$NON-NLS-1$
        var direct = row.direct().multiply(fraction);
        var indirect = row.indirect().multiply(fraction);
        var total = direct.add(indirect);
        var offset = row.key().parts().size();
        var contributions = row.contributions().stream().map(contribution -> {
            var parts = contribution.key().parts();
            var value = contribution.value().multiply(fraction);
            var contributionShare = share(value, denominator);
            return new ContributionRow(keys.unique(key.child(parts.subList(offset, parts.size()).toArray())),
                            contribution.label(), contribution.origin(), contribution.path(), contribution.sourceText(),
                            contribution.direct(), value, contributionShare, shareText(contributionShare));
        }).toList();
        var companyShare = share(total, denominator);
        return new CompanyRow(key, row.label(), direct, indirect, total, companyShare, shareText(companyShare),
                        contributions, Optional.of(detail));
    }

    /**
     * The share of the company exposure that has revenue data, the range of
     * reporting periods and the number of splits that had to be scaled.
     */
    private String revenueNotice(Map<Identifier, CompanyRow> companies, CompanyEnrichmentService.Snapshot attributes)
    {
        var all = BigDecimal.ZERO;
        var covered = BigDecimal.ZERO;
        var fallback = BigDecimal.ZERO;
        var splits = new ArrayList<RevenueSplit>();
        for (var entry : companies.entrySet())
        {
            all = all.add(entry.getValue().total());
            var split = attributes.revenue().get(entry.getKey());
            if (split != null)
            {
                covered = covered.add(entry.getValue().total());
                splits.add(split);
            }
            else if (!attributes.revenueWaiting().contains(entry.getKey())
                            && headquarters(attributes, entry.getKey()).isPresent())
            {
                fallback = fallback.add(entry.getValue().total());
            }
        }
        if (splits.isEmpty() && fallback.signum() <= 0 || all.signum() <= 0)
            return Messages.LookThroughRevenueCoverageNone;

        var rest = all.subtract(covered).subtract(fallback);
        var first = splits.stream().map(RevenueSplit::period).min(Comparator.naturalOrder()).orElse(null);
        var last = splits.stream().map(RevenueSplit::period).max(Comparator.naturalOrder()).orElse(null);
        var periods = first == null ? "\u2013" //$NON-NLS-1$
                        : first.equals(last) ? Values.Date.format(first)
                                        : Values.Date.format(first) + " \u2013 " + Values.Date.format(last); //$NON-NLS-1$
        var text = MessageFormat.format(Messages.LookThroughRevenueCoverage, percentOf(covered, all),
                        percentOf(fallback, all), percentOf(rest, all), periods);
        var incomplete = splits.stream().filter(RevenueSplit::incomplete).count();
        return incomplete == 0 ? text
                        : text + " " + MessageFormat.format(Messages.LookThroughRevenueIncomplete, incomplete); //$NON-NLS-1$
    }

    private static String percentOf(BigDecimal part, BigDecimal all)
    {
        return Values.Percent2.format(part.divide(all, SHARE_SCALE, RoundingMode.HALF_UP).doubleValue());
    }

    private GroupRow groupRow(Keys keys, Grouping grouping, GroupKind kind, String code, String label,
                    List<CompanyRow> members, Optional<BigDecimal> denominator)
    {
        var direct = members.stream().map(CompanyRow::direct).reduce(BigDecimal.ZERO, BigDecimal::add);
        var indirect = members.stream().map(CompanyRow::indirect).reduce(BigDecimal.ZERO, BigDecimal::add);
        var total = direct.add(indirect);
        var share = share(total, denominator);
        var key = keys.unique(RowKey.of("group:" + grouping.name().toLowerCase(Locale.ROOT) + ":" + code)); //$NON-NLS-1$ //$NON-NLS-2$
        return new GroupRow(key, grouping, kind, label, members.size(), direct, indirect, total, share,
                        shareText(share), members);
    }

    /** The country with its source, for the groupings that derive from the country. */
    private Optional<String> detail(LookThroughGrouping classifier, Grouping grouping, CompanyAttributes attributes)
    {
        if (attributes == null || attributes.country().isEmpty()
                        || (grouping != Grouping.COUNTRY && grouping != Grouping.REGION
                                        && grouping != Grouping.MARKET_CLASSIFICATION))
            return Optional.empty();
        var country = attributes.country().get();
        return Optional.of(MessageFormat.format(Messages.LookThroughDetailCountry,
                        classifier.country(country.value()).label(), sourceText(country.source())));
    }

    private static String sourceText(Source source)
    {
        return switch (source)
        {
            case REGISTER -> Messages.LookThroughAttributeSourceRegister;
            case PROVIDER -> Messages.LookThroughAttributeSourceProvider;
            case ISIN_PREFIX -> Messages.LookThroughAttributeSourceIsinPrefix;
        };
    }

    /**
     * What the view shows instead of a result. A calculation that is expected
     * but has not delivered yet is distinct from every empty state, so an
     * empty message never appears before the first result.
     */
    public enum ContentState
    {
        /** The first result for the open portfolio is still being calculated. */
        CALCULATING,
        /** A result exists; empty messages refer to it. */
        CALCULATED,
        /** No result and no calculation is expected, for example without a source. */
        NONE
    }

    public static ContentState contentState(boolean hasResult, boolean calculationExpected)
    {
        if (hasResult)
            return ContentState.CALCULATED;
        return calculationExpected ? ContentState.CALCULATING : ContentState.NONE;
    }

    /**
     * Identifiers of the held securities that the result classifies as
     * something other than a fund, for example a directly held share. A
     * security without classification is not included: it may still turn
     * out to be a fund.
     */
    private static Set<Map<String, String>> directlyClassified(LookThroughResult result)
    {
        var identifiers = new HashSet<Map<String, String>>();
        for (var contribution : result.contributions())
        {
            var state = contribution.originResolution().map(InstrumentResolution::state);
            if (state.isEmpty() || state.get() == InstrumentResolution.State.FUND
                            || state.get() == InstrumentResolution.State.UNRESOLVED)
                continue;
            var instrument = LookThroughCompositionService.instrumentOf(contribution.origin().security());
            if (!instrument.identifiers().isEmpty())
                identifiers.add(instrument.identifiers());
        }
        return identifiers;
    }

    /**
     * The securities value is always shown. Without a denominator it is only
     * the known part of the selection and labelled as incomplete.
     */
    private String valueText(SecuritiesValuation valuation)
    {
        var text = Values.Money.format(valuation.valuedSubset());
        return valuation.denominator().isPresent() ? text
                        : MessageFormat.format(Messages.LookThroughSummaryValueIncomplete, text);
    }

    /** Names every excluded position with its reason, or nothing. */
    private Optional<String> exclusionNotice(SecuritiesValuation valuation)
    {
        if (valuation.diagnostics().isEmpty())
            return Optional.empty();
        var names = valuation.diagnostics().stream() //
                        .map(diagnostic -> MessageFormat.format("{0} ({1})", //$NON-NLS-1$
                                        Objects.toString(diagnostic.security().getName(), ""), //$NON-NLS-1$
                                        exclusionText(diagnostic.reason()))) //
                        .collect(Collectors.joining(", ")); //$NON-NLS-1$
        return Optional.of(MessageFormat.format(Messages.LookThroughNoticeExcluded, names));
    }

    /**
     * One divided by the sum of the squared shares of the companies, each
     * relative to the total company exposure. Absent without company exposure.
     */
    private static Optional<BigDecimal> effectiveCompanies(LookThroughResult result)
    {
        var total = result.value(Category.COMPANY);
        if (result.companies().isEmpty() || total.signum() <= 0)
            return Optional.empty();
        var squares = BigDecimal.ZERO;
        for (var company : result.companies())
        {
            var weight = company.total().divide(total, MathContext.DECIMAL64);
            squares = squares.add(weight.multiply(weight));
        }
        return squares.signum() > 0 ? Optional.of(BigDecimal.ONE.divide(squares, MathContext.DECIMAL64))
                        : Optional.empty();
    }

    /** The effective number of companies as a whole number. */
    static long effectiveCount(BigDecimal value)
    {
        return value.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** The effective number of companies, rounded to a whole number. */
    static String effectiveText(BigDecimal value)
    {
        return MessageFormat.format(Messages.LookThroughSummaryEffectiveValue, effectiveCount(value));
    }

    /** Combined share of the ten largest companies. */
    private Optional<BigDecimal> topTenShare(LookThroughResult result, Optional<BigDecimal> denominator)
    {
        if (result.companies().isEmpty())
            return Optional.empty();
        var top = result.companies().stream().sorted(BY_TOTAL_THEN_IDENTIFIER).limit(10).map(Company::total)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return share(top, denominator);
    }

    private Optional<Coverage> coverage(LookThroughResult result, Optional<BigDecimal> denominator)
    {
        if (denominator.isEmpty())
            return Optional.empty();

        var segments = new ArrayList<Segment>();
        segments.add(segment(SegmentKind.COMPANIES, Messages.LookThroughCoverageCompanies,
                        result.value(Category.COMPANY), denominator.orElseThrow(), result));
        segments.add(segment(SegmentKind.UNGROUPED_EQUITY, Messages.LookThroughCategoryUngroupedEquity,
                        result.value(Category.UNGROUPED_EQUITY), denominator.orElseThrow(), result));
        segments.add(segment(SegmentKind.CASH_OR_OTHER, Messages.LookThroughCategoryCashOrOther,
                        result.value(Category.CASH_OR_OTHER), denominator.orElseThrow(), result));
        // Like the table, unverified provider positions only appear when present.
        var unverifiedValue = result.value(Category.UNVERIFIED_HOLDING);
        if (unverifiedValue.signum() != 0)
            segments.add(segment(SegmentKind.UNVERIFIED, Messages.LookThroughCategoryUnverified, unverifiedValue,
                            denominator.orElseThrow(), result));
        segments.add(segment(SegmentKind.UNRESOLVED, Messages.LookThroughCategoryUnresolved,
                        result.value(Category.UNRESOLVED), denominator.orElseThrow(), result));
        return Optional.of(new Coverage(segments));
    }

    private Segment segment(SegmentKind kind, String label, BigDecimal value, BigDecimal denominator,
                    LookThroughResult result)
    {
        var fraction = value.divide(denominator, SHARE_SCALE, RoundingMode.HALF_UP);
        var nonzero = value.signum() != 0;
        var currency = result.valuation().valuedSubset().getCurrencyCode();
        return new Segment(kind, label, value, formatAmount(value, currency), fraction,
                        boundedShareText(Values.Percent2, fraction, nonzero, value.compareTo(denominator) < 0),
                        nonzero);
    }

    /**
     * Display text of a coverage share. While a remainder exists the share is
     * rounded down, so it can never read as a full 100 percent, and a nonzero
     * value whose share rounds to zero is shown as below the smallest
     * displayable share instead of as zero.
     */
    private static String boundedShareText(Values<Double> format, BigDecimal fraction, boolean nonzero,
                    boolean remainder)
    {
        var value = remainder ? fraction.setScale(4, RoundingMode.DOWN) : fraction;
        var text = format.format(value.doubleValue());
        if (nonzero && text.equals(format.format(0d)))
            return "< " + format.format(0.0001d); //$NON-NLS-1$
        return text;
    }

    /**
     * Held funds and how many of them were decomposed, counted per held
     * security that the provider evidence classifies as a fund. A fund is
     * decomposed when at least one contribution passes through its
     * composition. The decomposed share of a fund is the part of its value
     * that reached a category other than unresolved.
     */
    private static final class FundCounts
    {
        private final Map<Security, BigDecimal> held = new LinkedHashMap<>();
        private final Map<Security, BigDecimal> decomposed = new HashMap<>();
        private boolean unclassified;

        private FundCounts(LookThroughResult result)
        {
            for (var contribution : result.contributions())
            {
                var security = contribution.origin().security();
                var fund = contribution.originResolution()
                                .filter(resolution -> resolution.state() == InstrumentResolution.State.FUND)
                                .isPresent();
                if (!fund)
                {
                    // A held position without classification may still be a
                    // fund that needs a composition.
                    if (contribution.isDirect() && contribution.category() == Category.UNRESOLVED)
                        unclassified = true;
                    continue;
                }
                held.putIfAbsent(security, BigDecimal.valueOf(contribution.origin().value().getAmount()));
                if (!contribution.isDirect())
                {
                    var value = contribution.category() == Category.UNRESOLVED
                                    || contribution.category() == Category.UNVERIFIED_HOLDING ? BigDecimal.ZERO
                                                    : contribution.value();
                    decomposed.merge(security, value, BigDecimal::add);
                }
            }
        }

        private int held()
        {
            return held.size();
        }

        private int decomposed()
        {
            return decomposed.size();
        }

        private boolean unclassified()
        {
            return unclassified;
        }

        private Map<Map<String, String>, String> shares()
        {
            var shares = new HashMap<Map<String, String>, String>();
            held.forEach((security, value) -> {
                if (value.signum() <= 0)
                    return;
                var resolved = decomposed.getOrDefault(security, BigDecimal.ZERO);
                var fraction = resolved.divide(value, SHARE_SCALE, RoundingMode.DOWN);
                var text = boundedShareText(Values.Percent, fraction, resolved.signum() != 0,
                                resolved.compareTo(value) < 0);
                shares.putIfAbsent(LookThroughCompositionService.instrumentOf(security).identifiers(), text);
            });
            return shares;
        }
    }

    /**
     * The issuer breakdown is shown from provider-classified constituents, but
     * the underlying weights are only validated when every composition on a
     * path declares total-fund-NAV semantics.
     */
    private boolean hasUnverifiedAllocationSemantics(LookThroughResult result)
    {
        return result.contributions().stream() //
                        .flatMap(contribution -> contribution.path().stream()) //
                        .map(LookThroughContribution.Edge::composition) //
                        .anyMatch(validation -> !validation.semanticsVerified());
    }

    /** Formats an exact minor-unit value for display only, never for decisions. */
    public static String formatAmount(BigDecimal value, String currencyCode)
    {
        var minorUnits = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        return Values.Money.format(Money.of(currencyCode, minorUnits));
    }

    /** The key of the row of a company; the table holds one row per company. */
    static RowKey companyKey(Identifier identity)
    {
        return RowKey.of(RowKind.COMPANY, identity);
    }

    private CompanyRow companyRow(Keys keys, Company company, Optional<BigDecimal> denominator,
                    Optional<String> detail)
    {
        var key = keys.unique(companyKey(company.identity()));
        var contributions = company.contributions().stream() //
                        .map(contribution -> contributionRow(keys, key, contribution, denominator)) //
                        .toList();
        var share = share(company.total(), denominator);
        // The company group is keyed by issuer identifier; the issuer label is
        // the instrument name supplied by the provider or the owned security.
        var label = company.contributions().stream() //
                        .map(contribution -> contribution.instrument().label()) //
                        .filter(value -> !value.isBlank()).findFirst().orElse(company.identity().value());
        return new CompanyRow(key, label, company.direct(), company.indirect(), company.total(), share, shareText(share),
                        contributions, detail);
    }

    private ContributionRow contributionRow(Keys keys, RowKey parent, LookThroughContribution contribution,
                    Optional<BigDecimal> denominator)
    {
        var path = pathText(contribution);
        var origin = Objects.toString(contribution.origin().security().getName(), ""); //$NON-NLS-1$
        var sourceText = contribution.isDirect() ? Messages.LookThroughSourceDirect
                        : MessageFormat.format(Messages.LookThroughSourceIndirect, path, origin);

        var share = share(contribution.value(), denominator);
        return new ContributionRow(keys.unique(sourceKey(parent, contribution)),
                        Objects.toString(contribution.instrument().label(), ""), origin, path, sourceText, //$NON-NLS-1$
                        contribution.isDirect(), contribution.value(), share, shareText(share));
    }

    private CategoryRow categoryRow(Keys keys, LookThroughResult result, Category category,
                    Optional<BigDecimal> denominator)
    {
        var key = keys.unique(RowKey.of(RowKind.CATEGORY, category));
        var value = result.value(category);
        var share = share(value, denominator);
        return new CategoryRow(key, category, categoryText(category), value, share, shareText(share),
                        details(keys, key, result, category, denominator));
    }

    private List<DetailRow> details(Keys keys, RowKey parent, LookThroughResult result, Category category,
                    Optional<BigDecimal> denominator)
    {
        return result.contributions().stream() //
                        .filter(contribution -> contribution.category() == category) //
                        .map(contribution -> detailRow(keys, parent, contribution, denominator)) //
                        .sorted(BY_DETAIL_VALUE) //
                        .toList();
    }

    private DetailRow detailRow(Keys keys, RowKey parent, LookThroughContribution contribution,
                    Optional<BigDecimal> denominator)
    {
        var path = pathText(contribution);
        var origin = Objects.toString(contribution.origin().security().getName(), ""); //$NON-NLS-1$
        var sourceText = contribution.isDirect() ? Messages.LookThroughSourceDirect
                        : MessageFormat.format(Messages.LookThroughSourceIndirect, path, origin);
        var share = share(contribution.value(), denominator);
        return new DetailRow(keys.unique(sourceKey(parent, contribution)),
                        Objects.toString(contribution.instrument().label(), ""), sourceText, //$NON-NLS-1$
                        note(contribution), contribution.isDirect(), contribution.value(), share, shareText(share));
    }

    /**
     * Identifies one source below its parent row: the owned security, every
     * fund on the path with the occurrence index of the row in its
     * composition, and the instrument identifiers.
     */
    private RowKey sourceKey(RowKey parent, LookThroughContribution contribution)
    {
        var path = contribution.path().stream() //
                        .map(edge -> (Object) List.of(edge.provenance().fund().identifiers(), edge.sourceIndex())) //
                        .toList();
        return parent.child(Objects.toString(contribution.origin().security().getUUID(), ""), path, //$NON-NLS-1$
                        contribution.instrument().identifiers());
    }

    private String pathText(LookThroughContribution contribution)
    {
        return contribution.path().stream() //
                        .map(edge -> edge.composition().source().provenance().fund().label()) //
                        .collect(Collectors.joining(" / ")); //$NON-NLS-1$
    }

    private String note(LookThroughContribution contribution)
    {
        return switch (contribution.category())
        {
            case UNVERIFIED_HOLDING -> Messages.LookThroughReasonProviderWeightUnverified;
            case UNGROUPED_EQUITY -> Messages.LookThroughReasonMissingCompany;
            case UNRESOLVED -> unresolvedText(contribution);
            case CASH_OR_OTHER -> ""; //$NON-NLS-1$
            default -> ""; //$NON-NLS-1$
        };
    }

    private String unresolvedText(LookThroughContribution contribution)
    {
        var cause = switch (contribution.reason())
        {
            case MISSING_COMPOSITION -> Messages.LookThroughReasonMissingComposition;
            case UNUSABLE_COMPOSITION -> Messages.LookThroughReasonUnusableComposition;
            case NAV_SHORTFALL -> Messages.LookThroughReasonCompositionShortfall;
            case CYCLE, DEPTH_LIMIT, COMPOSITION_IDENTITY_MISMATCH -> Messages.LookThroughReasonTraversalStopped;
            default -> null;
        };
        if (cause != null)
            return cause;
        if (contribution.resolution().isEmpty())
            return Messages.LookThroughReasonMissingResolution;
        return switch (contribution.resolution().orElseThrow().reason())
        {
            case MISSING_IDENTIFIERS -> Messages.LookThroughReasonMissingIdentifiers;
            case MISSING_IDENTITY -> Messages.LookThroughReasonMissingIdentity;
            case AMBIGUOUS_IDENTITY -> Messages.LookThroughReasonAmbiguousIdentity;
            case UNKNOWN_CLASSIFICATION -> Messages.LookThroughReasonUnknownClassification;
            case AMBIGUOUS_CLASSIFICATION -> Messages.LookThroughReasonAmbiguousClassification;
            case MISSING_COMPANY -> Messages.LookThroughReasonMissingCompany;
            case AMBIGUOUS_COMPANY -> Messages.LookThroughReasonAmbiguousCompany;
            case VERIFIED -> Messages.LookThroughReasonMissingResolution;
        };
    }

    private Optional<BigDecimal> share(BigDecimal value, Optional<BigDecimal> denominator)
    {
        return denominator.map(amount -> value.divide(amount, SHARE_SCALE, RoundingMode.HALF_UP));
    }

    /** Share with a percent sign, for texts without a percent column. */
    private static Optional<String> percentText(Optional<BigDecimal> share)
    {
        return share.map(value -> Values.Percent2.format(value.doubleValue()));
    }

    private Optional<String> shareText(Optional<BigDecimal> share)
    {
        return share.map(value -> Values.Percent.format(value.doubleValue()));
    }

    private Completeness completeness(LookThroughResult result, Optional<Money> denominator)
    {
        // A present denominator already implies a complete valuation with a
        // positive valued subset. Complete coverage additionally requires the
        // entire selection to be supported, no unresolved or unverified value at
        // all (checked exactly, never through a rounded share), no unverified
        // company grouping and an affirmative complete declaration for every
        // contributing composition. Display rounding is deliberately not
        // consulted.
        var valuation = result.valuation();
        var complete = denominator.isPresent() //
                        && valuation.isValuationComplete() //
                        && valuation.isEntireSelectionSupported() //
                        && result.value(Category.UNRESOLVED).signum() == 0 //
                        && result.value(Category.UNVERIFIED_HOLDING).signum() == 0 //
                        && result.value(Category.UNGROUPED_EQUITY).signum() == 0 //
                        && !hasUnverifiedAllocationSemantics(result) //
                        && hasAffirmativelyCompleteCompositions(result);
        return complete ? Completeness.COMPLETE : Completeness.PARTIAL;
    }

    /**
     * The validator rejects an unverified denominator and an unsupported
     * exposure basis, but it treats completeness metadata as unverified.
     * Anything below an affirmative complete declaration therefore leaves
     * coverage partial: neither a reconciled sum nor an unknown or partial
     * declaration proves that the breakdown covers the fund. Successfully
     * decomposed rows keep their validation on the path edges; only failed and
     * shortfall rows carry it directly.
     */
    private boolean hasAffirmativelyCompleteCompositions(LookThroughResult result)
    {
        return result.contributions().stream() //
                        .flatMap(contribution -> contribution.path().stream()) //
                        .map(edge -> edge.composition().source()) //
                        .allMatch(source -> source.completeness() == FundComposition.Completeness.COMPLETE);
    }

    private String categoryText(Category category)
    {
        return switch (category)
        {
            case CASH_OR_OTHER -> Messages.LookThroughCategoryCashOrOther;
            case UNGROUPED_EQUITY -> Messages.LookThroughCategoryUngroupedEquity;
            case UNRESOLVED -> Messages.LookThroughCategoryUnresolved;
            case UNVERIFIED_HOLDING -> Messages.LookThroughCategoryUnverified;
            default -> throw new IllegalArgumentException(category.name());
        };
    }

    private String exclusionText(SecuritiesValuation.Reason reason)
    {
        return switch (reason)
        {
            case UNSUPPORTED_NEGATIVE_POSITION -> Messages.LookThroughReasonUnsupportedNegativePosition;
            case MISSING_VALUATION -> Messages.LookThroughReasonMissingValuation;
            case MISSING_CONVERSION -> Messages.LookThroughReasonMissingConversion;
            default -> throw new IllegalArgumentException(reason.name());
        };
    }
}
