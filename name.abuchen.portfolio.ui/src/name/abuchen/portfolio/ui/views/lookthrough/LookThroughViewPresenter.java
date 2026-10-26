package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult.Company;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation;
import name.abuchen.portfolio.ui.Messages;

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
        COMPANY, CONTRIBUTION, CATEGORY, DETAIL, EXCLUDED
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

    public sealed interface Row permits CompanyRow, ContributionRow, CategoryRow, DetailRow, ExcludedRow
    {
        RowKey key();

        RowKind kind();

        String label();
    }

    /** Aggregated verified exposure of one company identity. */
    public record CompanyRow(RowKey key, String label, BigDecimal direct, BigDecimal indirect, BigDecimal total,
                    Optional<BigDecimal> share, Optional<String> shareText, List<ContributionRow> contributions)
                    implements Row
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
                    boolean attention, String indicatorText)
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
                    Optional<Coverage> coverage, Map<Map<String, String>, String> decomposedShares)
    {
        public Model
        {
            rows = List.copyOf(rows);
            decomposedShares = Map.copyOf(decomposedShares);
        }
    }

    public Model present(LookThroughResult result)
    {
        Objects.requireNonNull(result);

        var denominator = result.denominator();
        var denominatorAmount = denominator.map(value -> BigDecimal.valueOf(value.getAmount()));
        var valuation = result.valuation();
        var empty = valuation.positions().isEmpty() && valuation.diagnostics().isEmpty();

        var keys = new Keys();
        var rows = new ArrayList<Row>();
        result.companies().stream().sorted(BY_TOTAL_THEN_IDENTIFIER)
                        .forEach(company -> rows.add(companyRow(keys, company, denominatorAmount)));

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
        notices.add(completenessText);

        var open = result.value(Category.UNRESOLVED).add(result.value(Category.UNVERIFIED_HOLDING));
        var resolvedShare = share(open, denominatorAmount).map(BigDecimal.ONE::subtract);
        var topTenShare = topTenShare(result, denominatorAmount);
        var summary = new Summary(MessageFormat.format(Messages.LookThroughSummaryAsOf,
                        Values.Date.format(valuation.date())), valueText(valuation), resolvedShare,
                        resolvedShare.map(value -> boundedShareText(value, value.signum() > 0, open.signum() != 0)),
                        topTenShare, topTenShare.map(value -> Values.Percent.format(value.doubleValue())),
                        funds.decomposed(), funds.held(),
                        funds.held() > 0 ? Optional.of(MessageFormat.format(Messages.LookThroughSummaryOf,
                                        funds.decomposed(), funds.held())) : Optional.empty(),
                        funds.held() > 0 || funds.unclassified(),
                        denominator.isEmpty() || !valuation.diagnostics().isEmpty() || unverifiedNotice.isPresent(),
                        String.join("\n\n", notices)); //$NON-NLS-1$

        return new Model(valuation.date(), valuation.valuedSubset().getCurrencyCode(), scopeText, denominator,
                        denominatorText, completeness, completenessText, unverifiedNotice, empty, rows, summary,
                        empty ? Optional.empty() : coverage(result, denominatorAmount), funds.shares());
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
                        boundedShareText(fraction, nonzero, value.compareTo(denominator) < 0), nonzero);
    }

    /**
     * Display text of a coverage share. While a remainder exists the share is
     * rounded down, so it can never read as a full 100 percent, and a nonzero
     * value whose share rounds to zero is shown as below the smallest
     * displayable share instead of as zero.
     */
    private static String boundedShareText(BigDecimal fraction, boolean nonzero, boolean remainder)
    {
        var value = remainder ? fraction.setScale(4, RoundingMode.DOWN) : fraction;
        var text = Values.Percent.format(value.doubleValue());
        if (nonzero && text.equals(Values.Percent.format(0d)))
            return "< " + Values.Percent.format(0.0001d); //$NON-NLS-1$
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
                var text = boundedShareText(fraction, resolved.signum() != 0, resolved.compareTo(value) < 0);
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

    private CompanyRow companyRow(Keys keys, Company company, Optional<BigDecimal> denominator)
    {
        var key = keys.unique(RowKey.of(RowKind.COMPANY, company.identity()));
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
                        contributions);
    }

    private ContributionRow contributionRow(Keys keys, RowKey parent, LookThroughContribution contribution,
                    Optional<BigDecimal> denominator)
    {
        var path = pathText(contribution);
        var origin = Objects.toString(contribution.origin().security().getName(), ""); //$NON-NLS-1$
        var sourceText = contribution.isDirect()
                        ? MessageFormat.format(Messages.LookThroughSourceDirect, origin)
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
        var sourceText = contribution.isDirect()
                        ? MessageFormat.format(Messages.LookThroughSourceDirect, origin)
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
