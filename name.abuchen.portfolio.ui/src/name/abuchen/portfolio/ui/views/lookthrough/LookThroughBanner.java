package name.abuchen.portfolio.ui.views.lookthrough;

import java.util.Optional;

/**
 * Selects the single banner of the look-through view. The selection is
 * SWT-free so the priority is testable: a running refresh wins over suspended
 * provider access, which wins over a missing API key, which wins over an
 * empty portfolio. A portfolio that holds only directly classified securities
 * needs no composition and therefore gets no key banner.
 */
public final class LookThroughBanner
{
    public enum Kind
    {
        PROGRESS, SUSPENDED, NO_KEY, EMPTY
    }

    /**
     * Facts the selection depends on.
     *
     * @param refreshing
     *            a refresh with visible progress is running
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
    public record Facts(boolean refreshing, Optional<Boolean> keyConfigured, boolean accessSuspended,
                    boolean needsCompositions, boolean empty)
    {
    }

    private LookThroughBanner()
    {
    }

    public static Optional<Kind> select(Facts facts)
    {
        if (facts.refreshing())
            return Optional.of(Kind.PROGRESS);

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
