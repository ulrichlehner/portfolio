package name.abuchen.portfolio.ui.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.CompositionCredentials.SecureStore;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.snapshot.lookthrough.VersionedCompositionStore;
import name.abuchen.portfolio.ui.preferences.EulerpoolPreferencePage.Outcome;

public class EulerpoolPreferencePageTest
{
    @Test
    public void testUnvisitedPageDoesNotReadCredentialsOrSavePortableSettings()
    {
        var credentials = new CompositionCredentials(new SecureStore()
        {
            @Override
            public Optional<String> read(ProviderAccount account)
            {
                throw new AssertionError();
            }

            @Override
            public void write(ProviderAccount account, String token)
            {
                throw new AssertionError();
            }

            @Override
            public void remove(ProviderAccount account)
            {
                throw new AssertionError();
            }
        });
        var page = new EulerpoolPreferencePage(credentials);
        var preferences = new PreferenceStore();
        page.setPreferenceStore(preferences);
        // The containing dialog saves even pages the user never visited.
        assertTrue(page.performOk());
        assertTrue(page.performCancel());
        assertFalse(preferences.needsSaving());
    }

    private static class MemoryStore implements SecureStore
    {
        private String token;
        private boolean failWrites;
        private int writes;

        @Override
        public Optional<String> read(ProviderAccount account)
        {
            return Optional.ofNullable(token);
        }

        @Override
        public void write(ProviderAccount account, String value) throws IOException
        {
            if (failWrites)
                throw new IOException();
            writes++;
            token = value;
        }

        @Override
        public void remove(ProviderAccount account)
        {
            token = null;
        }
    }

    @Test
    public void testValidKeyIsStoredSecurely()
    {
        var store = new MemoryStore();
        var credentials = new CompositionCredentials(store);
        var page = new EulerpoolPreferencePage(credentials);

        assertEquals(Outcome.STORED, page.submit("abc.DEF-123", false)); //$NON-NLS-1$
        assertEquals("abc.DEF-123", store.token); //$NON-NLS-1$
        assertEquals(CompositionCredentials.Mode.SECURE, credentials.state(EulerpoolPreferencePage.ACCOUNT).mode());
    }

    @Test
    public void testCancelStoresNothing()
    {
        var store = new MemoryStore();
        var page = new EulerpoolPreferencePage(new CompositionCredentials(store));

        assertTrue(page.performCancel());
        assertEquals(0, store.writes);
        assertEquals(null, store.token);
    }

    @Test
    public void testEmptyFieldKeepsStoredKey()
    {
        var store = new MemoryStore();
        store.token = "stored-key"; //$NON-NLS-1$
        var page = new EulerpoolPreferencePage(new CompositionCredentials(store));

        assertEquals(Outcome.NOTHING, page.submit("", false)); //$NON-NLS-1$
        assertEquals(Outcome.NOTHING, page.submit(null, false));
        assertEquals("stored-key", store.token); //$NON-NLS-1$
        assertEquals(0, store.writes);
    }

    @Test
    public void testKeyWithLineBreakIsRejected()
    {
        var store = new MemoryStore();
        store.token = "stored-key"; //$NON-NLS-1$
        var credentials = new CompositionCredentials(store);
        var page = new EulerpoolPreferencePage(credentials);

        assertEquals(Outcome.INVALID_KEY, page.submit("abc\ndef", false)); //$NON-NLS-1$
        assertEquals(Outcome.INVALID_KEY, page.submit("abc def", false)); //$NON-NLS-1$
        assertEquals("stored-key", store.token); //$NON-NLS-1$
        assertEquals(0, store.writes);
    }

    @Test
    public void testUnavailableStoreOffersSessionOnlyWithoutWriting()
    {
        var store = new MemoryStore();
        store.failWrites = true;
        var credentials = new CompositionCredentials(store);
        var page = new EulerpoolPreferencePage(credentials);

        assertEquals(Outcome.STORAGE_UNAVAILABLE, page.submit("abc123", false)); //$NON-NLS-1$
        assertEquals(null, store.token);

        assertEquals(Outcome.SESSION_ONLY, page.submit("abc123", true)); //$NON-NLS-1$
        assertEquals(null, store.token);
        assertEquals(0, store.writes);
        assertEquals(CompositionCredentials.Mode.SESSION_ONLY,
                        credentials.state(EulerpoolPreferencePage.ACCOUNT).mode());
        assertEquals("abc123", credentials.getBearerToken(EulerpoolPreferencePage.ACCOUNT).orElseThrow()); //$NON-NLS-1$
    }

    private static final ProviderAccount ACCOUNT = EulerpoolPreferencePage.ACCOUNT;

    @Test
    public void testConfirmingLocalStoragePermitsPersistenceAndRetainsByDefault()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        var applied = new AtomicInteger();
        var page = new EulerpoolPreferencePage(new CompositionCredentials(new MemoryStore()), preferences,
                        applied::incrementAndGet);
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.empty(),
                        preferences::isLocalStorageConfirmed);

        // Off by default: unverified, session only, nothing stored by opening the page.
        assertFalse(preferences.isLocalStorageConfirmed(ACCOUNT));
        assertEquals(Permission.UNVERIFIED, provider.getPersistencePolicy(ACCOUNT).permission());
        assertEquals(Reason.POLICY_UNVERIFIED, EulerpoolPreferencePage.policyFor(false)
                        .effectiveModeDetail(Optional.of(true)).reason());

        page.applyPersistence(true, true);
        assertTrue(preferences.isLocalStorageConfirmed(ACCOUNT));
        assertEquals(Optional.of(true), preferences.get(ACCOUNT));
        assertEquals(1, applied.get());
        var policy = provider.getPersistencePolicy(ACCOUNT);
        assertEquals(Permission.PERMITTED, policy.permission());
        assertTrue(policy.maximumAge().isEmpty());
        assertEquals(StorageMode.PERSISTENT, policy.effectiveMode(preferences.get(ACCOUNT)));
        assertEquals(Reason.PERMITTED,
                        EulerpoolPreferencePage.policyFor(true).effectiveModeDetail(Optional.of(true)).reason());
    }

    @Test
    public void testCancelLeavesConfirmationUnchanged()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        var applied = new AtomicInteger();
        var page = new EulerpoolPreferencePage(new CompositionCredentials(new MemoryStore()), preferences,
                        applied::incrementAndGet);

        assertTrue(page.performCancel());
        assertTrue(page.performOk());
        assertFalse(preferences.isLocalStorageConfirmed(ACCOUNT));
        assertEquals(Optional.empty(), preferences.get(ACCOUNT));
        assertEquals(0, applied.get());
    }

    @Test
    public void testWithdrawingTheConfirmationDeletesStoredDataAndKeepsSessionOnly() throws IOException
    {
        var directory = java.nio.file.Files.createTempDirectory("lookthrough-withdraw"); //$NON-NLS-1$
        var preferences = new CompositionPersistencePreferences.InMemory();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.empty(),
                        preferences::isLocalStorageConfirmed);
        try (var store = new VersionedCompositionStore(directory.toRealPath(), Clock.systemUTC()))
        {
            // The running session reacts to every change like the application backend.
            preferences.addListener(account -> store.configure(provider.getPersistencePolicy(account),
                            preferences.get(account)));
            var page = new EulerpoolPreferencePage(new CompositionCredentials(new MemoryStore()), preferences,
                            () -> {
                            });
            page.applyPersistence(true, true);

            var fund = new CompositionInstrument("Synthetic fund", Map.of("ISIN", "SYNTHETIC001")); //$NON-NLS-1$ //$NON-NLS-2$
            var evidence = new InstrumentEvidence(fund.identifiers(),
                            new Assertion<>(new Identifier("ISIN", "SYNTHETIC001"), //$NON-NLS-1$ //$NON-NLS-2$
                                            new Provenance(ACCOUNT, "synthetic", Instant.now())), //$NON-NLS-1$
                            Optional.empty(), Optional.empty());
            store.writeEvidence(ACCOUNT, fund.identifiers(), List.of(evidence), store.generation(ACCOUNT));
            try (var files = java.nio.file.Files.list(directory))
            {
                assertEquals(1, files.count());
            }

            page.applyPersistence(false, true);
            assertFalse(preferences.isLocalStorageConfirmed(ACCOUNT));
            try (var files = java.nio.file.Files.list(directory))
            {
                assertEquals(0, files.count());
            }
            assertTrue(store.readEvidence(ACCOUNT, fund.identifiers()).isEmpty());
            assertEquals(Permission.UNVERIFIED, provider.getPersistencePolicy(ACCOUNT).permission());
        }
    }

    @Test
    public void testRegisterLookupIsOnByDefaultAndCanBeSwitchedOff()
    {
        var preferences = new CompositionPersistencePreferences.InMemory();
        var page = new EulerpoolPreferencePage(new CompositionCredentials(new MemoryStore()), preferences, () -> {
        });
        assertTrue(preferences.isRegisterLookupEnabled());

        page.applyRegisterLookup(false);
        assertFalse(preferences.isRegisterLookupEnabled());
        page.applyRegisterLookup(true);
        assertTrue(preferences.isRegisterLookupEnabled());

        // An unvisited page leaves the switch unchanged.
        preferences.setRegisterLookupEnabled(false);
        assertTrue(page.performOk());
        assertFalse(preferences.isRegisterLookupEnabled());
    }
}
