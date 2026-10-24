package name.abuchen.portfolio.ui.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.online.CompositionCredentials.SecureStore;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

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
}
