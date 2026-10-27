package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.CompanyAttributes.Source;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;

/** Identifiers and values are invented. */
@SuppressWarnings("nls")
public class CompanyAttributesTest
{
    private static final Instant PROFILE_DATE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REGISTER_DATE = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-03T00:00:00Z");
    private static final Identifier COMPANY = new Identifier(InstrumentEvidence.ISIN, "KY0000000001");

    private static CompanyProfile profile(String country)
    {
        return new CompanyProfile(COMPANY.value(), Optional.ofNullable(country), Optional.of("HKD"),
                        Optional.of("Invented sector"), Optional.of("Invented industry"),
                        Optional.of(new BigDecimal("250.5")), PROFILE_DATE);
    }

    private static RegisterRecord register(String headquarters)
    {
        return new RegisterRecord(COMPANY.value(), "SYNTHETICLEI00000001", Optional.ofNullable(headquarters),
                        Optional.of("KY"), REGISTER_DATE);
    }

    @Test
    public void testCountryNamesMapToIsoCodesAndUnmappedNamesAreUnknown()
    {
        assertEquals(Optional.of("US"), CompanyAttributes.countryOfName("United States"));
        assertEquals(Optional.of("DE"), CompanyAttributes.countryOfName(" germany "));
        assertEquals(Optional.of("CH"), CompanyAttributes.countryOfName("Switzerland"));
        assertEquals(Optional.of("HK"), CompanyAttributes.countryOfName("Hong Kong"));
        assertEquals(Optional.of("KR"), CompanyAttributes.countryOfName("South Korea"));
        assertTrue(CompanyAttributes.countryOfName("Invented Land").isEmpty());
        assertTrue(CompanyAttributes.countryOfName("").isEmpty());
        assertTrue(CompanyAttributes.countryOfName(null).isEmpty());
    }

    @Test
    public void testIsinPrefixGivesCountryOnlyForAssignedCodes()
    {
        assertEquals(Optional.of("KY"), CompanyAttributes.countryOfIsin("KY0000000001"));
        assertTrue(CompanyAttributes.countryOfIsin("XS0000000001").isEmpty());
        assertTrue(CompanyAttributes.countryOfIsin("X").isEmpty());
    }

    @Test
    public void testRegisterCountryWinsOverProfileAndIsin()
    {
        var attributes = CompanyAttributes.resolve(COMPANY, Optional.of(profile("US")), Optional.of(register("HK")),
                        NOW);
        var country = attributes.country().orElseThrow();
        assertEquals("HK", country.value());
        assertEquals(Source.REGISTER, country.source());
        assertEquals(REGISTER_DATE, country.retrievedAt());
        // All other attributes come from the profile with its date.
        assertEquals(new CompanyAttributes.Attribute<>("Invented sector", Source.PROVIDER, PROFILE_DATE),
                        attributes.sector().orElseThrow());
        assertEquals("Invented industry", attributes.industry().orElseThrow().value());
        assertEquals("HKD", attributes.currency().orElseThrow().value());
        assertEquals(new BigDecimal("250.5"), attributes.marketCapMillions().orElseThrow().value());
    }

    @Test
    public void testProfileCountryIsUsedWithoutRegisterHeadquarters()
    {
        var attributes = CompanyAttributes.resolve(COMPANY, Optional.of(profile("US")), Optional.of(register(null)),
                        NOW);
        assertEquals(new CompanyAttributes.Attribute<>("US", Source.PROVIDER, PROFILE_DATE),
                        attributes.country().orElseThrow());
    }

    @Test
    public void testIsinPrefixIsTheLastResortAndOtherAttributesStayUnknown()
    {
        var attributes = CompanyAttributes.resolve(COMPANY, Optional.of(profile(null)), Optional.empty(), NOW);
        assertEquals(new CompanyAttributes.Attribute<>("KY", Source.ISIN_PREFIX, NOW),
                        attributes.country().orElseThrow());

        var unknown = CompanyAttributes.resolve(COMPANY, Optional.empty(), Optional.empty(), NOW);
        assertEquals(Source.ISIN_PREFIX, unknown.country().orElseThrow().source());
        assertTrue(unknown.currency().isEmpty());
        assertTrue(unknown.sector().isEmpty());
        assertTrue(unknown.industry().isEmpty());
        assertTrue(unknown.marketCapMillions().isEmpty());

        var other = CompanyAttributes.resolve(new Identifier("synthetic:id", "KY-like"), Optional.empty(),
                        Optional.empty(), NOW);
        assertTrue(other.country().isEmpty());
    }

    @Test
    public void testInvalidCodesAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> new CompanyProfile("KY0000000001", Optional.of("XX"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new CompanyProfile("KY0000000001", Optional.empty(),
                        Optional.of("usd"), Optional.empty(), Optional.empty(), Optional.empty(), NOW));
        assertThrows(IllegalArgumentException.class,
                        () -> new RegisterRecord("KY0000000001", "short", Optional.empty(), Optional.empty(), NOW));
    }
}
