package name.abuchen.portfolio.snapshot.lookthrough;

import java.util.Map;
import java.util.Objects;

/**
 * Source identifiers and label, independent of owned securities. Identifier keys
 * identify their namespace (including the provider for provider-specific IDs).
 * Neither a label nor an identifier establishes asset type or company grouping.
 * An empty map retains an unidentified source row for later resolution.
 */
public record CompositionInstrument(String label, Map<String, String> identifiers)
{
    public CompositionInstrument
    {
        Objects.requireNonNull(label);
        identifiers = Map.copyOf(identifiers);
    }
}
