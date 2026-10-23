package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

public record CompositionProvenance(ProviderAccount source, CompositionInstrument fund, Instant retrievedAt,
                Optional<Instant> providerRefreshedAt, Optional<LocalDate> holdingsEffectiveDate)
{
    public CompositionProvenance
    {
        Objects.requireNonNull(source);
        Objects.requireNonNull(fund);
        Objects.requireNonNull(retrievedAt);
        Objects.requireNonNull(providerRefreshedAt);
        Objects.requireNonNull(holdingsEffectiveDate);
    }
}
