package name.abuchen.portfolio.oauth.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonSyntaxException;

@SuppressWarnings("nls")
public class OAuthConfigTest
{
    @After
    public void clearProperty()
    {
        System.clearProperty(OAuthConfig.CONFIG_FILE_PROPERTY);
    }

    private Path write(String json) throws Exception
    {
        var file = Files.createTempFile("oauth-config", ".json");
        file.toFile().deleteOnExit();
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void testLoadsRegistrationFromConfiguredFile() throws Exception
    {
        var file = write("{\"clientId\":\"synthetic-client\",\"apiResource\":\"https://api.example.org\"}");
        System.setProperty(OAuthConfig.CONFIG_FILE_PROPERTY, file.toString());

        var config = OAuthConfig.load();

        assertThat(config.clientId, is("synthetic-client"));
        assertThat(config.apiResource, is("https://api.example.org"));
    }

    @Test
    public void testWithoutRegistrationNothingIsConfigured() throws Exception
    {
        // A local build has no embedded registration and no configured file.
        System.clearProperty(OAuthConfig.CONFIG_FILE_PROPERTY);
        assertThat(OAuthConfig.load(), is(nullValue()));

        // A missing file must not break startup, it only leaves the feed
        // unconfigured.
        System.setProperty(OAuthConfig.CONFIG_FILE_PROPERTY, "/nonexistent/oauth-config.json");
        assertThat(OAuthConfig.load(), is(nullValue()));
    }

    @Test
    public void testOversizedAndMalformedFilesAreHandled() throws Exception
    {
        var oversized = write("{\"clientId\":\"" + "x".repeat(70 * 1024) + "\"}");
        System.setProperty(OAuthConfig.CONFIG_FILE_PROPERTY, oversized.toString());
        assertThat(OAuthConfig.load(), is(nullValue()));

        var malformed = write("{\"clientId\":");
        System.setProperty(OAuthConfig.CONFIG_FILE_PROPERTY, malformed.toString());
        try
        {
            OAuthConfig.load();
            throw new AssertionError("Malformed registration must not be accepted");
        }
        catch (JsonSyntaxException e)
        {
            // expected: the caller reports that the feed is not configured
        }
    }
}
