"""The digital-authentification login: screens rendered in place, not pages.

A fake Playwright page plays the Angular app: the URL stays on `/connexion`
while the password is checked, then the app shows the strong-authentication
notice, the approval screen or a `/message/<code>` route. Synthetic fixtures
only.
"""

import json
import time
import unittest
from unittest.mock import patch

from fastapi import HTTPException

import bank

FORM_URL = bank.BASE + "/auth/digital-authentification/connexion"
CLIENT_AREA = bank.BASE + "/voscomptes/canalXHTML/comptesCommun/fixture.ea"
KEYS = list("6892175304")
POSITIONS = [1, 0, 2, 3, 4, 5]
KEYPAD = "div[data-tb-cvd-id='password'] button"
NOTICE_BUTTON = f"{bank.SPA_AF_NOTICE} button"


class FakeLocator:
    def __init__(self, page, selector, index=None):
        self.page = page
        self.selector = selector
        self.index = index

    async def is_visible(self):
        return False

    async def all_text_contents(self):
        return list(KEYS)

    async def count(self):
        if self.selector == KEYPAD:
            return len(KEYS)
        return 1 if self.selector == self.page.screen else 0

    def nth(self, index):
        return FakeLocator(self.page, self.selector, index)

    async def click(self):
        self.page.clicked.append((self.selector, self.index))


class FakeSpaPage:
    """``after`` maps a clicked selector to the screen tag or URL it leads to."""

    def __init__(self, after):
        self._urls = [FORM_URL]
        self.screen = None
        self.after = after
        self.clicked: list = []
        self.body = "<html><body>fixture</body></html>"

    @property
    def url(self):
        # A queued redirect shows each URL once, as a hop the caller can see.
        current = self._urls[0]
        if len(self._urls) > 1:
            self._urls.pop(0)
        return current

    @url.setter
    def url(self, value):
        self._urls = [value]

    def locator(self, selector):
        return FakeLocator(self, selector)

    async def click(self, selector, timeout=None):
        self.clicked.append((selector, None))
        outcome = self.after.get(selector)
        if outcome is None:
            return
        if isinstance(outcome, list):
            self.screen = None
            self._urls = list(outcome)
        elif outcome.startswith("https://"):
            self.screen = None
            self.url = outcome
        else:
            self.screen = outcome

    async def wait_for_load_state(self, state, timeout=None):
        return None

    async def content(self):
        return self.body


class FakeContext:
    async def cookies(self):
        return [{"name": "XO1_SESSIONID", "value": "fixture", "domain": "voscomptesenligne.labanquepostale.fr"}]

    async def close(self):
        return None


async def keypad(page):
    client = bank.BrowserBankClient()
    state = {"page": page, "keys": list(KEYS), "context": FakeContext(), "created_at": time.time()}
    await client.pending.put_or_raise("proc", state)
    await client.keypad("proc", POSITIONS)
    return client


class SpaLoginTest(unittest.IsolatedAsyncioTestCase):
    async def test_the_in_place_notice_is_continued_to_the_approval_screen(self):
        page = FakeSpaPage(
            {"#btnConfirmer": bank.SPA_AF_NOTICE, NOTICE_BUTTON: bank.SPA_AF_APPROVAL}
        )
        with self.assertLogs("lbp-auth", level="INFO") as logs:
            client = await keypad(page)
        self.assertIn((NOTICE_BUTTON, None), page.clicked)
        self.assertTrue(any("awaits mobile approval" in line for line in logs.output))

        # The approved app leaves `/auth/` for the client area.
        page.url = CLIENT_AREA
        page.body = "<html><body><a>Se déconnecter</a></body></html>"
        jar = json.loads(await client.complete("proc"))
        self.assertEqual([cookie["name"] for cookie in jar["cookies"]], ["XO1_SESSIONID"])

    async def test_the_keypad_clicks_positions_then_confirms(self):
        page = FakeSpaPage({"#btnConfirmer": bank.SPA_AF_APPROVAL})
        await keypad(page)
        self.assertEqual(
            page.clicked,
            [(KEYPAD, position) for position in POSITIONS] + [("#btnConfirmer", None)],
        )

    async def test_a_refused_password_route_is_invalid_credentials(self):
        page = FakeSpaPage(
            {"#btnConfirmer": FORM_URL.replace("/connexion", "/message/0x132120c8")}
        )
        with self.assertRaises(HTTPException) as raised:
            await keypad(page)
        self.assertEqual(raised.exception.status_code, 401)
        self.assertEqual(raised.exception.detail, "INVALID_CREDENTIALS")

    async def test_another_message_route_is_upstream_unavailable(self):
        page = FakeSpaPage(
            {"#btnConfirmer": FORM_URL.replace("/connexion", "/message/0x13212070")}
        )
        with self.assertRaises(HTTPException) as raised:
            await keypad(page)
        self.assertEqual(raised.exception.status_code, 502)

    async def test_an_sms_challenge_is_refused_explicitly(self):
        page = FakeSpaPage(
            {
                "#btnConfirmer": bank.SPA_AF_NOTICE,
                NOTICE_BUTTON: "hj9-authentification-numero-securise",
            }
        )
        with self.assertLogs("lbp-auth", level="WARNING") as logs:
            with self.assertRaises(HTTPException) as raised:
                await keypad(page)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertTrue(any("screen=hj9-authentification-numero-securise" in line for line in logs.output))

    async def test_a_recognized_device_is_classified_after_the_session_route(self):
        # The session route stays the current URL for a few reads, as when the
        # page redirects by script rather than by a 302.
        routing = bank.BASE + "/auth/routing/connexion/session"
        synthesis = bank.BASE + bank.SYNTHESE_CCP
        page = FakeSpaPage({"#btnConfirmer": [routing, routing, routing, synthesis]})
        client = await keypad(page)
        jar = json.loads(await client.complete("proc"))
        self.assertEqual([cookie["name"] for cookie in jar["cookies"]], ["XO1_SESSIONID"])

    async def test_an_error_route_after_continuer_is_an_approval_failure(self):
        page = FakeSpaPage(
            {
                "#btnConfirmer": bank.SPA_AF_NOTICE,
                NOTICE_BUTTON: FORM_URL.replace("/connexion", "/message/AfIncidentTechnique"),
            }
        )
        with self.assertLogs("lbp-auth", level="WARNING") as logs:
            with self.assertRaises(HTTPException) as raised:
                await keypad(page)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertTrue(any("approval failed param=AfIncidentTechnique" in line for line in logs.output))

    async def test_a_refused_approval_fails_the_completion(self):
        page = FakeSpaPage({"#btnConfirmer": bank.SPA_AF_APPROVAL})
        client = await keypad(page)
        page.url = FORM_URL.replace("/connexion", "/message/AfRefusee")
        with self.assertRaises(HTTPException) as raised:
            await client.complete("proc")
        self.assertEqual(raised.exception.status_code, 502)

    async def test_the_login_app_itself_never_reads_as_logged_in(self):
        # Still inside `/auth/` (the approval screen), so markers in the page
        # are not trusted; nothing returns before the timeout.
        page = FakeSpaPage({"#btnConfirmer": bank.SPA_AF_APPROVAL})
        client = await keypad(page)
        page.body = "<html><body>Se déconnecter</body></html>"
        with patch.object(bank, "APPROVAL_TIMEOUT_SECONDS", 0.05), patch.object(
            bank, "APPROVAL_POLL_SECONDS", 0.01
        ):
            with self.assertRaises(HTTPException) as raised:
                await client.complete("proc")
        self.assertEqual(raised.exception.detail, "APP_VALIDATION_TIMEOUT")

    async def test_a_stalled_login_app_page_is_never_a_session(self):
        # Nothing follows the submit: the classify step reads a page that is
        # still the login app, so markers in it must not park a session.
        page = FakeSpaPage({})
        page.body = "<html><body>Se déconnecter</body></html>"
        with patch.object(bank, "SUBMIT_WAIT_SECONDS", 0.01):
            with self.assertRaises(HTTPException) as raised:
                await keypad(page)
        self.assertEqual(raised.exception.status_code, 502)


if __name__ == "__main__":
    unittest.main()
