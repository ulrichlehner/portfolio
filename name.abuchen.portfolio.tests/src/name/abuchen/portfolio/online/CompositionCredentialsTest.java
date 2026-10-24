package name.abuchen.portfolio.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.online.CompositionCredentials.Mode;
import name.abuchen.portfolio.online.CompositionCredentials.Result;
import name.abuchen.portfolio.online.CompositionCredentials.SecureStore;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider.Response;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult.Status;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/** Invented keys only. No default/local secure storage is opened by these tests. */
@SuppressWarnings("nls")
public class CompositionCredentialsTest
{
    private static final String FIRST = "synthetic-first-key";
    private static final String SECOND = "synthetic-second-key";
    private static final ProviderAccount ACCOUNT = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "one");

    private static final class Store implements SecureStore
    {
        private final Map<ProviderAccount, String> values = new HashMap<>();
        private boolean readFailure;
        private boolean writeFailure;
        private boolean deleteFailure;

        @Override
        public Optional<String> read(ProviderAccount account) throws IOException
        {
            if (readFailure)
                throw new IOException(FIRST);
            return Optional.ofNullable(values.get(account));
        }

        @Override
        public void write(ProviderAccount account, String token) throws IOException
        {
            values.put(account, token);
            if (writeFailure)
                throw new IOException(token);
        }

        @Override
        public void remove(ProviderAccount account) throws IOException
        {
            if (deleteFailure)
                throw new IOException(FIRST);
            values.remove(account);
        }
    }

    @Test
    public void testSecureReplacementAndRemovalChangeRevisionWithoutExposingSecrets()
    {
        var store = new Store();
        var credentials = new CompositionCredentials(store);
        assertEquals(0, credentials.state(ACCOUNT).revision());
        assertEquals(Result.STORED, credentials.replaceSecure(ACCOUNT, FIRST));
        assertEquals(Optional.of(FIRST), credentials.getBearerToken(ACCOUNT));
        assertEquals(1, credentials.state(ACCOUNT).revision());
        assertEquals(Mode.SECURE, credentials.state(ACCOUNT).mode());
        assertEquals(Result.STORED, credentials.replaceSecure(ACCOUNT, SECOND));
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(ACCOUNT));
        assertEquals(2, credentials.state(ACCOUNT).revision());
        assertFalse(credentials.toString().contains(SECOND));
        assertFalse(credentials.state(ACCOUNT).toString().contains(SECOND));
        assertEquals(Result.REMOVED, credentials.remove(ACCOUNT));
        assertTrue(credentials.getBearerToken(ACCOUNT).isEmpty());
        assertEquals(3, credentials.state(ACCOUNT).revision());
        assertTrue(new CompositionCredentials(store).getBearerToken(ACCOUNT).isEmpty());
    }

    @Test
    public void testSessionReplacementRemovesRetainedKeyAndDoesNotSurviveRestart()
    {
        var store = new Store();
        store.values.put(ACCOUNT, FIRST);
        var credentials = new CompositionCredentials(store);
        assertEquals(Result.SESSION_ONLY, credentials.replaceSession(ACCOUNT, SECOND));
        assertEquals(Mode.SESSION_ONLY, credentials.state(ACCOUNT).mode());
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(ACCOUNT));
        assertTrue(store.values.isEmpty());
        assertTrue(new CompositionCredentials(store).getBearerToken(ACCOUNT).isEmpty());
    }

    @Test
    public void testFailedWriteDoesNotImplicitlyUseSessionKeyOrRestoreOldKey()
    {
        var store = new Store();
        store.values.put(ACCOUNT, FIRST);
        store.writeFailure = true;
        var credentials = new CompositionCredentials(store);
        assertEquals(Result.STORAGE_UNAVAILABLE, credentials.replaceSecure(ACCOUNT, SECOND));
        assertEquals(Mode.UNAVAILABLE, credentials.state(ACCOUNT).mode());
        assertTrue(credentials.getBearerToken(ACCOUNT).isEmpty());
        assertTrue(store.values.isEmpty());
        assertEquals(Result.SESSION_ONLY, credentials.replaceSession(ACCOUNT, SECOND));
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(ACCOUNT));
    }

    @Test
    public void testLockedStorageCanBeExplicitlyBypassedForThisSessionOnly()
    {
        var store = new Store();
        store.readFailure = true;
        var credentials = new CompositionCredentials(store);
        assertTrue(credentials.getBearerToken(ACCOUNT).isEmpty());
        assertEquals(Mode.UNAVAILABLE, credentials.state(ACCOUNT).mode());
        assertEquals(Result.SESSION_ONLY, credentials.replaceSession(ACCOUNT, SECOND));
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(ACCOUNT));
    }

    @Test
    public void testFailedDeletionBlocksOldAccessAndReportsUnconfirmedDiskDeletion()
    {
        var store = new Store();
        store.values.put(ACCOUNT, FIRST);
        var credentials = new CompositionCredentials(store);
        assertEquals(Optional.of(FIRST), credentials.getBearerToken(ACCOUNT));
        store.deleteFailure = true;
        assertEquals(Result.DELETION_FAILED, credentials.remove(ACCOUNT));
        assertTrue(credentials.getBearerToken(ACCOUNT).isEmpty());
        assertEquals(1, credentials.state(ACCOUNT).revision());
        // No claim of cross-restart removal when the backend cannot flush.
        assertEquals(Optional.of(FIRST), new CompositionCredentials(store).getBearerToken(ACCOUNT));
        store.deleteFailure = false;
        assertEquals(Result.REMOVED, credentials.remove(ACCOUNT));
        assertTrue(new CompositionCredentials(store).getBearerToken(ACCOUNT).isEmpty());
    }

    @Test
    public void testFailedDeletionDuringReplacementNeverUsesTheOldKey()
    {
        var store = new Store();
        store.values.put(ACCOUNT, FIRST);
        store.deleteFailure = true;
        var credentials = new CompositionCredentials(store);
        assertEquals(Result.DELETION_FAILED, credentials.replaceSecure(ACCOUNT, SECOND));
        assertTrue(credentials.getBearerToken(ACCOUNT).isEmpty());
        assertEquals(Result.DELETION_FAILED, credentials.replaceSession(ACCOUNT, SECOND));
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(ACCOUNT));
        assertEquals(Mode.SESSION_ONLY, credentials.state(ACCOUNT).mode());
    }

    @Test
    public void testAccountAndProviderIsolation()
    {
        var otherAccount = new ProviderAccount(ACCOUNT.providerId(), "two");
        var otherProvider = new ProviderAccount("OTHER", ACCOUNT.accountId());
        var credentials = new CompositionCredentials(new Store());
        credentials.replaceSecure(ACCOUNT, FIRST);
        credentials.replaceSecure(otherAccount, SECOND);
        credentials.replaceSession(otherProvider, SECOND);
        credentials.remove(ACCOUNT);
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(otherAccount));
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(otherProvider));
        assertEquals(1, credentials.state(otherAccount).revision());
        assertEquals(Mode.SESSION_ONLY, credentials.state(otherProvider).mode());
    }

    @Test
    public void testInvalidKeysCannotInjectHeadersOrChangeConfiguration()
    {
        var credentials = new CompositionCredentials(new Store());
        credentials.replaceSecure(ACCOUNT, FIRST);
        for (var invalid : new String[] { "", "a\r\nInjected: true", " a", "a b", "https://example.invalid/?key=a" })
        {
            assertEquals(Result.INVALID_KEY, credentials.replaceSecure(ACCOUNT, invalid));
            assertEquals(Result.INVALID_KEY, credentials.replaceSession(ACCOUNT, invalid));
        }
        assertEquals(1, credentials.state(ACCOUNT).revision());
        assertEquals(Optional.of(FIRST), credentials.getBearerToken(ACCOUNT));
    }

    @Test
    public void testRequestUsesCurrentKeyOnlyInBearerHeaderAndRemovalStopsRequests()
    {
        var credentials = new CompositionCredentials(new Store());
        var headers = new ArrayList<String>();
        var uris = new ArrayList<URI>();
        var provider = new EulerpoolFundCompositionProvider(credentials::getBearerToken, (uri, header) -> {
            headers.add(header);
            uris.add(uri);
            // Even transport errors containing secrets must not escape.
            throw new IOException(header);
        }, Clock.systemUTC());
        var fund = new CompositionInstrument("Invented fund", Map.of("ISIN", "SYNTHETIC001"));
        credentials.replaceSession(ACCOUNT, FIRST);
        var first = provider.getComposition(ACCOUNT, fund);
        assertEquals(Status.FAILED, first.status());
        assertFalse(first.toString().contains(FIRST));
        credentials.replaceSession(ACCOUNT, SECOND);
        assertEquals(Status.FAILED, provider.getComposition(ACCOUNT, fund).status());
        credentials.remove(ACCOUNT);
        assertEquals(Status.AUTHENTICATION_FAILED, provider.getComposition(ACCOUNT, fund).status());
        assertEquals(java.util.List.of("Bearer " + FIRST, "Bearer " + SECOND), headers);
        for (var uri : uris)
        {
            assertEquals("https://api.eulerpool.com/api/1/etf/holdings/SYNTHETIC001", uri.toString());
            assertEquals(null, uri.getQuery());
            assertEquals(null, uri.getUserInfo());
        }
        assertFalse(new Response(500, SECOND, Optional.empty()).toString().contains(SECOND));
        assertEquals(Status.AUTHENTICATION_FAILED,
                        new EulerpoolFundCompositionProvider().getComposition(ACCOUNT, fund).status());
    }

    @Test
    public void testMissingPlatformLinkageDoesNotEscapeOrRequirePlaintextFallback()
    {
        var credentials = new CompositionCredentials(new SecureStore()
        {
            @Override
            public Optional<String> read(ProviderAccount account)
            {
                throw new NoClassDefFoundError(FIRST);
            }

            @Override
            public void write(ProviderAccount account, String token)
            {
                throw new NoClassDefFoundError(token);
            }

            @Override
            public void remove(ProviderAccount account)
            {
                throw new NoClassDefFoundError(FIRST);
            }
        });
        assertEquals(Mode.UNAVAILABLE, credentials.state(ACCOUNT).mode());
        assertEquals(Result.DELETION_FAILED, credentials.replaceSession(ACCOUNT, SECOND));
        assertEquals(Optional.of(SECOND), credentials.getBearerToken(ACCOUNT));
    }
}
