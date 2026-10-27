package name.abuchen.portfolio.online.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.online.Factory;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider.Response;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionInstrument;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.Permission;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy.StorageMode;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionResult.Status;
import name.abuchen.portfolio.snapshot.lookthrough.CompositionValidator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Completeness;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.Denominator;
import name.abuchen.portfolio.snapshot.lookthrough.FundComposition.ExposureBasis;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.ProviderAccount;

/** All payloads and identifiers here are invented, not copied provider data. */
@SuppressWarnings("nls")
public class EulerpoolFundCompositionProviderTest
{
    private static final Instant NOW = Instant.parse("2026-01-03T12:00:00Z");
    private static final Instant REFRESH = Instant.parse("2026-01-02T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ProviderAccount ACCOUNT = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "local-one");
    private static final CompositionInstrument FUND = new CompositionInstrument("Invented fund",
                    Map.of("ISIN", "SYNTHETIC001", EulerpoolFundCompositionProvider.SYMBOL, "FUND.TEST"));
    private static final String TOKEN = "synthetic-session-token";
    private static final String ROW = """
                    {"etf_symbol":"FUND.TEST","symbol":"ROW.TEST","name":"Invented constituent",
                     "isin":"SYNTHETIC002","cusip":"INVENTED2","percent":37.12345678901234567890123456789,
                     "share":12.5,"value":210.0,"assetType":null,"logo":null,
                     "updated_at":"2026-01-02T01:00:00Z"}
                    """;

    private CompositionResult retrieve(int status, String body, Optional<String> retryAfter)
    {
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(status, body, retryAfter), CLOCK);
        return provider.getComposition(ACCOUNT, FUND);
    }

    private CompositionResult retrieve(String body)
    {
        return retrieve(200, body, Optional.empty());
    }

    @Test
    public void testIdentifierOnlyRequestUsesBearerHeaderAndIsinPreference()
    {
        var calls = new ArrayList<URI>();
        var provider = new EulerpoolFundCompositionProvider(account -> {
            assertEquals(ACCOUNT, account);
            return Optional.of(TOKEN);
        }, (uri, authorization) -> {
            calls.add(uri);
            assertEquals("Bearer " + TOKEN, authorization);
            assertEquals("https", uri.getScheme());
            assertEquals("api.eulerpool.com", uri.getHost());
            assertEquals("/api/1/etf/holdings/SYNTHETIC001", uri.getPath());
            assertEquals(null, uri.getQuery());
            assertEquals(null, uri.getUserInfo());
            assertFalse(uri.toString().contains(TOKEN));
            assertFalse(uri.toString().contains(FUND.label()));
            return new Response(200, "[" + ROW + "]", Optional.empty());
        }, CLOCK);

        assertEquals(Status.RECEIVED, provider.getComposition(ACCOUNT, FUND).status());
        assertEquals(1, calls.size());
        assertFalse(provider.toString().contains(TOKEN));
    }

    @Test
    public void testExactDecimalAndIndependentProvenanceWithoutAcceptanceClaims()
    {
        var result = retrieve("[" + ROW + "]");
        assertEquals(Status.RECEIVED, result.status());
        var composition = result.composition().orElseThrow();
        assertEquals(ACCOUNT, composition.provenance().source());
        assertEquals(FUND, composition.provenance().fund());
        assertEquals(NOW, composition.provenance().retrievedAt());
        assertEquals(Optional.of(REFRESH), composition.provenance().providerRefreshedAt());
        assertTrue(composition.provenance().holdingsEffectiveDate().isEmpty());
        assertEquals(Denominator.UNKNOWN, composition.denominator());
        assertEquals(ExposureBasis.UNKNOWN, composition.exposureBasis());
        assertEquals(Completeness.UNKNOWN, composition.completeness());
        var row = composition.constituents().getFirst();
        assertEquals("0.3712345678901234567890123456789", row.weight().orElseThrow());
        assertEquals("Invented constituent", row.instrument().label());
        assertEquals(Map.of("ISIN", "SYNTHETIC002", "CUSIP", "INVENTED2",
                        EulerpoolFundCompositionProvider.SYMBOL, "ROW.TEST"), row.instrument().identifiers());
        assertTrue(result.retryAfter().isEmpty());
    }

    @Test
    public void testHundredPercentAndEquityLabelDoNotValidateSemantics()
    {
        var composition = retrieve("[{\"name\":\"Invented equity\",\"percent\":100,\"assetType\":\"Equity\"}]")
                        .composition().orElseThrow();
        assertEquals(Denominator.UNKNOWN, composition.denominator());
        assertEquals(ExposureBasis.UNKNOWN, composition.exposureBasis());
        assertEquals(Completeness.UNKNOWN, composition.completeness());
        assertTrue(composition.constituents().getFirst().instrument().identifiers().isEmpty());
        var validation = new CompositionValidator().validate(composition);
        assertFalse(validation.isUsable());
        assertTrue(validation.rows().isEmpty());
        assertTrue(validation.shortfall().isEmpty());
    }

    @Test
    public void testNullAndMissingFieldsRemainUnknown()
    {
        for (var body : List.of("[{\"name\":null,\"symbol\":null,\"isin\":null,\"cusip\":null,"
                        + "\"percent\":null,\"share\":null,\"value\":null,\"assetType\":null,"
                        + "\"updated_at\":null,\"logo\":null}]", "[{}]"))
        {
            var composition = retrieve(body).composition().orElseThrow();
            assertTrue(composition.provenance().providerRefreshedAt().isEmpty());
            assertTrue(composition.provenance().holdingsEffectiveDate().isEmpty());
            var row = composition.constituents().getFirst();
            assertEquals("", row.instrument().label());
            assertTrue(row.instrument().identifiers().isEmpty());
            assertTrue(row.weight().isEmpty());
        }
    }

    @Test
    public void testEmptyResponsesAreNotReplacementSnapshots()
    {
        for (var result : List.of(retrieve("[]"), retrieve(204, null, Optional.empty()),
                        retrieve(404, "not found", Optional.empty())))
        {
            assertEquals(Status.UNAVAILABLE, result.status());
            assertTrue(result.composition().isEmpty());
        }
    }

    @Test
    public void testMalformedResponseIsRedacted()
    {
        var bodies = new ArrayList<>(List.of("", " ", "null", "{}", "not json " + TOKEN, "[", "[null]",
                        "[true]", "[1]", "[] []", "[{name:'not strict JSON'}]", "[{\"percent\":NaN}]",
                        "[{\"percent\":Infinity}]", "[{\"percent\":\"37.5\"}]", "[{\"percent\":true}]",
                        "[{\"percent\":{}}]", "[{\"name\":9}]", "[{\"isin\":[]}]",
                        "[{\"assetType\":false}]", "[{\"share\":\"bad\"}]", "[{\"value\":false}]",
                        "[{\"updated_at\":\"not a date\"}]"));
        bodies.add(null);
        for (var body : bodies)
        {
            var result = retrieve(body);
            assertEquals(String.valueOf(body), Status.MALFORMED_RESPONSE, result.status());
            assertTrue(result.composition().isEmpty());
            assertFalse(result.toString().contains(TOKEN));
        }
    }

    @Test
    public void testDuplicatesSignedAndOverfullWeightsAreNotDiscardedOrNormalized()
    {
        var composition = retrieve("[" + ROW + "," + ROW + ","
                        + "{\"percent\":-2.5},{\"percent\":120},{\"percent\":1e-25}]")
                        .composition().orElseThrow();
        assertEquals(5, composition.constituents().size());
        assertEquals(composition.constituents().get(0), composition.constituents().get(1));
        assertEquals(Optional.of("-0.025"), composition.constituents().get(2).weight());
        assertEquals(Optional.of("1.20"), composition.constituents().get(3).weight());
        assertEquals(Optional.of("0.000000000000000000000000001"), composition.constituents().get(4).weight());
    }

    @Test
    public void testMixedRowRefreshDatesAreNotReportedAsNewestOrEffectiveDate()
    {
        for (var second : List.of(ROW.replace("2026-01-02T01:00:00Z", "2026-01-01T01:00:00Z"),
                        ROW.replace("\"2026-01-02T01:00:00Z\"", "null")))
        {
            var composition = retrieve("[" + ROW + "," + second + "]").composition().orElseThrow();
            assertTrue(composition.provenance().providerRefreshedAt().isEmpty());
            assertTrue(composition.provenance().holdingsEffectiveDate().isEmpty());
        }
    }

    @Test
    public void testProviderSymbolLookupWithoutSearchOrEnrichmentRequests()
    {
        var calls = new ArrayList<URI>();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), (uri, authorization) -> {
            calls.add(uri);
            return new Response(200, "[]", Optional.empty());
        }, CLOCK);
        var fund = new CompositionInstrument("Invented ticker fund",
                        Map.of(EulerpoolFundCompositionProvider.SYMBOL, "FUND.TEST"));
        assertEquals(Status.UNAVAILABLE, provider.getComposition(ACCOUNT, fund).status());
        assertEquals(List.of(URI.create("https://api.eulerpool.com/api/1/etf/holdings/FUND.TEST")), calls);
    }

    @Test
    public void testMissingOrUnsafeIdentifiersDoNotCauseRequests()
    {
        var provider = new EulerpoolFundCompositionProvider(account -> {
            throw new AssertionError("No credentials needed for unsupported lookup");
        }, (uri, authorization) -> {
            throw new AssertionError("No request expected");
        }, CLOCK);
        for (var identifiers : List.of(Map.<String, String>of(), Map.of("CUSIP", "INVENTED1"),
                        Map.of("ISIN", " "), Map.of("ISIN", "../escape"), Map.of("ISIN", "id?token=secret"),
                        Map.of("ISIN", "https://example.invalid"), Map.of("ISIN", "id\r\nheader")))
            assertEquals(Status.UNSUPPORTED, provider.getComposition(ACCOUNT,
                            new CompositionInstrument("Never use this name as lookup", identifiers)).status());
    }

    @Test
    public void testMissingAndInvalidCredentialsDoNotCauseRequests()
    {
        for (var token : List.of(Optional.<String>empty(), Optional.of(""), Optional.of(" "),
                        Optional.of("bad\r\nheader")))
        {
            var provider = new EulerpoolFundCompositionProvider(account -> token, (uri, authorization) -> {
                throw new AssertionError("No request expected");
            }, CLOCK);
            assertEquals(Status.AUTHENTICATION_FAILED, provider.getComposition(ACCOUNT, FUND).status());
        }
    }

    @Test
    public void testAccountsAreResolvedIndependentlyOnEveryCall()
    {
        var other = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "local-two");
        var tokens = new HashMap<>(Map.of(ACCOUNT, TOKEN, other, "other-synthetic-token"));
        var headers = new ArrayList<String>();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.ofNullable(tokens.get(account)),
                        (uri, authorization) -> {
                            headers.add(authorization);
                            return new Response(200, "[]", Optional.empty());
                        }, CLOCK);
        provider.getComposition(ACCOUNT, FUND);
        provider.getComposition(other, FUND);
        tokens.put(ACCOUNT, "replacement-synthetic-token");
        provider.getComposition(ACCOUNT, FUND);
        tokens.remove(ACCOUNT);
        assertEquals(Status.AUTHENTICATION_FAILED, provider.getComposition(ACCOUNT, FUND).status());
        assertEquals(List.of("Bearer " + TOKEN, "Bearer other-synthetic-token", "Bearer replacement-synthetic-token"),
                        headers);
    }

    @Test
    public void testFailureStatusesNeverExposeReflectedBodyOrRetryForAuth()
    {
        for (var entry : Map.of(401, Status.AUTHENTICATION_FAILED, 402, Status.ENTITLEMENT_FAILED,
                        403, Status.ENTITLEMENT_FAILED, 500, Status.FAILED, 302, Status.FAILED).entrySet())
        {
            var result = retrieve(entry.getKey(), "reflected secret: " + TOKEN, Optional.of("60"));
            assertEquals(entry.getValue(), result.status());
            assertTrue(result.composition().isEmpty());
            assertTrue(result.retryAfter().isEmpty());
            assertFalse(result.toString().contains(TOKEN));
        }
        assertFalse(new Response(401, TOKEN, Optional.of(TOKEN)).toString().contains(TOKEN));
    }

    @Test
    public void testRateLimitRetryMetadataUsesClockAndHttpDate()
    {
        assertEquals(Optional.of(NOW.plusSeconds(75)),
                        retrieve(429, TOKEN, Optional.of("75")).retryAfter());
        assertEquals(Optional.of(NOW.plusSeconds(120)),
                        retrieve(429, TOKEN, Optional.of("Sat, 3 Jan 2026 12:02:00 GMT")).retryAfter());
        for (var header : List.of(Optional.<String>empty(), Optional.of("-1"), Optional.of("invalid " + TOKEN),
                        Optional.of("999999999999999999999999999999999")))
        {
            var result = retrieve(429, TOKEN, header);
            assertEquals(Status.RATE_LIMITED, result.status());
            assertTrue(result.retryAfter().isEmpty());
            assertTrue(result.composition().isEmpty());
            assertFalse(result.toString().contains(TOKEN));
        }
    }

    @Test
    public void testTransportAndCredentialErrorsAreRedacted()
    {
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), (uri, authorization) -> {
            throw new IOException("reflected " + authorization);
        }, CLOCK);
        var result = provider.getComposition(ACCOUNT, FUND);
        assertEquals(Status.FAILED, result.status());
        assertFalse(result.toString().contains(TOKEN));

        provider = new EulerpoolFundCompositionProvider(account -> {
            throw new IOException(TOKEN);
        }, (uri, authorization) -> {
            throw new AssertionError("No request expected");
        }, CLOCK);
        result = provider.getComposition(ACCOUNT, FUND);
        assertEquals(Status.FAILED, result.status());
        assertFalse(result.toString().contains(TOKEN));
    }

    @Test
    public void testDiscoveryIsSafelyUnconfiguredAndPersistenceAlwaysUnverified()
    {
        var provider = Factory.getFundCompositionProviders().stream()
                        .filter(p -> EulerpoolFundCompositionProvider.ID.equals(p.getId())).findFirst().orElseThrow();
        assertEquals(Status.AUTHENTICATION_FAILED, provider.getComposition(ACCOUNT, FUND).status());
        var other = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "local-two");
        for (var account : List.of(ACCOUNT, other))
        {
            var policy = provider.getPersistencePolicy(account);
            assertEquals(account, policy.account());
            assertEquals(Permission.UNVERIFIED, policy.permission());
            assertTrue(policy.maximumAge().isEmpty());
            assertEquals(StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.of(true)));
            assertEquals(StorageMode.SESSION_ONLY, policy.effectiveMode(Optional.empty()));
        }
        var wrong = new ProviderAccount("OTHER-PROVIDER", "local-one");
        assertThrows(IllegalArgumentException.class, () -> provider.getPersistencePolicy(wrong));
        assertThrows(IllegalArgumentException.class, () -> provider.getComposition(wrong, FUND));
    }

    @Test
    public void testPersistencePermittedOnlyForConfirmedAccount()
    {
        var other = new ProviderAccount(EulerpoolFundCompositionProvider.ID, "local-two");
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> {
                            throw new AssertionError("No request expected");
                        }, CLOCK, ACCOUNT::equals);

        var confirmed = provider.getPersistencePolicy(ACCOUNT);
        assertEquals(Permission.PERMITTED, confirmed.permission());
        assertTrue(confirmed.maximumAge().isEmpty());
        assertEquals(StorageMode.PERSISTENT, confirmed.effectiveMode(Optional.empty()));
        assertEquals(StorageMode.SESSION_ONLY, confirmed.effectiveMode(Optional.of(false)));

        var unconfirmed = provider.getPersistencePolicy(other);
        assertEquals(Permission.UNVERIFIED, unconfirmed.permission());
        assertEquals(StorageMode.SESSION_ONLY, unconfirmed.effectiveMode(Optional.of(true)));
    }

    private Optional<name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence> classify(CompositionInstrument instrument,
                    EulerpoolFundCompositionProvider.Transport transport) throws IOException
    {
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), transport, CLOCK);
        return provider.classify(ACCOUNT, instrument);
    }

    @Test
    public void testClassifyStockAsGroupedEquity() throws IOException
    {
        var instrument = new CompositionInstrument("APPLE", Map.of("ISIN", "SYNTHETIC100"));
        var evidence = classify(instrument, (uri, authorization) -> new Response(200,
                        "{\"count\":1,\"results\":[{\"name\":\"Apple\",\"isin\":\"SYNTHETIC100\","
                                        + "\"ticker\":\"APL\",\"type\":\"stock\",\"currency\":null}]}",
                        Optional.empty())).orElseThrow();
        assertEquals(AssetType.EQUITY, evidence.classification().orElseThrow().value());
        assertTrue(evidence.company().isPresent());
        assertEquals("SYNTHETIC100", evidence.company().orElseThrow().value().value());
    }

    @Test
    public void testClassifyEtfAsFundWithoutCompany() throws IOException
    {
        var instrument = new CompositionInstrument("Invented fund", Map.of("ISIN", "SYNTHETIC001"));
        var evidence = classify(instrument, (uri, authorization) -> new Response(200,
                        "{\"count\":1,\"results\":[{\"name\":\"Fund\",\"isin\":\"SYNTHETIC001\","
                                        + "\"ticker\":\"F.T\",\"type\":\"etf\",\"currency\":null}]}",
                        Optional.empty())).orElseThrow();
        assertEquals(AssetType.FUND, evidence.classification().orElseThrow().value());
        assertTrue(evidence.company().isEmpty());
    }

    @Test
    public void testClassifyBondThroughBondListFallback() throws IOException
    {
        var instrument = new CompositionInstrument("Invented bond", Map.of("ISIN", "SYNTHETICBND1"));
        var evidence = classify(instrument, (uri, authorization) -> {
            if (uri.getPath().equals("/api/1/equity/search"))
                return new Response(200, "{\"count\":0,\"results\":[]}", Optional.empty());
            return new Response(200, "{\"data\":[{\"isin\":\"SYNTHETICBND1\",\"figi\":null,"
                            + "\"description\":\"Bond\",\"exchange\":null}],\"total\":1,\"offset\":0,\"limit\":50}",
                            Optional.empty());
        }).orElseThrow();
        assertEquals(AssetType.OTHER_NON_EQUITY, evidence.classification().orElseThrow().value());
        assertTrue(evidence.company().isEmpty());
    }

    @Test
    public void testClassifyUnknownReturnsEmpty() throws IOException
    {
        var instrument = new CompositionInstrument("Invented unknown", Map.of("ISIN", "SYNTHETICUNK1"));
        var evidence = classify(instrument, (uri, authorization) -> new Response(200,
                        "{\"count\":0,\"results\":[]}", Optional.empty()));
        assertTrue(evidence.isEmpty());
    }

    @Test
    public void testClassificationDoesNotHideTransportErrors() throws IOException
    {
        var instrument = new CompositionInstrument("Invented", Map.of("ISIN", "SYNTHETICERR1"));
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> {
                            throw new IOException("transport");
                        }, CLOCK);
        assertThrows(IOException.class, () -> provider.classify(ACCOUNT, instrument));
        assertTrue(provider.classify(ACCOUNT, new CompositionInstrument("No id", Map.of())).isEmpty());
    }

    @Test
    public void testProfileFallbackRequiresMatchingIdentity() throws IOException
    {
        var instrument = new CompositionInstrument("Synthetic issuer", Map.of("ISIN", "SYNTHETIC100"));
        for (var returned : List.of("SYNTHETIC100", "DIFFERENT"))
        {
            var evidence = classify(instrument, (uri, authorization) -> {
                if (uri.getPath().contains("/profile/"))
                    return new Response(200, "{\"isin\":\"" + returned
                                    + "\",\"sector\":\"Industrials\",\"industry\":\"Services\"}", Optional.empty());
                return new Response(200, "{\"results\":[],\"data\":[]}", Optional.empty());
            });
            assertEquals(returned.equals("SYNTHETIC100"), evidence.isPresent());
        }
    }

    @Test
    public void testDifferentIsinIsNotAcceptedFromSingleOrTickerMatch() throws IOException
    {
        var instrument = new CompositionInstrument("Synthetic wanted", Map.of("ISIN", "SYNTHETIC100",
                        EulerpoolFundCompositionProvider.SYMBOL, "SYN"));
        var evidence = classify(instrument, (uri, authorization) -> {
            if (uri.getPath().endsWith("/search"))
                return new Response(200, "{\"results\":[{\"isin\":\"DIFFERENT\",\"ticker\":\"SYN\","
                                + "\"type\":\"stock\"}]}", Optional.empty());
            return new Response(404, "", Optional.empty());
        });
        assertTrue(evidence.isEmpty());
    }

    @Test
    public void testClassificationFailureStopsFallbackRequests() throws IOException
    {
        var instrument = new CompositionInstrument("Synthetic", Map.of("ISIN", "SYNTHETIC100"));
        for (int status : new int[] { 401, 403, 429 })
        {
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure.class,
                            () -> classify(instrument, (uri, authorization) -> {
                                requests.incrementAndGet();
                                return new Response(status, "", Optional.of("60"));
                            }));
            assertEquals(1, requests.get());
        }
    }

    @Test
    public void testClassificationRequestCostBoundsFallbacks() throws IOException
    {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var instrument = new CompositionInstrument("Synthetic", Map.of("ISIN", "SYNTHETIC100",
                        EulerpoolFundCompositionProvider.SYMBOL, "SYN"));
        assertTrue(classify(instrument, (uri, authorization) -> {
            requests.incrementAndGet();
            return new Response(404, "", Optional.empty());
        }).isEmpty());
        assertEquals(EulerpoolFundCompositionProvider.CLASSIFICATION_REQUEST_COST, requests.get());
    }

    private static name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentCache profileCache()
    {
        var cache = new name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentCache(null, CLOCK);
        cache.configure(new name.abuchen.portfolio.snapshot.lookthrough.CompositionPersistencePolicy(ACCOUNT,
                        Permission.UNVERIFIED, Optional.empty()), Optional.empty());
        return cache;
    }

    @Test
    public void testConstituentIsClassifiedFromItsProfileInOneRequestAndSharesIt() throws IOException
    {
        var cache = profileCache();
        var paths = new ArrayList<String>();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), (uri, authorization) -> {
            paths.add(uri.getPath());
            if (uri.getPath().equals("/api/1/equity/profile/XX0000000017"))
                return new Response(200, "{\"isin\":\"XX0000000017\",\"country\":\"United States\","
                                + "\"sector\":\"Invented sector\",\"industry\":null}", Optional.empty());
            throw new AssertionError("Only the profile is needed");
        }, CLOCK);
        var instrument = new CompositionInstrument("Invented company", Map.of("ISIN", "XX0000000017"));
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var evidence = provider.classify(ACCOUNT, instrument, cache, true, requests).orElseThrow();
        assertEquals(AssetType.EQUITY, evidence.classification().orElseThrow().value());
        assertTrue(evidence.company().isPresent());
        assertEquals(1, requests.get());
        assertEquals(List.of("/api/1/equity/profile/XX0000000017"), paths);
        // Enrichment finds the profile in the shared cache.
        var shared = cache.profile(ACCOUNT, "XX0000000017").orElseThrow().value().orElseThrow();
        assertEquals(Optional.of("US"), shared.country());
        assertEquals(Optional.of("Invented sector"), shared.sector());

        // A known profile classifies without any request.
        var again = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(AssetType.EQUITY, provider.classify(ACCOUNT, instrument, cache, true, again).orElseThrow()
                        .classification().orElseThrow().value());
        assertEquals(0, again.get());
        assertEquals(1, paths.size());
    }

    @Test
    public void testProfileNotFoundIsSharedAndSearchDecides() throws IOException
    {
        var cache = profileCache();
        var paths = new ArrayList<String>();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), (uri, authorization) -> {
            paths.add(uri.getPath());
            if (uri.getPath().equals("/api/1/equity/search"))
                return new Response(200, "{\"results\":[{\"isin\":\"XX0000000025\",\"type\":\"etf\"}]}",
                                Optional.empty());
            return new Response(404, "", Optional.empty());
        }, CLOCK);
        var instrument = new CompositionInstrument("Invented fund", Map.of("ISIN", "XX0000000025"));
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var evidence = provider.classify(ACCOUNT, instrument, cache, true, requests).orElseThrow();
        assertEquals(AssetType.FUND, evidence.classification().orElseThrow().value());
        assertEquals(List.of("/api/1/equity/profile/XX0000000025", "/api/1/equity/search"), paths);
        assertEquals(2, requests.get());
        // "Not found" is an answer for enrichment too.
        assertTrue(cache.profile(ACCOUNT, "XX0000000025").orElseThrow().value().isEmpty());

        // Search first, as for held instruments: the known "not found" saves the profile request.
        paths.clear();
        var held = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(provider.classify(ACCOUNT, instrument, cache, false, held).isPresent());
        assertEquals(List.of("/api/1/equity/search"), paths);
        assertEquals(1, held.get());
    }

    @Test
    public void testSearchAnswerForOtherIsinOrMalformedBodyIsAnAnsweredLookup() throws IOException
    {
        var instrument = new CompositionInstrument("Invented", Map.of("ISIN", "SYNTHETIC0001"));
        assertTrue(classify(instrument, (uri, authorization) -> new Response(200,
                        "{\"count\":1,\"results\":[{\"isin\":\"SYNTHETIC0002\",\"type\":\"stock\"}]}",
                        Optional.empty())).isEmpty());
        assertTrue(classify(instrument, (uri, authorization) -> new Response(200, "<html>not json",
                        Optional.empty())).isEmpty());
    }

    @Test
    public void testClientErrorsOfOneInstrumentAreAnsweredLookups() throws IOException
    {
        var instrument = new CompositionInstrument("Invented", Map.of("ISIN", "SYNTHETIC0001"));
        for (var status : new int[] { 400, 404, 410, 422 })
            assertTrue(String.valueOf(status),
                            classify(instrument, (uri, authorization) -> new Response(status, "", Optional.empty()))
                                            .isEmpty());
    }

    @Test
    public void testServerErrorsAndTimeoutsAreNotAnswers() throws IOException
    {
        var instrument = new CompositionInstrument("Invented", Map.of("ISIN", "SYNTHETIC0001"));
        for (var status : new int[] { 408, 500, 502, 503 })
            assertThrows(String.valueOf(status), IOException.class, () -> classify(instrument,
                            (uri, authorization) -> new Response(status, "", Optional.empty())));
        // A later step failing is not an answer either.
        assertThrows(IOException.class, () -> classify(instrument, (uri, authorization) -> new Response(
                        uri.getPath().contains("bonds") ? 503 : 404, "", Optional.empty())));
    }

    @Test
    public void testInstrumentWithoutIsinIsOnlySearched() throws IOException
    {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(404, "", Optional.empty()), CLOCK);
        var instrument = new CompositionInstrument("Invented", Map.of(EulerpoolFundCompositionProvider.SYMBOL, "SYN"));
        assertTrue(provider.classify(ACCOUNT, instrument, profileCache(), true, requests).isEmpty());
        // Profile and bond list answer by ISIN only.
        assertEquals(1, requests.get());
    }

    @Test
    public void testSecondaryTickerDoesNotCreateConflictingAliasesAcrossFunds() throws IOException
    {
        var first = new CompositionInstrument("Synthetic first listing", Map.of("ISIN", "SYNTHETIC100",
                        EulerpoolFundCompositionProvider.SYMBOL, "SYN.FIRST"));
        var second = new CompositionInstrument("Synthetic second listing", Map.of("ISIN", "SYNTHETIC100",
                        EulerpoolFundCompositionProvider.SYMBOL, "SYN.SECOND"));
        var evidence = classify(first, (uri, authorization) -> new Response(200,
                        "{\"results\":[{\"isin\":\"SYNTHETIC100\",\"ticker\":\"SYN\",\"type\":\"stock\"}]}",
                        Optional.empty())).orElseThrow();
        assertEquals(Map.of("ISIN", "SYNTHETIC100"), evidence.identifiers());
        var resolved = new name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolver().resolve(second,
                        List.of(evidence), ignored -> List.of(), () -> false);
        assertEquals(name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.State.GROUPED_EQUITY,
                        resolved.state());
    }

    @Test
    public void testLegitimateDuplicateRowsAreKept()
    {
        var fund = new CompositionInstrument("Synthetic fund", Map.of("ISIN", "SYNTHETIC-DUP"));
        var result = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(200, "[" //
                        + "{\"isin\":\"SYNTHETIC-DUP1\",\"name\":\"Same instrument\","
                        + "\"percent\":30.0,\"share\":1000,\"value\":300,\"updated_at\":\"2026-01-01T00:00:00Z\"},"
                        + "{\"isin\":\"SYNTHETIC-DUP1\",\"name\":\"Same instrument\","
                        + "\"percent\":70.0,\"share\":2000,\"value\":700,\"updated_at\":\"2026-01-01T00:00:00Z\"}]",
                        Optional.empty()), CLOCK).getComposition(ACCOUNT, fund);
        assertEquals(CompositionResult.Status.RECEIVED, result.status());
        assertEquals(2, result.composition().orElseThrow().constituents().size());
    }

    @Test
    public void testRepeatedSecurityWithSameNominalAndDifferentWeightIsMalformed()
    {
        var fund = new CompositionInstrument("Synthetic fund", Map.of("ISIN", "SYNTHETIC-DUP"));
        var result = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(200, "[" //
                        + "{\"isin\":\"SYNTHETIC-DUP1\",\"name\":\"Same instrument\","
                        + "\"percent\":60.0,\"share\":1000,\"value\":600,\"updated_at\":\"2026-01-01T00:00:00Z\"},"
                        + "{\"isin\":\"SYNTHETIC-DUP1\",\"name\":\"Same instrument\","
                        + "\"percent\":40.0,\"share\":1000,\"value\":400,\"updated_at\":\"2026-01-01T00:00:00Z\"}]",
                        Optional.empty()), CLOCK).getComposition(ACCOUNT, fund);
        assertEquals(CompositionResult.Status.MALFORMED_RESPONSE, result.status());
        assertTrue(result.composition().isEmpty());
    }

    @Test
    public void testCompanyProfileFieldsAreParsedStrictly() throws IOException
    {
        var calls = new ArrayList<URI>();
        var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), (uri, authorization) -> {
            calls.add(uri);
            return new Response(200, """
                            {"isin":"XX0000000017","ticker":"SYN","name":"Invented company","logo":null,
                             "country":"United States","currency":"USD","employees":12.5,"sector":"Invented sector",
                             "industry":"Invented industry","ipo":"2001-01-01","mcap":1234.5,"shares":10.0,
                             "website":null,"wkn":null,"description":"Invented"}
                            """, Optional.empty());
        }, CLOCK);
        var profile = provider.profile(ACCOUNT, "XX0000000017").orElseThrow();
        assertEquals("/api/1/equity/profile/XX0000000017", calls.get(0).getPath());
        assertEquals(Optional.of("US"), profile.country());
        assertEquals(Optional.of("USD"), profile.currency());
        assertEquals(Optional.of("Invented sector"), profile.sector());
        assertEquals(Optional.of("Invented industry"), profile.industry());
        assertEquals(Optional.of(new java.math.BigDecimal("1234.5")), profile.marketCapMillions());
        assertEquals(NOW, profile.retrievedAt());
    }

    @Test
    public void testCompanyProfileUnmappedCountryNotFoundAndMalformedAnswers() throws IOException
    {
        var unmapped = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(200,
                                        "{\"isin\":\"XX0000000017\",\"country\":\"Invented Land\",\"mcap\":null}",
                                        Optional.empty()),
                        CLOCK).profile(ACCOUNT, "XX0000000017").orElseThrow();
        assertTrue(unmapped.country().isEmpty());
        assertTrue(unmapped.marketCapMillions().isEmpty());

        assertTrue(new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(404, null, Optional.empty()), CLOCK)
                                        .profile(ACCOUNT, "XX0000000017").isEmpty());
        for (var body : List.of("{\"isin\":\"XX0000000025\",\"sector\":\"Other\"}", "{\"isin\":1}",
                        "{\"isin\":\"XX0000000017\"} trailing", "[]", ""))
        {
            var provider = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                            (uri, authorization) -> new Response(200, body, Optional.empty()), CLOCK);
            assertThrows(IOException.class, () -> provider.profile(ACCOUNT, "XX0000000017"));
        }
        var limited = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(429, null, Optional.of("60")), CLOCK);
        var failure = assertThrows(
                        name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure.class,
                        () -> limited.profile(ACCOUNT, "XX0000000017"));
        assertEquals(Status.RATE_LIMITED, failure.result().status());
        assertThrows(IllegalArgumentException.class, () -> limited.profile(ACCOUNT, "XX/../other"));
    }

    private static EulerpoolFundCompositionProvider regions(String body, List<URI> calls)
    {
        return new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN), (uri, authorization) -> {
            calls.add(uri);
            return new Response(200, body, Optional.empty());
        }, CLOCK);
    }

    @Test
    public void testRevenueRegionsUseLatestPeriodAndLargestGeographicalAxis() throws IOException
    {
        var calls = new ArrayList<URI>();
        var split = regions("""
                        [{"period":"2023-06-30","unit":"u_usd","axis":"srt_StatementGeographicalAxis","label":"Old","value":1,"percentage":100,"member":"country_FR","symbol":"SYN"},
                         {"period":"2024-06-30","unit":"u_usd","axis":"srt_ProductOrServiceAxis","label":"Product","value":1,"percentage":100,"member":"x_Product","symbol":"SYN"},
                         {"period":"2024-06-30","unit":"u_usd","axis":"srt_StatementGeographicalAxis","label":"United States","value":6,"percentage":60,"member":"country_US","symbol":"SYN"},
                         {"period":"2024-06-30","unit":"u_usd","axis":"srt_StatementGeographicalAxis","label":"Europe","value":3,"percentage":30,"member":"srt_EuropeMember","symbol":"SYN"},
                         {"period":"2024-06-30","unit":"u_usd","axis":"srt_StatementGeographicalAxis","label":"Other","value":1,"percentage":10,"member":"","symbol":"SYN"},
                         {"period":"2024-06-30","unit":"u_usd","axis":"x_OtherGeographicalAxis","label":"Asia","value":10,"percentage":100,"member":"x_AsiaMember","symbol":"SYN"}]
                        """, calls).revenueRegions(ACCOUNT, "XX0000000017").orElseThrow();
        assertEquals("/api/1/equity/regions/XX0000000017", calls.get(0).getPath());
        assertEquals(java.time.LocalDate.parse("2024-06-30"), split.period());
        assertEquals(3, split.shares().size());
        assertEquals(0, new java.math.BigDecimal("60").compareTo(split.shares().get(0).percent()));
        assertEquals("country:US", split.shares().get(0).member().key());
        assertFalse(split.incomplete());
        assertEquals(NOW, split.retrievedAt());
    }

    @Test
    public void testRevenueRegionsNotFoundEmptyAndMalformedAnswers() throws IOException
    {
        assertTrue(new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(404, null, Optional.empty()), CLOCK)
                                        .revenueRegions(ACCOUNT, "XX0000000017").isEmpty());
        assertTrue(regions("[]", new ArrayList<>()).revenueRegions(ACCOUNT, "XX0000000017").isEmpty());
        // Only non-geographical axes: no revenue by region.
        assertTrue(regions("[{\"period\":\"2024-06-30\",\"axis\":\"srt_ProductOrServiceAxis\",\"percentage\":100}]",
                        new ArrayList<>()).revenueRegions(ACCOUNT, "XX0000000017").isEmpty());
        for (var body : List.of("{}", "[1]", "[{\"axis\":\"geo\"}]", "[{\"period\":\"soon\",\"axis\":\"geo\"}]",
                        "[{\"period\":\"2024-06-30\",\"axis\":\"geographical\",\"percentage\":\"7\"}]", "[] trailing",
                        ""))
        {
            var provider = regions(body, new ArrayList<>());
            assertThrows(IOException.class, () -> provider.revenueRegions(ACCOUNT, "XX0000000017"));
        }
        var limited = new EulerpoolFundCompositionProvider(account -> Optional.of(TOKEN),
                        (uri, authorization) -> new Response(429, null, Optional.of("60")), CLOCK);
        var failure = assertThrows(
                        name.abuchen.portfolio.snapshot.lookthrough.CompositionRefreshCoordinator.EnrichmentFailure.class,
                        () -> limited.revenueRegions(ACCOUNT, "XX0000000017"));
        assertEquals(Status.RATE_LIMITED, failure.result().status());
        assertThrows(IllegalArgumentException.class, () -> limited.revenueRegions(ACCOUNT, "XX/../other"));
    }
}
