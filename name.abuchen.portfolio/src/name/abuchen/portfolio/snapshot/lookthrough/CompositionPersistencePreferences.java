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
