package name.abuchen.portfolio.online.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.online.impl.GleifCompanyRegister.Response;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService.RegisterRateLimited;
import name.abuchen.portfolio.snapshot.lookthrough.RegisterRecord;

/** All payloads, ISINs and LEIs here are invented, not copied register data. */
@SuppressWarnings("nls")
public class GleifCompanyRegisterTest
{
    private static final Instant NOW = Instant.parse("2026-01-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FIRST = "KY0000000001";
    private static final String SECOND = "XX0000000002";
    private static final String LEI = "SYNTHETICLEI00000001";

    private final List<URI> calls = new ArrayList<>();
    private final ArrayDeque<Response> answers = new ArrayDeque<>();
    private int pauses;

    private GleifCompanyRegister register()
    {
        return new GleifCompanyRegister(uri -> {
            calls.add(uri);
            return answers.removeFirst();
        }, () -> pauses++, CLOCK);
    }

    private static String record(String lei, String headquarters, String legal)
    {
        return """
                        {"type":"lei-records","id":"%1$s","attributes":{"lei":"%1$s",
                         "entity":{"legalName":{"name":"Invented Holding"},
                         "legalAddress":{"country":%3$s},"headquartersAddress":{"country":%2$s}}}}
                        """.formatted(lei, headquarters == null ? "null" : "\"" + headquarters + "\"",
                        legal == null ? "null" : "\"" + legal + "\"");
    }

    private static Response page(int total, String... records)
    {
        return new Response(200, "{\"meta\":{\"pagination\":{\"total\":" + total + "}},\"data\":["
                        + String.join(",", records) + "]}", Optional.empty());
    }

    @Test
    public void testSingleIsinIsAttributedWithHeadquartersAndLegalCountry() throws IOException
    {
        answers.add(page(1, record(LEI, "HK", "KY")));
        var result = register().lookup(List.of(FIRST));
        assertEquals(Map.of(FIRST, Optional.of(new RegisterRecord(FIRST, LEI, Optional.of("HK"), Optional.of("KY"),
                        NOW))), result);
        var uri = calls.get(0);
        assertEquals("https", uri.getScheme());
        assertEquals("api.gleif.org", uri.getHost());
        assertEquals("/api/v1/lei-records", uri.getPath());
        assertEquals("filter[isin]=" + FIRST + "&page[size]=200", uri.getQuery());
        assertEquals(0, pauses);
    }

    @Test
    public void testEmptyBatchIsDefinitiveForAllIsins() throws IOException
    {
        answers.add(page(0));
        var result = register().lookup(List.of(FIRST, SECOND));
        assertEquals(Map.of(FIRST, Optional.empty(), SECOND, Optional.empty()), result);
        assertEquals(1, calls.size());
        assertEquals("filter[isin]=" + FIRST + "," + SECOND + "&page[size]=200", calls.get(0).getQuery());
    }

    @Test
    public void testBatchWithRecordsFallsBackToSingleLookupsAndAmbiguityIsNoRecord() throws IOException
    {
        var third = "XX0000000003";
        answers.add(page(2, record(LEI, "HK", "KY"), record("SYNTHETICLEI00000002", "DE", "DE")));
        answers.add(page(1, record(LEI, "HK", "KY")));
        answers.add(page(0));
        answers.add(page(2, record(LEI, "HK", "KY"), record("SYNTHETICLEI00000002", "DE", "DE")));
        var result = register().lookup(List.of(FIRST, SECOND, third));
        // The batch records cannot be attributed, so the ISINs were asked one by one.
        assertEquals(Optional.of("HK"), result.get(FIRST).orElseThrow().headquartersCountry());
        assertEquals(Optional.empty(), result.get(SECOND));
        assertEquals(Optional.empty(), result.get(third));
        assertEquals(4, calls.size());
        assertEquals("filter[isin]=" + SECOND + "&page[size]=200", calls.get(2).getQuery());
        // Every request after the first is paced.
        assertEquals(3, pauses);
    }

    @Test
    public void testUnassignedCountryIsUnknownAndMalformedAnswersFail() throws IOException
    {
        answers.add(page(1, record(LEI, "XX", null)));
        var record = register().lookup(List.of(FIRST)).get(FIRST).orElseThrow();
        assertTrue(record.headquartersCountry().isEmpty());
        assertTrue(record.legalCountry().isEmpty());

        for (var body : List.of("{\"data\":[]}", "{\"meta\":{\"pagination\":{\"total\":0}},\"data\":{}}",
                        "{\"meta\":{\"pagination\":{\"total\":1}},\"data\":[{\"attributes\":{\"lei\":\"short\"}}]}",
                        "{\"meta\":{\"pagination\":{\"total\":0}},\"data\":[]} trailing", "", "[]"))
        {
            answers.add(new Response(200, body, Optional.empty()));
            assertThrows(IOException.class, () -> register().lookup(List.of(FIRST)));
        }
        answers.add(new Response(500, null, Optional.empty()));
        assertThrows(IOException.class, () -> register().lookup(List.of(FIRST)));
        assertThrows(IllegalArgumentException.class, () -> register().lookup(List.of("not-an-isin")));
    }

    @Test
    public void testRateLimitCarriesRetryTime()
    {
        answers.add(new Response(429, null, Optional.of("30")));
        var limited = assertThrows(RegisterRateLimited.class, () -> register().lookup(List.of(FIRST)));
        assertEquals(Optional.of(NOW.plusSeconds(30)), limited.retryAt());
    }
}
