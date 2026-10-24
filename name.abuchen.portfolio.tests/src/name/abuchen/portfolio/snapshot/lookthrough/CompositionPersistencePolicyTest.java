package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.Test;

public class CompositionPersistencePolicyTest
{
    private static final ProviderAccount ACCOUNT = new ProviderAccount("provider", "account");

    @Test
    public void permittedAbsentDefaultsPersistent()
    {
        var policy = new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.PERMITTED, Optional.empty());
        assertEquals(CompositionPersistencePolicy.StorageMode.PERSISTENT, policy.effectiveMode(Optional.empty()));
        assertEquals(CompositionPersistencePolicy.Reason.PERMITTED, policy.effectiveModeDetail(Optional.empty()).reason());
        assertTrue(policy.effectiveMode(Optional.empty()) == policy.effectiveModeDetail(Optional.empty()).mode());
    }

    @Test
    public void permittedOptOutSessionOnly()
    {
        var policy = new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.PERMITTED, Optional.empty());
        assertEquals(CompositionPersistencePolicy.StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.of(false)));
        assertEquals(CompositionPersistencePolicy.Reason.USER_OPT_OUT, policy.effectiveModeDetail(Optional.of(false)).reason());
    }

    @Test
    public void prohibitedPreferenceOnSessionOnly()
    {
        var policy = new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.PROHIBITED, Optional.empty());
        assertEquals(CompositionPersistencePolicy.StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.of(true)));
        assertEquals(CompositionPersistencePolicy.Reason.POLICY_PROHIBITED, policy.effectiveModeDetail(Optional.of(true)).reason());
    }

    @Test
    public void unverifiedPreferenceOnSessionOnly()
    {
        var policy = new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.UNVERIFIED, Optional.empty());
        assertEquals(CompositionPersistencePolicy.StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.of(true)));
        assertEquals(CompositionPersistencePolicy.Reason.POLICY_UNVERIFIED, policy.effectiveModeDetail(Optional.of(true)).reason());
    }

    @Test
    public void unverifiedPreferenceExplicitTrueFalseSessionOnly()
    {
        var policy = new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.UNVERIFIED, Optional.empty());
        assertEquals(CompositionPersistencePolicy.StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.of(true)));
        assertEquals(CompositionPersistencePolicy.StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.of(false)));
    }

    @Test
    public void effectiveModeConsistentWithDetail()
    {
        var policy = new CompositionPersistencePolicy(ACCOUNT, CompositionPersistencePolicy.Permission.PERMITTED,
                        Optional.of(Duration.ofDays(1)));
        for (var pref : new Optional[] { Optional.empty(), Optional.of(true), Optional.of(false) })
        {
            assertEquals(policy.effectiveModeDetail(pref).mode(), policy.effectiveMode(pref));
        }
    }
}
