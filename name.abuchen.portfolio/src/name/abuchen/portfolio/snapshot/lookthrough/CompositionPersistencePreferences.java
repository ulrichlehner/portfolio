package name.abuchen.portfolio.snapshot.lookthrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Application-local, non-secret persistence preference, keyed by provider/account.
 * This is neither portfolio serialization nor credential storage. An absent value
 * means the default (on where policy permits it). Implementations must notify
 * listeners synchronously when a value changes.
 */
public interface CompositionPersistencePreferences
{
    Optional<Boolean> get(ProviderAccount account);

    void set(ProviderAccount account, boolean enabled);

    /**
     * The user's explicit confirmation that the provider plan of this account
     * allows storing its data locally. Absent means not confirmed. This is the
     * user's own statement, not a verified provider permission.
     */
    boolean isLocalStorageConfirmed(ProviderAccount account);

    void setLocalStorageConfirmed(ProviderAccount account, boolean confirmed);

    /**
     * Whether company headquarters are looked up in the public legal entity
     * register. Not account-specific; on by default.
     */
    boolean isRegisterLookupEnabled();

    void setRegisterLookupEnabled(boolean enabled);

    void addListener(Consumer<ProviderAccount> listener);

    void removeListener(Consumer<ProviderAccount> listener);

    /** Convenience binder for tests and integration code. */
    default AutoCloseable bind(CompositionStore store, Function<ProviderAccount, CompositionPersistencePolicy> policies)
    {
        Objects.requireNonNull(store);
        Objects.requireNonNull(policies);
        Consumer<ProviderAccount> listener = account -> store.configure(policies.apply(account), get(account));
        addListener(listener);
        return () -> removeListener(listener);
    }

    /** In-memory implementation for tests and synthetic providers. */
    final class InMemory implements CompositionPersistencePreferences
    {
        private final Map<ProviderAccount, Boolean> values = new HashMap<>();
        private final Map<ProviderAccount, Boolean> confirmations = new HashMap<>();
        private boolean registerLookup = true;
        private final List<Consumer<ProviderAccount>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public synchronized Optional<Boolean> get(ProviderAccount account)
        {
            Objects.requireNonNull(account);
            return Optional.ofNullable(values.get(account));
        }

        @Override
        public void set(ProviderAccount account, boolean enabled)
        {
            Objects.requireNonNull(account);
            synchronized (this)
            {
                values.put(account, enabled);
            }
            var snapshot = new ArrayList<>(listeners);
            for (var listener : snapshot)
                listener.accept(account);
        }

        @Override
        public synchronized boolean isLocalStorageConfirmed(ProviderAccount account)
        {
            Objects.requireNonNull(account);
            return confirmations.getOrDefault(account, false);
        }

        @Override
        public void setLocalStorageConfirmed(ProviderAccount account, boolean confirmed)
        {
            Objects.requireNonNull(account);
            synchronized (this)
            {
                confirmations.put(account, confirmed);
            }
            var snapshot = new ArrayList<>(listeners);
            for (var listener : snapshot)
                listener.accept(account);
        }

        @Override
        public synchronized boolean isRegisterLookupEnabled()
        {
            return registerLookup;
        }

        @Override
        public synchronized void setRegisterLookupEnabled(boolean enabled)
        {
            registerLookup = enabled;
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
    }
}
