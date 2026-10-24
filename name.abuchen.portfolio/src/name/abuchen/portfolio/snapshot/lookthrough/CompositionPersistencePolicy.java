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

    public enum Reason
    {
        PERMITTED, USER_OPT_OUT, POLICY_PROHIBITED, POLICY_UNVERIFIED
    }

    public record EffectiveMode(StorageMode mode, Reason reason)
    {
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
        return effectiveModeDetail(persistencePreference).mode();
    }

    /** Reports both the effective mode and the reason for UI/integration code. */
    public EffectiveMode effectiveModeDetail(Optional<Boolean> persistencePreference)
    {
        if (permission != Permission.PERMITTED)
            return new EffectiveMode(StorageMode.SESSION_ONLY,
                            permission == Permission.PROHIBITED ? Reason.POLICY_PROHIBITED : Reason.POLICY_UNVERIFIED);
        if (!persistencePreference.orElse(true))
            return new EffectiveMode(StorageMode.SESSION_ONLY, Reason.USER_OPT_OUT);
        return new EffectiveMode(StorageMode.PERSISTENT, Reason.PERMITTED);
    }
}
