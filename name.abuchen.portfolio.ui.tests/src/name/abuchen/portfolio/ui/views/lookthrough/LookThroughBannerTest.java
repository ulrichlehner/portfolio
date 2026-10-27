package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.ui.views.lookthrough.LookThroughBanner.Facts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughBanner.Kind;

public class LookThroughBannerTest
{
    private static final Optional<Boolean> KEY = Optional.of(Boolean.TRUE);
    private static final Optional<Boolean> NO_KEY = Optional.of(Boolean.FALSE);
    private static final Optional<Boolean> UNKNOWN = Optional.empty();

    private static Optional<Kind> select(Optional<Boolean> key, boolean suspended, boolean needsCompositions,
                    boolean empty)
    {
        return LookThroughBanner.select(new Facts(key, suspended, needsCompositions, empty));
    }

    @Test
    public void testRefreshProgressIsNotABanner()
    {
        // The progress is shown in the summary row, so no banner kind exists
        // for it and a running refresh cannot push the view down.
        assertEquals(List.of(Kind.SUSPENDED, Kind.NO_KEY, Kind.EMPTY), List.of(Kind.values()));
        assertEquals(Optional.empty(), select(KEY, false, true, false));
    }

    @Test
    public void testSuspendedAccessWinsOverMissingKeyAndEmptyPortfolio()
    {
        assertEquals(Optional.of(Kind.SUSPENDED), select(KEY, true, true, false));
        assertEquals(Optional.of(Kind.SUSPENDED), select(UNKNOWN, true, false, true));
    }

    @Test
    public void testWithoutKeyARejectionIsReportedAsMissingKey()
    {
        assertEquals(Optional.of(Kind.NO_KEY), select(NO_KEY, true, true, false));
    }

    @Test
    public void testMissingKeyIsOnlyReportedWhenFundsNeedAComposition()
    {
        assertEquals(Optional.of(Kind.NO_KEY), select(NO_KEY, false, true, false));
        assertEquals(Optional.empty(), select(NO_KEY, false, false, false));
        assertEquals(Optional.empty(), select(UNKNOWN, false, true, false));
    }

    @Test
    public void testEmptyPortfolioIsTheLastBanner()
    {
        assertEquals(Optional.of(Kind.EMPTY), select(NO_KEY, false, false, true));
        assertEquals(Optional.of(Kind.EMPTY), select(KEY, false, false, true));
    }

    @Test
    public void testConfiguredKeyWithoutProblemsShowsNoBanner()
    {
        assertEquals(Optional.empty(), select(KEY, false, true, false));
    }
}
