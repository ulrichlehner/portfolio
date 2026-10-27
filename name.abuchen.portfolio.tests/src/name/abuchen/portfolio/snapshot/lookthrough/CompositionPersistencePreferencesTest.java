package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class CompositionPersistencePreferencesTest
{
    private static final ProviderAccount A = new ProviderAccount("provider", "account-a");
    private static final ProviderAccount B = new ProviderAccount("provider", "account-b");

    private static CompositionPersistencePolicy policy(ProviderAccount account,
                    CompositionPersistencePolicy.Permission permission)
    {
        return new CompositionPersistencePolicy(account, permission, Optional.empty());
    }

    private static CompositionInstrument fund(String id)
    {
        return new CompositionInstrument("", Map.of("isin", id));
    }

    private static CompositionResult composition(ProviderAccount account, CompositionInstrument fund)
    {
        var provenance = new CompositionProvenance(account, fund, Instant.parse("2026-01-01T00:00:00Z"),
                        Optional.of(Instant.parse("2025-12-31T23:50:00Z")), Optional.empty());
        var body = new FundComposition(provenance, FundComposition.Denominator.TOTAL_FUND_NAV,
                        FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                        List.of(new FundComposition.Constituent(fund, Optional.of("1"))));
        return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(body), Optional.empty());
    }

    private static void put(CompositionStore store, ProviderAccount account, CompositionInstrument fund)
    {
        store.complete(store.beginRefresh(account, fund), composition(account, fund));
    }

    private static long files(Path directory) throws Exception
    {
        if (!Files.exists(directory))
            return 0;
        try (var stream = Files.list(directory))
        {
            return stream.count();
        }
    }

    @Test
    public void absentReturnsEmpty()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        assertEquals(Optional.empty(), preferences.get(A));
    }

    @Test
    public void setGetRoundTrip()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        preferences.set(A, false);
        assertEquals(Optional.of(false), preferences.get(A));
        preferences.set(A, true);
        assertEquals(Optional.of(true), preferences.get(A));
    }

    @Test
    public void localStorageConfirmationDefaultsOffAndIsPerAccount()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        var count = new AtomicInteger();
        preferences.addListener(account -> count.incrementAndGet());
        assertFalse(preferences.isLocalStorageConfirmed(A));
        preferences.setLocalStorageConfirmed(A, true);
        assertTrue(preferences.isLocalStorageConfirmed(A));
        assertFalse(preferences.isLocalStorageConfirmed(B));
        preferences.setLocalStorageConfirmed(A, false);
        assertFalse(preferences.isLocalStorageConfirmed(A));
        assertEquals(2, count.get());
    }

    @Test
    public void oneListenerNotificationPerChange()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        var count = new AtomicInteger();
        preferences.addListener(account -> count.incrementAndGet());
        preferences.set(A, false);
        preferences.set(A, true);
        assertEquals(2, count.get());
    }

    @Test
    public void perAccountIsolation()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        preferences.set(A, false);
        assertEquals(Optional.of(false), preferences.get(A));
        assertEquals(Optional.empty(), preferences.get(B));
    }

    @Test
    public void bindAppliesConfigureAndClosedHandleStopsNotifications() throws Exception
    {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var directory = Files.createTempDirectory("prefs-bind");
        var preferences = new CompositionPersistencePreferences.InMemory();

        try (var store = new VersionedCompositionStore(directory, clock))
        {
            var handle = preferences.bind(store, account -> policy(account,
                            CompositionPersistencePolicy.Permission.PERMITTED));
            var fund = fund("id");

            store.configure(policy(A, CompositionPersistencePolicy.Permission.PERMITTED), Optional.of(true));
            put(store, A, fund);
            assertEquals(1, files(directory));

            preferences.set(A, false);
            assertEquals(0, files(directory));
            assertEquals(CompositionStore.State.SESSION_ONLY, store.read(A, fund).state());
            assertTrue(store.read(A, fund).usableComposition().isPresent());

            handle.close();
            preferences.set(A, true);
            assertEquals(0, files(directory));
            assertTrue(store.read(A, fund).usableComposition().isPresent());
        }
    }

    @Test
    public void bindingWhileDisabledCreatesNoDirectory() throws Exception
    {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var parent = Files.createTempDirectory("prefs-disabled");
        var directory = parent.resolve("cache");
        var preferences = new CompositionPersistencePreferences.InMemory();

        try (var store = new VersionedCompositionStore(directory, clock))
        {
            preferences.bind(store, account -> policy(account, CompositionPersistencePolicy.Permission.UNVERIFIED));
            preferences.set(A, true);
            assertFalse(Files.exists(directory));
            assertTrue(store.read(A, fund("id")).usableComposition().isEmpty());
        }
    }
}
