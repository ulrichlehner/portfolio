package name.abuchen.portfolio.online.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService;
import name.abuchen.portfolio.snapshot.lookthrough.RegisterRecord;
import name.abuchen.portfolio.util.OnlineHelper;
import name.abuchen.portfolio.util.WebAccess;

/**
 * Looks up the legal entity records of ISINs in the public GLEIF register
 * (https://api.gleif.org, no key, CC0 data). Requests contain only ISINs.
 * <p>
 * A batch request filters by several ISINs at once, but its records do not
 * say which requested ISIN they belong to. An empty batch answer is therefore
 * definitive for all its ISINs; a batch of one ISIN is attributed directly.
 * Otherwise the ISINs of the batch are looked up one by one. A single ISIN
 * with more than one record is ambiguous and counts as "no record" rather
 * than picking one. Requests are paced by a fixed pause; HTTP 429 stops the
 * lookup with the server's retry time.
 */
@SuppressWarnings("nls")
public final class GleifCompanyRegister implements CompanyEnrichmentService.Register
{
    /** Transport implementations must not follow redirects. */
    @FunctionalInterface
    public interface Transport
    {
        Response get(URI uri) throws IOException;
    }

    public record Response(int status, String body, Optional<String> retryAfter)
    {
        public Response
        {
            Objects.requireNonNull(retryAfter);
        }
    }

    /** Waits between two requests; tests pass a no-op. */
    @FunctionalInterface
    public interface Pause
    {
        void pause() throws InterruptedIOException;
    }

    /** One record of an answer, before it is attributed to an ISIN. */
    private record Entry(String lei, Optional<String> headquartersCountry, Optional<String> legalCountry,
                    Instant retrievedAt)
    {
    }

    private record Page(List<Entry> records, long total)
    {
    }

    private static final String ENDPOINT = "https://api.gleif.org/api/v1/lei-records";
    private static final Duration PAUSE = Duration.ofMillis(1100);
    private static final String ISIN_PATTERN = "[A-Z]{2}[A-Z0-9]{9}[0-9]";

    private final Transport transport;
    private final Pause pause;
    private final Clock clock;
    private boolean requested;

    public GleifCompanyRegister()
    {
        this(GleifCompanyRegister::download, GleifCompanyRegister::sleep, Clock.systemUTC());
    }

    public GleifCompanyRegister(Transport transport, Pause pause, Clock clock)
    {
        this.transport = Objects.requireNonNull(transport);
        this.pause = Objects.requireNonNull(pause);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized Map<String, Optional<RegisterRecord>> lookup(List<String> isins) throws IOException
    {
        var distinct = isins.stream().distinct().toList();
        if (distinct.isEmpty())
            return Map.of();
        if (distinct.size() > CompanyEnrichmentService.REGISTER_BATCH
                        || distinct.stream().anyMatch(isin -> isin == null || !isin.matches(ISIN_PATTERN)))
            throw new IllegalArgumentException();

        var result = new HashMap<String, Optional<RegisterRecord>>();
        var page = request(distinct);
        if (page.total() == 0 && page.records().isEmpty())
        {
            for (var isin : distinct)
                result.put(isin, Optional.empty());
            return result;
        }
        if (distinct.size() == 1)
        {
            result.put(distinct.get(0), attribute(distinct.get(0), page));
            return result;
        }
        // Records of a batch cannot be attributed to an ISIN.
        for (var isin : distinct)
            result.put(isin, attribute(isin, request(List.of(isin))));
        return result;
    }

    private static Optional<RegisterRecord> attribute(String isin, Page page)
    {
        if (page.total() != 1 || page.records().size() != 1)
            return Optional.empty();
        var entry = page.records().get(0);
        return Optional.of(new RegisterRecord(isin, entry.lei(), entry.headquartersCountry(),
                        entry.legalCountry(), entry.retrievedAt()));
    }

    private Page request(List<String> isins) throws IOException
    {
        if (requested)
            pause.pause();
        requested = true;
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException();
        // Brackets are percent-encoded; the ISINs are validated, so nothing else needs encoding.
        var uri = URI.create(ENDPOINT + "?filter%5Bisin%5D=" + String.join(",", isins) + "&page%5Bsize%5D="
                        + CompanyEnrichmentService.REGISTER_BATCH);
        var response = transport.get(uri);
        return switch (response.status())
        {
            case 200 -> parse(response.body());
            case 404 -> new Page(List.of(), 0);
            case 429 -> throw new CompanyEnrichmentService.RegisterRateLimited(retryAfter(response.retryAfter()));
            default -> throw new IOException();
        };
    }

    private Page parse(String body) throws IOException
    {
        if (body == null || body.isBlank())
            throw new IOException();
        try (var reader = new JsonReader(new StringReader(body)))
        {
            reader.setStrictness(Strictness.STRICT);
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException();
            var root = json.getAsJsonObject();
            var data = root.get("data");
            if (data == null || !data.isJsonArray())
                throw new IOException();
            var total = object(object(object(root, "meta"), "pagination"), "total");
            var retrieved = clock.instant();
            var records = new ArrayList<Entry>();
            for (var element : data.getAsJsonArray())
            {
                var attributes = object(element, "attributes");
                var lei = string(object(attributes, "lei")).filter(value -> value.matches("[A-Z0-9]{20}"))
                                .orElseThrow(IOException::new);
                var entity = object(attributes, "entity");
                records.add(new Entry(lei, country(entity, "headquartersAddress"), country(entity, "legalAddress"),
                                retrieved));
            }
            if (!total.isJsonPrimitive() || !total.getAsJsonPrimitive().isNumber())
                throw new IOException();
            var count = total.getAsJsonPrimitive().getAsBigDecimal().longValueExact();
            if (count < records.size())
                throw new IOException();
            return new Page(List.copyOf(records), count);
        }
        catch (RuntimeException e)
        {
            // No response bodies or exception messages escape.
            throw new IOException();
        }
    }

    private static JsonElement object(JsonElement parent, String field) throws IOException
    {
        if (parent == null || !parent.isJsonObject())
            throw new IOException();
        var value = parent.getAsJsonObject().get(field);
        if (value == null)
            throw new IOException();
        return value;
    }

    private static JsonObject object(JsonElement element, String field, boolean optional) throws IOException
    {
        var value = element.getAsJsonObject().get(field);
        if (value == null || value.isJsonNull())
        {
            if (optional)
                return null;
            throw new IOException();
        }
        if (!value.isJsonObject())
            throw new IOException();
        return value.getAsJsonObject();
    }

    private static Optional<String> country(JsonElement entity, String address) throws IOException
    {
        if (!entity.isJsonObject())
            throw new IOException();
        var value = object(entity, address, true);
        if (value == null)
            return Optional.empty();
        // An unassigned or malformed code is unknown, not guessed.
        return string(value.get("country")).filter(CompanyAttributes::isCountryCode);
    }

    private static Optional<String> string(JsonElement value) throws IOException
    {
        if (value == null || value.isJsonNull())
            return Optional.empty();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException();
        return Optional.of(value.getAsString());
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

    private static void sleep() throws InterruptedIOException
    {
        try
        {
            Thread.sleep(PAUSE);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private static final CloseableHttpClient CLIENT = createClient();

    private static CloseableHttpClient createClient()
    {
        var manager = new PoolingHttpClientConnectionManager();
        manager.setDefaultConnectionConfig(WebAccess.defaultConnectionConfig);
        return HttpClientBuilder.create().setConnectionManager(manager)
                        .setDefaultRequestConfig(WebAccess.defaultRequestConfig)
                        .setUserAgent(OnlineHelper.getUserAgent()).useSystemProperties().disableRedirectHandling()
                        .disableAutomaticRetries().build();
    }

    private static Response download(URI uri) throws IOException
    {
        var request = new HttpGet(uri);
        request.setHeader("Accept", "application/vnd.api+json");
        return CLIENT.execute(request, response -> {
            var retry = Optional.ofNullable(response.getFirstHeader("Retry-After")).map(h -> h.getValue());
            var body = response.getCode() == 200 && response.getEntity() != null
                            ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : null;
            return new Response(response.getCode(), body, retry);
        });
    }
}
