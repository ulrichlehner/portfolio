package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.util.Optional;

/**
 * Provider-backed instrument classification. A provider classifies an
 * instrument by its own identifier, for example a stock, ETF, mutual fund or
 * bond. The returned evidence carries an identifier, an asset type and, for
 * equities, the issuer identity used for grouping. Providers never infer a
 * company from a label: they answer from their own security database.
 * <p>
 * An empty result means the provider has no classification for the instrument,
 * not that the instrument is unknown to everyone. Adapters must not throw for
 * missing data; transport and authentication failures are reported by the
 * caller through its own result channel.
 */
@FunctionalInterface
public interface InstrumentClassifier
{
    Optional<InstrumentEvidence> classify(ProviderAccount account, CompositionInstrument instrument) throws IOException;
}
