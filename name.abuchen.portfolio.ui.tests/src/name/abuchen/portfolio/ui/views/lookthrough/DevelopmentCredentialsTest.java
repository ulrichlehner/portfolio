package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.After;
import org.junit.Test;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

@SuppressWarnings("nls")
public class DevelopmentCredentialsTest
{
    private static final ProviderAccount ACCOUNT = new ProviderAccount("synthetic", "test");

    private final CompositionCredentials credentials = new CompositionCredentials(new CompositionCredentials.SecureStore()
    {
        @Override
        public Optional<String> read(ProviderAccount account)
        {
            return Optional.empty();
        }

        @Override
        public void write(ProviderAccount account, String token)
        {
            throw new AssertionError("Development bootstrap must never write secure storage");
        }

        @Override
        public void remove(ProviderAccount account)
        {
        }
    });

    @After
    public void clearProperty()
    {
        System.clearProperty(DevelopmentCredentials.KEY_FILE_PROPERTY);
    }

    private Path keyFile(String content) throws Exception
    {
        var file = Files.createTempFile("lens-dev-key", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void testInstallsSessionKeyFromPrivateFile() throws Exception
    {
        var file = keyFile("  synthetic-dev-token\n");
        System.setProperty(DevelopmentCredentials.KEY_FILE_PROPERTY, file.toString());
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(CompositionCredentials.Mode.SESSION_ONLY, credentials.state(ACCOUNT).mode());
        assertEquals(Optional.of("synthetic-dev-token"), credentials.getBearerToken(ACCOUNT));
    }

    @Test
    public void testNoPropertyKeepsUnconfiguredState()
    {
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(CompositionCredentials.Mode.UNCONFIGURED, credentials.state(ACCOUNT).mode());
    }

    @Test
    public void testExistingKeyIsNeverReplaced() throws Exception
    {
        credentials.replaceSession(ACCOUNT, "already-active");
        System.setProperty(DevelopmentCredentials.KEY_FILE_PROPERTY, keyFile("other-token").toString());
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(Optional.of("already-active"), credentials.getBearerToken(ACCOUNT));
    }

    @Test
    public void testMissingFileAndInvalidContentStayUnconfigured() throws Exception
    {
        System.setProperty(DevelopmentCredentials.KEY_FILE_PROPERTY, "/nonexistent/lens-dev-key");
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(CompositionCredentials.Mode.UNCONFIGURED, credentials.state(ACCOUNT).mode());

        System.setProperty(DevelopmentCredentials.KEY_FILE_PROPERTY, keyFile("bad token with spaces").toString());
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(CompositionCredentials.Mode.UNCONFIGURED, credentials.state(ACCOUNT).mode());
    }

    @Test
    public void testOversizedAndLinkedFilesAreIgnored() throws Exception
    {
        var large = keyFile("x".repeat(5000));
        System.setProperty(DevelopmentCredentials.KEY_FILE_PROPERTY, large.toString());
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(CompositionCredentials.Mode.UNCONFIGURED, credentials.state(ACCOUNT).mode());

        var target = keyFile("linked-token");
        var link = Files.createTempFile("lens-dev-link", ".txt");
        Files.delete(link);
        Files.createSymbolicLink(link, target);
        System.setProperty(DevelopmentCredentials.KEY_FILE_PROPERTY, link.toString());
        DevelopmentCredentials.install(credentials, ACCOUNT);
        assertEquals(CompositionCredentials.Mode.UNCONFIGURED, credentials.state(ACCOUNT).mode());
        assertTrue(Files.isSymbolicLink(link));
    }
}
