package name.abuchen.portfolio.snapshot.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Provenance;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.LookupStatus;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.State;

public class InstrumentResolverTest
{
    private final InstrumentResolver resolver = new InstrumentResolver();
    private final Provenance provenance = new Provenance(new ProviderAccount("synthetic", "test"), "record",
                    Instant.parse("2026-01-02T12:00:00Z"));

    private CompositionInstrument instrument(String id)
    {
        return new CompositionInstrument("Ambiguous synthetic name", Map.of("synthetic:instrument", id));
    }

    private InstrumentEvidence evidence(CompositionInstrument source, String identity, AssetType type, String company)
    {
        return new InstrumentEvidence(source.identifiers(),
                        new Assertion<>(new Identifier("synthetic:instrument", identity), provenance),
                        Optional.ofNullable(type).map(value -> new Assertion<>(value, provenance)),
                        Optional.ofNullable(company).map(value -> new Assertion<>(
                                        new Identifier("synthetic:company", value), provenance)));
    }

    private InstrumentResolution resolve(CompositionInstrument source, InstrumentEvidence... evidence)
    {
        return resolver.resolve(source, List.of(evidence), ignored -> {
            throw new AssertionError("No lookup permitted");
        }, () -> false);
    }

    @Test
    public void testSameNameDoesNotMatchDifferentInstruments()
    {
        var first = instrument("first");
        var second = instrument("second");
        var result = resolve(first, evidence(second, "second", AssetType.EQUITY, "company"));

        assertEquals(first.label(), second.label());
        assertEquals(State.UNRESOLVED, result.state());
        assertEquals(Reason.MISSING_IDENTITY, result.reason());
        assertEquals(LookupStatus.BUDGET_EXHAUSTED, result.lookupStatus());
        assertSame(first, result.source());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    public void testMissingIdentifiersNeverUseLabelLookup()
    {
        var source = new CompositionInstrument("Ambiguous synthetic name", Map.of());
        var result = resolver.resolve(source, List.of(evidence(instrument("first"), "first", AssetType.EQUITY, null)),
                        ignored -> {
                            throw new AssertionError("Names cannot establish identity");
                        }, () -> {
                            throw new AssertionError("No budget needed without lookup identifiers");
                        });

        assertEquals(State.UNRESOLVED, result.state());
        assertEquals(Reason.MISSING_IDENTIFIERS, result.reason());
        assertEquals(LookupStatus.NO_IDENTIFIERS, result.lookupStatus());
        assertSame(source, result.source());
    }

    @Test
    public void testBlankIdentifiersRemainMissing()
    {
        var result = resolve(new CompositionInstrument("Synthetic", Map.of("ISIN", " ")));
        assertEquals(Reason.MISSING_IDENTIFIERS, result.reason());
    }

    @Test
    public void testIdentifiersAreScopedByNamespace()
    {
        var source = instrument("first");
        var differentProvider = new CompositionInstrument(source.label(), Map.of("other:instrument", "first"));
        var result = resolve(source, evidence(differentProvider, "first", AssetType.EQUITY, "issuer"));
        assertEquals(Reason.MISSING_IDENTITY, result.reason());
    }

    @Test
    public void testIsinAndCompanyDoNotProveEquity()
    {
        var source = new CompositionInstrument("Synthetic identifier only", Map.of("ISIN", "SYNTHETIC001"));
        var result = resolve(source, evidence(source, "first", null, "company"));

        assertEquals(State.UNRESOLVED, result.state());
        assertEquals(Reason.UNKNOWN_CLASSIFICATION, result.reason());
        assertTrue(result.instrument().isPresent());
        assertTrue(result.classification().isEmpty());
        assertTrue(result.company().isEmpty());
    }

    @Test
    public void testKnownEquityWithoutCompanyIsUngrouped()
    {
        var source = instrument("first");
        var result = resolve(source, evidence(source, "first", AssetType.EQUITY, null));

        assertEquals(State.UNGROUPED_EQUITY, result.state());
        assertEquals(Reason.MISSING_COMPANY, result.reason());
        assertEquals(Optional.of(AssetType.EQUITY), result.classification());
        assertTrue(result.instrument().isPresent());
        assertTrue(result.company().isEmpty());
    }

    @Test
    public void testShareClassesRemainDistinctUntilExplicitCompanyEvidence()
    {
        var first = instrument("class-a");
        var second = instrument("class-b");
        var ungrouped = resolve(second, evidence(second, "class-b", AssetType.EQUITY, null),
                        evidence(first, "class-a", AssetType.EQUITY, "issuer"));
        assertEquals(State.UNGROUPED_EQUITY, ungrouped.state());

        var firstGrouped = resolve(first, evidence(first, "class-a", AssetType.EQUITY, "issuer"));
        var secondGrouped = resolve(second, evidence(second, "class-b", AssetType.EQUITY, "issuer"));
        assertEquals(State.GROUPED_EQUITY, firstGrouped.state());
        assertEquals(State.GROUPED_EQUITY, secondGrouped.state());
        assertNotEquals(firstGrouped.instrument(), secondGrouped.instrument());
        assertEquals(firstGrouped.company(), secondGrouped.company());
    }

    @Test
    public void testDepositaryReceiptNeedsExplicitUnderlyingCompanyEvidence()
    {
        var receipt = instrument("receipt");
        var share = instrument("ordinary-share");
        var ordinary = evidence(share, "ordinary-share", AssetType.EQUITY, "underlying-issuer");
        var ungrouped = resolve(receipt, ordinary,
                        evidence(receipt, "receipt", AssetType.DEPOSITARY_RECEIPT, null));
        assertEquals(State.UNGROUPED_EQUITY, ungrouped.state());
        assertEquals(Optional.of(AssetType.DEPOSITARY_RECEIPT), ungrouped.classification());

        var grouped = resolve(receipt,
                        evidence(receipt, "receipt", AssetType.DEPOSITARY_RECEIPT, "underlying-issuer"));
        var direct = resolve(share, ordinary);
        assertEquals(State.GROUPED_EQUITY, grouped.state());
        assertNotEquals(direct.instrument(), grouped.instrument());
        assertEquals(direct.company(), grouped.company());
    }

    @Test
    public void testAmbiguousIdentityDoesNotChooseFirstCandidate()
    {
        var source = instrument("alias");
        var result = resolve(source, evidence(source, "first", AssetType.EQUITY, "issuer"),
                        evidence(source, "second", AssetType.EQUITY, "issuer"));

        assertEquals(State.UNRESOLVED, result.state());
        assertEquals(Reason.AMBIGUOUS_IDENTITY, result.reason());
        assertEquals(2, result.evidence().size());
        assertTrue(result.instrument().isEmpty());
        assertEquals(LookupStatus.NOT_NEEDED, result.lookupStatus());
    }

    @Test
    public void testConflictingAliasCannotBeIgnored()
    {
        var source = new CompositionInstrument("Synthetic", Map.of("synthetic:instrument", "alias", "ISIN", "first"));
        var conflicting = new InstrumentEvidence(Map.of("synthetic:instrument", "alias", "ISIN", "second"),
                        new Assertion<>(new Identifier("synthetic:instrument", "alias"), provenance),
                        Optional.of(new Assertion<>(AssetType.EQUITY, provenance)), Optional.empty());

        assertEquals(Reason.AMBIGUOUS_IDENTITY, resolve(source, conflicting).reason());
    }

    @Test
    public void testConflictingClassificationIsUnresolved()
    {
        var source = instrument("first");
        var result = resolve(source, evidence(source, "first", AssetType.EQUITY, "issuer"),
                        evidence(source, "first", AssetType.FUND, null));

        assertEquals(State.UNRESOLVED, result.state());
        assertEquals(Reason.AMBIGUOUS_CLASSIFICATION, result.reason());
        assertTrue(result.company().isEmpty());
    }

    @Test
    public void testConflictingCompaniesLeaveKnownEquityUngrouped()
    {
        var source = instrument("first");
        var result = resolve(source, evidence(source, "first", AssetType.EQUITY, "first-issuer"),
                        evidence(source, "first", AssetType.EQUITY, "second-issuer"));

        assertEquals(State.UNGROUPED_EQUITY, result.state());
        assertEquals(Reason.AMBIGUOUS_COMPANY, result.reason());
        assertTrue(result.instrument().isPresent());
        assertTrue(result.company().isEmpty());
        assertEquals(2, result.evidence().size());
    }

    @Test
    public void testCashOtherAndNestedFundsAreDistinct()
    {
        for (var type : List.of(AssetType.CASH, AssetType.OTHER_NON_EQUITY, AssetType.FUND))
        {
            var source = instrument(type.name());
            var result = resolve(source, evidence(source, type.name(), type, "not-an-equity-company"));
            assertEquals(type == AssetType.FUND ? State.FUND : State.CASH_OR_OTHER, result.state());
            assertEquals(Optional.of(type), result.classification());
            assertTrue(result.company().isEmpty());
            assertEquals(LookupStatus.NOT_NEEDED, result.lookupStatus());
        }
    }

    @Test
    public void testOneBudgetAdmittedLookupCanResolveMissingIsinUsingProviderIdentifier()
    {
        var source = instrument("provider-row");
        var calls = new AtomicInteger();
        var permits = new AtomicInteger();
        var mapping = evidence(source, "verified-instrument", AssetType.EQUITY, "issuer");
        var result = resolver.resolve(source, List.of(), requested -> {
            assertSame(source, requested);
            calls.incrementAndGet();
            return List.of(mapping);
        }, () -> {
            permits.incrementAndGet();
            return true;
        });

        assertEquals(1, calls.get());
        assertEquals(1, permits.get());
        assertEquals(State.GROUPED_EQUITY, result.state());
        assertEquals(LookupStatus.ATTEMPTED, result.lookupStatus());
        assertEquals(List.of(mapping), result.evidence());
    }

    @Test
    public void testLookupCanEnrichClassificationAndCompanyWithIndependentProvenance()
    {
        var source = instrument("first");
        var initial = evidence(source, "first", null, null);
        var classificationSource = new Provenance(new ProviderAccount("classifier", "test"), "classification",
                        provenance.retrievedAt());
        var companySource = new Provenance(new ProviderAccount("mapper", "test"), "company-mapping",
                        provenance.retrievedAt());
        var enrichment = new InstrumentEvidence(source.identifiers(), initial.identity(),
                        Optional.of(new Assertion<>(AssetType.EQUITY, classificationSource)),
                        Optional.of(new Assertion<>(new Identifier("synthetic:company", "issuer"), companySource)));
        var result = resolver.resolve(source, List.of(initial), ignored -> List.of(enrichment), () -> true);

        assertEquals(State.GROUPED_EQUITY, result.state());
        assertEquals(List.of(initial, enrichment), result.evidence());
        assertSame(classificationSource, result.evidence().get(1).classification().orElseThrow().provenance());
        assertSame(companySource, result.evidence().get(1).company().orElseThrow().provenance());
    }

    @Test
    public void testEmptyLookupDoesNotRetry()
    {
        var calls = new AtomicInteger();
        var result = resolver.resolve(instrument("missing"), List.of(), ignored -> {
            calls.incrementAndGet();
            return List.of();
        }, () -> true);

        assertEquals(1, calls.get());
        assertEquals(State.UNRESOLVED, result.state());
        assertEquals(Reason.MISSING_IDENTITY, result.reason());
        assertEquals(LookupStatus.ATTEMPTED, result.lookupStatus());
    }

    @Test
    public void testFailedLookupPreservesUngroupedEvidence()
    {
        var source = instrument("first");
        var initial = evidence(source, "first", AssetType.EQUITY, null);
        var calls = new AtomicInteger();
        var result = resolver.resolve(source, List.of(initial), ignored -> {
            calls.incrementAndGet();
            throw new IOException("Synthetic failure");
        }, () -> true);

        assertEquals(1, calls.get());
        assertEquals(State.UNGROUPED_EQUITY, result.state());
        assertEquals(Reason.MISSING_COMPANY, result.reason());
        assertEquals(LookupStatus.FAILED, result.lookupStatus());
        assertEquals(List.of(initial), result.evidence());
    }

    @Test
    public void testCompleteEvidenceDoesNotConsumeBudget()
    {
        var source = instrument("first");
        var result = resolver.resolve(source, List.of(evidence(source, "first", AssetType.EQUITY, "issuer")),
                        ignored -> {
                            throw new AssertionError("Already resolved");
                        }, () -> {
                            throw new AssertionError("Already resolved");
                        });
        assertEquals(LookupStatus.NOT_NEEDED, result.lookupStatus());
    }

    @Test
    public void testEvidenceAndResultsAreImmutableAndRejectEmptyAssertions()
    {
        var source = instrument("first");
        var initial = evidence(source, "first", AssetType.EQUITY, "issuer");
        var supplied = new ArrayList<>(List.of(initial));
        var result = resolver.resolve(source, supplied, ignored -> List.of(), () -> false);
        supplied.clear();

        assertFalse(result.evidence().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> initial.identifiers().clear());
        assertThrows(IllegalArgumentException.class, () -> new Identifier(" ", "value"));
        assertThrows(IllegalArgumentException.class, () -> new Provenance(provenance.source(), " ", Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                        () -> new InstrumentEvidence(Map.of(), initial.identity(), Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new Assertion<>(AssetType.EQUITY, null));
    }
}
