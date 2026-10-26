package name.abuchen.portfolio.ui.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.CompositionCredentials.SecureStore;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
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
}
