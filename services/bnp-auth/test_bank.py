"""Allow-list, login outcomes, session state and the browser flows, on fake pages (no network, no browser).

All values are synthetic. Selectors and hosts that were seen on the public login pages are the ones
asserted here; the second factor is a heuristic and is only tested as "never guess".
"""

import asyncio
import base64
import json
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException

import bank

BNP = bank.BRANDS["bnp"]
HELLO = bank.BRANDS["hellobank"]
LOGIN = "https://connexion.mabanque.bnpparibas/as/authorization.oauth2?client_id=x"
STEP2 = "https://connexion.mabanque.bnpparibas/as/abc/resume/as/authorization.ping"
SECURE = "https://mabanque.bnpparibas/fr/connexion/comptes"
KEYS = ["3", "8", "1", "0", "5", "9", "2", "7", "4", "6"]
JAR = [{"name": "S", "value": "v", "domain": "mabanque.bnpparibas"}]


def run(coro):
    return asyncio.run(coro)


class AllowListTest(unittest.TestCase):
    def test_only_exact_https_brand_hosts_pass(self):
        ok = [
            "https://mabanque.bnpparibas/fr/x",
            "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2",
            "https://content.connexion.mabanque.bnpparibas/a.css",
            "https://www.hellobank.fr/",
            "https://connexion.hellobank.fr:443/as/x",
            "https://content.connexion.hellobank.fr/a.css",
            "data:text/plain,a",
            "about:blank",
        ]
        bad = [
            "http://mabanque.bnpparibas/",
            "https://mabanque.bnpparibas.evil.example/",
            "https://evil.example/mabanque.bnpparibas",
            "https://user@mabanque.bnpparibas/",
            "https://mabanque.bnpparibas:8443/",
            "https://xmabanque.bnpparibas/",
            "https://hellobank.fr/",  # not an allow-listed host: only www.
            "https://assets.adobedtm.com/x.js",
            "https://aa.online-metrix.net/fp.js",
            "https://169.254.169.254/",
            "https://localhost/",
            "ftp://mabanque.bnpparibas/",
            "//mabanque.bnpparibas/",
            "",
            None,
            5,
        ]
        for url in ok:
            with self.subTest(url=url):
                self.assertTrue(bank.is_allowed_url(url))
        for url in bad:
            with self.subTest(url=url):
                self.assertFalse(bank.is_allowed_url(url))

    def test_a_brand_never_reaches_the_other_brands_hosts(self):
        self.assertFalse(bank.is_allowed_url("https://www.hellobank.fr/", BNP.hosts))
        self.assertFalse(bank.is_allowed_url("https://connexion.mabanque.bnpparibas/", HELLO.hosts))

    def test_every_hop_of_a_redirect_chain_is_checked(self):
        guard = bank.Guard(BNP.hosts)
        clean = MagicMock(url=SECURE, redirected_from=MagicMock(url=LOGIN, redirected_from=None))
        guard.observe(clean)
        self.assertFalse(guard.tainted)
        hijacked = MagicMock(url=SECURE, redirected_from=MagicMock(url="https://evil.example/x", redirected_from=None))
        guard.observe(hijacked)
        self.assertTrue(guard.tainted)
        self.assertEqual(guard.unknown_hosts, ["evil.example"])

    def test_a_redirect_loop_is_cut_off(self):
        hop = MagicMock(url=LOGIN, redirected_from=None)
        for _ in range(bank.MAX_REDIRECTS + 1):
            hop = MagicMock(url=LOGIN, redirected_from=hop)
        guard = bank.Guard(BNP.hosts)
        guard.observe(hop)
        self.assertTrue(guard.tainted)

    def test_analytics_hosts_are_refused_quietly_and_any_other_unknown_host_stops_the_attempt(self):
        guard = bank.Guard(BNP.hosts)
        allowed, analytics, unknown = MagicMock(), MagicMock(), MagicMock()
        allowed.request.url = LOGIN
        analytics.request.url = "https://aa.online-metrix.net/fp/tags.js?org_id=x"
        unknown.request.url = "https://secure-sms.example.net/otp?token=secret"
        for route in (allowed, analytics, unknown):
            route.continue_, route.abort = AsyncMock(), AsyncMock()
        run(guard.route(allowed))
        run(guard.route(analytics))
        self.assertFalse(guard.tainted)
        run(guard.route(unknown))
        allowed.continue_.assert_awaited_once()
        analytics.abort.assert_awaited_once()
        unknown.abort.assert_awaited_once()
        self.assertEqual(guard.blocked, 2)
        self.assertTrue(guard.tainted)
        # Only the host name is kept: never the path or query.
        self.assertEqual(guard.unknown_hosts, ["secure-sms.example.net"])

    def test_the_unknown_host_log_line_names_the_host_only(self):
        guard = bank.Guard(BNP.hosts)
        with self.assertLogs("bnp-auth", level="WARNING") as logs:
            guard._unknown("https://secure-sms.example.net/otp?token=secret")
        self.assertIn("secure-sms.example.net", logs.output[0])
        self.assertNotIn("secret", logs.output[0])


class LoginOutcomeTest(unittest.TestCase):
    def failure(self, url):
        error = bank.login_failure(url)
        return (error.status_code, error.detail) if error else None

    def test_error_code_parameter_maps_to_codes(self):
        base = "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2?error_code="
        self.assertEqual(self.failure(base + "201"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "21501"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "3"), (429, "RATE_LIMITED"))
        self.assertEqual(self.failure(base + "202"), (429, "RATE_LIMITED"))
        self.assertEqual(self.failure(base + "50"), (409, "ACTION_REQUIRED"))
        self.assertEqual(self.failure(base + "1002"), (410, "AUTH_ATTEMPT_EXPIRED"))
        self.assertEqual(self.failure(base + "SecretErrorException"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "LockedAccount"), (429, "RATE_LIMITED"))
        self.assertEqual(self.failure(base + "TechnicalException"), (502, "UPSTREAM_UNAVAILABLE"))

    def test_an_unknown_error_code_is_never_read_as_a_wrong_password(self):
        base = "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2?error_code="
        for value in ("99999", "-7", "NeverSeen", "%00"):
            with self.subTest(value=value):
                self.assertEqual(self.failure(base + value), (502, "UPSTREAM_FORMAT_CHANGED"))

    def test_normal_urls_are_not_failures(self):
        self.assertIsNone(self.failure(LOGIN))
        self.assertIsNone(self.failure(SECURE))

    def test_site_and_login_urls_are_told_apart(self):
        self.assertTrue(bank.on_login(LOGIN, BNP))
        self.assertFalse(bank.on_login(LOGIN, HELLO))
        self.assertTrue(bank.on_site(SECURE, BNP))
        self.assertFalse(bank.on_site("https://mabanque.bnpparibas/auth/login", BNP))
        self.assertFalse(bank.on_site(LOGIN, BNP))

    def test_session_death_is_the_string_minus_ten(self):
        self.assertFalse(bank.session_alive({"codeRetour": "-10", "message": "user not connected"}))
        self.assertFalse(bank.session_alive({"codeRetour": -10}))
        self.assertFalse(bank.session_alive({"codeRetour": "-30"}))
        self.assertFalse(bank.session_alive("nope"))
        self.assertFalse(bank.session_alive({"codeRetour": "7"}))
        self.assertTrue(bank.session_alive({"codeRetour": 0}))
        self.assertTrue(bank.session_alive({"message": "OK"}))

    def test_second_factor_classification_never_guesses(self):
        self.assertEqual(bank.classify_second_factor(1, "Saisissez le code reçu par SMS"), "sms")
        self.assertEqual(bank.classify_second_factor(0, "Validez dans votre application Clé Digitale"), "app")
        self.assertIsNone(bank.classify_second_factor(0, "Bienvenue"))
        self.assertIsNone(bank.classify_second_factor(2, "code"))


class SessionStateTest(unittest.TestCase):
    def test_only_the_brands_cookies_survive_a_round_trip(self):
        jar = bank.encode_session(BNP, [
            {"name": "S", "value": "v", "domain": ".mabanque.bnpparibas", "path": "/", "extra": 1},
            {"name": "T", "value": "t", "domain": "connexion.mabanque.bnpparibas"},
            {"name": "X", "value": "x", "domain": "evil.example"},
            {"name": "H", "value": "h", "domain": "www.hellobank.fr"},
        ])
        brand, cookies = bank.decode_session(jar)
        self.assertIs(brand, BNP)
        self.assertEqual([c["name"] for c in cookies], ["S", "T"])
        self.assertNotIn("extra", cookies[0])

    def test_nothing_to_keep_is_a_failure_not_an_empty_session(self):
        with self.assertRaises(HTTPException) as caught:
            bank.encode_session(BNP, [{"name": "X", "value": "x", "domain": "evil.example"}])
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_a_tampered_state_is_refused(self):
        def enc(data):
            return base64.b64encode(json.dumps(data).encode()).decode()

        good = {"name": "S", "value": "v", "domain": "mabanque.bnpparibas"}
        for state in (
            "not base64!", enc({"v": 1, "brand": "bnp", "cookies": [good]}), enc({"v": 2, "brand": "other", "cookies": [good]}),
            enc({"v": 2, "brand": "bnp", "cookies": []}),
            enc({"v": 2, "brand": "bnp", "cookies": [{"name": "S", "value": "v", "domain": "evil.example"}]}),
            enc({"v": 2, "brand": "bnp", "cookies": [{"name": 1, "value": "v", "domain": "mabanque.bnpparibas"}]}), enc([]),
        ):
            with self.subTest(state=state[:20]), self.assertRaises(HTTPException) as caught:
                bank.decode_session(state)
            self.assertEqual(caught.exception.detail, "INVALID_SESSION_STATE")


class FakeKeys:
    def __init__(self, page):
        self.page = page

    def nth(self, index):
        button = MagicMock()
        button.click = AsyncMock(side_effect=lambda **_: self.page.pressed.append(index))
        return button


class FakePage:
    def __init__(self, url=LOGIN):
        self.url = url
        self.results = []
        self.evaluated = []
        self.filled = []
        self.pressed = []
        self.clicked = []
        self.codes = 0
        self.text = ""
        self.on_submit = lambda: None
        self.sms_typed = []

    async def goto(self, url, **_):
        return None  # the real browser follows the redirect; tests set `url` up front

    async def evaluate(self, script, arg=None):
        self.evaluated.append((script, arg))
        return self.results.pop(0)

    async def fill(self, selector, value, **_):
        self.filled.append((selector, value))

    def get_by_role(self, *_, **__):
        button = MagicMock()
        button.click = AsyncMock(side_effect=lambda **_: self.clicked.append("continue"))
        return button

    async def wait_for_selector(self, *_, **__):
        return None

    def locator(self, selector):
        if selector == bank.KEY_BUTTONS:
            return FakeKeys(self)
        field = MagicMock()
        field.fill = AsyncMock(side_effect=lambda value, **_: self.sms_typed.append(value))
        field.press = AsyncMock()
        return MagicMock(first=field)

    async def click(self, selector, **_):
        self.clicked.append(selector)
        if self.on_submit:
            self.on_submit()

    async def query_selector_all(self, _):
        return [MagicMock(is_visible=AsyncMock(return_value=True)) for _ in range(self.codes)]

    async def inner_text(self, *_, **__):
        return self.text


def state_for(page, brand=BNP, **extra):
    guard = bank.Guard(brand.hosts)
    context = MagicMock(cookies=AsyncMock(return_value=list(JAR)), add_cookies=AsyncMock())
    return {"brand": brand, "page": page, "guard": guard, "context": context, "created_at": time.time(), **extra}


def form(**over):
    found = {
        "form": True, "user": True, "grid": True, "submit": True, "pad": True, "method": "post",
        "action": "https://connexion.mabanque.bnpparibas/as/abc/resume/as/authorization.ping",
        "keys": list(KEYS), "columns": 5,
    }
    found.update(over)
    return found


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.client = bank.BrowserBankClient()
        for target, value in (("_close", AsyncMock()), ("MIN_LOGIN_GAP", 0), ("POLL_SECONDS", 0.01)):
            patcher = patch.object(bank, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def initiate(self, page, *, brand="bnp", results=None, state=None):
        page.results = results if results is not None else [form(), form()]
        with patch.object(bank, "launch", AsyncMock(return_value=state or state_for(page))):
            return run(self.client.initiate("1234567890", brand))

    def test_initiate_returns_ten_digit_keys_and_parks_the_attempt(self):
        page = FakePage()
        result = self.initiate(page)
        self.assertEqual(result["keypad"], {"keys": KEYS, "columns": 5})
        self.assertIn(result["processId"], self.client.pending)
        self.assertEqual(page.filled, [(bank.USER_INPUT, "1234567890")])
        self.assertEqual(page.clicked, ["continue"])
        self.assertNotIn("1234567890", json.dumps(result))

    def test_a_page_that_is_not_the_expected_form_is_a_changed_keypad(self):
        for over in ({"form": False}, {"user": False}, {"grid": False}, {"submit": False}, {"pad": False},
                     {"method": "get"}, {"action": "https://evil.example/as/x/authorization.ping"},
                     {"action": "https://connexion.mabanque.bnpparibas/other"},
                     {"action": "http://connexion.mabanque.bnpparibas/as/x/authorization.ping"}):
            page = FakePage()
            with self.subTest(over=over), self.assertRaises(HTTPException) as caught:
                self.initiate(page, results=[form(**over)])
            self.assertEqual((caught.exception.status_code, caught.exception.detail), (409, "KEYPAD_CHANGED"))
            # A refused page never receives the customer number.
            self.assertEqual(page.filled, [])
        self.assertEqual(len(self.client.pending), 0)

    def test_a_keypad_that_is_not_ten_distinct_digits_in_five_columns_is_refused(self):
        bad = [KEYS[:9], KEYS + ["1"], ["a"] + KEYS[1:], ["1"] * 10, ["12"] + KEYS[1:], [3] + KEYS[1:]]
        for keys in bad:
            with self.subTest(keys=keys), self.assertRaises(HTTPException) as caught:
                self.initiate(FakePage(), results=[form(), form(keys=keys)])
            self.assertEqual(caught.exception.detail, "KEYPAD_CHANGED")
        with self.assertRaises(HTTPException):
            self.initiate(FakePage(), results=[form(), form(columns=4)])

    def test_an_unexpected_start_page_is_a_changed_keypad(self):
        page = FakePage("https://mabanque.bnpparibas/auth/login")
        page.goto = AsyncMock()
        with patch.object(bank, "launch", AsyncMock(return_value=state_for(page))), self.assertRaises(HTTPException) as caught:
            run(self.client.initiate("1234567890", "bnp"))
        self.assertEqual(caught.exception.detail, "KEYPAD_CHANGED")

    def test_an_unknown_host_during_the_load_is_reported(self):
        page = FakePage()
        state = state_for(page)
        state["guard"]._unknown("https://sso.unknown.example/x")
        with self.assertRaises(HTTPException) as caught:
            self.initiate(page, state=state)
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_unknown_brands_and_a_full_pool_are_refused_before_any_browser(self):
        with self.assertRaises(HTTPException):
            run(self.client.initiate("1234567890", "other"))
        self.client.pending._states.update({str(i): {"created_at": time.time()} for i in range(bank.MAX_PENDING)})
        with patch.object(bank, "launch", AsyncMock()) as launch, self.assertRaises(HTTPException) as caught:
            run(self.client.initiate("1234567890", "bnp"))
        self.assertEqual(caught.exception.status_code, 503)
        launch.assert_not_awaited()

    def test_two_logins_in_a_row_are_spaced_out(self):
        with patch.object(bank, "MIN_LOGIN_GAP", 60):
            self.client._last_login = time.monotonic()
            with patch.object(bank, "launch", AsyncMock()) as launch, self.assertRaises(HTTPException) as caught:
                run(self.client.initiate("1234567890", "bnp"))
        self.assertEqual(caught.exception.detail, "RATE_LIMITED")
        launch.assert_not_awaited()

    def park(self, page, stage="keypad", **extra):
        run(self.client.pending.put("p1", state_for(page, stage=stage, **extra)))

    def keypad(self, page, positions, *, alive=None, text="", codes=0):
        page.results = [{"value": "".join(str(p) for p in positions), "enabled": True}]
        page.text, page.codes = text, codes
        self.park(page, submit_url=LOGIN)
        if page.url == LOGIN:
            page.on_submit = lambda: setattr(page, "url", STEP2)
        with patch.object(bank, "probe_session", AsyncMock(return_value=alive)), patch.object(bank, "STABLE_SECONDS", 0.2):
            return run(self.client.keypad("p1", positions))

    def test_the_keypad_positions_are_clicked_in_order_and_submitted_once(self):
        page = FakePage()
        status = self.keypad(page, [0, 9, 2, 3, 4, 5], codes=1, text="code")
        self.assertEqual(status, "CODE_REQUIRED")
        self.assertEqual(page.pressed, [0, 9, 2, 3, 4, 5])
        self.assertEqual(page.clicked.count(bank.SUBMIT_BUTTON), 1)
        # The same attempt cannot send the keypad again.
        with self.assertRaises(HTTPException) as caught:
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")

    def test_the_pages_own_field_must_hold_the_clicked_positions_before_submit(self):
        for pressed in ({"value": "012", "enabled": True}, {"value": "012345", "enabled": False}):
            page = FakePage()
            page.results = [pressed]
            self.park(page, submit_url=LOGIN)
            with self.subTest(pressed=pressed), self.assertRaises(HTTPException) as caught:
                run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
            self.assertEqual(caught.exception.detail, "KEYPAD_CHANGED")
            self.assertNotIn(bank.SUBMIT_BUTTON, page.clicked)

    def test_bad_positions_never_reach_the_page(self):
        for positions in ([0, 1, 2, 3, 4], [0, 1, 2, 3, 4, 10], [0, 1, 2, 3, 4, -1]):
            page = FakePage()
            self.park(page, submit_url=LOGIN)
            with self.subTest(positions=positions), self.assertRaises(HTTPException):
                run(self.client.keypad("p1", positions))
            self.assertEqual(page.pressed, [])

    def test_an_app_approval_page_is_told_apart_from_an_sms_page(self):
        page = FakePage()
        status = self.keypad(page, [0, 1, 2, 3, 4, 5], text="Validez avec votre application Clé Digitale")
        self.assertEqual(status, "APPROVAL_PENDING")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "app")

    def test_an_unclassifiable_strong_authentication_stops_the_login(self):
        page = FakePage()
        with self.assertRaises(HTTPException) as caught:
            self.keypad(page, [0, 1, 2, 3, 4, 5], text="Bienvenue")
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (422, "MFA_TYPE_UNSUPPORTED"))
        self.assertNotIn("p1", self.client.pending)

    def test_a_login_without_second_factor_goes_straight_to_the_session(self):
        page = FakePage(SECURE)
        status = self.keypad(page, [0, 1, 2, 3, 4, 5], alive=True)
        self.assertEqual(status, "APPROVAL_PENDING")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "none")

    def test_a_refusal_after_the_keypad_ends_the_attempt_with_the_banks_reason(self):
        page = FakePage()
        page.results = [{"value": "012345", "enabled": True}]
        self.park(page, submit_url=LOGIN)
        page.on_submit = lambda: setattr(page, "url", "https://connexion.mabanque.bnpparibas/as/authorization.oauth2?error_code=201")
        with patch.object(bank, "probe_session", AsyncMock(return_value=False)), self.assertRaises(HTTPException) as caught:
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "INVALID_CREDENTIALS"))
        self.assertNotIn("p1", self.client.pending)

    def test_a_redirect_to_an_unknown_host_after_submit_is_reported_not_followed(self):
        page = FakePage()
        page.results = [{"value": "012345", "enabled": True}]
        state = state_for(page, stage="keypad", submit_url=LOGIN)
        run(self.client.pending.put("p1", state))
        page.on_submit = lambda: state["guard"]._unknown("https://mfa.unknown.example/x")
        with patch.object(bank, "probe_session", AsyncMock(return_value=None)), self.assertRaises(HTTPException) as caught:
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        self.assertNotIn("p1", self.client.pending)

    def test_a_tainted_browser_never_clicks(self):
        page = FakePage()
        state = state_for(page, stage="keypad", submit_url=LOGIN)
        state["guard"].tainted = True
        run(self.client.pending.put("p1", state))
        with self.assertRaises(HTTPException):
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(page.pressed, [])

    def test_complete_without_a_matching_attempt_is_expired(self):
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("nope", None))
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")
        self.park(FakePage())  # still at the keypad stage
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", "123456"))
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")

    def test_an_sms_login_needs_a_code_and_returns_only_the_cookie_jar(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="sms")
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", None))
        self.assertEqual(caught.exception.detail, "INVALID_OTP")
        self.park(page, stage="mfa", kind="sms")
        with patch.object(bank, "probe_session", AsyncMock(return_value=True)):
            jar = run(self.client.complete("p1", "123456"))
        self.assertEqual(page.sms_typed, ["123456"])
        self.assertNotIn("123456", jar)
        self.assertIs(bank.decode_session(jar)[0], BNP)
        self.assertNotIn("p1", self.client.pending)

    def test_a_wrong_sms_code_keeps_the_attempt_for_one_more_try(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="sms")
        with patch.object(bank, "SMS_SETTLE_SECONDS", 0.05), patch.object(bank, "probe_session", AsyncMock(return_value=False)), \
                self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", "000000"))
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "INVALID_OTP"))
        self.assertIn("p1", self.client.pending)

    def test_an_app_approval_is_waited_for_then_returns_the_jar(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="app")
        answers = iter([False, False, True])
        with patch.object(bank, "probe_session", AsyncMock(side_effect=lambda *_: next(answers))):
            jar = run(self.client.complete("p1", None))
        self.assertIs(bank.decode_session(jar)[0], BNP)

    def test_an_app_approval_that_never_comes_times_out_and_closes_the_browser(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="app")
        with patch.object(bank, "APP_APPROVAL_SECONDS", 0.05), patch.object(bank, "probe_session", AsyncMock(return_value=False)), \
                self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", None))
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (408, "APP_VALIDATION_TIMEOUT"))
        self.assertNotIn("p1", self.client.pending)

    def test_a_code_sent_to_an_app_approval_is_refused_and_never_typed(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="app")
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", "123456"))
        self.assertEqual(caught.exception.detail, "INVALID_REQUEST")
        self.assertEqual(page.sms_typed, [])

    def test_a_login_that_never_reaches_a_session_is_not_a_session(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="none")
        with patch.object(bank, "probe_session", AsyncMock(return_value=False)), self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", None))
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def accounts(self, page, fetch):
        jar = bank.encode_session(BNP, JAR)
        page.goto = AsyncMock()
        st = state_for(page)
        with patch.object(bank, "launch", AsyncMock(return_value=st)), patch.object(bank, "fetch_json", fetch):
            return run(self.client.accounts(jar)), st

    def test_accounts_with_a_dead_session_asks_for_a_new_login(self):
        async def fetch(*_a, **_k):
            raise AssertionError("not asked off the secure site")

        with self.assertRaises(HTTPException) as caught:
            self.accounts(FakePage(LOGIN), fetch)  # the bank bounced back to the login page
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "SESSION_EXPIRED"))

    def test_accounts_with_the_minus_ten_answer_asks_for_a_new_login(self):
        async def fetch(_page, key, body=None):
            return {"codeRetour": "-10", "message": "user not connected"}

        with self.assertRaises(HTTPException) as caught:
            self.accounts(FakePage(SECURE), fetch)
        self.assertEqual(caught.exception.detail, "SESSION_EXPIRED")

    def test_accounts_returns_the_snapshot_and_the_rotated_jar(self):
        answers = {
            bank.INFOS_CLIENT: {"codeRetour": 0, "message": "OK"},
            "ibans": {"codeRetour": 0, "data": {"listeRib": []}},
            "accounts": {"codeRetour": 0, "data": {"infoUdc": {"familleCompte": [
                {"idFamilleCompte": "8", "compte": [{"key": "k", "value": "1234", "libelleProduit": "Credit immobilier", "soldeDispo": -10}]}]}}},
        }

        async def fetch(_page, key, body=None):
            return answers[key]

        result, st = self.accounts(FakePage(SECURE), fetch)
        self.assertEqual(result["brand"], "bnp")
        self.assertEqual([(a["kind"], a["balance"]) for a in result["accounts"]], [("LOAN", "-10.00")])
        self.assertIs(bank.decode_session(result["sessionState"])[0], BNP)
        st["context"].add_cookies.assert_awaited_once()

    def test_a_dead_session_during_the_snapshot_is_not_an_incomplete_account(self):
        async def fetch(_page, key, body=None):
            if key == bank.INFOS_CLIENT:
                return {"codeRetour": 0, "message": "OK"}
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

        with self.assertRaises(HTTPException) as caught:
            self.accounts(FakePage(SECURE), fetch)
        self.assertEqual(caught.exception.detail, "SESSION_EXPIRED")


class FetchTest(unittest.TestCase):
    def fetch(self, result):
        page = FakePage(SECURE)
        page.results = [result]
        return run(bank.fetch_json(page, "accounts"))

    def test_status_mapping(self):
        self.assertEqual(self.fetch({"status": 200, "text": '{"codeRetour": 0}'})["codeRetour"], 0)
        for status, detail in ((401, "SESSION_EXPIRED"), (403, "SESSION_EXPIRED"), (500, "UPSTREAM_UNAVAILABLE")):
            with self.subTest(status=status), self.assertRaises(HTTPException) as caught:
                self.fetch({"status": status, "text": ""})
            self.assertEqual(caught.exception.detail, detail)
        with self.assertRaises(HTTPException):
            self.fetch(None)

    def test_only_the_fixed_endpoints_are_ever_requested(self):
        self.assertTrue(all(method in ("GET", "POST") and path.startswith("/") and "//" not in path
                            for method, path in bank.ENDPOINTS.values()))
        self.assertEqual({path.split("/")[1] for _, path in bank.ENDPOINTS.values()},
                         {"udc-wspl", "rib-wspl", "rop2-wspl", "pe-war"})


if __name__ == "__main__":
    unittest.main()
