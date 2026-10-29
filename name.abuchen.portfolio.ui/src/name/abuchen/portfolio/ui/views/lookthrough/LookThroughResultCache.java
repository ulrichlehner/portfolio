package name.abuchen.portfolio.ui.views.lookthrough;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.snapshot.lookthrough.FundOverlap;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughGrouping.Grouping;

/**
 * Session-only cache of the last presented breakdown, one entry per portfolio.
 * The entry is weakly keyed by the client, so it disappears when the portfolio
 * is closed and collected. It is never persisted and never causes a request;
 * it only lets a re-created view show its last result until the background
 * calculation replaces it.
 */
public final class LookThroughResultCache
{
    /**
     * One stored presentation. The inputs describe what the result was
     * calculated from; they are not compared when reading because the view
     * reads the cache before it gathers the current inputs.
     */
    public record Entry(LookThroughResult result, LookThroughInputs inputs, Grouping grouping,
                    LookThroughViewPresenter.Model model, FundOverlap overlap, Instant storedAt)
    {
        public Entry
        {
            Objects.requireNonNull(result);
            Objects.requireNonNull(inputs);
            Objects.requireNonNull(grouping);
            Objects.requireNonNull(model);
            Objects.requireNonNull(overlap);
            Objects.requireNonNull(storedAt);
        }
    }

    private final Map<Client, Entry> entries = new WeakHashMap<>();

    public synchronized void put(Client client, Entry entry)
    {
        entries.put(Objects.requireNonNull(client), Objects.requireNonNull(entry));
    }

    public synchronized Optional<Entry> get(Client client)
    {
        return Optional.ofNullable(entries.get(client));
    }

    /**
     * Reads the entry unless the portfolio has unsaved changes. Such a change
     * makes every stored number wrong, so the entry is dropped instead of
     * shown.
     */
    public synchronized Optional<Entry> get(Client client, boolean dirty)
    {
        if (!dirty)
            return get(client);
        entries.remove(client);
        return Optional.empty();
    }

    public synchronized void clear(Client client)
    {
        entries.remove(client);
    }

    public synchronized void clear()
    {
        entries.clear();
    }

    /** Number of stored portfolios; for tests. */
    public synchronized int size()
    {
        return entries.size();
    }
}
