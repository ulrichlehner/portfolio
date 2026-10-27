package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Optional;

import org.junit.Test;

import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService.Progress;
import name.abuchen.portfolio.snapshot.lookthrough.CompanyEnrichmentService.State;
import name.abuchen.portfolio.ui.Messages;

public class LookThroughCompanyDataTextTest
{
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z"); //$NON-NLS-1$

    private static String text(Progress progress)
    {
        return LookThroughDataStatus.companyText(progress, NOW, ZoneOffset.UTC).orElseThrow();
    }

    @Test
    public void testNoLineWithoutCompanies()
    {
        assertTrue(LookThroughDataStatus.companyText(Progress.none(), NOW, ZoneOffset.UTC).isEmpty());
    }

    @Test
    public void testLoadingAndComplete()
    {
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataLoading, 1234, 3975, ""), //$NON-NLS-1$
                        text(new Progress(1234, 3975, State.LOADING, Optional.empty())));
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataComplete, 3975, 3975, ""), //$NON-NLS-1$
                        text(new Progress(3975, 3975, State.COMPLETE, Optional.empty())));
    }

    @Test
    public void testUnavailableNamesTheRetryTimeToday()
    {
        var retry = NOW.plusSeconds(40 * 60);
        var time = retry.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataUnavailable, 12, 40, time),
                        text(new Progress(12, 40, State.UNAVAILABLE, Optional.of(retry))));
    }

    @Test
    public void testPauseUntilTomorrowNamesTheDate()
    {
        var retry = NOW.plusSeconds(24 * 3600);
        var date = retry.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT));
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataPaused, 12, 40, date),
                        text(new Progress(12, 40, State.PAUSED, Optional.of(retry))));
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataPausedOpen, 12, 40, ""), //$NON-NLS-1$
                        text(new Progress(12, 40, State.PAUSED, Optional.empty())));
    }

    @Test
    public void testNoKeyAndNoAccess()
    {
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataNoKey, 0, 40, ""), //$NON-NLS-1$
                        text(new Progress(0, 40, State.NO_KEY, Optional.empty())));
        assertEquals(MessageFormat.format(Messages.LookThroughCompanyDataNoAccess, 5, 40, ""), //$NON-NLS-1$
                        text(new Progress(5, 40, State.NO_ACCESS, Optional.empty())));
    }
}
