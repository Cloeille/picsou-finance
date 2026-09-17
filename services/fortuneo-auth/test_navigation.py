"""Deterministic browser tests for Fortuneo navigation helpers.

Every page is synthetic, anonymized HTML loaded with ``set_content``. These
tests never contact Fortuneo and create no traces, videos, or screenshots.
Live authentication remains a separate manual validation step documented in
``docs/features/fortuneo.md``.
"""

import unittest
from unittest.mock import patch

from playwright.async_api import async_playwright

from main import (
    LOGIN_FIELD_SELECTORS,
    _SSO_SUBMIT_SCRIPT,
    _defer_investor_profile_gate,
    _dismiss_cookie_consent,
    _first_visible,
    _otp_visible,
    _read_session_storage,
    _restore_session_storage,
    _wait_for_login_outcome,
    _wait_for_token_exchange,
)


class FortuneoNavigationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True)
        self.context = await self.browser.new_context()
        self.page = await self.context.new_page()

    async def asyncTearDown(self):
        await self.context.close()
        await self.browser.close()
        await self.playwright.stop()

    async def test_login_selector_falls_back_to_visible_name_attribute(self):
        await self.page.set_content(
            """
            <input id="LOGIN" style="display:none">
            <input id="visible-login" name="LOGIN">
            """
        )

        field = await _first_visible(self.page, LOGIN_FIELD_SELECTORS, 100)

        self.assertIsNotNone(field)
        self.assertEqual(await field.get_attribute("id"), "visible-login")

    async def test_cookie_consent_is_clicked_when_present(self):
        await self.page.set_content(
            """
            <button id="popin_tc_privacy_button">Accept</button>
            <script>
              document.querySelector('button').addEventListener('click', () => {
                document.title = 'consent-dismissed';
              });
            </script>
            """
        )

        await _dismiss_cookie_consent(self.page)

        self.assertEqual(await self.page.title(), "consent-dismissed")

    async def test_investor_profile_gate_is_deferred_with_a_trusted_click(self):
        await self.page.set_content(
            """
            <button>Plus tard</button>
            <script>
              document.querySelector('button').addEventListener('click', event => {
                document.title = event.isTrusted ? 'trusted' : 'synthetic';
              });
            </script>
            """
        )

        dismissed = await _defer_investor_profile_gate(self.page)

        self.assertTrue(dismissed)
        self.assertEqual(await self.page.title(), "trusted")

    async def test_investor_profile_gate_accepts_a_safe_variant_in_a_child_frame(self):
        await self.page.set_content("<iframe></iframe>")
        frame = self.page.frames[1]
        await frame.set_content(
            """
            <a href="#" role="button">Je le ferai plus tard</a>
            <script>
              document.querySelector('a').addEventListener('click', event => {
                event.preventDefault();
                document.body.dataset.clicked = event.isTrusted ? 'trusted' : 'synthetic';
              });
            </script>
            """
        )

        dismissed = await _defer_investor_profile_gate(self.page)

        self.assertTrue(dismissed)
        self.assertEqual(await frame.locator("body").get_attribute("data-clicked"), "trusted")

    async def test_investor_profile_gate_never_clicks_the_create_action(self):
        await self.page.set_content(
            """
            <h1>Créer votre profil investisseur</h1>
            <button>Créer mon profil</button>
            <script>
              document.querySelector('button').addEventListener('click', () => {
                document.title = 'unsafe-action-clicked';
              });
            </script>
            """
        )

        with patch("main.INVESTOR_PROFILE_TIMEOUT_MS", 100):
            dismissed = await _defer_investor_profile_gate(self.page)

        self.assertFalse(dismissed)
        self.assertNotEqual(await self.page.title(), "unsafe-action-clicked")

    async def test_otp_detection_requires_all_six_digit_inputs(self):
        digit = '<input inputmode="numeric" pattern="[0-9]*">'
        await self.page.set_content(digit * 5)
        self.assertFalse(await _otp_visible(self.page))

        await self.page.set_content(digit * 6)
        self.assertTrue(await _otp_visible(self.page))

    async def test_login_outcome_distinguishes_token_otp_and_timeout(self):
        await self.page.set_content("<main>Waiting</main>")
        self.assertEqual(
            await _wait_for_login_outcome(self.page, {"value": "captured-key"}, 1),
            "success",
        )

        digit = '<input inputmode="numeric" pattern="[0-9]*">'
        await self.page.set_content(digit * 6)
        self.assertEqual(
            await _wait_for_login_outcome(self.page, {"value": None}, 1),
            "otp",
        )
        self.assertEqual(
            await _wait_for_login_outcome(self.page, {"value": None}, 0),
            "timeout",
        )
        self.assertFalse(await _wait_for_token_exchange({"value": None}, 0))

    async def test_session_storage_round_trips_without_writing_to_disk(self):
        await self.page.route(
            "https://anonymous.invalid/",
            lambda route: route.fulfill(body="<main>Anonymous test origin</main>"),
        )
        await self.page.goto("https://anonymous.invalid/")

        await _restore_session_storage(self.page, {"opaque-test-key": "opaque-test-value"})

        self.assertEqual(
            await _read_session_storage(self.page),
            {"opaque-test-key": "opaque-test-value"},
        )

    async def test_sso_handshake_submits_a_post_form_to_the_expected_path(self):
        await self.page.set_content(
            """
            <base href="https://anonymous.invalid/">
            <script>
              HTMLFormElement.prototype.submit = function () {
                document.title = `${this.method}:${new URL(this.action).pathname}`;
              };
            </script>
            """
        )

        await self.page.evaluate(_SSO_SUBMIT_SCRIPT)
        await self.page.wait_for_function(
            "() => document.title === 'post:/ssoacces'", timeout=5_000
        )


if __name__ == "__main__":
    unittest.main()
