package name.abuchen.portfolio.online;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/**
 * Composition services are discovered through ServiceLoader and Factory, using
 * META-INF/services/name.abuchen.portfolio.online.FundCompositionProvider.
 * Implementations have a public no-argument constructor. They must reject an
 * account whose provider ID differs from getId(). Requests carry only fund
 * identifiers, never owned securities, quantities or portfolio data. Credential
 * configuration is local and separate from these opaque account identities.
 *
 * Results are unvalidated source snapshots or explicit retrieval failures (see
 * CompositionResult). A provider must retain unknown provenance and allocation
 * semantics rather than inferring them from successful retrieval.
 *
 * Persistence policy and local preference are independent per provider/account.
 * Only PERMITTED plus an enabled preference allows disk reads/writes, within
 * approved retention. Session-only mode uses the same validation/calculations,
 * retaining data only in memory until exit; restart/offline use needs retrieval.
 * Policy withdrawal or opt-out must delete that account's retained compositions,
 * mappings and derived results and invalidate pending writes. Reduced retention
 * must remove expired data even after refresh failure. Other accounts remain
 * independent. These are storage-consumer obligations, not provider disk I/O.
 */
public interface FundCompositionProvider
{
    String getId();

    CompositionPersistencePolicy getPersistencePolicy(ProviderAccount account);

    CompositionResult getComposition(ProviderAccount account, CompositionInstrument fund);
}
