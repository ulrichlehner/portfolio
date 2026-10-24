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

import org.junit.Test;

/**
 * Synthetic toggling through the preference listener. The store has no timer and
 * never asks the platform: only a preference change or an explicit configure call
 * re-evaluates the effective persistence mode.
 */
public class CompositionStorePreferenceToggleTest
{
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final ProviderAccount A = new ProviderAccount("provider", "account-a");
    private static final ProviderAccount B = new ProviderAccount("provider", "account-b");

    private static CompositionPersistencePolicy permitted(ProviderAccount account)
    {
        return new CompositionPersistencePolicy(account, CompositionPersistencePolicy.Permission.PERMITTED,
                        Optional.empty());
    }

    private static CompositionPersistencePolicy unverified(ProviderAccount account)
    {
        return new CompositionPersistencePolicy(account, CompositionPersistencePolicy.Permission.UNVERIFIED,
                        Optional.empty());
    }

    private static CompositionInstrument instrument(String id)
    {
        return new CompositionInstrument("Synthetic " + id, Map.of("synthetic:id", id));
    }

    private static FundComposition composition(ProviderAccount account, CompositionInstrument fund)
    {
        return new FundComposition(new CompositionProvenance(account, fund, START, Optional.of(START.minusSeconds(600)),
                        Optional.empty()), FundComposition.Denominator.TOTAL_FUND_NAV,
                        FundComposition.ExposureBasis.PHYSICAL_HOLDINGS, FundComposition.Completeness.PARTIAL,
                        List.of(new FundComposition.Constituent(instrument("equity"), Optional.of("1"))));
    }

    private static CompositionResult received(FundComposition source)
    {
        return new CompositionResult(CompositionResult.Status.RECEIVED, Optional.of(source), Optional.empty());
    }

    private static CompositionStore.Snapshot put(CompositionStore store, ProviderAccount account,
                    CompositionInstrument fund)
    {
        return store.complete(store.beginRefresh(account, fund), received(composition(account, fund)));
    }

    private static long files(Path directory) throws Exception
    {
        if (!Files.exists(directory))
            return 0;
        try (var paths = Files.list(directory))
        {
            return paths.count();
        }
    }

    @Test
    public void toggleOffDeletesOnlyAffectedAccountAndOtherAccountSurvivesRestart() throws Exception
    {
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var directory = Files.createTempDirectory("toggle");
        var fundA = instrument("fund-a");
        var fundB = instrument("fund-b");
        var preferences = new CompositionPersistencePreferences.InMemory();

        try (var store = new VersionedCompositionStore(directory, clock))
        {
            store.configure(permitted(A), Optional.of(true));
            store.configure(permitted(B), Optional.of(true));
            put(store, A, fundA);
            put(store, B, fundB);
            assertEquals(2, files(directory));

            preferences.bind(store, account -> permitted(account));
            preferences.set(A, false);

            assertEquals(1, files(directory));
            assertEquals(CompositionStore.State.SESSION_ONLY, store.read(A, fundA).state());
            assertTrue(store.read(A, fundA).usableComposition().isPresent());
            assertEquals(CompositionStore.State.PERSISTENT, store.read(B, fundB).state());
            assertTrue(store.read(B, fundB).usableComposition().isPresent());
        }

        try (var restarted = new VersionedCompositionStore(directory, clock))
        {
            restarted.configure(permitted(A), Optional.of(true));
            restarted.configure(permitted(B), Optional.of(true));
            assertTrue(restarted.read(A, fundA).usableComposition().isEmpty());
            assertTrue(restarted.read(B, fundB).usableComposition().isPresent());
        }
    }

    @Test
    public void pendingTicketCannotWriteAfterToggleOff() throws Exception
    {
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var directory = Files.createTempDirectory("toggle-pending");
        var fund = instrument("fund");
        var preferences = new CompositionPersistencePreferences.InMemory();

        try (var store = new VersionedCompositionStore(directory, clock))
        {
            store.configure(permitted(A), Optional.of(true));
            var pending = store.beginRefresh(A, fund);

            preferences.bind(store, account -> permitted(account));
            preferences.set(A, false);

            store.complete(pending, received(composition(A, fund)));
            assertEquals(0, files(directory));
            assertTrue(store.read(A, fund).usableComposition().isPresent());
        }
    }

    @Test
    public void reEnableWritesNothingRetroactivelyButLaterRefreshPersists() throws Exception
    {
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var directory = Files.createTempDirectory("toggle-reenable");
        var fund = instrument("fund");
        var preferences = new CompositionPersistencePreferences.InMemory();

        try (var store = new VersionedCompositionStore(directory, clock))
        {
            store.configure(permitted(A), Optional.of(true));
            put(store, A, fund);
            assertEquals(1, files(directory));

            preferences.bind(store, account -> permitted(account));
            preferences.set(A, false);
            assertEquals(0, files(directory));
            assertTrue(store.read(A, fund).usableComposition().isPresent());

            preferences.set(A, true);
            assertEquals(0, files(directory));

            put(store, A, fund);
            assertEquals(1, files(directory));
        }
    }

    @Test
    public void preferenceOnWhilePolicyUnverifiedWritesNothingAndCreatesNoDirectory() throws Exception
    {
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var parent = Files.createTempDirectory("toggle-unverified");
        var directory = parent.resolve("cache");
        var fund = instrument("fund");
        var preferences = new CompositionPersistencePreferences.InMemory();

        try (var store = new VersionedCompositionStore(directory, clock))
        {
            preferences.bind(store, account -> unverified(account));
            preferences.set(A, true);

            assertEquals(CompositionStore.State.SESSION_ONLY, store.read(A, fund).state());
            put(store, A, fund);
            assertFalse(Files.exists(directory));
            assertTrue(store.read(A, fund).usableComposition().isPresent());
        }
    }
}
