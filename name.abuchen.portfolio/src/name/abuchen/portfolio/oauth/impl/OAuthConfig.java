package name.abuchen.portfolio.oauth.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Scanner;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import name.abuchen.portfolio.PortfolioLog;

public class OAuthConfig
{
    /**
     * Optional file with the client registration. The registration is
     * deliberately not part of the repository: only the official release embeds
     * it, and the build verifies its checksum when signing. A locally built or
     * self-hosted application can therefore point at a copy obtained from an
     * official release instead of carrying the registration itself.
     */
    public static final String CONFIG_FILE_PROPERTY = "portfolio.oauth.configFile"; //$NON-NLS-1$

    private static final String RESOURCE = "config.json"; //$NON-NLS-1$

    /** A registration is a short JSON document, not an arbitrary file. */
    private static final long MAX_BYTES = 64 * 1024;

    public String clientId;
    public String baseUrl;
    public String authEndpoint;
    public String tokenEndpoint;
    public String revocationEndpoint;
    public String authScope;
    public String apiResource;

    public static OAuthConfig load() throws JsonSyntaxException
    {
        try (InputStream config = open())
        {
            if (config == null)
                return null;

            try (Scanner scanner = new Scanner(config, StandardCharsets.UTF_8.name()))
            {
                String json = scanner.useDelimiter("\\A").next(); //$NON-NLS-1$
                Gson gson = new Gson();
                return gson.fromJson(json, OAuthConfig.class);
            }
        }
        catch (IOException e)
        {
            PortfolioLog.error(e);
            return null;
        }
    }

    private static InputStream open() throws IOException
    {
        var configured = System.getProperty(CONFIG_FILE_PROPERTY);
        if (configured == null || configured.isBlank())
            return OAuthConfig.class.getResourceAsStream(RESOURCE);

        try
        {
            var path = Path.of(configured);
            if (Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES)
                return Files.newInputStream(path);

            PortfolioLog.warning("Ignoring OAuth configuration, not a readable file: " + path); //$NON-NLS-1$
        }
        catch (InvalidPathException e)
        {
            PortfolioLog.warning("Ignoring OAuth configuration, invalid path: " + configured); //$NON-NLS-1$
        }

        // Fall back to the embedded registration of an official build.
        return OAuthConfig.class.getResourceAsStream(RESOURCE);
    }
}
