package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.List;

import name.abuchen.portfolio.model.Security;

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
}
