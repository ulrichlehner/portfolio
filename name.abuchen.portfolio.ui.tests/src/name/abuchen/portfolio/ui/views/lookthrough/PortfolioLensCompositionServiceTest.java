package name.abuchen.portfolio.ui.views.lookthrough;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.online.impl.EulerpoolFundCompositionProvider;
import name.abuchen.portfolio.ui.views.lookthrough.LookThroughRefreshController.FundRef;

/**
 * Synthetic-only tests of the request candidate mapping. No client data, no
 * credentials and no provider access; only the translation from held
 * securities to fund references is exercised.
 */
@SuppressWarnings("nls")
public class PortfolioLensCompositionServiceTest
{
    private static Security security(String name, String isin)
    {
        var security = new Security(name, null);
        security.setIsin(isin);
        return security;
    }

    private static String isin(FundRef ref)
    {
        return ref.fund().identifiers().get("ISIN");
    }

    @Test
    public void testOnlySecuritiesWithAnIsinBecomeCandidates()
    {
        var funds = PortfolioLensCompositionService.fundsOf(List.of(//
                        security("Synthetic fund A", "DE0000000001"), //
                        security("Synthetic fund without isin", null), //
                        security("Synthetic fund with blank isin", "  ")));

        assertEquals(1, funds.size());
        assertEquals("Synthetic fund A", funds.get(0).fund().label());
        assertEquals("DE0000000001", isin(funds.get(0)));
        assertEquals(EulerpoolFundCompositionProvider.ID, funds.get(0).account().providerId());
    }

    @Test
    public void testDuplicateIsinsKeepTheFirstLabelAndTheSelectionOrder()
    {
        var funds = PortfolioLensCompositionService.fundsOf(List.of(//
                        security("Synthetic fund A", "DE0000000001"), //
                        security("Synthetic fund renamed", "DE0000000001"), //
                        security("Synthetic fund B", "DE0000000002")));

        assertEquals(List.of("Synthetic fund A", "Synthetic fund B"),
                        funds.stream().map(ref -> ref.fund().label()).toList());
        assertEquals("DE0000000001", isin(funds.get(0)));
        assertEquals("DE0000000002", isin(funds.get(1)));
    }

    @Test
    public void testIsinIsTrimmedAndIdentifiersCarryNothingElse()
    {
        var funds = PortfolioLensCompositionService.fundsOf(
                        List.of(security("Synthetic fund A", "  DE0000000001  ")));

        assertEquals(1, funds.size());
        assertEquals("DE0000000001", isin(funds.get(0)));
        assertEquals(1, funds.get(0).fund().identifiers().size());
    }

    @Test
    public void testEmptySelectionYieldsNoCandidates()
    {
        assertTrue(PortfolioLensCompositionService.fundsOf(List.of()).isEmpty());
    }
}
