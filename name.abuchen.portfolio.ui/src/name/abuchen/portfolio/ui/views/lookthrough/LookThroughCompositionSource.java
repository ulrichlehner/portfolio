package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.List;
import java.util.Optional;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;

import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.RefreshGateway;

/**
 * Supplies the look-through view with the memory-only access to retained
 * compositions, the global storage facts and the funds of the current portfolio.
 * <p>
 * Implementations must not perform requests in any of these methods. The
 * gateway read is memory-only, {@link #globalFacts()} reports the current
 * store state and {@link #funds()} only lists the held funds. Every method
 * is called on the user interface thread while rendering or while preparing a
 * manual refresh, so a request here would turn a redraw or a resize into
 * provider traffic.
 * <p>
 * The only exception is {@link #keyConfigured()}, which reads the local
 * credential state and is called off the user interface thread.
 * <p>
 * The source may be absent when no composition backend is configured. The view
 * then shows no status and offers no refresh, but keeps working with the
 * provided result.
 */
public interface LookThroughCompositionSource
{
    /** Securities with a nonzero net position, including those without identifiers. */
    default List<Security> securities()
    {
        return List.of();
    }

    RefreshGateway gateway();

    LookThroughDataStatus.GlobalFacts globalFacts();

    List<FundRef> funds();

    /**
     * Session-cached classifications of held instruments, from the provider.
     * Empty while no classification has run; the analysis resolves held
     * instruments from this evidence.
     */
    default List<InstrumentEvidence> classifications()
    {
        return List.of();
    }

    /**
     * Schedules the background enrichment of a new result's companies and
     * returns at once. Unlike the other methods this may lead to requests,
     * but only for companies without fresh cached attributes, never because
     * of a redraw.
     */
    default void enrich(LookThroughResult result)
    {
        // No enrichment without a provider backend.
    }

    /**
     * Company attributes (country, currency, sector, industry, size) and the
     * number of companies still pending, for the presenter. Memory-only.
     */
    default CompanyEnrichmentService.Snapshot companyAttributes()
    {
        return CompanyEnrichmentService.Snapshot.empty();
    }

    /**
     * Registers a listener that is called, on a background thread, whenever
     * company attributes changed. Returns the action that removes it.
     */
    default Runnable addEnrichmentListener(Runnable listener)
    {
        return () -> {
        };
    }

    /**
     * Stops the running enrichment after its current request, for example
     * when the view is closed. Answers received so far are kept; the next
     * {@link #enrich} continues.
     */
    default void stopEnrichment()
    {
        // No enrichment without a provider backend.
    }

    /**
     * Whether an API key is configured, for the onboarding banner. Empty when
     * the source has no credentials. Unlike the other methods this may read
     * the secure storage, so callers must not invoke it on the user interface
     * thread. It never performs a request.
     */
    default Optional<Boolean> keyConfigured()
    {
        return Optional.empty();
    }

    /**
     * Whether the source is the application's own provider backend whose
     * results may be kept in the session cache. Injected sources, for example
     * a preview, return false so their result is never reused.
     */
    default boolean cacheable()
    {
        return false;
    }
}
