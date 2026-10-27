package name.abuchen.portfolio.snapshot.lookthrough;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Descriptive fields of a provider's company profile. The country is an ISO
 * 3166 alpha-2 code, the currency an ISO 4217 code and the market
 * capitalization in millions of that currency. Only these fields are kept; no
 * description, logo or raw payload.
 */
public record CompanyProfile(String isin, Optional<String> country, Optional<String> currency,
                Optional<String> sector, Optional<String> industry, Optional<BigDecimal> marketCapMillions,
                Instant retrievedAt)
{
    public CompanyProfile
    {
        Objects.requireNonNull(isin);
        Objects.requireNonNull(country);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(sector);
        Objects.requireNonNull(industry);
        Objects.requireNonNull(marketCapMillions);
        Objects.requireNonNull(retrievedAt);
        if (isin.isBlank() || country.filter(code -> !CompanyAttributes.isCountryCode(code)).isPresent()
                        || currency.filter(code -> !code.matches("[A-Z]{3}")).isPresent() //$NON-NLS-1$
                        || sector.filter(String::isBlank).isPresent() || industry.filter(String::isBlank).isPresent()
                        || marketCapMillions.filter(value -> value.signum() < 0).isPresent())
            throw new IllegalArgumentException();
    }
}
