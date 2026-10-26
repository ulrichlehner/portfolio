package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.ExchangeRateTimeSeries;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolver;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughContribution.Category;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughEngine;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuationAdapter;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CategoryRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CompanyRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ExcludedRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Model;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Row;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.RowKey;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.Segment;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.SegmentKind;

/**
 * Synthetic-only tests of the SWT-free presenter. The look-through result is
 * built through the public pipeline (market valuation adapter plus engine) so
 * that no core internals are duplicated or modified.
 */
@SuppressWarnings("nls")
public class LookThroughViewPresenterTest
{
    private static final String TRADE_DATE = "2026-01-02";
    private static final LocalDate VALUATION_DATE = LocalDate.parse("2026-01-15");
    private static final Instant RETRIEVED = Instant.parse("2026-01-16T12:00:00Z");
    private static final ProviderAccount SOURCE = new ProviderAccount("synthetic", "test");

    private final Client client = new Client();
    private final PortfolioBuilder portfolio = new PortfolioBuilder();
    private final Map<Security, InstrumentResolution> owned = new HashMap<>();
    private final Map<CompositionInstrument, InstrumentResolution> resolutions = new HashMap<>();
    private final Map<Identifier, FundComposition> compositions = new HashMap<>();
    private final Map<Identifier, FundComposition> unverified = new HashMap<>();
    private final Map<String, CompositionInstrument> instruments = new HashMap<>();
    private final List<Security> selected = new ArrayList<>();

    private CompositionInstrument instrument(String id, AssetType type, String company)
    {
        return instrument(id, "Synthetic " + id, type, company);
    }

    private CompositionInstrument instrument(String id, String label, AssetType type, String company)
    {
        var existing = instruments.get(id);
        if (existing != null)
            return existing;

        var instrument = new CompositionInstrument(label, Map.of("synthetic:instrument", id));
        var provenance = new Provenance(SOURCE, "record-" + id, RETRIEVED);
        var evidence = new InstrumentEvidence(instrument.identifiers(),
                        new Assertion<>(new Identifier("synthetic:instrument", id), provenance),
                        Optional.ofNullable(type).map(value -> new Assertion<>(value, provenance)),
                        Optional.ofNullable(company)
                                        .map(value -> new Assertion<>(new Identifier("synthetic:company", value),
                                                        provenance)));
        resolutions.put(instrument, new InstrumentResolver().resolve(instrument, List.of(evidence),
                        ignored -> List.of(), () -> false));
        instruments.put(id, instrument);
        return instrument;
    }

    private Security hold(String id, String name, AssetType type, String company, double shares, double price)
    {
        var security = new SecurityBuilder("EUR").addPrice(TRADE_DATE, Values.Quote.factorize(price)).addTo(client);
        security.setName(name);
        security.setIsin("SYNTH-" + id);

        var resolution = resolutions.get(instrument(id, type, company));
        owned.put(security, resolution);
        portfolio.inbound_delivery(security, TRADE_DATE, Values.Share.factorize(shares),
                        Values.Amount.factorize(shares * price));
        selected.add(security);
        return security;
    }

    private Security holdWithoutPrice(String id, String name, AssetType type, String company, double shares)
    {
        var security = new SecurityBuilder("EUR").addTo(client);
        security.setName(name);
        security.setIsin("SYNTH-" + id);

        var resolution = resolutions.get(instrument(id, type, company));
        owned.put(security, resolution);
        portfolio.inbound_delivery(security, TRADE_DATE, Values.Share.factorize(shares), 0);
        selected.add(security);
        return security;
    }

    private Constituent row(CompositionInstrument instrument, String weight)
    {
        return new Constituent(instrument, Optional.of(weight));
    }

    private void composition(CompositionInstrument fund, Constituent... rows)
    {
        composition(Completeness.UNKNOWN, fund, rows);
    }

    private void composition(Completeness completeness, CompositionInstrument fund, Constituent... rows)
    {
        var provenance = new CompositionProvenance(SOURCE, fund, RETRIEVED, Optional.empty(), Optional.empty());
        var composition = new FundComposition(provenance, Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS,
                        completeness, List.of(rows));
        compositions.put(resolutions.get(fund).instrument().orElseThrow(), composition);
    }

    private LookThroughResult result()
    {
        return result(unverified);
    }

    private LookThroughResult result(Map<Identifier, FundComposition> unverifiedCompositions)
    {
        portfolio.addTo(client);

        var rates = new ExchangeRateProviderFactory(client)
        {
            @Override
            public List<ExchangeRateTimeSeries> getAvailableTimeSeries()
            {
                return List.of();
            }
        };
        var snapshot = ClientSnapshot.create(client, new CurrencyConverterImpl(rates, "EUR"), VALUATION_DATE);
        var valuation = new SecuritiesValuationAdapter(rates).value(snapshot, selected);
        return new LookThroughEngine().calculate(valuation, owned, resolutions, compositions, unverifiedCompositions);
    }

    private Model model(LookThroughResult result)
    {
        return new LookThroughViewPresenter().present(result);
    }

    private CompanyRow company(Model model, String label)
    {
        return model.rows().stream().filter(CompanyRow.class::isInstance).map(CompanyRow.class::cast)
                        .filter(row -> row.label().equals(label)).findFirst().orElseThrow();
    }

    private CategoryRow category(Model model, Category category)
    {
        return model.rows().stream().filter(CategoryRow.class::isInstance).map(CategoryRow.class::cast)
                        .filter(row -> row.category() == category).findFirst().orElseThrow();
    }

    private ExcludedRow excluded(Model model, String label)
    {
        return model.rows().stream().filter(ExcludedRow.class::isInstance).map(ExcludedRow.class::cast)
                        .filter(row -> row.label().equals(label)).findFirst().orElseThrow();
    }

    private BigDecimal allocatedShares(Model model)
    {
        var total = BigDecimal.ZERO;
        for (var row : model.rows())
        {
            if (row instanceof CompanyRow companyRow)
                total = total.add(companyRow.share().orElseThrow());
            else if (row instanceof CategoryRow categoryRow)
                total = total.add(categoryRow.share().orElseThrow());
        }
        return total;
    }

    private List<RowKey> keys(List<? extends Row> rows)
    {
        var keys = new ArrayList<RowKey>();
        for (var row : rows)
        {
            keys.add(row.key());
            if (row instanceof CompanyRow companyRow)
                keys.addAll(keys(companyRow.contributions()));
            else if (row instanceof CategoryRow categoryRow)
                keys.addAll(keys(categoryRow.details()));
        }
        return keys;
    }

    private void amount(String expected, BigDecimal actual)
    {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    public void testDirectAndTwoFundOverlapAggregatesIntoOneCompanyWithTraceableSources()
    {
        var direct = hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 3, 10);
        hold("first-fund", "Synthetic first fund holding", AssetType.FUND, null, 1, 100);
        hold("second-fund", "Synthetic second fund holding", AssetType.FUND, null, 1, 100);
        new AccountBuilder().deposit_(TRADE_DATE, Values.Amount.factorize(9000)).addTo(client);

        var company = instrument("company", AssetType.EQUITY, "SYNTH-CO");
        var cash = instrument("cash", AssetType.CASH, null);
        var ungrouped = instrument("ungrouped", AssetType.EQUITY, null);
        var missing = new CompositionInstrument("Synthetic missing constituent",
                        Map.of("synthetic:instrument", "missing"));
        var nested = instrument("nested-fund", AssetType.FUND, null);

        composition(instruments.get("first-fund"), row(company, "0.4"), row(cash, "0.1"), row(ungrouped, "0.2"),
                        row(missing, "0.3"));
        composition(instruments.get("second-fund"), row(company, "0.5"), row(nested, "0.5"));

        var result = result();
        var model = model(result);

        var companyRow = company(model, "Synthetic company");
        amount("3000", companyRow.direct());
        amount("9000", companyRow.indirect());
        amount("12000", companyRow.total());
        assertEquals(3, companyRow.contributions().size());

        var directRows = companyRow.contributions().stream().filter(contribution -> contribution.direct()).toList();
        assertEquals(1, directRows.size());
        assertEquals(direct.getName(), directRows.get(0).origin());
        assertEquals("", directRows.get(0).path());
        assertTrue(directRows.get(0).sourceText().contains(direct.getName()));
        amount("3000", directRows.get(0).value());

        var indirectRows = companyRow.contributions().stream().filter(contribution -> !contribution.direct()).toList();
        assertEquals(2, indirectRows.size());
        assertEquals(1, indirectRows.stream().filter(contribution -> contribution.path().equals("Synthetic first-fund"))
                        .count());
        assertEquals(1, indirectRows.stream().filter(contribution -> contribution.path().equals("Synthetic second-fund"))
                        .count());
        assertEquals(2, indirectRows.stream().filter(contribution -> contribution.origin().endsWith("fund holding"))
                        .count());

        amount("1000", category(model, Category.CASH_OR_OTHER).value());
        amount("2000", category(model, Category.UNGROUPED_EQUITY).value());
        amount("8000", category(model, Category.UNRESOLVED).value());

        assertEquals(Money.of("EUR", 23000), model.denominator().orElseThrow());
        assertTrue(model.denominatorText().isPresent());
        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model.completeness());

        var allocated = companyRow.total().add(category(model, Category.UNGROUPED_EQUITY).value())
                        .add(category(model, Category.CASH_OR_OTHER).value())
                        .add(category(model, Category.UNRESOLVED).value());
        amount(result.total().toPlainString(), allocated);
        amount(Long.toString(result.valuation().valuedSubset().getAmount()), result.total());
        amount("12000", result.value(Category.COMPANY));

        assertTrue(Math.abs(allocatedShares(model).doubleValue() - 1d) < 0.0001);
    }

    @Test
    public void testSingleDirectCompanyIsCompleteWhileZeroCategoriesStayVisible()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-ONE", 1, 10);

        var result = result();
        var model = model(result);

        assertEquals(LookThroughViewPresenter.Completeness.COMPLETE, model.completeness());
        assertEquals(Money.of("EUR", 1000), model.denominator().orElseThrow());
        assertEquals(3, model.rows().stream().filter(CategoryRow.class::isInstance).count());
        assertEquals(0, BigDecimal.ZERO.compareTo(category(model, Category.UNGROUPED_EQUITY).value()));
        assertEquals(0, BigDecimal.ZERO.compareTo(category(model, Category.CASH_OR_OTHER).value()));
        assertEquals(0, BigDecimal.ZERO.compareTo(category(model, Category.UNRESOLVED).value()));

        var companyRow = company(model, "Synthetic company");
        amount("1000", companyRow.total());
        amount("1", companyRow.share().orElseThrow());
    }

    @Test
    public void testIncompleteValuationWithholdsEveryShareAndStaysPartial()
    {
        holdWithoutPrice("unpriced-company", "Synthetic unpriced holding", AssetType.EQUITY, "SYNTH-UNPRICED", 4);
        hold("priced-company", "Synthetic priced holding", AssetType.EQUITY, "SYNTH-PRICED", 1, 100);

        var result = result();
        var model = model(result);

        assertTrue(model.denominator().isEmpty());
        assertTrue(model.denominatorText().isEmpty());
        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model.completeness());

        for (var row : model.rows())
        {
            if (row instanceof CompanyRow companyRow)
            {
                assertTrue(companyRow.share().isEmpty());
                assertTrue(companyRow.shareText().isEmpty());
                companyRow.contributions().forEach(contribution -> assertTrue(contribution.shareText().isEmpty()));
            }
            else if (row instanceof CategoryRow categoryRow)
            {
                assertTrue(categoryRow.share().isEmpty());
                assertTrue(categoryRow.shareText().isEmpty());
            }
        }

        assertEquals(SecuritiesValuation.Reason.MISSING_VALUATION,
                        excluded(model, "Synthetic unpriced holding").exclusion());
        amount("10000", company(model, "Synthetic priced-company").total());
    }

    @Test
    public void testExcludedNegativePositionStaysVisibleAndPreventsCompleteStatus()
    {
        hold("positive", "Synthetic positive holding", AssetType.EQUITY, "SYNTH-POS", 2, 50);
        var negative = new SecurityBuilder("EUR").addPrice(TRADE_DATE, Values.Quote.factorize(30)).addTo(client);
        negative.setName("Synthetic negative holding");
        negative.setIsin("SYNTH-NEG");
        new PortfolioBuilder().sellPrice(negative, TRADE_DATE, 3, 30).addTo(client);
        selected.add(negative);

        var result = result();
        var model = model(result);

        assertEquals(Money.of("EUR", 10000), model.denominator().orElseThrow());
        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model.completeness());
        assertEquals(SecuritiesValuation.Reason.UNSUPPORTED_NEGATIVE_POSITION,
                        excluded(model, "Synthetic negative holding").exclusion());

        var companyRow = company(model, "Synthetic positive");
        amount("10000", companyRow.total());
        amount("1", companyRow.share().orElseThrow());
        assertEquals(0, BigDecimal.ZERO.compareTo(category(model, Category.UNRESOLVED).value()));
    }

    @Test
    public void testRoundedResidualKeepsResultPartialAlthoughSharesReachOne()
    {
        hold("rounding-fund", "Synthetic rounding fund holding", AssetType.FUND, null, 1, 100);

        var company = instrument("rounding-company", AssetType.EQUITY, "SYNTH-ROUND");
        composition(instruments.get("rounding-fund"), row(company, "0.999999"));

        var result = result();
        var model = model(result);

        var companyRow = company(model, "Synthetic rounding-company");
        amount("9999.99", companyRow.total());
        amount("0.01", category(model, Category.UNRESOLVED).value());
        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model.completeness());
        assertTrue(model.denominator().isPresent());

        amount("0.999999", companyRow.share().orElseThrow());
        amount("0.000001", category(model, Category.UNRESOLVED).share().orElseThrow());
        amount("1", allocatedShares(model));
    }

    @Test
    public void testUnverifiedCompositionCompletenessStaysPartialDespiteExactAllocation()
    {
        hold("unverified-fund", "Synthetic unverified fund holding", AssetType.FUND, null, 1, 100);
        composition(Completeness.UNKNOWN, instruments.get("unverified-fund"),
                        row(instrument("all-in-company", AssetType.EQUITY, "SYNTH-ALL-IN"), "1"));

        var result = result();
        var model = model(result);

        amount("0", category(model, Category.UNRESOLVED).value());
        amount("0", category(model, Category.UNGROUPED_EQUITY).value());
        amount("0", category(model, Category.CASH_OR_OTHER).value());
        amount("10000", company(model, "Synthetic all-in-company").total());
        assertTrue(model.denominator().isPresent());
        amount(Long.toString(result.valuation().valuedSubset().getAmount()), result.total());
        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model.completeness());
    }

    @Test
    public void testAffirmativelyCompleteCompositionCanReachCompleteCoverage()
    {
        hold("verified-fund", "Synthetic verified fund holding", AssetType.FUND, null, 1, 100);
        composition(Completeness.COMPLETE, instruments.get("verified-fund"),
                        row(instrument("verified-company", AssetType.EQUITY, "SYNTH-VERIFIED"), "1"));

        var model = model(result());

        amount("0", category(model, Category.UNRESOLVED).value());
        amount("10000", company(model, "Synthetic verified-company").total());
        assertEquals(LookThroughViewPresenter.Completeness.COMPLETE, model.completeness());
    }

    @Test
    public void testCompaniesSortByTotalDescendingThenByIdentifier()
    {
        hold("company-b", "Synthetic B holding", AssetType.EQUITY, "SYNTH-B", 1, 10);
        hold("company-a", "Synthetic A holding", AssetType.EQUITY, "SYNTH-A", 1, 10);
        hold("company-c", "Synthetic C holding", AssetType.EQUITY, "SYNTH-C", 2, 10);

        var labels = model(result()).rows().stream().filter(CompanyRow.class::isInstance).map(CompanyRow.class::cast)
                        .map(CompanyRow::label).toList();

        assertEquals(List.of("Synthetic company-c", "Synthetic company-a", "Synthetic company-b"), labels);
    }

    @Test
    public void testEveryRowCarriesItsExactValueAndCategory()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 1, 10);

        var model = model(result());

        for (Row row : model.rows())
        {
            if (row instanceof CompanyRow companyRow)
            {
                assertEquals(LookThroughViewPresenter.RowKind.COMPANY, row.kind());
                amount(companyRow.direct().add(companyRow.indirect()).toPlainString(), companyRow.total());
            }
            else if (row instanceof CategoryRow categoryRow)
            {
                assertEquals(LookThroughViewPresenter.RowKind.CATEGORY, row.kind());
                assertEquals(0, BigDecimal.ZERO.compareTo(categoryRow.value()));
                assertEquals(0, BigDecimal.ZERO.compareTo(categoryRow.share().orElseThrow()));
            }
            else if (row instanceof ExcludedRow)
            {
                assertEquals(LookThroughViewPresenter.RowKind.EXCLUDED, row.kind());
            }
        }
    }

    @Test
    public void testUnverifiedCompositionShowsIssuerWithNoticeAndPartialCoverage()
    {
        hold("unverified-fund", "Synthetic unverified fund", AssetType.FUND, null, 1, 100);
        var fund = instrument("unverified-fund", AssetType.FUND, null);
        var issuer = instrument("issuer", AssetType.EQUITY, "SYNTH-CO");
        var provenance = new CompositionProvenance(SOURCE, fund, RETRIEVED, Optional.empty(), Optional.empty());
        unverified.put(resolutions.get(fund).instrument().orElseThrow(), new FundComposition(provenance,
                        Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.UNKNOWN, List.of(row(issuer, "1"))));

        var model = model(result());

        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model.completeness());
        assertTrue(model.unverifiedNotice().isPresent());
        var company = company(model, "Synthetic issuer");
        amount("10000", company.total());
        assertEquals(LookThroughViewPresenter.RowKind.COMPANY, company.kind());
    }

    @Test
    public void testEmptyPortfolioIsReportedAsEmptyWithoutInventingCategories()
    {
        var model = model(result());

        assertTrue(model.empty());
        assertTrue(model.rows().isEmpty());
        assertTrue(model.denominator().isEmpty());
    }

    @Test
    public void testKnownFundWithoutCompositionHasSpecificExplanation()
    {
        hold("missing-fund", "Synthetic missing fund", AssetType.FUND, null, 1, 100);
        var unresolved = category(model(result()), Category.UNRESOLVED);
        assertEquals(Messages.LookThroughCategoryUnresolved, unresolved.label());
        assertEquals(Messages.LookThroughReasonMissingComposition, unresolved.details().getFirst().note());
    }

    @Test
    public void testShortfallDoesNotLookLikeMissingClassification()
    {
        hold("partial-fund", "Synthetic partial fund", AssetType.FUND, null, 1, 100);
        composition(instruments.get("partial-fund"),
                        row(instrument("issuer", AssetType.EQUITY, "SYNTH-ISSUER"), "0.8"));
        assertEquals(Messages.LookThroughReasonCompositionShortfall,
                        category(model(result()), Category.UNRESOLVED).details().getFirst().note());
    }

    @Test
    public void testCompleteDeclarationCannotValidateUnknownAllocationSemantics()
    {
        hold("unverified-fund", "Synthetic unverified fund", AssetType.FUND, null, 1, 100);
        var fund = instruments.get("unverified-fund");
        var issuer = instrument("issuer", AssetType.EQUITY, "SYNTH-ISSUER");
        unverified.put(resolutions.get(fund).instrument().orElseThrow(), new FundComposition(
                        new CompositionProvenance(SOURCE, fund, RETRIEVED, Optional.empty(), Optional.empty()),
                        Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.COMPLETE, List.of(row(issuer, "1"))));
        assertEquals(LookThroughViewPresenter.Completeness.PARTIAL, model(result()).completeness());
    }

    @Test
    public void testEqualResultsProduceEqualAndUniqueKeys()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 3, 10);
        hold("first-fund", "Synthetic first fund holding", AssetType.FUND, null, 1, 100);
        hold("second-fund", "Synthetic second fund holding", AssetType.FUND, null, 1, 100);

        var company = instrument("company", AssetType.EQUITY, "SYNTH-CO");
        var cash = instrument("cash", AssetType.CASH, null);
        var missing = new CompositionInstrument("Synthetic missing constituent",
                        Map.of("synthetic:instrument", "missing"));

        composition(instruments.get("first-fund"), row(company, "0.4"), row(company, "0.3"), row(cash, "0.3"));
        composition(instruments.get("second-fund"), row(company, "0.5"), row(missing, "0.5"));

        var result = result();
        var first = keys(model(result).rows());
        var second = keys(model(result).rows());

        assertEquals(first, second);
        assertEquals(first.size(), new HashSet<>(first).size());

        // The same company held twice through one fund keeps two sources.
        var companyRow = company(model(result), "Synthetic company");
        assertEquals(4, companyRow.contributions().size());
        companyRow.contributions().forEach(contribution -> assertEquals(companyRow.key(),
                        new RowKey(contribution.key().parts().subList(0, companyRow.key().parts().size()))));
    }

    @Test
    public void testDistinctCompaniesWithTheSameLabelKeepDistinctKeys()
    {
        instrument("first-company", "Synthetic same name", AssetType.EQUITY, "SYNTH-FIRST");
        instrument("second-company", "Synthetic same name", AssetType.EQUITY, "SYNTH-SECOND");
        hold("first-company", "Synthetic first holding", AssetType.EQUITY, "SYNTH-FIRST", 1, 10);
        hold("second-company", "Synthetic second holding", AssetType.EQUITY, "SYNTH-SECOND", 2, 10);

        var companies = model(result()).rows().stream().filter(CompanyRow.class::isInstance)
                        .map(CompanyRow.class::cast).toList();

        assertEquals(2, companies.size());
        assertEquals(companies.get(0).label(), companies.get(1).label());
        assertNotEquals(companies.get(0).key(), companies.get(1).key());
    }

    private Segment segment(Model model, SegmentKind kind)
    {
        return model.coverage().orElseThrow().segments().stream().filter(segment -> segment.kind() == kind)
                        .findFirst().orElseThrow();
    }

    @Test
    public void testSummaryOfPartialCoverageCountsFundsAndSplitsCoverage()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 3, 10);
        hold("first-fund", "Synthetic first fund holding", AssetType.FUND, null, 1, 100);
        hold("second-fund", "Synthetic second fund holding", AssetType.FUND, null, 1, 100);

        var company = instrument("company", AssetType.EQUITY, "SYNTH-CO");
        var cash = instrument("cash", AssetType.CASH, null);
        var ungrouped = instrument("ungrouped", AssetType.EQUITY, null);
        var missing = new CompositionInstrument("Synthetic missing constituent",
                        Map.of("synthetic:instrument", "missing"));
        var nested = instrument("nested-fund", AssetType.FUND, null);

        composition(instruments.get("first-fund"), row(company, "0.4"), row(cash, "0.1"), row(ungrouped, "0.2"),
                        row(missing, "0.3"));
        composition(instruments.get("second-fund"), row(company, "0.5"), row(nested, "0.5"));

        var model = model(result());
        var summary = model.summary();

        // 8000 of 23000 stay unresolved: the missing constituent and the
        // nested fund without composition.
        var resolved = BigDecimal.ONE.subtract(new BigDecimal("8000").divide(new BigDecimal("23000"), 6,
                        RoundingMode.HALF_UP));
        amount(resolved.toPlainString(), summary.resolvedShare().orElseThrow());
        assertEquals(Values.Percent.format(resolved.setScale(4, RoundingMode.DOWN).doubleValue()),
                        summary.resolvedText().orElseThrow());
        amount(new BigDecimal("12000").divide(new BigDecimal("23000"), 6, RoundingMode.HALF_UP).toPlainString(),
                        summary.topTenShare().orElseThrow());
        assertEquals(2, summary.heldFunds());
        assertEquals(2, summary.decomposedFunds());
        assertEquals("2 of 2", summary.fundsText().orElseThrow());
        assertTrue(summary.needsCompositions());
        assertTrue(summary.valueText().contains(Values.Money.format(Money.of("EUR", 23000))));
        assertTrue(summary.indicatorText().contains(model.scopeText()));
        assertTrue(summary.indicatorText().contains(model.denominatorText().orElseThrow()));
        assertTrue(summary.indicatorText().contains(Messages.LookThroughLabelPartial));
        assertTrue(summary.asOfText().contains(Values.Date.format(VALUATION_DATE)));

        amount("12000", segment(model, SegmentKind.COMPANIES).value());
        amount("2000", segment(model, SegmentKind.UNGROUPED_EQUITY).value());
        amount("1000", segment(model, SegmentKind.CASH_OR_OTHER).value());
        amount("8000", segment(model, SegmentKind.UNRESOLVED).value());
        assertEquals(4, model.coverage().orElseThrow().segments().size());
        var fractions = model.coverage().orElseThrow().segments().stream().map(Segment::fraction)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(fractions.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.00001")) < 0);

        assertEquals(Values.Percent.format(0.7d), model.decomposedShares().get(Map.of("ISIN", "SYNTH-first-fund")));
        assertEquals(Values.Percent.format(0.5d), model.decomposedShares().get(Map.of("ISIN", "SYNTH-second-fund")));
    }

    @Test
    public void testTinyUnresolvedRemainderStaysVisibleAndNeverReadsAsFullCoverage()
    {
        hold("big-fund", "Synthetic big fund holding", AssetType.FUND, null, 1, 1_000_000);
        var company = instrument("company", AssetType.EQUITY, "SYNTH-CO");
        var missing = new CompositionInstrument("Synthetic missing constituent",
                        Map.of("synthetic:instrument", "missing"));
        composition(instruments.get("big-fund"), row(company, "0.999999"), row(missing, "0.000001"));

        var model = model(result());
        var unresolved = segment(model, SegmentKind.UNRESOLVED);

        amount("100", unresolved.value());
        assertTrue(unresolved.nonzero());
        assertEquals("< " + Values.Percent.format(0.0001d), unresolved.shareText());
        assertNotEquals(Values.Percent.format(1d), model.summary().resolvedText().orElseThrow());
        assertNotEquals(Values.Percent.format(1d), segment(model, SegmentKind.COMPANIES).shareText());
        assertNotEquals(Values.Percent.format(1d), model.decomposedShares().get(Map.of("ISIN", "SYNTH-big-fund")));

        var coverage = model.coverage().orElseThrow();
        var widths = coverage.widths(200, 3);
        var index = coverage.segments().indexOf(unresolved);
        assertTrue(widths[index] >= 3);
        assertEquals(200, Arrays.stream(widths).sum());
        for (int ii = 0; ii < widths.length; ii++)
            assertEquals(coverage.segments().get(ii).nonzero(), widths[ii] > 0);
    }

    @Test
    public void testMissingDenominatorWithholdsPercentagesAndNamesTheMissingValuation()
    {
        holdWithoutPrice("unpriced-company", "Synthetic unpriced holding", AssetType.EQUITY, "SYNTH-UNPRICED", 4);
        hold("priced-company", "Synthetic priced holding", AssetType.EQUITY, "SYNTH-PRICED", 1, 100);

        var model = model(result());
        var summary = model.summary();

        assertTrue(summary.resolvedShare().isEmpty());
        assertTrue(summary.resolvedText().isEmpty());
        assertTrue(summary.topTenText().isEmpty());
        assertTrue(model.coverage().isEmpty());
        assertEquals(MessageFormat.format(Messages.LookThroughSummaryValueIncomplete,
                        Values.Money.format(Money.of("EUR", 10000))), summary.valueText());
        assertTrue(summary.attention());
        assertTrue(summary.indicatorText().contains(Messages.LookThroughLabelNoDenominator));
        assertTrue(summary.indicatorText().contains("Synthetic unpriced holding"));
        assertTrue(summary.indicatorText().contains(Messages.LookThroughReasonMissingValuation));
    }

    @Test
    public void testOnlyDirectHoldingsNeedNoComposition()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-ONE", 1, 10);

        var summary = model(result()).summary();

        assertFalse(summary.needsCompositions());
        assertFalse(summary.attention());
        assertEquals(0, summary.heldFunds());
        assertTrue(summary.fundsText().isEmpty());
        assertEquals(Values.Percent.format(1d), summary.resolvedText().orElseThrow());
    }

    @Test
    public void testEmptyPortfolioHasNoCoverageAndNeedsNoComposition()
    {
        var model = model(result());

        assertTrue(model.empty());
        assertTrue(model.coverage().isEmpty());
        assertFalse(model.summary().needsCompositions());
        assertTrue(model.summary().fundsText().isEmpty());
        assertTrue(model.summary().resolvedText().isEmpty());
        assertTrue(model.decomposedShares().isEmpty());
    }
}
