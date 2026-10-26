package name.abuchen.portfolio.ui.views.lookthrough;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import name.abuchen.portfolio.online.CompositionCredentials;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/**
 * Development convenience for the managed test launcher only: install a
 * session-only provider key from a local file when no key is configured yet.
 * <p>
 * The key never appears in arguments, logs or preferences; only the file path is
 * passed as a system property. An existing secure or session key always wins. Any
 * read, format or validation problem is ignored, so a missing file simply keeps
 * the unconfigured state. This must not be used outside local development.
 */
public final class DevelopmentCredentials
{
    static final String KEY_FILE_PROPERTY = "portfolio.lens.developmentKeyFile"; //$NON-NLS-1$

    /** Refuse larger files: a key is a short single line, not a blob. */
    private static final long MAX_BYTES = 4096;

    private DevelopmentCredentials()
    {
    }

    public static CompositionCredentials install(CompositionCredentials credentials, ProviderAccount account)
    {
        if (credentials.state(account).mode() != CompositionCredentials.Mode.UNCONFIGURED)
            return credentials;
        var configured = System.getProperty(KEY_FILE_PROPERTY);
        if (configured == null || configured.isBlank())
            return credentials;
        try
        {
            var path = Path.of(configured);
            // Never follow a link: the launcher points at a regular private file.
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES)
                return credentials;
            var token = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
            if (!token.isEmpty())
                credentials.replaceSession(account, token);
        }
        catch (IOException | RuntimeException e)
        {
            // A development convenience must never break startup or echo the key.
            return credentials;
        }
        return credentials;
    }
}
