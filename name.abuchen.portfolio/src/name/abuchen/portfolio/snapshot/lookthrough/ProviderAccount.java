package name.abuchen.portfolio.snapshot.lookthrough;

import java.util.Objects;

/** A provider ID and an opaque local account ID, never a credential or login. */
public record ProviderAccount(String providerId, String accountId)
{
    public ProviderAccount
    {
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(accountId);
        if (providerId.isBlank() || accountId.isBlank())
            throw new IllegalArgumentException();
    }
}
