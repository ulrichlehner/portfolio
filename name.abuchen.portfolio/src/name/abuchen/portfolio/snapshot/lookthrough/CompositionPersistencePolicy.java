package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Policy for exactly one opaque provider/account identity. PERMITTED requires
 * verified retention: maximumAge bounds retention from original retrieval (not
 * last access); empty explicitly means verified unlimited retention. Unknown
 * retention must use UNVERIFIED, never PERMITTED. Non-permitted policies carry
 * no retention grant. This policy grants no runtime/display/distribution rights.
 */
public record CompositionPersistencePolicy(ProviderAccount account, Permission permission,
                Optional<Duration> maximumAge)
{
    public enum Permission
    {
        PERMITTED, PROHIBITED, UNVERIFIED
    }

    public enum StorageMode
    {
        PERSISTENT, SESSION_ONLY
    }

    public CompositionPersistencePolicy
    {
        Objects.requireNonNull(account);
        Objects.requireNonNull(permission);
        Objects.requireNonNull(maximumAge);
        if (permission != Permission.PERMITTED && maximumAge.isPresent())
            throw new IllegalArgumentException();
        if (maximumAge.isPresent() && (maximumAge.get().isNegative() || maximumAge.get().isZero()))
            throw new IllegalArgumentException();
    }

    /** An absent local preference defaults on only where policy permits it. */
    public StorageMode effectiveMode(Optional<Boolean> persistencePreference)
    {
        return permission == Permission.PERMITTED && persistencePreference.orElse(true) ? StorageMode.PERSISTENT
                        : StorageMode.SESSION_ONLY;
    }
}
