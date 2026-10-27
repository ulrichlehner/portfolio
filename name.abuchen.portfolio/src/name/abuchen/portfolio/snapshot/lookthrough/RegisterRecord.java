package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The legal entity register record of an ISIN's issuer: its LEI and the ISO
 * 3166 alpha-2 countries of headquarters and legal address. Nothing else of
 * the record is kept.
 */
public record RegisterRecord(String isin, String lei, Optional<String> headquartersCountry,
                Optional<String> legalCountry, Instant retrievedAt)
{
    public RegisterRecord
    {
        Objects.requireNonNull(isin);
        Objects.requireNonNull(lei);
        Objects.requireNonNull(headquartersCountry);
        Objects.requireNonNull(legalCountry);
        Objects.requireNonNull(retrievedAt);
        if (isin.isBlank() || !lei.matches("[A-Z0-9]{20}") //$NON-NLS-1$
                        || headquartersCountry.filter(code -> !CompanyAttributes.isCountryCode(code)).isPresent()
                        || legalCountry.filter(code -> !CompanyAttributes.isCountryCode(code)).isPresent())
            throw new IllegalArgumentException();
    }
}
