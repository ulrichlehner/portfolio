package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The latest reported revenue of a company by geography, normalized to
 * countries, known regions and an "other or unspecified" remainder. Only
 * percentages are kept. The shares add up to exactly 100; when the reported
 * entries did not, they were scaled and the reported sum is kept.
 */
public record RevenueSplit(String isin, LocalDate period, List<Share> shares, BigDecimal reportedSum,
                Instant retrievedAt)
{
    /** What a revenue share belongs to. */
    public enum Kind
    {
        COUNTRY, REGION, OTHER
    }

    /**
     * A country (ISO code), a region (key of PP's region template, or one of
     * {@link #EMEA}, {@link #APAC}, {@link #LATAM}) or the remainder.
     */
    public record Member(Kind kind, String code)
    {
        public static final Member OTHER = new Member(Kind.OTHER, "other"); //$NON-NLS-1$

        public Member
        {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(code);
            if (code.isBlank() || code.length() > 64)
                throw new IllegalArgumentException();
        }

        /** Stable text form, for example {@code country:US}. */
        public String key()
        {
            return kind.name().toLowerCase(Locale.ROOT) + ':' + code;
        }

        public static Optional<Member> parse(String key)
        {
            if (key == null)
                return Optional.empty();
            var index = key.indexOf(':');
            if (index <= 0)
                return Optional.empty();
            try
            {
                return Optional.of(new Member(Kind.valueOf(key.substring(0, index).toUpperCase(Locale.ROOT)),
                                key.substring(index + 1)));
            }
            catch (IllegalArgumentException e)
            {
                return Optional.empty();
            }
        }
    }

    /** One member with its percentage of the revenue (0 to 100). */
    public record Share(Member member, BigDecimal percent)
    {
        public Share
        {
            Objects.requireNonNull(member);
            Objects.requireNonNull(percent);
            if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0)
                throw new IllegalArgumentException();
        }
    }

    /** One reported entry as delivered: label and member as text, percentage as reported. */
    public record Entry(String label, String member, BigDecimal percentage)
    {
    }

    public static final String EMEA = "EMEA"; //$NON-NLS-1$
    public static final String APAC = "APAC"; //$NON-NLS-1$
    public static final String LATAM = "LATAM"; //$NON-NLS-1$
    /**
     * China including Hong Kong and Taiwan as reported by some filers. It is no
     * country and cannot be split over the three, so it is a region of its own.
     */
    public static final String GREATER_CHINA = "GREATER_CHINA"; //$NON-NLS-1$

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 8;
    /** Reported sums within this distance of 100 count as complete. */
    private static final BigDecimal TOLERANCE = BigDecimal.ONE;
    private static final String COUNTRY_PREFIX = "country_"; //$NON-NLS-1$

    /** Normalized label or member (lower case, letters and digits only) to a region key. */
    private static final Map<String, String> REGIONS = Map.ofEntries( //
                    Map.entry("europe", "R10"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("europeanunion", "R10"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("restofeurope", "R10"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("americas", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("america", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("northamerica", "R2010"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("southamerica", "R2040"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("latinamerica", LATAM), //$NON-NLS-1$
                    Map.entry("asia", "R30"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("restofasia", "R30"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("africa", "R40"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("oceania", "R50"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("emea", EMEA), //$NON-NLS-1$
                    Map.entry("europemiddleeastandafrica", EMEA), //$NON-NLS-1$
                    Map.entry("europemiddleeastafrica", EMEA), //$NON-NLS-1$
                    Map.entry("asiapacific", APAC), //$NON-NLS-1$
                    Map.entry("apac", APAC), //$NON-NLS-1$
                    Map.entry("asiapacificregion", APAC), //$NON-NLS-1$
                    Map.entry("europeanmiddleeastandafrica", EMEA), //$NON-NLS-1$
                    Map.entry("europemiddleeastindiaandafrica", EMEA), //$NON-NLS-1$
                    Map.entry("emeia", EMEA), //$NON-NLS-1$
                    Map.entry("emearegion", EMEA), //$NON-NLS-1$
                    Map.entry("apacregion", APAC), //$NON-NLS-1$
                    Map.entry("asiapac", APAC), //$NON-NLS-1$
                    Map.entry("asiapacificandjapan", APAC), //$NON-NLS-1$
                    Map.entry("asiapacificincludingjapan", APAC), //$NON-NLS-1$
                    Map.entry("asiapacificexcludingjapan", APAC), //$NON-NLS-1$
                    Map.entry("asiapacificexjapan", APAC), //$NON-NLS-1$
                    Map.entry("otheramericas", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("restofamericas", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("americasexcludingus", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("americasexcludingunitedstates", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("americasexus", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("americasexcludingtheus", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("americasexcludingtheunitedstates", "R20"), //$NON-NLS-1$ //$NON-NLS-2$
                    Map.entry("greaterchina", GREATER_CHINA), //$NON-NLS-1$
                    Map.entry("greaterchinaregion", GREATER_CHINA), //$NON-NLS-1$
                    Map.entry("chinaincludinghongkongandtaiwan", GREATER_CHINA), //$NON-NLS-1$
                    Map.entry("chinahongkongandtaiwan", GREATER_CHINA), //$NON-NLS-1$
                    Map.entry("chinaincludinghongkong", GREATER_CHINA), //$NON-NLS-1$
                    Map.entry("chinaincludinghongkongtaiwan", GREATER_CHINA)); //$NON-NLS-1$

    /**
     * Labels that name the remainder explicitly. They stay in "other or
     * unspecified"; the set exists so that none of them is mistaken for a
     * country or region.
     */
    private static final Set<String> REMAINDER = Set.of("international", "foreign", "nonus", "outsideus", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "outsideunitedstates", "outsidetheus", "outsidetheunitedstates", "restofworld", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "restoftheworld", "allothercountries", "othercountries", "allother", "other", "others", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    "nonusa", "nonunitedstates", "foreigncountries", "internationalmarkets"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    public RevenueSplit
    {
        Objects.requireNonNull(isin);
        Objects.requireNonNull(period);
        Objects.requireNonNull(reportedSum);
        Objects.requireNonNull(retrievedAt);
        shares = List.copyOf(shares);
        if (isin.isBlank() || shares.isEmpty() || reportedSum.signum() <= 0
                        || shares.stream().map(Share::percent).reduce(BigDecimal.ZERO, BigDecimal::add)
                                        .compareTo(HUNDRED) != 0
                        || shares.stream().map(Share::member).distinct().count() != shares.size())
            throw new IllegalArgumentException();
    }

    /** Whether the reported entries did not add up to 100 percent. */
    public boolean incomplete()
    {
        return reportedSum.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0;
    }

    /** The share of a member in percent, zero when it is not part of the split. */
    public BigDecimal percentOf(Member member)
    {
        return shares.stream().filter(share -> share.member().equals(member)).map(Share::percent).findFirst()
                        .orElse(BigDecimal.ZERO);
    }

    /**
     * Normalizes the entries of one period and axis. Entries without a
     * positive percentage are ignored. Returns empty when nothing remains.
     */
    public static Optional<RevenueSplit> of(String isin, LocalDate period, List<Entry> entries, Instant retrievedAt)
    {
        var merged = new LinkedHashMap<Member, BigDecimal>();
        for (var entry : entries)
        {
            if (entry.percentage() == null || entry.percentage().signum() <= 0)
                continue;
            merged.merge(memberOf(entry.member(), entry.label()), entry.percentage(), BigDecimal::add);
        }
        var sum = merged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() <= 0)
            return Optional.empty();

        // A split delivered as fractions (adding up to about 1) is scaled to percent.
        if (sum.compareTo(new BigDecimal("1.5")) <= 0 //$NON-NLS-1$
                        && merged.values().stream().allMatch(value -> value.compareTo(BigDecimal.ONE) <= 0))
        {
            merged.replaceAll((member, value) -> value.multiply(HUNDRED));
            sum = sum.multiply(HUNDRED);
        }

        var scaled = new LinkedHashMap<Member, BigDecimal>();
        var total = sum;
        merged.forEach((member, value) -> scaled.put(member,
                        value.multiply(HUNDRED).divide(total, SCALE, RoundingMode.HALF_EVEN)));
        // The rounding remainder goes to the largest share, so the shares add up to exactly 100.
        var remainder = HUNDRED.subtract(scaled.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        var largest = scaled.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        scaled.merge(largest, remainder, BigDecimal::add);

        var shares = new ArrayList<Share>();
        scaled.forEach((member, value) -> shares.add(new Share(member, value)));
        shares.sort(Comparator.comparing(Share::percent).reversed().thenComparing(share -> share.member().key()));
        return Optional.of(new RevenueSplit(isin, period, shares, sum, retrievedAt));
    }

    /**
     * Country members map to their ISO code, known region members and labels to
     * a region key, country names to their code (case, spacing and punctuation
     * do not matter); everything else, including the explicit rest of the
     * world, is the remainder.
     */
    static Member memberOf(String member, String label)
    {
        if (member != null && member.startsWith(COUNTRY_PREFIX))
        {
            var code = member.substring(COUNTRY_PREFIX.length()).toUpperCase(Locale.ROOT);
            if (CompanyAttributes.isCountryCode(code))
                return new Member(Kind.COUNTRY, code);
        }
        for (var text : new String[] { member, label })
        {
            var key = simplify(text);
            if (key.isEmpty())
                continue;
            if (REMAINDER.contains(key))
                return Member.OTHER;
            var region = REGIONS.get(key);
            if (region != null)
                return new Member(Kind.REGION, region);
        }
        if (label != null)
        {
            var country = CompanyAttributes.countryOfName(label);
            if (country.isPresent())
                return new Member(Kind.COUNTRY, country.get());
        }
        return Member.OTHER;
    }

    /** Lower case letters and digits of a label or member name, without a taxonomy prefix and the suffix "member". */
    private static String simplify(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        var value = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        var colon = value.indexOf('_');
        if (colon > 0 && value.indexOf(' ') < 0)
            value = value.substring(colon + 1);
        value = value.replaceAll("[^a-z0-9]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (value.endsWith("member") && value.length() > "member".length()) //$NON-NLS-1$ //$NON-NLS-2$
            value = value.substring(0, value.length() - "member".length()); //$NON-NLS-1$
        return value;
    }
}
