package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;

import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.ui.views.lookthrough.LookThroughBanner.Facts;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughBanner.Kind;

public class LookThroughBannerTest
{
    private static final Optional<Boolean> KEY = Optional.of(Boolean.TRUE);
    private static final Optional<Boolean> NO_KEY = Optional.of(Boolean.FALSE);
    private static final Optional<Boolean> UNKNOWN = Optional.empty();

    private static Optional<Kind> select(boolean refreshing, Optional<Boolean> key, boolean suspended,
                    boolean needsCompositions, boolean empty)
    {
        return LookThroughBanner.select(new Facts(refreshing, key, suspended, needsCompositions, empty));
    }

    @Test
    public void testRunningRefreshWinsOverEveryOtherState()
    {
        assertEquals(Optional.of(Kind.PROGRESS), select(true, KEY, true, true, false));
        assertEquals(Optional.of(Kind.PROGRESS), select(true, NO_KEY, false, true, true));
    }

    @Test
    public void testSuspendedAccessWinsOverMissingKeyAndEmptyPortfolio()
    {
        assertEquals(Optional.of(Kind.SUSPENDED), select(false, KEY, true, true, false));
        assertEquals(Optional.of(Kind.SUSPENDED), select(false, UNKNOWN, true, false, true));
    }

    @Test
    public void testWithoutKeyARejectionIsReportedAsMissingKey()
    {
        assertEquals(Optional.of(Kind.NO_KEY), select(false, NO_KEY, true, true, false));
    }

    @Test
    public void testMissingKeyIsOnlyReportedWhenFundsNeedAComposition()
    {
        assertEquals(Optional.of(Kind.NO_KEY), select(false, NO_KEY, false, true, false));
        assertEquals(Optional.empty(), select(false, NO_KEY, false, false, false));
        assertEquals(Optional.empty(), select(false, UNKNOWN, false, true, false));
    }

    @Test
    public void testEmptyPortfolioIsTheLastBanner()
    {
        assertEquals(Optional.of(Kind.EMPTY), select(false, NO_KEY, false, false, true));
        assertEquals(Optional.of(Kind.EMPTY), select(false, KEY, false, false, true));
    }

    @Test
    public void testConfiguredKeyWithoutProblemsShowsNoBanner()
    {
        assertEquals(Optional.empty(), select(false, KEY, false, true, false));
    }
}
