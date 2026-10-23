package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.LookupStatus;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.State;

/**
 * Deterministic identifier-based resolution with at most one injected lookup per
 * invocation. No network, retries, guessed mappings or persistence are provided.
 * The caller supplies available evidence and a provider-wide budget admission
 * function. A permit admits one bounded lookup; adapters must account for actual
 * request costs and enforce their own deadlines. Redraws must reuse results, not
 * invoke resolution again. Identifier namespaces and values are exact, not fuzzy.
 */
public final class InstrumentResolver
{
    @FunctionalInterface
    public interface EvidenceLookup
    {
        /** Return verified evidence only; no name-only equivalence assertions. */
        List<InstrumentEvidence> lookup(CompositionInstrument instrument) throws IOException;
    }

    public InstrumentResolution resolve(CompositionInstrument source, List<InstrumentEvidence> available,
                    EvidenceLookup lookup, BooleanSupplier acquirePermit)
    {
        Objects.requireNonNull(lookup);
        Objects.requireNonNull(acquirePermit);
        var evidence = new ArrayList<>(List.copyOf(available));
        var initial = evaluate(source, evidence, LookupStatus.NOT_NEEDED);
        if (initial.reason() == Reason.MISSING_IDENTIFIERS)
            return evaluate(source, evidence, LookupStatus.NO_IDENTIFIERS);
        if (initial.reason() != Reason.MISSING_IDENTITY && initial.reason() != Reason.UNKNOWN_CLASSIFICATION
                        && initial.reason() != Reason.MISSING_COMPANY)
            return initial;
        if (!acquirePermit.getAsBoolean())
            return evaluate(source, evidence, LookupStatus.BUDGET_EXHAUSTED);
        try
        {
            evidence.addAll(List.copyOf(lookup.lookup(source)));
            return evaluate(source, evidence, LookupStatus.ATTEMPTED);
        }
        catch (IOException e)
        {
            // Preserve usable evidence; do not expose provider payloads in errors.
            return evaluate(source, evidence, LookupStatus.FAILED);
        }
    }

    private InstrumentResolution evaluate(CompositionInstrument source, List<InstrumentEvidence> available,
                    LookupStatus status)
    {
        var matching = available.stream().filter(item -> item.identifiers().entrySet().stream()
                        .anyMatch(entry -> !entry.getKey().isBlank() && !entry.getValue().isBlank()
                                        && entry.getValue().equals(source.identifiers().get(entry.getKey())))).toList();
        if (source.identifiers().entrySet().stream()
                        .noneMatch(entry -> !entry.getKey().isBlank() && !entry.getValue().isBlank()))
            return unresolved(source, matching, Reason.MISSING_IDENTIFIERS, status);
        if (matching.isEmpty())
            return unresolved(source, matching, Reason.MISSING_IDENTITY, status);

        var identities = matching.stream().map(item -> item.identity().value()).distinct().toList();
        var conflictingAlias = matching.stream().anyMatch(item -> item.identifiers().entrySet().stream()
                        .anyMatch(entry -> source.identifiers().containsKey(entry.getKey())
                                        && !entry.getValue().equals(source.identifiers().get(entry.getKey()))));
        if (identities.size() != 1 || conflictingAlias)
            return unresolved(source, matching, Reason.AMBIGUOUS_IDENTITY, status);

        var types = matching.stream().flatMap(item -> item.classification().stream()).map(Assertion::value).distinct()
                        .toList();
        if (types.size() != 1)
            return new InstrumentResolution(source, State.UNRESOLVED,
                            types.isEmpty() ? Reason.UNKNOWN_CLASSIFICATION : Reason.AMBIGUOUS_CLASSIFICATION,
                            Optional.of(identities.getFirst()), Optional.empty(), Optional.empty(), matching, status);

        var type = types.getFirst();
        var companies = matching.stream().flatMap(item -> item.company().stream()).map(Assertion::value).distinct()
                        .toList();
        var equity = type == AssetType.EQUITY || type == AssetType.DEPOSITARY_RECEIPT;
        var state = type == AssetType.FUND ? State.FUND : State.CASH_OR_OTHER;
        var reason = Reason.VERIFIED;
        if (equity)
        {
            state = companies.size() == 1 ? State.GROUPED_EQUITY : State.UNGROUPED_EQUITY;
            reason = companies.size() == 1 ? Reason.VERIFIED
                            : companies.isEmpty() ? Reason.MISSING_COMPANY : Reason.AMBIGUOUS_COMPANY;
        }
        return new InstrumentResolution(source, state, reason, Optional.of(identities.getFirst()), Optional.of(type),
                        equity && companies.size() == 1 ? Optional.of(companies.getFirst()) : Optional.empty(),
                        matching, status);
    }

    private InstrumentResolution unresolved(CompositionInstrument source, List<InstrumentEvidence> evidence,
                    Reason reason, LookupStatus status)
    {
        return new InstrumentResolution(source, State.UNRESOLVED, reason, Optional.empty(), Optional.empty(),
                        Optional.empty(), evidence, status);
    }
}
