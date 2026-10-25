package name.abuchen.portfolio.online.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import name.abuchen.portfolio.online.FundCompositionProvider;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionProvenance;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult.Status;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Constituent;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentClassifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;
import name.abuchen.portfolio.util.OnlineHelper;
import name.abuchen.portfolio.util.WebAccess;

/**
 * Source adapter for the public Eulerpool OpenAPI holdings schema:
 * https://api.eulerpool.com/api/1/documentation/yaml. The endpoint describes top
 * holdings, not validated NAV or economic exposure. No classification or company
 * identity is inferred from labels, ISINs or nullable assetType strings.
 *
 * Lookup prefers the ISIN namespace, then eulerpool:symbol. Credentials are
 * resolved per call/account, never configured on a shared discovered instance.
 * The no-argument discovery instance is deliberately unconfigured; local secure
 * storage/session entry integration must supply CredentialAccess separately.
 */
@SuppressWarnings("nls")
public final class EulerpoolFundCompositionProvider implements FundCompositionProvider, InstrumentClassifier
{
    public static final String ID = "EULERPOOL";
    public static final String SYMBOL = "eulerpool:symbol";
    public static final int CLASSIFICATION_REQUEST_COST = 3;

    @FunctionalInterface
    public interface CredentialAccess
    {
        Optional<String> getBearerToken(ProviderAccount account) throws IOException;
    }

    /** Transport implementations must not log credentials or follow redirects. */
    @FunctionalInterface
    public interface Transport
    {
        Response get(URI uri, String authorization) throws IOException;
    }

    public record Response(int status, String body, Optional<String> retryAfter)
    {
        public Response
        {
            Objects.requireNonNull(retryAfter);
        }

        @Override
        public String toString()
        {
            return "Eulerpool response [status=" + status + "]";
        }
    }

    private final CredentialAccess credentials;
    private final Transport transport;
    private final Clock clock;

    public EulerpoolFundCompositionProvider()
    {
        this(account -> Optional.empty());
    }

    public EulerpoolFundCompositionProvider(CredentialAccess credentials)
    {
        this(credentials, EulerpoolFundCompositionProvider::download, Clock.systemUTC());
    }

    public EulerpoolFundCompositionProvider(CredentialAccess credentials, Transport transport, Clock clock)
    {
        this.credentials = Objects.requireNonNull(credentials);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public CompositionPersistencePolicy getPersistencePolicy(ProviderAccount account)
    {
        checkAccount(account);
        return new CompositionPersistencePolicy(account, Permission.UNVERIFIED, Optional.empty());
    }

    @Override
    public CompositionResult getComposition(ProviderAccount account, CompositionInstrument fund)
    {
        checkAccount(account);
        Objects.requireNonNull(fund);
        var identifier = Optional.ofNullable(fund.identifiers().get("ISIN")).filter(s -> !s.isBlank())
                        .or(() -> Optional.ofNullable(fund.identifiers().get(SYMBOL)).filter(s -> !s.isBlank()));
        // Restrict identifiers to one path segment, never a URL or query string.
        if (identifier.isEmpty() || !identifier.get().matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            return failure(Status.UNSUPPORTED);

        try
        {
            var token = credentials.getBearerToken(account);
            if (token.isEmpty() || !token.get().matches("[A-Za-z0-9._~+/-]+=*"))
                return failure(Status.AUTHENTICATION_FAILED);

            var uri = URI.create("https://api.eulerpool.com/api/1/etf/holdings/" + identifier.get());
            var response = transport.get(uri, "Bearer " + token.get());
            return switch (response.status())
            {
                case 200 -> parse(account, fund, response.body());
                case 204, 404 -> failure(Status.UNAVAILABLE);
                case 401 -> failure(Status.AUTHENTICATION_FAILED);
                case 402, 403 -> failure(Status.ENTITLEMENT_FAILED);
                case 429 -> new CompositionResult(Status.RATE_LIMITED, Optional.empty(),
                                retryAfter(response.retryAfter()));
                default -> failure(Status.FAILED);
            };
        }
        catch (InterruptedIOException e)
        {
            return failure(Thread.currentThread().isInterrupted() ? Status.CANCELLED : Status.FAILED);
        }
        catch (IOException | RuntimeException e)
        {
            // No response bodies, exception messages, headers or keys escape.
            return failure(Status.FAILED);
        }
    }

    @Override
    public Optional<InstrumentEvidence> classify(ProviderAccount account, CompositionInstrument instrument)
                    throws IOException
    {
        checkAccount(account);
        Objects.requireNonNull(instrument);
        var queries = identifiers(instrument);
        if (queries.isEmpty())
            return Optional.empty();
        var token = credentials.getBearerToken(account);
        if (token.isEmpty() || !token.get().matches("[A-Za-z0-9._~+/-]+=*"))
            throw new EnrichmentFailure(failure(Status.AUTHENTICATION_FAILED));

        // Exact ISIN or ticker evidence only. A result's ISIN is never used to
        // replace the source identity: some providers index a different listing.
        for (var query : queries)
        {
            var search = classificationResponse("https://api.eulerpool.com/api/1/equity/search?q=" + query, token.get());
            if (search.status() == 200)
            {
                var evidence = parseSearch(account, instrument, search.body());
                if (evidence.isPresent())
                    return evidence;
            }
            else if (search.status() != 204 && search.status() != 404)
                throw new IOException();
        }
        for (var query : queries)
        {
            var profile = classificationResponse("https://api.eulerpool.com/api/1/equity/profile/" + query, token.get());
            if (profile.status() == 200)
            {
                var evidence = parseProfile(account, instrument, profile.body());
                if (evidence.isPresent())
                    return evidence;
            }
            else if (profile.status() != 204 && profile.status() != 404)
                throw new IOException();
        }
        for (var query : queries)
        {
            var bonds = classificationResponse("https://api.eulerpool.com/api/1/bonds/list?q=" + query, token.get());
            if (bonds.status() == 200 && isBond(query, bonds.body()))
                return bondEvidence(account, instrument);
            if (bonds.status() != 200 && bonds.status() != 204 && bonds.status() != 404)
                throw new IOException();
        }
        return Optional.empty();
    }

    private List<String> identifiers(CompositionInstrument instrument)
    {
        var result = new ArrayList<String>();
        var isin = instrument.identifiers().get("ISIN");
        if (isin != null && !isin.isBlank())
            result.add(isin.trim());
        var symbol = instrument.identifiers().get(SYMBOL);
        if (result.isEmpty() && symbol != null && !symbol.isBlank())
            result.add(symbol.trim());
        result.removeIf(value -> !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*"));
        return List.copyOf(result);
    }

    private Response classificationResponse(String url, String token) throws IOException
    {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException();
        var response = transport.get(URI.create(url), "Bearer " + token);
        var status = switch (response.status())
        {
            case 401 -> Status.AUTHENTICATION_FAILED;
            case 402, 403 -> Status.ENTITLEMENT_FAILED;
            case 429 -> Status.RATE_LIMITED;
            default -> null;
        };
        if (status != null)
            throw new EnrichmentFailure(new CompositionResult(status, Optional.empty(),
                            status == Status.RATE_LIMITED ? retryAfter(response.retryAfter()) : Optional.empty()));
        return response;
    }

    private Optional<InstrumentEvidence> parseSearch(ProviderAccount account, CompositionInstrument instrument,
                    String body)
    {
        if (body == null || body.isBlank())
            return Optional.empty();
        try (var reader = new JsonReader(new StringReader(body)))
        {
            reader.setStrictness(Strictness.STRICT);
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                return Optional.empty();
            var results = json.getAsJsonObject().get("results");
            if (results == null || !results.isJsonArray())
                return Optional.empty();
            var isin = instrument.identifiers().get("ISIN");
            var symbol = instrument.identifiers().get(SYMBOL);
            Optional<InstrumentEvidence> match = Optional.empty();
            for (var element : results.getAsJsonArray())
            {
                if (!element.isJsonObject())
                    continue;
                var row = element.getAsJsonObject();
                var resultIsin = text(row, "isin").orElse(null);
                var resultTicker = text(row, "ticker").orElse(null);
                var type = text(row, "type").orElse(null);
                if (type == null)
                    continue;
                boolean exact = isin != null && !isin.isBlank() ? isin.equalsIgnoreCase(resultIsin)
                                : symbol != null && symbol.equalsIgnoreCase(resultTicker);
                if (exact)
                {
                    var candidate = buildEvidence(account, instrument, type, "equity/search"); //$NON-NLS-1$
                    if (match.isPresent() && !match.get().classification().map(Assertion::value)
                                    .equals(candidate.flatMap(InstrumentEvidence::classification).map(Assertion::value)))
                        return Optional.empty();
                    match = candidate;
                }
            }
            return match;
        }
        catch (IOException | RuntimeException e)
        {
            return Optional.empty();
        }
    }

    private Optional<InstrumentEvidence> parseProfile(ProviderAccount account, CompositionInstrument instrument,
                    String body)
    {
        if (body == null || body.isBlank())
            return Optional.empty();
        try (var reader = new JsonReader(new StringReader(body)))
        {
            reader.setStrictness(Strictness.STRICT);
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                return Optional.empty();
            var sector = text(json.getAsJsonObject(), "sector").orElse("");
            var industry = text(json.getAsJsonObject(), "industry").orElse("");
            var isin = instrument.identifiers().get("ISIN");
            if (isin == null || !isin.equalsIgnoreCase(text(json.getAsJsonObject(), "isin").orElse("")))
                return Optional.empty();
            if (sector.isBlank() && industry.isBlank())
                return Optional.empty();
            return buildEvidence(account, instrument, "stock", "equity/profile"); //$NON-NLS-1$
        }
        catch (IOException | RuntimeException e)
        {
            return Optional.empty();
        }
    }

    private Optional<InstrumentEvidence> buildEvidence(ProviderAccount account, CompositionInstrument instrument,
                    String type, String reference)
    {
        var assetType = switch (type.toLowerCase())
        {
            case "stock", "share", "equity" -> AssetType.EQUITY;
            case "etf", "fund", "mutual fund", "mutual_fund" -> AssetType.FUND;
            case "bond", "fixed income", "crypto", "cryptocurrency" -> AssetType.OTHER_NON_EQUITY;
            default -> null;
        };
        if (assetType == null)
            return Optional.empty();
        var provenance = new Provenance(account, reference, clock.instant());
        var isin = instrument.identifiers().get("ISIN");
        var identity = isin != null && !isin.isBlank() ? new Identifier("ISIN", isin.trim())
                        : new Identifier(SYMBOL, instrument.identifiers().get(SYMBOL));
        return Optional.of(new InstrumentEvidence(Map.of(identity.namespace(), identity.value()),
                        new Assertion<>(identity, provenance),
                        Optional.of(new Assertion<>(assetType, provenance)),
                        assetType == AssetType.EQUITY ? Optional.of(new Assertion<>(identity, provenance))
                                        : Optional.empty()));
    }

    private Optional<InstrumentEvidence> bondEvidence(ProviderAccount account, CompositionInstrument instrument)
    {
        var provenance = new Provenance(account, "bonds/list", clock.instant());
        var isin = instrument.identifiers().get("ISIN");
        var identity = isin != null && !isin.isBlank() ? new Identifier("ISIN", isin.trim())
                        : new Identifier(SYMBOL, instrument.identifiers().get(SYMBOL));
        return Optional.of(new InstrumentEvidence(Map.of(identity.namespace(), identity.value()),
                        new Assertion<>(identity, provenance),
                        Optional.of(new Assertion<>(AssetType.OTHER_NON_EQUITY, provenance)), Optional.empty()));
    }

    private boolean isBond(String query, String body)
    {
        if (body == null || body.isBlank())
            return false;
        try (var reader = new JsonReader(new StringReader(body)))
        {
            reader.setStrictness(Strictness.STRICT);
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                return false;
            var data = json.getAsJsonObject().get("data");
            if (data == null || !data.isJsonArray())
                return false;
            for (var element : data.getAsJsonArray())
            {
                if (element.isJsonObject() && query.equalsIgnoreCase(text(element.getAsJsonObject(), "isin").orElse("")))
                    return true;
            }
            return false;
        }
        catch (IOException | RuntimeException e)
        {
            return false;
        }
    }

    private CompositionResult parse(ProviderAccount account, CompositionInstrument fund, String body)
    {
        if (body == null || body.isBlank())
            return failure(Status.MALFORMED_RESPONSE);
        try (var reader = new JsonReader(new StringReader(body)))
        {
            reader.setStrictness(Strictness.STRICT);
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonArray() || reader.peek() != JsonToken.END_DOCUMENT)
                return failure(Status.MALFORMED_RESPONSE);
            var rows = json.getAsJsonArray();
            if (rows.isEmpty())
                return failure(Status.UNAVAILABLE);

            var constituents = new ArrayList<Constituent>();
            var refreshTimes = new ArrayList<Optional<Instant>>();
            for (var element : rows)
            {
                var row = element.getAsJsonObject();
                var ids = new HashMap<String, String>();
                for (var field : new String[] { "isin", "cusip", "symbol" })
                {
                    var value = text(row, field);
                    if (value.isPresent() && !value.get().isBlank())
                        ids.put(switch (field)
                        {
                            case "isin" -> "ISIN";
                            case "cusip" -> "CUSIP";
                            default -> SYMBOL;
                        }, value.get());
                }
                // Validate known nullable fields without inventing classifications.
                text(row, "assetType");
                text(row, "etf_symbol");
                text(row, "logo");
                number(row, "share");
                number(row, "value");
                var weight = number(row, "percent").map(value -> value.movePointLeft(2).toPlainString());
                constituents.add(new Constituent(new CompositionInstrument(text(row, "name").orElse(""), ids),
                                weight));
                refreshTimes.add(text(row, "updated_at").map(Instant::parse));
            }
            // A common refresh is representable; different/missing row vintages
            // must not be collapsed into the newest date or an invented date.
            var refresh = refreshTimes.stream().distinct().count() == 1 ? refreshTimes.getFirst()
                            : Optional.<Instant>empty();
            var provenance = new CompositionProvenance(account, fund, clock.instant(), refresh, Optional.empty());
            var composition = new FundComposition(provenance, Denominator.UNKNOWN, ExposureBasis.UNKNOWN,
                            Completeness.UNKNOWN, constituents);
            return new CompositionResult(Status.RECEIVED, Optional.of(composition), Optional.empty());
        }
        catch (IOException | RuntimeException e)
        {
            return failure(Status.MALFORMED_RESPONSE);
        }
    }

    private static Optional<String> text(JsonObject row, String field)
    {
        var value = row.get(field);
        if (value == null || value.isJsonNull())
            return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException();
        return Optional.of(value.getAsString());
    }

    private static Optional<BigDecimal> number(JsonObject row, String field)
    {
        JsonElement value = row.get(field);
        if (value == null || value.isJsonNull())
            return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return Optional.of(new BigDecimal(value.getAsString()));
    }

    private Optional<Instant> retryAfter(Optional<String> header)
    {
        if (header.isEmpty())
            return Optional.empty();
        try
        {
            var value = header.get().trim();
            if (value.matches("[0-9]+"))
                return Optional.of(clock.instant().plusSeconds(Long.parseLong(value)));
            return Optional.of(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        }
        catch (RuntimeException e)
        {
            return Optional.empty();
        }
    }

    private static CompositionResult failure(Status status)
    {
        return new CompositionResult(status, Optional.empty(), Optional.empty());
    }

    private static void checkAccount(ProviderAccount account)
    {
        if (!ID.equals(account.providerId()))
            throw new IllegalArgumentException();
    }

    private static final org.apache.hc.client5.http.impl.classic.CloseableHttpClient CLIENT = createClient();

    private static org.apache.hc.client5.http.impl.classic.CloseableHttpClient createClient()
    {
        var manager = new PoolingHttpClientConnectionManager();
        manager.setDefaultConnectionConfig(WebAccess.defaultConnectionConfig);
        manager.setDefaultMaxPerRoute(16);
        manager.setMaxTotal(32);
        return HttpClientBuilder.create().setConnectionManager(manager)
                        .setDefaultRequestConfig(WebAccess.defaultRequestConfig)
                        .setUserAgent(OnlineHelper.getUserAgent()).useSystemProperties().disableRedirectHandling()
                        .disableAutomaticRetries().build();
    }

    private static Response download(URI uri, String authorization) throws IOException
    {
        var request = new HttpGet(uri);
        request.setHeader("Authorization", authorization);
        request.setHeader("Accept", "application/json");
        return CLIENT.execute(request, response -> {
            var retry = Optional.ofNullable(response.getFirstHeader("Retry-After")).map(h -> h.getValue());
            // Error bodies are not needed and may reflect credentials.
            var body = response.getCode() == 200 && response.getEntity() != null
                            ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : null;
            return new Response(response.getCode(), body, retry);
        });
    }
}
