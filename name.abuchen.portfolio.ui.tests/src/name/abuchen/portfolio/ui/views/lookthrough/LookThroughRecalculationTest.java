package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.Test;

/**
 * Synthetic tests of the coalescing calculation scheduler. Queued executors
 * replace the worker thread and the user interface thread, so the test
 * decides when each step runs.
 */
@SuppressWarnings("nls")
public class LookThroughRecalculationTest
{
    private static final class QueuedExecutor implements Executor
    {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command)
        {
            tasks.add(command);
        }

        private void runAll()
        {
            while (!tasks.isEmpty())
                tasks.poll().run();
        }
    }

    private final QueuedExecutor worker = new QueuedExecutor();
    private final QueuedExecutor display = new QueuedExecutor();
    private final List<String> delivered = new ArrayList<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private final List<String> calculated = new ArrayList<>();

    private final LookThroughRecalculation<String> recalculation = new LookThroughRecalculation<>(worker, display,
                    delivered::add, failures::add);

    private void request(String value)
    {
        recalculation.request(() -> {
            calculated.add(value);
            return value;
        });
    }

    @Test
    public void testBurstOfRequestsYieldsOneCalculation()
    {
        for (var i = 1; i <= 5; i++)
            request("request-" + i);

        assertEquals(1, worker.tasks.size());
        worker.runAll();
        display.runAll();

        assertEquals(List.of("request-5"), calculated);
        assertEquals(List.of("request-5"), delivered);
        assertTrue(failures.isEmpty());
    }

    @Test
    public void testStaleResultIsDiscarded()
    {
        request("old");
        worker.runAll();

        // A newer request arrives before the old result reaches the display.
        request("new");
        display.runAll();
        assertTrue(delivered.isEmpty());

        worker.runAll();
        display.runAll();
        assertEquals(List.of("old", "new"), calculated);
        assertEquals(List.of("new"), delivered);
    }

    @Test
    public void testRequestDuringCalculationRunsAfterwardsOnTheSameWorker()
    {
        recalculation.request(() -> {
            calculated.add("first");
            request("second");
            return "first";
        });

        worker.runAll();
        display.runAll();

        assertEquals(1, calculated.stream().filter("second"::equals).count());
        assertEquals(List.of("second"), delivered);
    }

    @Test
    public void testCancelDropsPendingResults()
    {
        request("value");
        worker.runAll();
        recalculation.cancel();
        display.runAll();

        assertTrue(delivered.isEmpty());
    }

    @Test
    public void testFailureIsReportedAndLaterRequestsStillRun()
    {
        recalculation.request(() -> {
            throw new IllegalStateException("synthetic failure");
        });
        worker.runAll();
        assertEquals(1, failures.size());

        request("after");
        worker.runAll();
        display.runAll();
        assertEquals(List.of("after"), delivered);
    }
}
