import json
import time
import unittest

from playwright.async_api import async_playwright

from main import (
    ModernPortfolioCollector,
    _click_portfolio_link,
    _mark_portfolio_onboarding_complete,
    _open_portfolio_from_account_menu,
    _safe_observed_path,
    _socketio_events,
)
from portfolio_parser import PortfolioFormatError


class PortfolioNavigationTest(unittest.IsolatedAsyncioTestCase):
    async def test_portfolio_onboarding_cookie_is_preseeded(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            context = await browser.new_context()
            try:
                await _mark_portfolio_onboarding_complete(context)

                cookies = await context.cookies("https://www.boursedirect.fr")

                onboarding = next(
                    cookie
                    for cookie in cookies
                    if cookie["name"] == "portfolio_onboarding_20260316"
                )
                self.assertEqual(onboarding["value"], "ok")
                self.assertTrue(onboarding["secure"])
            finally:
                await context.close()
                await browser.close()

    def test_socketio_frames_map_modern_pea_portfolio(self):
        collector = ModernPortfolioCollector()
        frames = [
            ["authorized", {"portfolios": [{
                "id": "PEA-42",
                "label": "PEA principal",
                "type": "PEA",
            }]}],
            ["balance.snapshot", {
                "portfolio": "PEA-42",
                "cash_valuation": {"current": 250.5},
                "portfolio_valuation": {"current": 1000},
                "total": {"current": 1250.5},
            }],
            ["position.snapshot", {
                "portfolio": "PEA-42",
                "key": "FR0000000001-XPAR-EUR-cash",
                "share": {
                    "isin": "FR0000000001",
                    "name": "Acme SA",
                    "ticker": "ACME",
                    "last": 100,
                    "currency": "EUR",
                },
                "quantity": {"number": 10},
                "average_price": {"number": 80},
            }],
        ]
        for frame in frames:
            collector._on_frame("42" + json.dumps(frame))
        collector.last_event_at = 0

        accounts = collector.result_if_ready()

        self.assertIsNotNone(accounts)
        self.assertEqual(accounts[0]["externalId"], "PEA-42")
        self.assertEqual(accounts[0]["type"], "PEA")
        self.assertEqual(accounts[0]["balanceEur"], "1250.5")
        self.assertEqual(accounts[0]["cashBalance"], "250.5")
        self.assertEqual(accounts[0]["positions"][0]["symbol"], "ACME")
        self.assertEqual(accounts[0]["positions"][0]["buyingPriceEur"], "80")
        self.assertEqual(accounts[0]["positions"][0]["quoteCurrency"], "EUR")
        self.assertEqual(accounts[0]["positions"][0]["currentValueEur"], "1000")
        self.assertTrue(accounts[0]["snapshotComplete"])

    def test_snapshot_waits_until_position_values_reconcile_with_account_balance(self):
        collector = ModernPortfolioCollector()
        frames = [
            ["authorized", {"portfolios": [{
                "id": "CTO-42", "label": "CTO", "type": "CTO",
            }]}],
            ["balance.snapshot", {
                "portfolio": "CTO-42",
                "cash_valuation": {"current": 50},
                "portfolio_valuation": {"current": 150},
                "total": {"current": 200},
            }],
            ["position.snapshot", {
                "portfolio": "CTO-42", "key": "one",
                "share": {"name": "One", "ticker": "ONE", "last": 100, "currency": "EUR"},
                "quantity": {"number": 1}, "average_price": {"number": 90},
            }],
        ]
        for frame in frames:
            collector._on_frame("42" + json.dumps(frame))
        collector.last_event_at = 0

        self.assertIsNone(collector.result_if_ready())
        self.assertEqual(collector.incomplete_reason, "position-total-mismatch")

        collector._on_frame("42" + json.dumps(["position.snapshot", {
            "portfolio": "CTO-42", "key": "two",
            "share": {"name": "Two", "ticker": "TWO", "last": 50, "currency": "EUR"},
            "quantity": {"number": 1}, "average_price": {"number": 40},
        }]))
        collector.last_event_at = 0

        accounts = collector.result_if_ready()
        self.assertIsNotNone(accounts)
        self.assertEqual(len(accounts[0]["positions"]), 2)

    def test_partial_updates_are_merged_with_the_last_complete_snapshot(self):
        collector = ModernPortfolioCollector()
        for frame in (
            ["authorized", {"portfolios": [{"id": "PEA", "label": "PEA", "type": "PEA"}]}],
            ["balance.snapshot", {
                "portfolio": "PEA", "cash_valuation": {"current": 0},
                "portfolio_valuation": {"current": 100}, "total": {"current": 100},
            }],
            ["position.snapshot", {
                "portfolio": "PEA", "key": "acme",
                "share": {"name": "Acme", "ticker": "ACME", "last": 100, "currency": "EUR"},
                "quantity": {"number": 1}, "average_price": {"number": 80},
                "valuation": 100,
            }],
            ["position.update", {
                "portfolio": "PEA", "key": "acme", "plusMinusValue": 20,
            }],
            ["balance.update", {"portfolio": "PEA", "total": {"current": 100}}],
        ):
            collector._on_frame("42" + json.dumps(frame))
        collector.last_event_at = 0

        accounts = collector.result_if_ready()

        self.assertEqual(accounts[0]["positions"][0]["symbol"], "ACME")
        self.assertEqual(accounts[0]["positions"][0]["currentValueEur"], "100")
        self.assertEqual(accounts[0]["positions"][0]["pnlEur"], "20")

    def test_demo_accounts_are_filtered_by_metadata_not_an_account_number(self):
        collector = ModernPortfolioCollector()
        collector._on_frame("42" + json.dumps(["authorized", {"portfolios": [
            {"id": "123TI456", "label": "CTO principal", "type": "CTO"},
            {"id": "virtual", "label": "Portefeuille fictif", "type": "CTO"},
        ]}]))

        self.assertEqual(
            [account_id for account_id, _ in collector._target_accounts()],
            ["123TI456"],
        )

    def test_current_price_without_a_currency_is_not_presented_as_eur(self):
        collector = ModernPortfolioCollector()
        for frame in (
            ["authorized", {"portfolios": [{"id": "PEA", "label": "PEA", "type": "PEA"}]}],
            ["balance.snapshot", {
                "portfolio": "PEA", "cash_valuation": {"current": 0},
                "portfolio_valuation": {"current": 100}, "total": {"current": 100},
            }],
            ["position.snapshot", {
                "portfolio": "PEA", "key": "unknown-currency",
                "share": {"name": "Acme", "ticker": "ACME", "last": 100},
                "quantity": {"number": 1}, "average_price": {"number": 80},
                "valuation": 100,
            }],
        ):
            collector._on_frame("42" + json.dumps(frame))
        collector.last_event_at = 0

        position = collector.result_if_ready()[0]["positions"][0]

        self.assertIsNone(position["currentPrice"])
        self.assertIsNone(position["quoteCurrency"])

    def test_snapshot_waits_for_settle_window_even_when_values_reconcile(self):
        collector = ModernPortfolioCollector()
        for frame in (
            ["authorized", {"portfolios": [{"id": "PEA", "label": "PEA", "type": "PEA"}]}],
            ["balance.snapshot", {
                "portfolio": "PEA", "cash_valuation": {"current": 10},
                "portfolio_valuation": {"current": 0}, "total": {"current": 10},
            }],
        ):
            collector._on_frame("42" + json.dumps(frame))

        collector.last_event_at = time.monotonic()
        self.assertIsNone(collector.result_if_ready())
        self.assertEqual(collector.incomplete_reason, "settling")

    def test_foreign_position_requires_broker_eur_valuation(self):
        collector = ModernPortfolioCollector()
        for frame in (
            ["authorized", {"portfolios": [{"id": "CTO", "label": "CTO", "type": "CTO"}]}],
            ["balance.snapshot", {
                "portfolio": "CTO", "cash_valuation": {"current": 0},
                "portfolio_valuation": {"current": 90}, "total": {"current": 90},
            }],
            ["position.snapshot", {
                "portfolio": "CTO", "key": "usd",
                "share": {"name": "US stock", "ticker": "USD", "last": 100, "currency": "USD"},
                "quantity": {"number": 1}, "average_price": {"number": 80},
            }],
        ):
            collector._on_frame("42" + json.dumps(frame))
        collector.last_event_at = 0
        self.assertIsNone(collector.result_if_ready())
        self.assertEqual(
            collector.incomplete_reason,
            "missing-position-eur-valuation",
        )

        collector._on_frame("42" + json.dumps(["position.update", {
            "portfolio": "CTO", "key": "usd", "valuation": 90,
            "plusMinusValue": 20,
            "share": {"name": "US stock", "ticker": "USD", "last": 100, "currency": "USD"},
            "quantity": {"number": 1}, "average_price": {"number": 80},
        }]))
        collector.last_event_at = 0

        accounts = collector.result_if_ready()
        self.assertEqual(accounts[0]["positions"][0]["quoteCurrency"], "USD")
        self.assertEqual(accounts[0]["positions"][0]["currentValueEur"], "90")
        self.assertEqual(accounts[0]["positions"][0]["buyingPriceEur"], "70")

    def test_malformed_required_balance_is_rejected_not_coerced_to_zero(self):
        collector = ModernPortfolioCollector()
        collector._on_frame("42" + json.dumps(["authorized", {
            "portfolios": [{"id": "PEA", "label": "PEA", "type": "PEA"}],
        }]))
        collector._on_frame("42" + json.dumps(["balance.snapshot", {
            "portfolio": "PEA", "cash_valuation": {"current": "invalid"},
            "portfolio_valuation": {"current": 0}, "total": {"current": 0},
        }]))
        collector.last_event_at = 0

        with self.assertRaises(PortfolioFormatError):
            collector.result_if_ready()

    def test_socketio_parser_ignores_engine_packets(self):
        self.assertEqual(_socketio_events('0{"sid":"abc"}'), [])
        self.assertEqual(
            _socketio_events('42["balance.snapshot",{"portfolio":"PEA-42"}]'),
            [("balance.snapshot", {"portfolio": "PEA-42"})],
        )

    def test_observed_network_path_redacts_account_identifier(self):
        self.assertEqual(
            _safe_observed_path(
                "https://www.boursedirect.fr/api/accounts/ABC12345/portfolio?token=secret"
            ),
            "/api/accounts/<id>/portfolio",
        )
        self.assertEqual(
            _safe_observed_path(
                "https://www.boursedirect.fr/fr/portefeuille/ALPHABETIC"
            ),
            "/fr/portefeuille/<id>",
        )

    async def test_exact_hidden_portfolio_link_receives_trusted_click(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                await page.set_content(
                    """
                    <div style="display: none">
                      <a id="decoy"
                         href="https://example.test/fr/page/portefeuille?ticket=decoy">
                        Portefeuille
                      </a>
                      <a id="portfolio"
                         href="https://example.test/fr/page/portefeuille?ticket=real">
                        Portefeuille réel
                      </a>
                    </div>
                    <script>
                      document.addEventListener('click', event => {
                        if (event.target.matches('a')) {
                          event.preventDefault();
                          document.title = `${event.target.id}:${event.isTrusted}`;
                        }
                      });
                    </script>
                    """
                )

                selected_page = await _click_portfolio_link(
                    page,
                    "https://example.test/fr/page/portefeuille?ticket=real",
                )

                self.assertIs(selected_page, page)
                self.assertEqual(await page.title(), "portfolio:true")
            finally:
                await browser.close()

    async def test_account_menu_opens_realtime_portfolio(self):
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            try:
                await page.set_content(
                    """
                    <a id="accounts" href="#accounts">Mes comptes</a>
                    <a id="realtime" href="#portfolio" style="display: none">
                      Portefeuille temps réel
                    </a>
                    <script>
                      document.addEventListener('click', event => {
                        event.preventDefault();
                        if (event.target.id === 'accounts') {
                          document.querySelector('#realtime').style.display = 'block';
                        }
                        if (event.target.id === 'realtime') {
                          document.title = event.isTrusted ? 'trusted' : 'synthetic';
                        }
                      });
                    </script>
                    """
                )

                selected_page, opened = await _open_portfolio_from_account_menu(page)

                self.assertTrue(opened)
                self.assertIs(selected_page, page)
                self.assertEqual(await page.title(), "trusted")
            finally:
                await browser.close()


if __name__ == "__main__":
    unittest.main()
