package name.abuchen.portfolio.online.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.StorageException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/** API-contract tests use mocks, never an installed user's secure preferences. */
@SuppressWarnings("nls")
public class EquinoxCompositionCredentialStoreTest
{
    private static final ProviderAccount ACCOUNT = new ProviderAccount("SYNTHETIC", "one");
    private static final String KEY = "synthetic-encrypted-key";

    @Test
    public void testExplicitEncryptionRoundTripAndFlush() throws Exception
    {
        var root = mock(ISecurePreferences.class);
        var node = mock(ISecurePreferences.class);
        when(root.node(anyString())).thenReturn(node);
        when(root.nodeExists(anyString())).thenReturn(true);
        when(node.keys()).thenReturn(new String[] { "bearer" });
        when(node.isEncrypted("bearer")).thenReturn(true);
        when(node.get("bearer", null)).thenReturn(KEY);
        var store = new EquinoxCompositionCredentialStore(root);
        store.write(ACCOUNT, KEY);
        verify(node).put("bearer", KEY, true);
        verify(node).flush();
        assertEquals(Optional.of(KEY), store.read(ACCOUNT));
        store.remove(ACCOUNT);
        verify(node).remove("bearer");
        verify(root).flush();
    }

    @Test
    public void testPlaintextIsRefusedWithoutRetrievingIt() throws Exception
    {
        var root = mock(ISecurePreferences.class);
        var node = mock(ISecurePreferences.class);
        when(root.nodeExists(anyString())).thenReturn(true);
        when(root.node(anyString())).thenReturn(node);
        when(node.keys()).thenReturn(new String[] { "bearer" });
        when(node.isEncrypted("bearer")).thenReturn(false);
        assertThrows(IOException.class, () -> new EquinoxCompositionCredentialStore(root).read(ACCOUNT));
        verify(node, never()).get("bearer", null);
    }

    @Test
    public void testLockedEncryptionIsSanitized() throws Exception
    {
        var root = mock(ISecurePreferences.class);
        var node = mock(ISecurePreferences.class);
        when(root.node(anyString())).thenReturn(node);
        org.mockito.Mockito.doThrow(new StorageException(StorageException.ENCRYPTION_ERROR, KEY))
                        .when(node).put("bearer", KEY, true);
        var failure = assertThrows(IOException.class,
                        () -> new EquinoxCompositionCredentialStore(root).write(ACCOUNT, KEY));
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains(KEY));
        verify(node, never()).flush();
    }

    @Test
    public void testProviderAccountPathsAreDistinctEvenWithSeparators() throws Exception
    {
        var root = mock(ISecurePreferences.class);
        var store = new EquinoxCompositionCredentialStore(root);
        store.read(new ProviderAccount("a/b", "c"));
        store.read(new ProviderAccount("a", "b/c"));
        store.read(new ProviderAccount("a", "c"));
        var paths = ArgumentCaptor.forClass(String.class);
        verify(root, org.mockito.Mockito.times(3)).nodeExists(paths.capture());
        assertEquals(3, paths.getAllValues().stream().distinct().count());
        for (var path : paths.getAllValues())
            assertEquals(3, path.split("/").length);
    }

    @Test
    public void testFlushFailureIsReported() throws Exception
    {
        var root = mock(ISecurePreferences.class);
        when(root.nodeExists(anyString())).thenReturn(true);
        when(root.node(anyString())).thenReturn(mock(ISecurePreferences.class));
        org.mockito.Mockito.doThrow(new IOException(KEY)).when(root).flush();
        var failure = assertThrows(IOException.class,
                        () -> new EquinoxCompositionCredentialStore(root).remove(ACCOUNT));
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains(KEY));
    }
}
