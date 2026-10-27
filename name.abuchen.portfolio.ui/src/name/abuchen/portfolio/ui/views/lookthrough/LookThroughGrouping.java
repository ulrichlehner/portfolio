package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.TaxonomyTemplate;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes.Attribute;
import name.abuchen.portfolio.snapshot.lookthrough.RevenueSplit;
import name.abuchen.portfolio.ui.Messages;

/**
 * The ways to group the companies of the Breakdown and the classifications
 * behind them. The view only reads PP's taxonomy templates; no taxonomy is
 * added to the client file. The class is SWT-free.
 */
public final class LookThroughGrouping
{
    /** Attribute that the Breakdown groups its companies by. */
    public enum Grouping
    {
        COMPANY, COUNTRY, REGION, MARKET_CLASSIFICATION, SECTOR, INDUSTRY, CURRENCY, REVENUE_REGION;

        public String label()
        {
            return switch (this)
            {
                case COMPANY -> Messages.LookThroughGroupingCompany;
                case COUNTRY -> Messages.LookThroughGroupingCountry;
                case REGION -> Messages.LookThroughGroupingRegion;
                case MARKET_CLASSIFICATION -> Messages.LookThroughGroupingMarket;
                case SECTOR -> Messages.LookThroughGroupingSector;
                case INDUSTRY -> Messages.LookThroughGroupingIndustry;
                case CURRENCY -> Messages.LookThroughGroupingCurrency;
                case REVENUE_REGION -> Messages.LookThroughGroupingRevenueRegion;
            };
        }
    }

    /** One group of a grouping: a stable code for the row key and the label to display. */
    public record Group(String code, String label)
    {
    }

    private static final String COUNTRY_PREFIX = "country_"; //$NON-NLS-1$

    /** Provider sector names that differ from the GICS names, in lower case. */
    private static final Map<String, String> SECTOR_ALIASES = Map.of( //
                    "technology", "45", //$NON-NLS-1$ //$NON-NLS-2$
                    "healthcare", "35", //$NON-NLS-1$ //$NON-NLS-2$
                    "financial services", "40", //$NON-NLS-1$ //$NON-NLS-2$
                    "financials", "40", //$NON-NLS-1$ //$NON-NLS-2$
                    "consumer cyclical", "25", //$NON-NLS-1$ //$NON-NLS-2$
                    "consumer defensive", "30", //$NON-NLS-1$ //$NON-NLS-2$
                    "basic materials", "15"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Classification maps of one locale. */
    private record Maps(Map<String, String> countryNames, Map<String, String> regionNames, Map<String, Group> regions, Map<String, Group> markets,
                    Map<String, Group> sectorsByName, Map<String, Group> sectorsByCode)
    {
    }

    private static final Map<Locale, Maps> CACHE = new ConcurrentHashMap<>();

    private final Maps maps = CACHE.computeIfAbsent(Locale.getDefault(), locale -> load());

    /** Whether the grouping needs the company attributes. */
    public static boolean usesAttributes(Grouping grouping)
    {
        return grouping != Grouping.COMPANY;
    }

    /**
     * The group of a company with the given attributes, or empty when the
     * attribute is unknown or has no entry in the classification.
     */
    public Optional<Group> groupOf(Grouping grouping, CompanyAttributes attributes)
    {
        return switch (grouping)
        {
            case COMPANY -> Optional.empty();
            case COUNTRY -> value(attributes.country()).map(this::country);
            case REGION -> value(attributes.country()).map(maps.regions()::get);
            case MARKET_CLASSIFICATION -> value(attributes.country()).map(maps.markets()::get);
            case SECTOR -> value(attributes.sector()).map(this::sector);
            case INDUSTRY -> value(attributes.industry()).map(name -> new Group(name.toLowerCase(Locale.ROOT), name));
            case CURRENCY -> value(attributes.currency()).map(code -> new Group(code, code));
            // split over several groups, see revenueGroup
            case REVENUE_REGION -> Optional.empty();
        };
    }

    /** The country with the localized name of PP's region template. */
    public Group country(String code)
    {
        var name = maps.countryNames().get(code);
        if (name == null)
        {
            var display = Locale.of("", code).getDisplayCountry(); //$NON-NLS-1$
            name = display.isBlank() ? code : display;
        }
        return new Group(code, name);
    }

    /**
     * The group of a revenue member: a country, a region of PP's region
     * template, one of the regions that the template lacks, or the remainder.
     */
    public Group revenueGroup(RevenueSplit.Member member)
    {
        var code = member.code();
        return switch (member.kind())
        {
            case COUNTRY -> country(code);
            case REGION -> new Group(member.key(), switch (code)
            {
                case RevenueSplit.EMEA -> Messages.LookThroughRegionEmea;
                case RevenueSplit.APAC -> Messages.LookThroughRegionApac;
                case RevenueSplit.LATAM -> Messages.LookThroughRegionLatam;
                case RevenueSplit.GREATER_CHINA -> Messages.LookThroughRegionGreaterChina;
                default -> maps.regionNames().getOrDefault(code, code);
            });
            case OTHER -> new Group(member.key(), Messages.LookThroughRegionOther);
        };
    }

    /**
     * The sector of PP's GICS template when the provider name equals its
     * English name (or a known alias); otherwise the name as delivered.
     */
    public Group sector(String name)
    {
        var normalized = name.trim().toLowerCase(Locale.ROOT);
        var group = maps.sectorsByName().get(normalized);
        if (group == null)
        {
            var code = SECTOR_ALIASES.get(normalized);
            if (code != null)
                group = maps.sectorsByCode().get(code);
        }
        return group != null ? group : new Group("name:" + normalized, name.trim()); //$NON-NLS-1$
    }

    private static <T> Optional<T> value(Optional<Attribute<T>> attribute)
    {
        return attribute.map(Attribute::value);
    }

    private static Maps load()
    {
        var names = new HashMap<String, String>();
        var regions = new HashMap<String, Group>();
        var markets = new HashMap<String, Group>();
        countries("regions", 1, names, regions); //$NON-NLS-1$
        countries("regions-msci", 1, names, markets); //$NON-NLS-1$
        var regionNames = new HashMap<String, String>();
        regionNames(TaxonomyTemplate.byId("regions").build().getRoot(), regionNames); //$NON-NLS-1$

        var byName = new HashMap<String, Group>();
        var byCode = new HashMap<String, Group>();
        var english = ResourceBundle.getBundle("name.abuchen.portfolio.model.taxonomy_templates.industry-gics-1st-level", //$NON-NLS-1$
                        Locale.ROOT, TaxonomyTemplate.class.getClassLoader());
        for (var sector : TaxonomyTemplate.byId("industry-gics-1st-level").build().getRoot().getChildren()) //$NON-NLS-1$
        {
            var group = new Group(sector.getKey(), sector.getName());
            byCode.put(sector.getKey(), group);
            if (english.containsKey(sector.getKey() + ".label")) //$NON-NLS-1$
                byName.put(english.getString(sector.getKey() + ".label").trim().toLowerCase(Locale.ROOT), group); //$NON-NLS-1$
        }
        return new Maps(Map.copyOf(names), Map.copyOf(regionNames), Map.copyOf(regions), Map.copyOf(markets), Map.copyOf(byName),
                        Map.copyOf(byCode));
    }

    /**
     * Reads the template without keeping it: every country leaf below a
     * classification of the given depth maps to that classification. A country
     * listed several times keeps its first group.
     */
    private static void countries(String template, int depth, Map<String, String> names, Map<String, Group> groups)
    {
        var root = TaxonomyTemplate.byId(template).build().getRoot();
        collect(root, 0, depth, null, names, groups);
    }

    /** The names of the continents and regions of the template, by their key. */
    private static void regionNames(Classification node, Map<String, String> names)
    {
        for (var child : node.getChildren())
        {
            if (!child.getKey().startsWith(COUNTRY_PREFIX))
            {
                names.put(child.getKey(), child.getName());
                regionNames(child, names);
            }
        }
    }

    private static void collect(Classification node, int level, int depth, Group group, Map<String, String> names,
                    Map<String, Group> groups)
    {
        var current = group;
        if (level == depth)
            current = new Group(node.getKey(), node.getName());

        if (node.getKey().startsWith(COUNTRY_PREFIX) && current != null)
        {
            var code = node.getKey().substring(COUNTRY_PREFIX.length());
            names.putIfAbsent(code, node.getName());
            groups.putIfAbsent(code, current);
        }
        for (var child : node.getChildren())
            collect(child, level + 1, depth, current, names, groups);
    }
}
