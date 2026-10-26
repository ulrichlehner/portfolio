package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.ui.services.IStylingEngine;
import org.junit.Test;

import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.ui.editor.PortfolioPart;
import name.abuchen.portfolio.ui.selection.SelectionService;

/** Exercises the real view's field injection without constructing SWT controls. */
@SuppressWarnings("nls")
public class LookThroughInjectionTest
{
    @Test
    public void testViewCreatesItsBackendWithoutPreRegisteredService() throws Exception
    {
        var context = EclipseContextFactory.create();
        try
        {
            context.set(IStylingEngine.class, mock(IStylingEngine.class));
            context.set(PortfolioPart.class, mock(PortfolioPart.class));
            context.set(ExchangeRateProviderFactory.class, mock(ExchangeRateProviderFactory.class));
            context.set(SelectionService.class, new SelectionService());
            // Mockito skips the SWT-dependent constructor and method injection,
            // but Eclipse still processes the real view's annotated fields.
            var view = mock(LookThroughView.class);
            ContextInjectionFactory.inject(view, context);
            var backend = LookThroughView.class.getDeclaredField("compositionService");
            backend.setAccessible(true);
            assertNotNull("Opening the look-through view must create the backend even in a fresh context", backend.get(view));
        }
        finally
        {
            context.dispose();
        }
    }
}
