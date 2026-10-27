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
import java.util.Set;

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
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes.Attribute;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes.Source;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.FundOverlap;
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
import name.abuchen.portfolio.snapshot.lookthrough.RevenueSplit;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuation;
import name.abuchen.portfolio.snapshot.lookthrough.SecuritiesValuationAdapter;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CategoryRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughGrouping.Grouping;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.CompanyRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ContentState;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ContributionRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.ExcludedRow;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.GroupKind;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughViewPresenter.GroupRow;
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
            if (row instanceof GroupRow groupRow)
                keys.addAll(keys(groupRow.companies()));
            else if (row instanceof CompanyRow companyRow)
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
    public void testCalculatingStateUntilTheFirstResult()
    {
        assertEquals(ContentState.CALCULATING, LookThroughViewPresenter.contentState(false, true));
        assertEquals(ContentState.CALCULATED, LookThroughViewPresenter.contentState(true, true));
        assertEquals(ContentState.CALCULATED, LookThroughViewPresenter.contentState(true, false));
        assertEquals(ContentState.NONE, LookThroughViewPresenter.contentState(false, false));
    }

    @Test
    public void testDirectlyClassifiedSecuritiesExcludeFundsAndUnknownSecurities()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 3, 10);
        hold("fund", "Synthetic fund holding", AssetType.FUND, null, 1, 100);
        hold("unknown", "Synthetic unknown holding", null, null, 1, 10);

        var model = model(result());

        assertEquals(Set.of(Map.of("ISIN", "SYNTH-company")), model.directlyClassified());
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
    public void testChartModelHasOnlyCompaniesWithDirectAndIndirectParts()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 3, 10);
        hold("fund", "Synthetic fund holding", AssetType.FUND, null, 1, 100);
        hold("unknown-fund", "Synthetic unknown fund holding", AssetType.FUND, null, 1, 100);

        var company = instrument("company", AssetType.EQUITY, "SYNTH-CO");
        var cash = instrument("cash", AssetType.CASH, null);
        var ungrouped = instrument("ungrouped", AssetType.EQUITY, null);
        composition(instruments.get("fund"), row(company, "0.6"), row(cash, "0.1"), row(ungrouped, "0.3"));

        var model = model(result());
        var chart = model.chart();

        assertEquals(1, chart.companies().size());
        var entry = chart.companies().get(0);
        assertEquals(company(model, "Synthetic company").key(), entry.key());
        assertEquals("Synthetic company", entry.label());
        amount("3000", entry.direct());
        amount("6000", entry.indirect());
        amount("9000", entry.total());
        assertEquals(1d / 3d, entry.directFraction(), 0.0001);
        assertEquals(company(model, "Synthetic company").share().map(share -> Values.Percent2.format(share.doubleValue())),
                        entry.shareText());

        // cash, ungrouped equities and the unresolved fund stay out
        amount("1000", category(model, Category.CASH_OR_OTHER).value());
        amount("3000", category(model, Category.UNGROUPED_EQUITY).value());
        assertTrue(category(model, Category.UNRESOLVED).value().signum() > 0);
        assertEquals(chart.companies(), chart.top());
    }

    @Test
    public void testChartModelWithoutCompaniesIsEmpty()
    {
        hold("unknown-fund", "Synthetic unknown fund holding", AssetType.FUND, null, 1, 100);

        var chart = model(result()).chart();

        assertTrue(chart.isEmpty());
        assertTrue(chart.top().isEmpty());
    }

    @Test
    public void testChartModelSortsAndCapsTheTopCompanies()
    {
        for (int ii = 1; ii <= 25; ii++)
            hold("company-" + ii, "Synthetic " + ii + " holding", AssetType.EQUITY, "SYNTH-" + ii, ii, 10);

        var chart = model(result()).chart();

        assertEquals(25, chart.companies().size());
        assertEquals(LookThroughViewPresenter.ChartModel.TOP_COMPANIES, chart.top().size());
        assertEquals("Synthetic company-25", chart.top().get(0).label());
        assertEquals("Synthetic company-6", chart.top().get(19).label());
        for (int ii = 1; ii < chart.companies().size(); ii++)
            assertTrue(chart.companies().get(ii - 1).total().compareTo(chart.companies().get(ii).total()) >= 0);
        assertEquals(chart.companies().subList(0, 20), chart.top());
    }

    @Test
    public void testLegendListsOnlyCategoriesWithAValue()
    {
        hold("company", "Synthetic direct company holding", AssetType.EQUITY, "SYNTH-CO", 1, 10);
        hold("fund", "Synthetic fund holding", AssetType.FUND, null, 1, 100);
        var company = instrument("company", AssetType.EQUITY, "SYNTH-CO");
        composition(instruments.get("fund"), row(company, "1"));

        var coverage = model(result()).coverage().orElseThrow();

        assertTrue(coverage.segments().stream().anyMatch(segment -> !segment.nonzero()));
        assertFalse(coverage.legend().isEmpty());
        assertTrue(coverage.legend().stream().allMatch(Segment::nonzero));
        assertEquals(coverage.segments().stream().filter(Segment::nonzero).toList(), coverage.legend());
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
        assertEquals(Values.Percent2.format(resolved.setScale(4, RoundingMode.DOWN).doubleValue()),
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
        assertEquals("< " + Values.Percent2.format(0.0001d), unresolved.shareText());
        assertNotEquals(Values.Percent2.format(1d), model.summary().resolvedText().orElseThrow());
        assertNotEquals(Values.Percent2.format(1d), segment(model, SegmentKind.COMPANIES).shareText());
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
        // twenty companies, so that none exceeds the concentration threshold
        for (int ii = 0; ii < 20; ii++)
            hold("company" + ii, "Synthetic direct company holding " + ii, AssetType.EQUITY, "SYNTH-" + ii, 1, 10);

        var summary = model(result()).summary();

        assertFalse(summary.needsCompositions());
        assertFalse(summary.attention());
        assertEquals(0, summary.heldFunds());
        assertTrue(summary.fundsText().isEmpty());
        assertEquals(Values.Percent2.format(1d), summary.resolvedText().orElseThrow());
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

    private static Identifier company(String id)
    {
        return new Identifier("synthetic:company", id);
    }

    private static CompanyAttributes attributes(String id, String country, String currency, String sector,
                    String industry)
    {
        return new CompanyAttributes(company(id),
                        Optional.ofNullable(country).map(v -> new Attribute<>(v, Source.REGISTER, RETRIEVED)),
                        Optional.ofNullable(currency).map(v -> new Attribute<>(v, Source.PROVIDER, RETRIEVED)),
                        Optional.ofNullable(sector).map(v -> new Attribute<>(v, Source.PROVIDER, RETRIEVED)),
                        Optional.ofNullable(industry).map(v -> new Attribute<>(v, Source.PROVIDER, RETRIEVED)),
                        Optional.empty());
    }

    private static CompanyEnrichmentService.Snapshot snapshot(Set<Identifier> waiting, CompanyAttributes... all)
    {
        var map = new java.util.LinkedHashMap<Identifier, CompanyAttributes>();
        for (var attributes : all)
            map.put(attributes.identity(), attributes);
        return new CompanyEnrichmentService.Snapshot(map, waiting.size(), waiting);
    }

    private LookThroughResult groupingResult()
    {
        hold("a", "Synthetic a", AssetType.EQUITY, "CO-A", 1, 100);
        hold("b", "Synthetic b", AssetType.EQUITY, "CO-B", 1, 300);
        hold("c", "Synthetic c", AssetType.EQUITY, "CO-C", 1, 50);
        hold("d", "Synthetic d", AssetType.EQUITY, "CO-D", 1, 20);
        hold("e", "Synthetic e", AssetType.EQUITY, "CO-E", 1, 10);
        return result();
    }

    private GroupRow group(Model model, String label)
    {
        return model.rows().stream().filter(GroupRow.class::isInstance).map(GroupRow.class::cast)
                        .filter(row -> row.label().equals(label)).findFirst().orElseThrow();
    }

    private List<GroupRow> groups(Model model)
    {
        return model.rows().stream().filter(GroupRow.class::isInstance).map(GroupRow.class::cast).toList();
    }

    @Test
    public void testCountryGroupsSumToTheCompanyExposureWithUnknownAndPending()
    {
        var result = groupingResult();
        var snapshot = snapshot(Set.of(company("CO-D")), //
                        attributes("CO-A", "US", null, null, null), attributes("CO-B", "US", null, null, null),
                        attributes("CO-C", "DE", null, null, null), attributes("CO-D", null, null, null, null),
                        attributes("CO-E", null, null, null, null));

        var model = new LookThroughViewPresenter().present(result, Grouping.COUNTRY, snapshot);
        var groups = groups(model);

        assertEquals(4, groups.size());
        assertEquals(RowKey.of("group:country:US"), groups.get(0).key());
        amount("40000", groups.get(0).total());
        assertEquals(2, groups.get(0).companies().size());
        assertEquals(RowKey.of("group:country:DE"), groups.get(1).key());
        assertEquals(GroupKind.UNKNOWN, groups.get(2).groupKind());
        assertEquals(MessageFormat.format(Messages.LookThroughGroupUnknown, 1), groups.get(2).label());
        assertEquals(GroupKind.PENDING, groups.get(3).groupKind());
        assertEquals(MessageFormat.format(Messages.LookThroughGroupPending, 1), groups.get(3).label());

        var sum = groups.stream().map(GroupRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        amount("48000", sum);
        assertEquals(0, sum.compareTo(result.value(Category.COMPANY)));
        // the country tooltip names the source
        var detail = groups.get(0).companies().get(0).detail().orElseThrow();
        assertTrue(detail.contains(Messages.LookThroughAttributeSourceRegister));

        // the charts show the groups with their companies nested inside
        var chart = model.chart();
        assertTrue(chart.isGrouped());
        assertEquals(4, chart.top().size());
        assertEquals(5, chart.companies().size());
    }

    @Test
    public void testCompanyGroupingKeepsFlatRows()
    {
        var result = groupingResult();
        var model = new LookThroughViewPresenter().present(result, Grouping.COMPANY, snapshot(Set.of()));
        assertTrue(groups(model).isEmpty());
        assertFalse(model.chart().isGrouped());
        assertEquals(keys(model(result).rows()), keys(model.rows()));
    }

    @Test
    public void testRegionAndMarketClassificationUseTheTemplates()
    {
        var result = groupingResult();
        var snapshot = snapshot(Set.of(), //
                        attributes("CO-A", "US", null, null, null), attributes("CO-B", "DE", null, null, null),
                        attributes("CO-C", "BR", null, null, null), attributes("CO-D", "ZZ", null, null, null),
                        attributes("CO-E", null, null, null, null));

        var regions = groups(new LookThroughViewPresenter().present(result, Grouping.REGION, snapshot));
        // US and BR are both America, DE is Europe
        amount("15000", regions.stream().filter(row -> row.key().equals(RowKey.of("group:region:R20"))).findFirst()
                        .orElseThrow().total());
        assertTrue(regions.stream().anyMatch(row -> row.key().equals(RowKey.of("group:region:R10"))));
        // a country that the template does not list is unknown, as is a missing country
        var unknown = regions.stream().filter(row -> row.groupKind() == GroupKind.UNKNOWN).findFirst().orElseThrow();
        assertEquals(2, unknown.count());

        var markets = groups(new LookThroughViewPresenter().present(result, Grouping.MARKET_CLASSIFICATION, snapshot));
        assertTrue(markets.stream().anyMatch(row -> row.key().equals(RowKey.of("group:market_classification:RW"))));
        assertTrue(markets.stream().anyMatch(row -> row.key().equals(RowKey.of("group:market_classification:REM"))));
    }

    @Test
    public void testSectorsMapToGicsByNameAndKeepUnmatchedNames()
    {
        var result = groupingResult();
        var snapshot = snapshot(Set.of(), //
                        attributes("CO-A", null, null, "Information Technology", null),
                        attributes("CO-B", null, null, "information technology", null),
                        attributes("CO-C", null, null, "Technology", null),
                        attributes("CO-D", null, null, "Invented sector", "Invented industry"),
                        attributes("CO-E", null, null, null, "Invented industry"));

        var model = new LookThroughViewPresenter().present(result, Grouping.SECTOR, snapshot);
        var technology = groups(model).stream().filter(row -> row.key().equals(RowKey.of("group:sector:45")))
                        .findFirst().orElseThrow();
        assertEquals(3, technology.count());
        amount("45000", technology.total());
        var invented = group(model, "Invented sector");
        assertEquals(RowKey.of("group:sector:name:invented sector"), invented.key());
        assertEquals(GroupKind.UNKNOWN, groups(model).get(groups(model).size() - 1).groupKind());

        var industries = groups(new LookThroughViewPresenter().present(result, Grouping.INDUSTRY, snapshot));
        assertTrue(industries.stream().anyMatch(row -> row.label().equals("Invented industry") && row.count() == 2));
    }

    @Test
    public void testCurrencyGroupsAndStableKeysWhenEnrichmentMovesACompany()
    {
        var result = groupingResult();
        var presenter = new LookThroughViewPresenter();
        var before = presenter.present(result, Grouping.CURRENCY, snapshot(Set.of(company("CO-A")), //
                        attributes("CO-A", null, null, null, null), attributes("CO-B", null, "USD", null, null),
                        attributes("CO-C", null, "USD", null, null), attributes("CO-D", null, "EUR", null, null),
                        attributes("CO-E", null, "EUR", null, null)));
        var pending = groups(before).stream().filter(row -> row.groupKind() == GroupKind.PENDING).findFirst()
                        .orElseThrow();
        var companyKey = pending.companies().get(0).key();

        var after = presenter.present(result, Grouping.CURRENCY, snapshot(Set.of(), //
                        attributes("CO-A", null, "USD", null, null), attributes("CO-B", null, "USD", null, null),
                        attributes("CO-C", null, "USD", null, null), attributes("CO-D", null, "EUR", null, null),
                        attributes("CO-E", null, "EUR", null, null)));
        assertTrue(groups(after).stream().noneMatch(row -> row.groupKind() != GroupKind.REGULAR));
        var usd = groups(after).stream().filter(row -> row.key().equals(RowKey.of("group:currency:USD")))
                        .findFirst().orElseThrow();
        assertTrue(usd.companies().stream().anyMatch(row -> row.key().equals(companyKey)));
        // the key of the moved company, its contributions and the group stay equal
        assertTrue(keys(after.rows()).contains(companyKey));
        assertTrue(keys(before.rows()).contains(RowKey.of("group:currency:EUR")));
        assertTrue(keys(after.rows()).contains(RowKey.of("group:currency:EUR")));
    }

    private static RevenueSplit split(String id, String period, RevenueSplit.Entry... entries)
    {
        return RevenueSplit.of(id, LocalDate.parse(period), List.of(entries), RETRIEVED).orElseThrow();
    }

    private static RevenueSplit.Entry entry(String label, String member, String percentage)
    {
        return new RevenueSplit.Entry(label, member, new BigDecimal(percentage));
    }

    @Test
    public void testRevenueRegionSplitsCompaniesAndTotalsEqualTheCompanyExposure()
    {
        var result = groupingResult();
        var splits = new java.util.LinkedHashMap<Identifier, RevenueSplit>();
        splits.put(company("CO-A"), split("CO-A", "2025-03-31", entry("United States", "country_US", "100")));
        splits.put(company("CO-B"), split("CO-B", "2025-06-30", entry("United States", "country_US", "60"),
                        entry("Europe", "srt_EuropeMember", "40")));
        // reported entries add up to 90 percent and are scaled
        splits.put(company("CO-C"), split("CO-C", "2025-06-30", entry("Germany", "country_DE", "45"),
                        entry("Europe", "srt_EuropeMember", "45")));
        var snapshot = new CompanyEnrichmentService.Snapshot(Map.of(), 0, Set.of(), splits, Set.of(company("CO-E")));

        var model = new LookThroughViewPresenter().present(result, Grouping.REVENUE_REGION, snapshot);
        var groups = groups(model);
        assertEquals(5, groups.size());

        var us = groups.get(0);
        assertEquals(RowKey.of("group:revenue_region:US"), us.key());
        amount("28000", us.total());
        assertEquals(2, us.companies().size());
        // 60 percent of the company with 30000 and the whole company with 10000
        amount("18000", us.companies().get(0).total());
        amount("10000", us.companies().get(1).total());
        var europe = groups.get(1);
        assertEquals(RowKey.of("group:revenue_region:region:R10"), europe.key());
        amount("14500", europe.total());
        amount("2500", groups.get(2).total());
        assertEquals(GroupKind.UNKNOWN, groups.get(3).groupKind());
        assertEquals(MessageFormat.format(Messages.LookThroughGroupNoRevenue, 1), groups.get(3).label());
        amount("2000", groups.get(3).total());
        assertEquals(GroupKind.PENDING, groups.get(4).groupKind());
        amount("1000", groups.get(4).total());

        var sum = groups.stream().map(GroupRow::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        amount("48000", sum);
        assertEquals(0, sum.compareTo(result.value(Category.COMPANY)));

        // parts of one company add up to its exposure, also direct and indirect and the contributions
        var parts = groups.stream().flatMap(group -> group.companies().stream())
                        .filter(row -> row.label().equals("Synthetic b")).toList();
        assertEquals(2, parts.size());
        amount("30000", parts.stream().map(CompanyRow::total).reduce(BigDecimal.ZERO, BigDecimal::add));
        amount("30000", parts.stream().map(CompanyRow::direct).reduce(BigDecimal.ZERO, BigDecimal::add));
        amount("30000", parts.stream().flatMap(row -> row.contributions().stream()).map(ContributionRow::value)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(parts.get(0).detail().orElseThrow().contains(Values.Percent2.format(0.6)));

        // company keys are unique per group, so selection and expansion work
        var all = keys(model.rows());
        assertEquals(all.size(), new java.util.HashSet<>(all).size());
        var scaled = groups.get(2).companies().get(0);
        assertTrue(scaled.detail().orElseThrow().contains(Values.Percent2.format(0.9)));

        // the coverage notice states the share with revenue data and the periods
        var notice = model.summary().indicatorText();
        assertTrue(notice.contains(Values.Percent2.format(0.9375)));
        assertTrue(notice.contains(Values.Date.format(LocalDate.parse("2025-03-31"))));
        assertTrue(notice.contains(Values.Date.format(LocalDate.parse("2025-06-30"))));
        assertTrue(notice.contains(MessageFormat.format(Messages.LookThroughRevenueIncomplete, 1)));

        // the charts nest the allocated parts in their groups, each with its own key
        var chart = model.chart();
        assertTrue(chart.isGrouped());
        assertEquals(5, chart.top().size());
        assertEquals(7, chart.companies().size());
    }

    @Test
    public void testRevenueRegionWithoutAnyDataKeepsAllCompaniesUnallocated()
    {
        var result = groupingResult();
        var model = new LookThroughViewPresenter().present(result, Grouping.REVENUE_REGION,
                        CompanyEnrichmentService.Snapshot.empty());
        var groups = groups(model);
        assertEquals(1, groups.size());
        assertEquals(GroupKind.UNKNOWN, groups.get(0).groupKind());
        amount("48000", groups.get(0).total());
        assertTrue(model.summary().indicatorText().contains(Messages.LookThroughRevenueCoverageNone));
    }

    @Test
    public void testOverlapMatrixOfTwoFundsAndItsTooltip()
    {
        var x = instrument("x", "Synthetic X", AssetType.EQUITY, "CO-X");
        var y = instrument("y", "Synthetic Y", AssetType.EQUITY, "CO-Y");
        var a = instrument("fund-a", AssetType.FUND, null);
        var b = instrument("fund-b", AssetType.FUND, null);
        hold("fund-a", "Fund A", AssetType.FUND, null, 20, 100);
        hold("fund-b", "Fund B", AssetType.FUND, null, 10, 100);
        composition(a, row(x, "0.05"), row(y, "0.02"));
        composition(b, row(x, "0.03"), row(y, "0.04"));

        var matrix = LookThroughOverlapModel.of(FundOverlap.of(result()));

        assertFalse(matrix.isEmpty());
        assertEquals(2, matrix.size());
        assertEquals("Fund A", matrix.name(0));
        assertEquals("Fund B", matrix.name(1));
        var cell = matrix.cell(0, 1);
        assertEquals(Values.Percent2.format(0.05d), cell.text());
        assertEquals(1d, cell.shade(), 1e-9);
        assertEquals(cell.text(), matrix.cell(1, 0).text());
        assertEquals(Optional.of(new Identifier("synthetic:company", "CO-X")), cell.largest());
        assertEquals(LookThroughViewPresenter.companyKey(new Identifier("synthetic:company", "CO-X")),
                        cell.largest().map(LookThroughViewPresenter::companyKey).orElseThrow());
        var lines = cell.tooltip().split("\n");
        assertEquals("Fund A / Fund B", lines[0]);
        assertTrue(lines[1].endsWith(cell.text()));
        assertEquals("Synthetic X: " + Values.Percent2.format(0.03d), lines[lines.length - 2]);
        assertEquals("Synthetic Y: " + Values.Percent2.format(0.02d), lines[lines.length - 1]);
        assertTrue(matrix.excludedText().isEmpty());
    }

    @Test
    public void testOverlapNeedsTwoDecomposedFundsAndNamesTheOthers()
    {
        var x = instrument("x", AssetType.EQUITY, "CO-X");
        var a = instrument("fund-a", AssetType.FUND, null);
        instrument("fund-b", AssetType.FUND, null);
        hold("fund-a", "Fund A", AssetType.FUND, null, 20, 100);
        hold("fund-b", "Fund B", AssetType.FUND, null, 10, 100);
        composition(a, row(x, "0.5"));

        var matrix = LookThroughOverlapModel.of(FundOverlap.of(result()));

        assertTrue(matrix.isEmpty());
        assertEquals(MessageFormat.format(Messages.LookThroughOverlapExcluded, "Fund B"),
                        matrix.excludedText().orElseThrow());
    }

    @Test
    public void testEffectiveNumberOfEvenlySpreadCompaniesIsTheirCount()
    {
        for (int ii = 0; ii < 100; ii++)
            hold("c" + ii, "Synthetic c" + ii, AssetType.EQUITY, "CO-" + ii, 1, 100);

        var summary = model(result()).summary();

        assertEquals(100d, summary.effectiveCompanies().orElseThrow().doubleValue(), 1e-9);
        assertEquals("100", summary.effectiveText().orElseThrow());
        var largest = summary.largest().orElseThrow();
        amount("0.01", largest.share());
        assertEquals(Values.Percent2.format(0.01d), largest.shareText());
        assertFalse(summary.indicatorText().contains(Messages.LookThroughNoticeConcentration));
    }

    @Test
    public void testCompanyAboveTenPercentIsNamedByTheAttentionIndicator()
    {
        hold("big", "Synthetic big", AssetType.EQUITY, "CO-BIG", 1, 120);
        for (int ii = 0; ii < 88; ii++)
            hold("s" + ii, "Synthetic s" + ii, AssetType.EQUITY, "CO-S" + ii, 1, 10);

        var summary = model(result()).summary();

        var largest = summary.largest().orElseThrow();
        assertEquals("Synthetic big", largest.name());
        amount("0.12", largest.share());
        assertEquals(Values.Percent2.format(0.12d), largest.shareText());
        assertTrue(summary.attention());
        assertTrue(summary.indicatorText().contains(MessageFormat.format(Messages.LookThroughNoticeConcentration,
                        "Synthetic big", Values.Percent2.format(0.12d))));
        // 12 % big, 88 times 1 %: 1 / (0.12^2 + 88 * 0.01^2)
        assertEquals(1d / (0.0144d + 88 * 0.0001d), summary.effectiveCompanies().orElseThrow().doubleValue(), 1e-6);
    }

    @Test
    public void testConcentrationIsWithheldWithoutCompanyExposure()
    {
        hold("cash", "Synthetic cash", AssetType.CASH, null, 1, 100);

        var summary = model(result()).summary();

        assertTrue(summary.effectiveCompanies().isEmpty());
        assertTrue(summary.effectiveText().isEmpty());
        assertTrue(summary.largest().isEmpty());
    }
}
