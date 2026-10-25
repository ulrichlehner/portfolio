package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Verified assertions supplied by an adapter, not inferred from labels. All alias
 * identifiers refer to the same instrument; the identity provenance must support
 * that mapping. Names are deliberately absent from matching and grouping keys.
 * Each classification and company assertion retains its own provenance.
 */
public record InstrumentEvidence(Map<String, String> identifiers, Assertion<Identifier> identity,
                Optional<Assertion<AssetType>> classification, Optional<Assertion<Identifier>> company)
{
    /** Namespaces must include the provider for provider-specific identifiers. */
    public record Identifier(String namespace, String value)
    {
        public Identifier
        {
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(value);
            if (namespace.isBlank() || value.isBlank())
                throw new IllegalArgumentException();
        }
    }

    /** Reference is an opaque source record reference, never credentials. */
    public record Provenance(ProviderAccount source, String reference, Instant retrievedAt)
    {
        public Provenance
        {
            Objects.requireNonNull(source);
            Objects.requireNonNull(reference);
            Objects.requireNonNull(retrievedAt);
            if (reference.isBlank())
                throw new IllegalArgumentException();
        }
    }

    public record Assertion<T>(T value, Provenance provenance)
    {
        public Assertion
        {
            Objects.requireNonNull(value);
            Objects.requireNonNull(provenance);
        }
    }

    /**
     * Unknown types have no assertion. OTHER_NON_EQUITY requires positive
     * non-equity evidence; it is not a fallback for unknown types or unsupported
     * derivatives. Classification does not validate the economic exposure basis.
     */
    public enum AssetType
    {
        EQUITY, DEPOSITARY_RECEIPT, FUND, CASH, OTHER_NON_EQUITY
    }

    public InstrumentEvidence
    {
        identifiers = Map.copyOf(identifiers);
        if (identifiers.isEmpty() || identifiers.entrySet().stream()
                        .anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank()))
            throw new IllegalArgumentException();
        Objects.requireNonNull(identity);
        Objects.requireNonNull(classification);
        Objects.requireNonNull(company);
    }

    public static final String ISIN = "ISIN"; //$NON-NLS-1$

    /**
     * Evidence supplied by a provider that answered with an ETF holdings list for
     * exactly this identifier. The provider thereby classifies the instrument as a
     * fund; nothing is inferred from its label. The allocation semantics of the
     * rows stay independent and unverified here.
     */
    public static InstrumentEvidence fundFromComposition(FundComposition composition)
    {
        Objects.requireNonNull(composition);
        var identifiers = composition.provenance().fund().identifiers();
        var identity = Optional.ofNullable(identifiers.get(ISIN)) //
                        .map(value -> new Identifier(ISIN, value)) //
                        .or(() -> identifiers.entrySet().stream().filter(entry -> !entry.getValue().isBlank())
                                        .sorted(Map.Entry.comparingByKey())
                                        .map(entry -> new Identifier(entry.getKey(), entry.getValue())).findFirst());
        if (identity.isEmpty())
            throw new IllegalArgumentException();
        var provenance = new Provenance(composition.provenance().source(), "etf/holdings", //$NON-NLS-1$
                        composition.provenance().retrievedAt());
        return new InstrumentEvidence(identifiers, new Assertion<>(identity.orElseThrow(), provenance),
                        Optional.of(new Assertion<>(AssetType.FUND, provenance)), Optional.empty());
    }
}
