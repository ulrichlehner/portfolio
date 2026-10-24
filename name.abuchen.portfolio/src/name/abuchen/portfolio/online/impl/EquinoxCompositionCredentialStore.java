package name.abuchen.portfolio.online.impl;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IProviderHints;

import name.abuchen.portfolio.online.CompositionCredentials.SecureStore;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/** Only encrypted values in a separate application-owned Equinox storage file. */
@SuppressWarnings("nls")
public final class EquinoxCompositionCredentialStore implements SecureStore
{
    private static final String KEY = "bearer";
    private ISecurePreferences root;

    public EquinoxCompositionCredentialStore()
    {
    }

    /** Injection seam for offline secure-preferences contract tests. */
    public EquinoxCompositionCredentialStore(ISecurePreferences root)
    {
        this.root = Objects.requireNonNull(root);
    }

    private ISecurePreferences root() throws IOException
    {
        if (root == null)
        {
            // Do not use the default user-wide store or the unrelated OAuth store.
            var url = FileLocator.resolve(URI.create(
                            "platform:/meta/name.abuchen.portfolio/lookthrough_credentials").toURL());
            root = SecurePreferencesFactory.open(url, Map.of(IProviderHints.PROMPT_USER, Boolean.FALSE));
            if (root == null)
                throw new IOException();
        }
        return root;
    }

    private static String path(ProviderAccount account)
    {
        // Reversible encoding is collision-free and contains no path separators.
        var hex = HexFormat.of();
        return "providers/" + hex.formatHex(account.providerId().getBytes(StandardCharsets.UTF_8)) + "/"
                        + hex.formatHex(account.accountId().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized Optional<String> read(ProviderAccount account) throws IOException
    {
        try
        {
            if (!root().nodeExists(path(account)))
                return Optional.empty();
            var node = root().node(path(account));
            if (!java.util.Arrays.asList(node.keys()).contains(KEY))
                return Optional.empty();
            // Refuse unexpected plaintext rather than accepting a downgraded store.
            if (!node.isEncrypted(KEY))
                throw new IOException();
            return Optional.ofNullable(node.get(KEY, null));
        }
        catch (StorageException | IOException | RuntimeException e)
        {
            throw new IOException();
        }
    }

    @Override
    public synchronized void write(ProviderAccount account, String token) throws IOException
    {
        try
        {
            var node = root().node(path(account));
            node.put(KEY, token, true);
            if (!node.isEncrypted(KEY) || !token.equals(node.get(KEY, null)))
                throw new IOException();
            node.flush();
        }
        catch (StorageException | IOException | RuntimeException e)
        {
            throw new IOException();
        }
    }

    @Override
    public synchronized void remove(ProviderAccount account) throws IOException
    {
        try
        {
            if (root().nodeExists(path(account)))
            {
                root().node(path(account)).remove(KEY);
                root().flush();
            }
        }
        catch (IOException | RuntimeException e)
        {
            throw new IOException();
        }
    }
}
