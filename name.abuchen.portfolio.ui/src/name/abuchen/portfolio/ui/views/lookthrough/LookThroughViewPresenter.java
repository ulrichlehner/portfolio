package name.abuchen.portfolio.ui.views.lookthrough;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
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

    public sealed interface Row permits CompanyRow, ContributionRow, CategoryRow, DetailRow, ExcludedRow
    {
        RowKind kind();

        String label();
    }

    /** Aggregated verified exposure of one company identity. */
    public record CompanyRow(String label, BigDecimal direct, BigDecimal indirect, BigDecimal total,
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
    public record ContributionRow(String label, String origin, String path, String sourceText, boolean direct,
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
    public record DetailRow(String label, String sourceText, String note, boolean direct, BigDecimal value,
                    Optional<BigDecimal> share, Optional<String> shareText) implements Row
    {
        @Override
        public RowKind kind()
        {
            return RowKind.DETAIL;
        }
    }

    /** Always-present aggregate for a non-company coverage category. */
    public record CategoryRow(Category category, String label, BigDecimal value, Optional<BigDecimal> share,
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
    public record ExcludedRow(String label, SecuritiesValuation.Reason exclusion, String reasonText) implements Row
    {
        @Override
        public RowKind kind()
        {
            return RowKind.EXCLUDED;
        }
    }

    public record Model(LocalDate valuationDate, String currencyCode, String scopeText, Optional<Money> denominator,
                    Optional<String> denominatorText, Completeness completeness, String completenessText,
                    Optional<String> unverifiedNotice, boolean empty, List<Row> rows)
    {
        public Model
        {
            rows = List.copyOf(rows);
        }
    }

    public Model present(LookThroughResult result)
    {
        Objects.requireNonNull(result);

        var denominator = result.denominator();
        var denominatorAmount = denominator.map(value -> BigDecimal.valueOf(value.getAmount()));
        var valuation = result.valuation();
        var empty = valuation.positions().isEmpty() && valuation.diagnostics().isEmpty();

        var rows = new ArrayList<Row>();
        result.companies().stream().sorted(BY_TOTAL_THEN_IDENTIFIER)
                        .forEach(company -> rows.add(companyRow(company, denominatorAmount)));

        // The coverage categories are rendered even when zero: their absence
        // would look like complete coverage of everything that was not resolved.
        // Unverified provider positions get their own category, and only when
        // present, so a portfolio without them stays uncluttered. An empty
        // portfolio shows the explicit empty state instead of zero rows.
        if (!empty)
        {
            rows.add(categoryRow(result, Category.UNGROUPED_EQUITY, denominatorAmount));
            rows.add(categoryRow(result, Category.CASH_OR_OTHER, denominatorAmount));
            rows.add(categoryRow(result, Category.UNRESOLVED, denominatorAmount));
        }

        for (var diagnostic : valuation.diagnostics())
            rows.add(new ExcludedRow(Objects.toString(diagnostic.security().getName(), ""), //$NON-NLS-1$
                            diagnostic.reason(), exclusionText(diagnostic.reason())));

        var completeness = completeness(result, denominator);

        return new Model(valuation.date(), valuation.valuedSubset().getCurrencyCode(),
                        MessageFormat.format(Messages.LookThroughLabelScope, Values.Date.format(valuation.date())),
                        denominator,
                        denominator.map(value -> MessageFormat.format(Messages.LookThroughLabelDenominator,
                                        Values.Money.format(value))),
                        completeness,
                        completeness == Completeness.COMPLETE ? Messages.LookThroughLabelComplete
                                        : Messages.LookThroughLabelPartial,
                        hasUnverifiedAllocationSemantics(result)
                                        ? Optional.of(Messages.LookThroughLabelUnverifiedNotice)
                                        : Optional.empty(),
                        empty, rows);
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

    private CompanyRow companyRow(Company company, Optional<BigDecimal> denominator)
    {
        var contributions = company.contributions().stream() //
                        .map(contribution -> contributionRow(contribution, denominator)) //
                        .toList();
        var share = share(company.total(), denominator);
        // The company group is keyed by issuer identifier; the issuer label is
        // the instrument name supplied by the provider or the owned security.
        var label = company.contributions().stream() //
                        .map(contribution -> contribution.instrument().label()) //
                        .filter(value -> !value.isBlank()).findFirst().orElse(company.identity().value());
        return new CompanyRow(label, company.direct(), company.indirect(), company.total(), share, shareText(share),
                        contributions);
    }

    private ContributionRow contributionRow(LookThroughContribution contribution, Optional<BigDecimal> denominator)
    {
        var path = pathText(contribution);
        var origin = Objects.toString(contribution.origin().security().getName(), ""); //$NON-NLS-1$
        var sourceText = contribution.isDirect()
                        ? MessageFormat.format(Messages.LookThroughSourceDirect, origin)
                        : MessageFormat.format(Messages.LookThroughSourceIndirect, path, origin);

        var share = share(contribution.value(), denominator);
        return new ContributionRow(Objects.toString(contribution.instrument().label(), ""), origin, path, sourceText, //$NON-NLS-1$
                        contribution.isDirect(), contribution.value(), share, shareText(share));
    }

    private CategoryRow categoryRow(LookThroughResult result, Category category, Optional<BigDecimal> denominator)
    {
        var value = result.value(category);
        var share = share(value, denominator);
        return new CategoryRow(category, categoryText(category), value, share, shareText(share),
                        details(result, category, denominator));
    }

    private List<DetailRow> details(LookThroughResult result, Category category, Optional<BigDecimal> denominator)
    {
        return result.contributions().stream() //
                        .filter(contribution -> contribution.category() == category) //
                        .map(contribution -> detailRow(contribution, denominator)) //
                        .sorted(BY_DETAIL_VALUE) //
                        .toList();
    }

    private DetailRow detailRow(LookThroughContribution contribution, Optional<BigDecimal> denominator)
    {
        var path = pathText(contribution);
        var origin = Objects.toString(contribution.origin().security().getName(), ""); //$NON-NLS-1$
        var sourceText = contribution.isDirect()
                        ? MessageFormat.format(Messages.LookThroughSourceDirect, origin)
                        : MessageFormat.format(Messages.LookThroughSourceIndirect, path, origin);
        var share = share(contribution.value(), denominator);
        return new DetailRow(Objects.toString(contribution.instrument().label(), ""), sourceText, //$NON-NLS-1$
                        note(contribution), contribution.isDirect(), contribution.value(), share, shareText(share));
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
