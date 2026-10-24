package name.abuchen.portfolio.online;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import name.abuchen.portfolio.online.impl.EquinoxCompositionCredentialStore;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/**
 * Application-local credentials, independent of portfolio/preference serialization.
 * Failed secure writes never implicitly retain the new key in memory: session
 * entry is a separate, explicit operation. No backend exception escapes this boundary.
 */
public final class CompositionCredentials
{
    public interface SecureStore
    {
        Optional<String> read(ProviderAccount account) throws IOException;

        void write(ProviderAccount account, String token) throws IOException;

        void remove(ProviderAccount account) throws IOException;
    }

    public enum Mode
    {
        UNCONFIGURED, SECURE, SESSION_ONLY, UNAVAILABLE
    }

    public enum Result
    {
        STORED, SESSION_ONLY, REMOVED, INVALID_KEY, STORAGE_UNAVAILABLE, DELETION_FAILED
    }

    /** Safe status for UI/jobs; revision is not derived from the secret. */
    public record State(Mode mode, long revision)
    {
    }

    private static final class Local
    {
        private static final CompositionCredentials INSTANCE = new CompositionCredentials(
                        new EquinoxCompositionCredentialStore());
    }

    private static final class Entry
    {
        private Mode mode = Mode.UNCONFIGURED;
        private long revision;
        private String sessionToken;
        private boolean overridden;
    }

    private final SecureStore store;
    private final Map<ProviderAccount, Entry> entries = new HashMap<>();

    public CompositionCredentials(SecureStore store)
    {
        this.store = Objects.requireNonNull(store);
    }

    /** One application-lifetime instance; construction does not open secure storage. */
    public static CompositionCredentials local()
    {
        return Local.INSTANCE;
    }

    public synchronized Optional<String> getBearerToken(ProviderAccount account)
    {
        var entry = entry(account);
        if (entry.overridden)
            return Optional.ofNullable(entry.sessionToken);
        try
        {
            var token = store.read(account);
            if (token.isPresent() && !valid(token.get()))
                throw new IOException();
            entry.mode = token.isPresent() ? Mode.SECURE : Mode.UNCONFIGURED;
            return token;
        }
        catch (IOException | RuntimeException | LinkageError e)
        {
            entry.mode = Mode.UNAVAILABLE;
            return Optional.empty();
        }
    }

    public synchronized State state(ProviderAccount account)
    {
        getBearerToken(account);
        var entry = entry(account);
        return new State(entry.mode, entry.revision);
    }

    public synchronized Result replaceSecure(ProviderAccount account, String token)
    {
        if (!valid(token))
            return Result.INVALID_KEY;
        var entry = invalidate(account);
        // Delete before replacement, so failed encryption cannot reactivate the old key.
        if (!delete(account))
            return Result.DELETION_FAILED;
        try
        {
            store.write(account, token);
            entry.overridden = false;
            entry.mode = Mode.SECURE;
            return Result.STORED;
        }
        catch (IOException | RuntimeException | LinkageError e)
        {
            entry.mode = Mode.UNAVAILABLE;
            // A flush failure might have left an encrypted pending write.
            return delete(account) ? Result.STORAGE_UNAVAILABLE : Result.DELETION_FAILED;
        }
    }

    public synchronized Result replaceSession(ProviderAccount account, String token)
    {
        if (!valid(token))
            return Result.INVALID_KEY;
        var entry = invalidate(account);
        entry.sessionToken = token;
        entry.mode = Mode.SESSION_ONLY;
        return delete(account) ? Result.SESSION_ONLY : Result.DELETION_FAILED;
    }

    public synchronized Result remove(ProviderAccount account)
    {
        invalidate(account);
        return delete(account) ? Result.REMOVED : Result.DELETION_FAILED;
    }

    private Entry entry(ProviderAccount account)
    {
        return entries.computeIfAbsent(Objects.requireNonNull(account), ignored -> new Entry());
    }

    private Entry invalidate(ProviderAccount account)
    {
        var entry = entry(account);
        entry.revision++;
        entry.overridden = true;
        entry.sessionToken = null;
        entry.mode = Mode.UNCONFIGURED;
        return entry;
    }

    private boolean delete(ProviderAccount account)
    {
        try
        {
            store.remove(account);
            return true;
        }
        catch (IOException | RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static boolean valid(String token)
    {
        return token != null && token.length() <= 8192 && token.matches("[A-Za-z0-9._~+/-]+=*"); //$NON-NLS-1$
    }
}
