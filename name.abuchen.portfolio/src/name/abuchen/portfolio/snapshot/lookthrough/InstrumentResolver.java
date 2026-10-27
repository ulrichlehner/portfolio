package name.abuchen.portfolio.snapshot.lookthrough;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Assertion;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.AssetType;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentEvidence.Identifier;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.LookupStatus;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.Reason;
import name.abuchen.portfolio.snapshot.lookthrough.InstrumentResolution.State;

/**
 * Deterministic identifier-based resolution with at most one injected lookup per
 * invocation. No network, retries, guessed mappings or persistence are provided;
 * the only assumption is the marked company assumption after an answered lookup.
 * The caller supplies available evidence and a provider-wide budget admission
 * function. A permit admits one bounded lookup; adapters must account for actual
 * request costs and enforce their own deadlines. Redraws must reuse results, not
 * invoke resolution again. Identifier namespaces and values are exact, not fuzzy.
 */
public final class InstrumentResolver
{
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]"); //$NON-NLS-1$
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+"); //$NON-NLS-1$

    /** Whole words or phrases of upper case words, separated by single spaces. */
    @SuppressWarnings("nls")
    private static final List<String> NON_COMPANY_WORDS = List.of( //
                    "UCITS", "ETF", "ETFS", "ETC", "ETN", "ETNS", "FUND", "FUNDS", "FONDS", "SICAV", "OEIC",
                    "TRUST UNIT", "TRUST UNITS", "LIQ", "LIQUIDITY", "MONEY MARKET", "MMF", "CASH", "COLLATERAL",
                    "TREASURIES", "US TREASURY", "U S TREASURY", "UNITED STATES TREASURY", "TREASURY BILL",
                    "TREASURY BILLS", "TREASURY NOTE", "TREASURY NOTES", "TREASURY BOND", "TREASURY BONDS",
                    "T BILL", "T BILLS", "TBILL", "TBILLS", "BOND", "BONDS", "NOTE", "NOTES", "FUTURES", "FUT",
                    "SWAP", "SWAPS", "OPTIONS", "FX", "FORWARD", "FORWARDS", "INDEX");

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
            return assumeCompany(evaluate(source, evidence, LookupStatus.ATTEMPTED));
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

    /**
     * Most constituents without any classification answer are ordinary listed
     * shares. One with an ISIN whose lookup found no classification is
     * assumed to be a company, keyed by its ISIN like a verified share, unless
     * its name indicates a non-company instrument. The assumption is marked by
     * its reason and never counts as verified.
     */
    private InstrumentResolution assumeCompany(InstrumentResolution resolution)
    {
        if (resolution.state() != State.UNRESOLVED || resolution.reason() != Reason.MISSING_IDENTITY
                        && resolution.reason() != Reason.UNKNOWN_CLASSIFICATION)
            return resolution;
        var source = resolution.source();
        var isin = source.identifiers().get(InstrumentEvidence.ISIN);
        if (isin == null || !ISIN.matcher(isin.trim()).matches() || indicatesNonCompany(source.label()))
            return resolution;
        var company = new Identifier(InstrumentEvidence.ISIN, isin.trim());
        return new InstrumentResolution(source, State.GROUPED_EQUITY, Reason.ASSUMED_COMPANY,
                        Optional.of(resolution.instrument().orElse(company)), Optional.of(AssetType.EQUITY),
                        Optional.of(company), resolution.evidence(), resolution.lookupStatus());
    }

    /**
     * True when the name contains a whole word or phrase of a fund, cash,
     * money market, bond or derivative position. Words that also occur in
     * names of listed companies are left out or only matched as phrases:
     * TRUST (banks), TREASURY (for example a wine producer), FUTURE and OPTION
     * (companies of that name) and BILL.
     */
    public static boolean indicatesNonCompany(String name)
    {
        if (name == null || name.isBlank())
            return false;
        var words = " " + String.join(" ", //$NON-NLS-1$ //$NON-NLS-2$
                        NON_ALPHANUMERIC.split(name.toUpperCase(Locale.ROOT).trim())) + " "; //$NON-NLS-1$
        return NON_COMPANY_WORDS.stream().anyMatch(word -> words.contains(" " + word + " ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private InstrumentResolution unresolved(CompositionInstrument source, List<InstrumentEvidence> evidence,
                    Reason reason, LookupStatus status)
    {
        return new InstrumentResolution(source, State.UNRESOLVED, reason, Optional.empty(), Optional.empty(),
                        Optional.empty(), evidence, status);
    }
}
