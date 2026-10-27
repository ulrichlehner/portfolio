package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.Optional;

/**
 * Selects the single banner of the look-through view. The selection is
 * SWT-free so the priority is testable: suspended provider access wins over a
 * missing API key, which wins over an empty portfolio. A portfolio that holds
 * only directly classified securities needs no composition and therefore gets
 * no key banner. Banners describe states; the progress of a refresh is a
 * reaction to a click and is shown inside the summary row instead, so it
 * never moves the view.
 */
public final class LookThroughBanner
{
    public enum Kind
    {
        SUSPENDED, NO_KEY, EMPTY
    }

    /**
     * Facts the selection depends on.
     *
     * @param keyConfigured
     *            an API key is stored for this session or securely; empty
     *            while the credential state is not known yet
     * @param accessSuspended
     *            the provider rejected the key or its permission
     * @param needsCompositions
     *            the portfolio holds funds or positions that are not
     *            classified yet
     * @param empty
     *            the portfolio has no current holdings
     */
    public record Facts(Optional<Boolean> keyConfigured, boolean accessSuspended,
                    boolean needsCompositions, boolean empty)
    {
    }

    private LookThroughBanner()
    {
    }

    public static Optional<Kind> select(Facts facts)
    {
        // A rejected key is only reported while a key exists; without a key
        // the missing key is the actual problem.
        var configured = facts.keyConfigured();
        if (facts.accessSuspended() && !configured.equals(Optional.of(Boolean.FALSE)))
            return Optional.of(Kind.SUSPENDED);

        if (configured.equals(Optional.of(Boolean.FALSE)) && facts.needsCompositions())
            return Optional.of(Kind.NO_KEY);

        if (facts.empty())
            return Optional.of(Kind.EMPTY);

        return Optional.empty();
    }
}
