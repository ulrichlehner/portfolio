package name.abuchen.portfolio.ui.preferences;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePreferences;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.ui.PortfolioPlugin;

/**
 * Instance-scoped, non-secret persistence preference for the desktop
 * application. Keys are hex-encoded to avoid ambiguity between provider and
 * account identifiers. Nothing here belongs to the portfolio or to credential
 * storage.
 */
public final class EclipseCompositionPersistencePreferences implements CompositionPersistencePreferences
{
    private static final String NODE = "name.abuchen.portfolio.ui";
    private static final String PREFIX = "lookthrough.persistence.";
    private static final String CONFIRMED_PREFIX = "lookthrough.localstorage.";

    private final IEclipsePreferences node;
    private final Map<String, ProviderAccount> keys = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<ProviderAccount>> listeners = new CopyOnWriteArrayList<>();

    public EclipseCompositionPersistencePreferences()
    {
        this(InstanceScope.INSTANCE.getNode(NODE));
    }

    public EclipseCompositionPersistencePreferences(IEclipsePreferences node)
    {
        this.node = Objects.requireNonNull(node);
        this.node.addPreferenceChangeListener(event -> handleChange(event));
    }

    private static String key(ProviderAccount account)
    {
        return key(PREFIX, account);
    }

    private static String key(String prefix, ProviderAccount account)
    {
        var hex = HexFormat.of();
        return prefix + hex.formatHex(account.providerId().getBytes(StandardCharsets.UTF_8)) + "."
                        + hex.formatHex(account.accountId().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized Optional<Boolean> get(ProviderAccount account)
    {
        Objects.requireNonNull(account);
        var k = key(account);
        keys.put(k, account);
        var value = node.get(k, null);
        if ("true".equals(value)) //$NON-NLS-1$
            return Optional.of(true);
        if ("false".equals(value)) //$NON-NLS-1$
            return Optional.of(false);
        return Optional.empty();
    }

    @Override
    public void set(ProviderAccount account, boolean enabled)
    {
        Objects.requireNonNull(account);
        var k = key(account);
        synchronized (this)
        {
            keys.put(k, account);
        }
        node.put(k, Boolean.toString(enabled));
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            // The toggle applied for this process; only its durability failed.
            PortfolioPlugin.log(e);
        }
    }

    @Override
    public synchronized boolean isLocalStorageConfirmed(ProviderAccount account)
    {
        Objects.requireNonNull(account);
        var k = key(CONFIRMED_PREFIX, account);
        keys.put(k, account);
        return "true".equals(node.get(k, null)); //$NON-NLS-1$
    }

    @Override
    public void setLocalStorageConfirmed(ProviderAccount account, boolean confirmed)
    {
        Objects.requireNonNull(account);
        var k = key(CONFIRMED_PREFIX, account);
        synchronized (this)
        {
            keys.put(k, account);
        }
        node.put(k, Boolean.toString(confirmed));
        try
        {
            node.flush();
        }
        catch (BackingStoreException e)
        {
            PortfolioPlugin.log(e);
        }
    }

    @Override
    public void addListener(Consumer<ProviderAccount> listener)
    {
        Objects.requireNonNull(listener);
        listeners.add(listener);
    }

    @Override
    public void removeListener(Consumer<ProviderAccount> listener)
    {
        Objects.requireNonNull(listener);
        listeners.remove(listener);
    }

    private void handleChange(PreferenceChangeEvent event)
    {
        ProviderAccount account;
        synchronized (this)
        {
            account = keys.get(event.getKey());
        }
        if (account == null)
            return;
        for (var listener : listeners)
            listener.accept(account);
    }
}
