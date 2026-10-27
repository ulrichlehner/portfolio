package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;

/**
 * Descriptive attributes of one company of a look-through result. Every
 * attribute carries its source and retrieval date; a missing attribute is
 * unknown and never guessed. Countries are ISO 3166 alpha-2 codes. The market
 * capitalization is in millions of the profile currency, as the provider
 * reports it.
 */
public record CompanyAttributes(Identifier identity, Optional<Attribute<String>> country,
                Optional<Attribute<String>> currency, Optional<Attribute<String>> sector,
                Optional<Attribute<String>> industry, Optional<Attribute<BigDecimal>> marketCapMillions)
{
    public enum Source
    {
        /** The public legal entity register (headquarters country). */
        REGISTER,
        /** The provider's company profile. */
        PROVIDER,
        /** Derived from the country prefix of the ISIN. */
        ISIN_PREFIX
    }

    public record Attribute<T>(T value, Source source, Instant retrievedAt)
    {
        public Attribute
        {
            Objects.requireNonNull(value);
            Objects.requireNonNull(source);
            Objects.requireNonNull(retrievedAt);
        }
    }

    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Map<String, String> COUNTRY_NAMES = countryNames();

    public CompanyAttributes
    {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(country);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(sector);
        Objects.requireNonNull(industry);
        Objects.requireNonNull(marketCapMillions);
    }

    private static Map<String, String> countryNames()
    {
        var names = new HashMap<String, String>();
        for (var code : Locale.getISOCountries())
        {
            var name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH); //$NON-NLS-1$
            if (!name.isBlank() && !name.equals(code))
                names.put(normalize(name), code);
        }
        // common names that differ from the JDK display names
        names.put("hong kong", "HK"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("macau", "MO"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("macao", "MO"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("south korea", "KR"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("korea", "KR"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("korea, republic of", "KR"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("taiwan, province of china", "TW"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("usa", "US"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("united states of america", "US"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("uk", "GB"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("great britain", "GB"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("russian federation", "RU"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("czech republic", "CZ"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("the netherlands", "NL"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("viet nam", "VN"); //$NON-NLS-1$ //$NON-NLS-2$
        names.put("turkiye", "TR"); //$NON-NLS-1$ //$NON-NLS-2$
        return Map.copyOf(names);
    }

    private static String normalize(String name)
    {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Maps an English country name, as used by the provider profile, to its ISO
     * 3166 alpha-2 code through the JDK's English display names. Unmapped names
     * are unknown rather than guessed.
     */
    public static Optional<String> countryOfName(String name)
    {
        if (name == null || name.isBlank())
            return Optional.empty();
        return Optional.ofNullable(COUNTRY_NAMES.get(normalize(name)));
    }

    /** Whether the value is an assigned ISO 3166 alpha-2 country code. */
    public static boolean isCountryCode(String code)
    {
        return code != null && code.length() == 2 && COUNTRIES.contains(code);
    }

    /**
     * The country of an ISIN's prefix. Prefixes that are no country code (for
     * example XS for international securities) give no country.
     */
    public static Optional<String> countryOfIsin(String isin)
    {
        if (isin == null || isin.length() < 2)
            return Optional.empty();
        var prefix = isin.substring(0, 2);
        return isCountryCode(prefix) ? Optional.of(prefix) : Optional.empty();
    }

    /**
     * Resolves the attributes of one company. The country comes from the
     * register's headquarters country, otherwise from the provider profile,
     * otherwise from the ISIN prefix. All other attributes come from the
     * profile only. A country derived from the ISIN carries the given
     * resolution instant as its date.
     */
    public static CompanyAttributes resolve(Identifier identity, Optional<CompanyProfile> profile,
                    Optional<RegisterRecord> register, Instant derivedAt)
    {
        Objects.requireNonNull(identity);
        Optional<Attribute<String>> country = register.flatMap(record -> record.headquartersCountry()
                        .map(code -> new Attribute<>(code, Source.REGISTER, record.retrievedAt())));
        if (country.isEmpty())
            country = profile.flatMap(p -> p.country().map(code -> new Attribute<>(code, Source.PROVIDER,
                            p.retrievedAt())));
        if (country.isEmpty() && InstrumentEvidence.ISIN.equals(identity.namespace()))
            country = countryOfIsin(identity.value())
                            .map(code -> new Attribute<>(code, Source.ISIN_PREFIX, derivedAt));
        return new CompanyAttributes(identity, country, //
                        profile.flatMap(p -> p.currency().map(v -> new Attribute<>(v, Source.PROVIDER,
                                        p.retrievedAt()))),
                        profile.flatMap(p -> p.sector().map(v -> new Attribute<>(v, Source.PROVIDER,
                                        p.retrievedAt()))),
                        profile.flatMap(p -> p.industry().map(v -> new Attribute<>(v, Source.PROVIDER,
                                        p.retrievedAt()))),
                        profile.flatMap(p -> p.marketCapMillions().map(v -> new Attribute<>(v, Source.PROVIDER,
                                        p.retrievedAt()))));
    }
}
