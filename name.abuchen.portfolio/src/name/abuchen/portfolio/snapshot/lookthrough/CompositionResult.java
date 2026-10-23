package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One retrieval attempt. RECEIVED means source data is available, not that it is
 * valid or complete. Failures never contain a replacement snapshot; callers keep
 * any previous valid snapshot separately, subject to its applicable retention.
 * Retry deadlines are independent of provenance dates. Authentication and
 * entitlement failures require configuration changes before retrying. No raw
 * response, exception text or credentials belong in this result.
 */
public record CompositionResult(Status status, Optional<FundComposition> composition, Optional<Instant> retryAfter)
{
    public enum Status
    {
        RECEIVED, UNAVAILABLE, UNSUPPORTED, MALFORMED_RESPONSE, RATE_LIMITED, AUTHENTICATION_FAILED,
        ENTITLEMENT_FAILED, FAILED, CANCELLED
    }

    public CompositionResult
    {
        Objects.requireNonNull(status);
        Objects.requireNonNull(composition);
        Objects.requireNonNull(retryAfter);
        if ((status == Status.RECEIVED) != composition.isPresent())
            throw new IllegalArgumentException();
        if (retryAfter.isPresent() && status != Status.RATE_LIMITED)
            throw new IllegalArgumentException();
    }
}
