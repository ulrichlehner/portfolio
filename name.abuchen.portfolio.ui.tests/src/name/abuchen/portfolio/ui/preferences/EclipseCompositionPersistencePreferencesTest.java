package name.abuchen.portfolio.ui.preferences;

import static org.junit.Assert.assertEquals;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

public class EclipseCompositionPersistencePreferencesTest
{
    @Test
    public void roundTripAndOneNotification() throws Exception
    {
        var node = InstanceScope.INSTANCE.getNode("test-" + System.nanoTime());
        var prefs = new EclipseCompositionPersistencePreferences(node);
        var account = new ProviderAccount("provider", "account");
        var count = new AtomicInteger();
        prefs.addListener(a -> count.incrementAndGet());

        prefs.set(account, false);
        assertEquals(Optional.of(false), prefs.get(account));
        assertEquals(1, count.get());

        prefs.set(account, true);
        assertEquals(Optional.of(true), prefs.get(account));
        assertEquals(2, count.get());

        node.removeNode();
    }
}
