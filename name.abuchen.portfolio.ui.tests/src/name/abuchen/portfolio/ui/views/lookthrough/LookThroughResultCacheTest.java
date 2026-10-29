package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.snapshot.lookthrough.FundOverlap;
import name.abuchen.portfolio.snapshot.lookthrough.LookThroughResult;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughGrouping.Grouping;

/**
 * Session-only cache of the last presentation. Synthetic objects only; nothing
 * touches the disk, the network or the provider.
 */
@SuppressWarnings("nls")
public class LookThroughResultCacheTest
{
    private static final Instant STORED_AT = Instant.parse("2026-10-29T00:00:00Z");

    private final Client client = new Client();
    private final LookThroughInputs inputs = new LookThroughInputs(List.of(), List.of(), List.of());

    private LookThroughResultCache.Entry entry()
    {
        return new LookThroughResultCache.Entry(mock(LookThroughResult.class), inputs, Grouping.COMPANY,
                        mock(LookThroughViewPresenter.Model.class), mock(FundOverlap.class), STORED_AT);
    }

    @Test
    public void testStoresAndReturnsTheLastPresentation()
    {
        var cache = new LookThroughResultCache();
        var entry = entry();

        cache.put(client, entry);

        assertEquals(1, cache.size());
        assertEquals(entry, cache.get(client).orElseThrow());
        assertEquals(Grouping.COMPANY, cache.get(client).orElseThrow().grouping());
        assertEquals(STORED_AT, cache.get(client).orElseThrow().storedAt());
    }

    @Test
    public void testReplacesTheEarlierEntryOfTheSamePortfolio()
    {
        var cache = new LookThroughResultCache();
        cache.put(client, entry());

        var replacement = new LookThroughResultCache.Entry(entry().result(), inputs, Grouping.COUNTRY,
                        entry().model(), entry().overlap(), STORED_AT.plusSeconds(60));
        cache.put(client, replacement);

        assertEquals(1, cache.size());
        assertEquals(Grouping.COUNTRY, cache.get(client).orElseThrow().grouping());
    }

    @Test
    public void testMissAndClear()
    {
        var cache = new LookThroughResultCache();
        assertTrue(cache.get(client).isEmpty());

        cache.put(client, entry());
        cache.clear(client);

        assertTrue(cache.get(client).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    public void testAnotherPortfolioHasNoEntry()
    {
        var cache = new LookThroughResultCache();
        cache.put(client, entry());

        assertFalse(cache.get(new Client()).isPresent());
    }

    @Test
    public void testClearRemovesEveryPortfolio()
    {
        var cache = new LookThroughResultCache();
        cache.put(client, entry());
        cache.put(new Client(), entry());

        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    public void testDirtyPortfolioNeverReadsTheEntryAndDropsIt()
    {
        var cache = new LookThroughResultCache();
        cache.put(client, entry());

        assertTrue(cache.get(client, true).isEmpty());
        assertEquals(0, cache.size());

        cache.put(client, entry());
        assertTrue(cache.get(client, false).isPresent());
    }

    @Test
    public void testSettingsChangeClearsTheSharedCache()
    {
        var cache = LookThroughCompositionService.resultCache();
        cache.put(client, entry());

        LookThroughCompositionService.reconfigure();

        assertTrue(cache.get(client).isEmpty());
    }
}
