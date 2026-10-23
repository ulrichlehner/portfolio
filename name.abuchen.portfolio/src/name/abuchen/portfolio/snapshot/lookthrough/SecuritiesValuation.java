package name.abuchen.portfolio.snapshot.lookthrough;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;

/**
 * Current positive securities only; account cash is always outside this scope.
 * This is valuation completeness, not composition or company coverage.
 */
public final class SecuritiesValuation
{
    public enum Reason
    {
        UNSUPPORTED_NEGATIVE_POSITION, MISSING_VALUATION, MISSING_CONVERSION
    }

    public record Position(Security security, long shares, Money value)
    {
    }

    public record Diagnostic(Security security, Reason reason)
    {
    }

    private final LocalDate date;
    private final List<Position> positions;
    private final List<Diagnostic> diagnostics;
    private final Money valuedSubset;

    SecuritiesValuation(LocalDate date, List<Position> positions, List<Diagnostic> diagnostics, Money valuedSubset)
    {
        this.date = date;
        this.positions = List.copyOf(positions);
        this.diagnostics = List.copyOf(diagnostics);
        this.valuedSubset = valuedSubset;
    }

    public LocalDate date()
    {
        return date;
    }

    public List<Position> positions()
    {
        return positions;
    }

    public List<Diagnostic> diagnostics()
    {
        return diagnostics;
    }

    /** Known value only. Must be labelled as a subset if valuations are missing. */
    public Money valuedSubset()
    {
        return valuedSubset;
    }

    public boolean isValuationComplete()
    {
        return diagnostics.stream().noneMatch(d -> d.reason() != Reason.UNSUPPORTED_NEGATIVE_POSITION);
    }

    /** False also for excluded shorts, even when the positive denominator is known. */
    public boolean isEntireSelectionSupported()
    {
        return diagnostics.isEmpty();
    }

    /**
     * Denominator for percentages of selected positive securities value, never of
     * account-inclusive assets. Absent for incomplete valuations and empty/zero
     * scope. Excluded negative positions must still be disclosed separately.
     */
    public Optional<Money> denominator()
    {
        return isValuationComplete() && valuedSubset.getAmount() > 0 ? Optional.of(valuedSubset) : Optional.empty();
    }
}
