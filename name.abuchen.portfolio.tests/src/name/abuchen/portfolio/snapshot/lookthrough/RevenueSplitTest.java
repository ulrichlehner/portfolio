package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.RevenueSplit.Entry;
import name.abuchen.portfolio.snapshot.lookthrough.RevenueSplit.Kind;
import name.abuchen.portfolio.snapshot.lookthrough.RevenueSplit.Member;

/** Identifiers and values are invented. */
@SuppressWarnings("nls")
public class RevenueSplitTest
{
    private static final LocalDate PERIOD = LocalDate.parse("2025-06-30");
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private static Entry entry(String label, String member, String percentage)
    {
        return new Entry(label, member, new BigDecimal(percentage));
    }

    private static RevenueSplit split(Entry... entries)
    {
        return RevenueSplit.of("XX0000000017", PERIOD, List.of(entries), NOW).orElseThrow();
    }

    private static final Member US = new Member(Kind.COUNTRY, "US");
    private static final Member EUROPE = new Member(Kind.REGION, "R10");

    @Test
    public void testCountryRegionAndRestOfWorldMembers()
    {
        var split = split(entry("United States", "country_US", "60"), entry("Europe", "srt_EuropeMember", "25"),
                        entry("Rest of World", "", "15"));
        assertEquals(3, split.shares().size());
        assertEquals(0, new BigDecimal("60").compareTo(split.percentOf(US)));
        assertEquals(0, new BigDecimal("25").compareTo(split.percentOf(EUROPE)));
        assertEquals(0, new BigDecimal("15").compareTo(split.percentOf(Member.OTHER)));
        assertFalse(split.incomplete());
        assertEquals(PERIOD, split.period());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(split.reportedSum()));
    }

    @Test
    public void testRegionTableAndCountryNamesWithoutMember()
    {
        var split = split(entry("Americas", "srt_AmericasMember", "20"), entry("Asia Pacific", "", "20"),
                        entry("EMEA", "", "20"), entry("Germany", "", "20"), entry("Invented Land", "x_Foo", "20"));
        assertEquals(0, new BigDecimal("20").compareTo(split.percentOf(new Member(Kind.REGION, "R20"))));
        assertEquals(0, new BigDecimal("20").compareTo(split.percentOf(new Member(Kind.REGION, RevenueSplit.APAC))));
        assertEquals(0, new BigDecimal("20").compareTo(split.percentOf(new Member(Kind.REGION, RevenueSplit.EMEA))));
        assertEquals(0, new BigDecimal("20").compareTo(split.percentOf(new Member(Kind.COUNTRY, "DE"))));
        assertEquals(0, new BigDecimal("20").compareTo(split.percentOf(Member.OTHER)));
    }

    @Test
    public void testIncompleteSplitIsScaledAndKeepsReportedSum()
    {
        var split = split(entry("United States", "country_US", "54"), entry("Europe", "srt_EuropeMember", "36"));
        assertTrue(split.incomplete());
        assertEquals(0, new BigDecimal("90").compareTo(split.reportedSum()));
        assertEquals(0, new BigDecimal("60").compareTo(split.percentOf(US)));
        assertEquals(0, new BigDecimal("40").compareTo(split.percentOf(EUROPE)));
        assertEquals(0, BigDecimal.valueOf(100)
                        .compareTo(split.shares().stream().map(RevenueSplit.Share::percent)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test
    public void testScaledSharesAddUpToExactlyOneHundred()
    {
        var split = split(entry("a", "country_US", "1"), entry("b", "country_DE", "1"), entry("c", "country_FR", "1"));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(split.shares().stream().map(RevenueSplit.Share::percent)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertTrue(split.incomplete());
    }

    @Test
    public void testDuplicatesMergeAndNonPositiveEntriesAreIgnored()
    {
        var split = split(entry("Other", "", "10"), entry("Unallocated", "", "5"), entry("United States", "country_US", "85"),
                        entry("Eliminations", "", "-20"), new Entry("No value", "", null));
        assertEquals(2, split.shares().size());
        assertEquals(0, new BigDecimal("15").compareTo(split.percentOf(Member.OTHER)));
        assertFalse(split.incomplete());
        assertEquals(US, split.shares().get(0).member());
    }

    @Test
    public void testFractionsAreTreatedAsPercentages()
    {
        var split = split(entry("United States", "country_US", "0.7"), entry("Europe", "srt_EuropeMember", "0.3"));
        assertEquals(0, new BigDecimal("70").compareTo(split.percentOf(US)));
        assertFalse(split.incomplete());
    }

    @Test
    public void testNothingUsableIsNoSplit()
    {
        assertTrue(RevenueSplit.of("XX0000000017", PERIOD, List.of(entry("x", "", "0"), entry("y", "", "-5")), NOW)
                        .isEmpty());
        assertTrue(RevenueSplit.of("XX0000000017", PERIOD, List.of(), NOW).isEmpty());
    }

    @Test
    public void testMemberKeyRoundTrip()
    {
        for (var member : List.of(US, EUROPE, Member.OTHER))
            assertEquals(member, Member.parse(member.key()).orElseThrow());
        assertTrue(Member.parse("planet:Mars").isEmpty());
        assertTrue(Member.parse("nonsense").isEmpty());
    }
}
