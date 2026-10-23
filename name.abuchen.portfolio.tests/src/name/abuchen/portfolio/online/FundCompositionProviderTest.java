package name.abuchen.portfolio.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult.Status;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

public class FundCompositionProviderTest
{
    private static final Instant RETRIEVED_AT = Instant.parse("2026-01-02T12:00:00Z");
    private static final CompositionInstrument FUND = new CompositionInstrument("Synthetic fund",
                    Map.of("test", "fund"));

    public static class OfflineProvider implements FundCompositionProvider
    {
        @Override
        public String getId()
        {
            return "offline-one";
        }

        @Override
        public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount account)
        {
            checkAccount(account);
            if ("other-permitted".equals(account.accountId()))
                return new CompositionPersistencePolicy(account, Permission.PERMITTED, Optional.of(Duration.ofDays(2)));
            return "permitted".equals(account.accountId())
                            ? new CompositionPersistencePolicy(account, Permission.PERMITTED,
                                            Optional.of(Duration.ofDays(7)))
                            : new CompositionPersistencePolicy(account, Permission.UNVERIFIED, Optional.empty());
        }

        @Override
        public CompositionResult getComposition(ProviderAccount account, CompositionInstrument fund)
        {
            checkAccount(account);
            return new CompositionResult(Status.RECEIVED, Optional.of(snapshot(account, fund)), Optional.empty());
        }

        private void checkAccount(ProviderAccount account)
        {
            if (!getId().equals(account.providerId()))
                throw new IllegalArgumentException();
        }
    }

    public static class OtherOfflineProvider implements FundCompositionProvider
    {
        @Override
        public String getId()
        {
            return "offline-two";
        }

        @Override
        public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount account)
        {
            checkAccount(account);
            return new CompositionPersistencePolicy(account, Permission.PROHIBITED, Optional.empty());
        }

        @Override
        public CompositionResult getComposition(ProviderAccount account, CompositionInstrument fund)
        {
            checkAccount(account);
            return new CompositionResult(Status.RECEIVED, Optional.of(snapshot(account, fund)), Optional.empty());
        }

        private void checkAccount(ProviderAccount account)
        {
            if (!getId().equals(account.providerId()))
                throw new IllegalArgumentException();
        }
    }

    private static FundComposition snapshot(ProviderAccount account, CompositionInstrument fund)
    {
        var provenance = new CompositionProvenance(account, fund, RETRIEVED_AT, Optional.empty(), Optional.empty());
        var instrument = new CompositionInstrument("Unidentified constituent", Map.of());
        return new FundComposition(provenance, Denominator.UNKNOWN, ExposureBasis.UNKNOWN, Completeness.UNKNOWN,
                        List.of(new Constituent(instrument, Optional.of("0.8"))));
    }

    private FundCompositionProvider provider(String id)
    {
        return Factory.getFundCompositionProviders().stream().filter(p -> id.equals(p.getId())).findFirst()
                        .orElseThrow();
    }

    @Test
    public void testDiscoveredProvidersAreSubstitutableWithoutOwnedConstituents()
    {
        var client = new Client();
        var owned = new Security();
        owned.setName(FUND.label());
        client.addSecurity(owned);
        var before = List.copyOf(client.getSecurities());
        var compositions = new ArrayList<FundComposition>();

        for (var id : List.of("offline-one", "offline-two"))
        {
            FundCompositionProvider provider = provider(id);
            var account = new ProviderAccount(id, "permitted");
            var result = provider.getComposition(account, FUND);
            assertEquals(Status.RECEIVED, result.status());
            var composition = result.composition().orElseThrow();
            compositions.add(composition);
            assertEquals(account, composition.provenance().source());
            assertEquals(FUND, composition.provenance().fund());
            assertEquals(RETRIEVED_AT, composition.provenance().retrievedAt());
            assertTrue(composition.provenance().providerRefreshedAt().isEmpty());
            assertTrue(composition.provenance().holdingsEffectiveDate().isEmpty());
            assertEquals(Denominator.UNKNOWN, composition.denominator());
            assertEquals(ExposureBasis.UNKNOWN, composition.exposureBasis());
            assertEquals(Completeness.UNKNOWN, composition.completeness());
            assertTrue(composition.constituents().getFirst().instrument().identifiers().isEmpty());
        }

        assertEquals(compositions.get(0).constituents(), compositions.get(1).constituents());
        assertNotEquals(compositions.get(0).provenance().source(), compositions.get(1).provenance().source());
        assertEquals(before, client.getSecurities());
        assertEquals(FUND.label(), owned.getName());
    }

    @Test
    public void testPoliciesAndPreferencesAreIsolatedByProviderAndAccount()
    {
        var first = new ProviderAccount("offline-one", "permitted");
        var second = new ProviderAccount("offline-two", "permitted");
        var unverified = new ProviderAccount("offline-one", "unverified");
        var otherPermitted = new ProviderAccount("offline-one", "other-permitted");
        var policies = Map.of(first, provider(first.providerId()).getPersistencePolicy(first), second,
                        provider(second.providerId()).getPersistencePolicy(second), unverified,
                        provider(unverified.providerId()).getPersistencePolicy(unverified), otherPermitted,
                        provider(otherPermitted.providerId()).getPersistencePolicy(otherPermitted));
        var preferences = new HashMap<ProviderAccount, Boolean>();

        assertEquals(Permission.PERMITTED, policies.get(first).permission());
        assertEquals(Optional.of(Duration.ofDays(7)), policies.get(first).maximumAge());
        assertEquals(Permission.PROHIBITED, policies.get(second).permission());
        assertEquals(Permission.UNVERIFIED, policies.get(unverified).permission());
        assertEquals(StorageMode.PERSISTENT, policies.get(first).effectiveMode(Optional.empty()));
        for (var account : List.of(second, unverified))
        {
            assertEquals(account, policies.get(account).account());
            assertEquals(StorageMode.SESSION_ONLY, policies.get(account).effectiveMode(Optional.empty()));
            assertEquals(StorageMode.SESSION_ONLY, policies.get(account).effectiveMode(Optional.of(true)));
        }

        preferences.put(first, false);
        assertEquals(StorageMode.SESSION_ONLY,
                        policies.get(first).effectiveMode(Optional.ofNullable(preferences.get(first))));
        assertFalse(preferences.containsKey(second));
        assertFalse(preferences.containsKey(unverified));
        assertEquals(Optional.of(Duration.ofDays(2)), policies.get(otherPermitted).maximumAge());
        assertEquals(StorageMode.PERSISTENT, policies.get(otherPermitted)
                        .effectiveMode(Optional.ofNullable(preferences.get(otherPermitted))));
        preferences.put(first, true);
        assertEquals(StorageMode.PERSISTENT,
                        policies.get(first).effectiveMode(Optional.ofNullable(preferences.get(first))));
        assertEquals(StorageMode.SESSION_ONLY, policies.get(second).effectiveMode(Optional.empty()));
        assertEquals(StorageMode.SESSION_ONLY, policies.get(unverified).effectiveMode(Optional.empty()));

        assertThrows(IllegalArgumentException.class, () -> provider(first.providerId()).getPersistencePolicy(second));
        assertThrows(IllegalArgumentException.class, () -> provider(second.providerId()).getComposition(first, FUND));
    }

    @Test
    public void testRawRowsRemainImmutableAndAvailableForValidation()
    {
        var ids = new HashMap<String, String>();
        ids.put("test", "constituent");
        var instrument = new CompositionInstrument("Synthetic constituent", ids);
        var rows = new ArrayList<Constituent>();
        for (var weight : List.of("-0.2", "NaN", "Infinity", "malformed", "0.600000000000000001"))
            rows.add(new Constituent(instrument, Optional.of(weight)));
        rows.add(new Constituent(instrument, Optional.empty()));
        rows.add(rows.getFirst());
        var original = List.copyOf(rows);
        var composition = new FundComposition(snapshot(new ProviderAccount("offline-one", "permitted"), FUND)
                        .provenance(), Denominator.TOTAL_FUND_NAV, ExposureBasis.PHYSICAL_HOLDINGS,
                        Completeness.PARTIAL, rows);
        ids.clear();
        rows.clear();

        assertEquals(original, composition.constituents());
        assertEquals(Map.of("test", "constituent"), instrument.identifiers());
        assertThrows(UnsupportedOperationException.class, () -> composition.constituents().clear());
        assertThrows(UnsupportedOperationException.class, () -> instrument.identifiers().clear());
    }

    @Test
    public void testFailuresCannotReplaceSnapshotsOrInventProvenance()
    {
        for (var status : Status.values())
        {
            if (status == Status.RECEIVED)
                continue;
            var result = new CompositionResult(status, Optional.empty(), Optional.empty());
            assertTrue(result.composition().isEmpty());
        }
        var retry = RETRIEVED_AT.plusSeconds(60);
        assertEquals(Optional.of(retry), new CompositionResult(Status.RATE_LIMITED, Optional.empty(), Optional.of(retry))
                        .retryAfter());
        var composition = snapshot(new ProviderAccount("offline-one", "permitted"), FUND);
        assertThrows(IllegalArgumentException.class,
                        () -> new CompositionResult(Status.FAILED, Optional.of(composition), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                        () -> new CompositionResult(Status.RECEIVED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                        () -> new CompositionResult(Status.RECEIVED, Optional.of(composition), Optional.of(retry)));
    }

    @Test
    public void testRetentionRequiresAnExplicitGrant()
    {
        var account = new ProviderAccount("offline-one", "permitted");
        for (var permission : List.of(Permission.PROHIBITED, Permission.UNVERIFIED))
            assertThrows(IllegalArgumentException.class, () -> new CompositionPersistencePolicy(account, permission,
                            Optional.of(Duration.ofDays(1))));
        for (var duration : List.of(Duration.ZERO, Duration.ofSeconds(-1)))
            assertThrows(IllegalArgumentException.class, () -> new CompositionPersistencePolicy(account,
                            Permission.PERMITTED, Optional.of(duration)));
        var unlimited = new CompositionPersistencePolicy(account, Permission.PERMITTED, Optional.empty());
        assertEquals(StorageMode.PERSISTENT, unlimited.effectiveMode(Optional.empty()));
        assertTrue(unlimited.maximumAge().isEmpty());
    }
}
