"""Fresh-login flow must wait for the first SSO screen instead of sniffing the URL once.

app.revolut.com redirects to sso.revolut.com/signin; a known device only reaches /passcode
after a delay. After the phone step an hCaptcha challenge or an unknown page must fail fast
instead of entering the mobile-approval wait. These tests drive `sync()` with a fake page
(no real browser).
"""

import contextlib
import json
import unittest
from unittest.mock import patch

from playwright.async_api import TimeoutError as PlaywrightTimeout

import main


class FakeLocator:
    def __init__(self, page, kind):
        self.page, self.kind = page, kind

    async def count(self):
        return self.page.counts[self.kind]


class FakePage:
    """`script` maps a wait_for_timeout call number to a state update."""

    def __init__(self, script, url="https://sso.revolut.com/signin?x=1"):
        self.url = url
        self.counts = {"phone": 0, "textbox": 0, "captcha": 0, **{sel: 0 for sel in main._PASSCODE_FIELD_INPUTS}}
        self.script = script
        self.waits = 0
        self.goto_error = None
        self.approval_polls = 0  # times the mobile-approval loop asked whether login completed

    async def goto(self, *_a, **_k):
        if self.goto_error:
            raise self.goto_error

    async def wait_for_timeout(self, _ms):
        self.waits += 1
        update = self.script.get(self.waits)
        if update:
            update(self)

    def locator(self, selector):
        kinds = {main._PHONE_INPUT: "phone",
                 **{sel: sel for sel in main._PASSCODE_FIELD_INPUTS}}
        return FakeLocator(self, kinds[selector])

    def get_by_role(self, role):
        if role != "textbox":
            raise AssertionError(role)
        return FakeLocator(self, "textbox")


def _show_passcode_boxes(page):
    page.counts["textbox"] = 6


def _show_passcode(page):
    page.url = "https://sso.revolut.com/passcode?x=1"
    _show_passcode_boxes(page)


def _show_phone(page):
    page.counts["phone"] = 1


def _show_captcha(page):
    page.counts["captcha"] = 1  # the phone field stays on screen under the challenge


@contextlib.asynccontextmanager
async def _fake_camoufox(page):
    class Ctx:
        async def new_page(self):
            return page

    yield Ctx()


async def _run_sync(test, page, calls, *, after_phone=_show_passcode, passcode_typed=True,
                    typed_with_inputs=None, after_passcode=None, approval_after_polls=1):
    """`after_phone` is what the page does once the phone number is submitted.

    `test` is the running TestCase; every patch is undone through its cleanups.
    """
    def monkeypatch_setattr(target, name, value):
        patcher = patch.object(target, name, value)
        patcher.start()
        test.addCleanup(patcher.stop)

    typed_with_inputs = [] if typed_with_inputs is None else typed_with_inputs

    async def no_reuse(_member_id):
        return None

    async def fill_phone(p, _phone):
        calls.append("phone")
        after_phone(p)

    async def fill_passcode(p, _passcode):
        calls.append("passcode")
        typed_with_inputs.append(p.counts["textbox"] >= 6)
        if after_passcode:
            after_passcode(p)
        return passcode_typed

    async def logged_in(p):
        p.approval_polls += 1
        return p.approval_polls >= approval_after_polls

    async def captcha_visible(p):
        return bool(p.counts["captcha"])

    async def device_id(_ctx):
        return "dev"

    async def settle(_p):
        pass

    async def harvest(_p, _device_id, on_progress=None):
        return {"accounts": []}

    monkeypatch_setattr(main, "_harvest_from_profile", no_reuse)
    monkeypatch_setattr(main, "_open_camoufox", lambda *_a, **_k: _fake_camoufox(page))
    monkeypatch_setattr(main, "_fill_phone", fill_phone)
    monkeypatch_setattr(main, "_fill_passcode", fill_passcode)
    monkeypatch_setattr(main, "_logged_in", logged_in)
    monkeypatch_setattr(main.captcha, "visible", captcha_visible)
    monkeypatch_setattr(main, "_device_id", device_id)
    monkeypatch_setattr(main, "_settle", settle)
    monkeypatch_setattr(main, "harvest_accounts", harvest)
    main._member_locks.clear()
    main._progress.clear()
    req = main.SyncRequest(phoneNumber="0600000000", passcode="123456", memberId="1")
    return await main.sync(req)


def _assert_login_aborted(test, result, status, error, page, calls, expected_calls):
    """The fail-fast contract: a flat error body, and the mobile-approval wait never started."""
    test.assertEqual(result.status_code, status)
    test.assertEqual(json.loads(result.body), {"error": error})
    test.assertEqual(calls, expected_calls)
    test.assertEqual(page.approval_polls, 0)
    test.assertNotEqual(main._progress[main._profile_key("1")]["phase"], "AWAITING_APPROVAL")


class LoginScreenTest(unittest.IsolatedAsyncioTestCase):
    async def test_known_device_lands_on_passcode_after_delay_without_filling_phone(self):
        page = FakePage({3: _show_passcode})  # still /signin with no inputs for the first polls
        calls = []

        result = await _run_sync(self, page, calls)

        self.assertEqual(result, {"accounts": []})
        self.assertEqual(calls, ["passcode"])

    async def test_phone_screen_fills_phone_then_passcode(self):
        page = FakePage({2: _show_phone})
        calls = []

        result = await _run_sync(self, page, calls)

        self.assertEqual(result, {"accounts": []})
        self.assertEqual(calls, ["phone", "passcode"])

    async def test_no_login_screen_returns_structured_error(self):
        page = FakePage({})
        calls = []

        result = await _run_sync(self, page, calls)

        self.assertEqual(result.status_code, 504)
        self.assertEqual(json.loads(result.body), {"error": "LOGIN_SCREEN_TIMEOUT"})
        self.assertEqual(calls, [])
        self.assertEqual(page.waits, main.LOGIN_SCREEN_WAIT_S * 1000 // main.LOGIN_SCREEN_POLL_MS)

    async def test_passcode_url_waits_for_the_inputs_before_typing(self):
        # The SPA switches to /passcode before the 6 boxes render: typing then would hit nothing.
        page = FakePage({3: _show_passcode_boxes}, url="https://sso.revolut.com/passcode")
        calls, typed_with_inputs = [], []

        result = await _run_sync(self, page, calls, typed_with_inputs=typed_with_inputs)

        self.assertEqual(result, {"accounts": []})
        self.assertEqual(calls, ["passcode"])
        self.assertEqual(typed_with_inputs, [True])
        self.assertGreaterEqual(page.waits, 3)

    async def test_passcode_in_query_string_is_not_the_passcode_screen(self):
        page = FakePage({2: _show_phone}, url="https://sso.revolut.com/signin?redirect=/passcode")
        calls = []

        result = await _run_sync(self, page, calls)

        self.assertEqual(result, {"accounts": []})
        self.assertEqual(calls, ["phone", "passcode"])

    async def _assert_single_passcode_field_counts_as_the_passcode_screen(self, selector):
        page = FakePage({2: lambda p: p.counts.update({selector: 1})})
        calls = []

        result = await _run_sync(self, page, calls)

        self.assertEqual(result, {"accounts": []})
        self.assertEqual(calls, ["passcode"])

    async def test_captcha_after_phone_step_fails_fast_without_waiting_for_approval(self):
        page = FakePage({2: _show_phone})
        calls = []

        result = await _run_sync(self, page, calls, after_phone=_show_captcha)

        _assert_login_aborted(self, result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, ["phone"])

    async def test_captcha_ends_the_post_phone_wait_without_burning_the_whole_budget(self):
        page = FakePage({2: _show_phone})

        await _run_sync(self, page, [], after_phone=_show_captcha)

        self.assertLess(page.waits, 10)

    async def test_captcha_on_the_first_screen_fails_fast_without_filling_the_phone(self):
        page = FakePage({2: _show_captcha})
        calls = []

        result = await _run_sync(self, page, calls)

        _assert_login_aborted(self, result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, [])

    async def test_unknown_state_after_phone_step_is_upstream_format_changed(self):
        page = FakePage({2: _show_phone})
        calls = []

        result = await _run_sync(self, page, calls, after_phone=lambda _page: None)

        _assert_login_aborted(self, result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, ["phone"])
        self.assertGreaterEqual(page.waits, main.LOGIN_SCREEN_WAIT_S * 1000 // main.LOGIN_SCREEN_POLL_MS)

    async def test_passcode_screen_without_a_passcode_input_is_upstream_format_changed(self):
        page = FakePage({3: _show_passcode})
        calls = []

        result = await _run_sync(self, page, calls, passcode_typed=False)

        _assert_login_aborted(self, result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, ["passcode"])

    async def test_playwright_timeout_in_a_login_step_is_upstream_format_changed(self):
        page = FakePage({2: _show_phone})
        calls = []

        def click_times_out(_page):
            raise PlaywrightTimeout("Locator.click: Timeout 30000ms exceeded.")

        result = await _run_sync(self, page, calls, after_phone=click_times_out)

        _assert_login_aborted(self, result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, ["phone"])

    async def test_captcha_wins_over_a_phone_field_on_the_first_screen(self):
        # A profile challenged last time shows the phone field with the challenge on top.
        page = FakePage({2: lambda p: (_show_phone(p), _show_captcha(p))})
        calls = []

        result = await _run_sync(self, page, calls)

        _assert_login_aborted(self, result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, [])

    async def test_goto_timeout_is_upstream_format_changed(self):
        page = FakePage({})
        page.goto_error = PlaywrightTimeout("Page.goto: Timeout 45000ms exceeded.")
        calls = []

        result = await _run_sync(self, page, calls)

        _assert_login_aborted(self, result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, [])

    async def _assert_captcha_blocks_visible_passcode_input(self, passcode_input):
        page = FakePage({})
        page.counts.update({"captcha": 1, passcode_input: 6 if passcode_input == "textbox" else 1})
        calls = []

        result = await _run_sync(self, page, calls)

        _assert_login_aborted(self, result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, [])

    async def test_captcha_blocks_passcode_inputs_after_phone_submission(self):
        page = FakePage({2: _show_phone})
        calls = []

        def passcode_under_captcha(p):
            _show_passcode(p)
            _show_captcha(p)

        result = await _run_sync(self, page, calls, after_phone=passcode_under_captcha)

        _assert_login_aborted(self, result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, ["phone"])

    async def test_captcha_after_passcode_submission_never_starts_approval_countdown(self):
        page = FakePage({2: _show_passcode})
        calls = []

        result = await _run_sync(self, page, calls, after_passcode=_show_captcha)

        _assert_login_aborted(self, result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, ["passcode"])
        progress = await main.progress("1")
        self.assertNotIn("remainingSeconds", progress)
        self.assertNotIn("elapsedSeconds", progress)

    async def test_captcha_during_approval_ends_wait_and_clears_countdown(self):
        page = FakePage({2: _show_passcode, 3: _show_captcha})

        result = await _run_sync(self, page, [], approval_after_polls=2)

        self.assertEqual(result.status_code, 403)
        self.assertEqual(json.loads(result.body), {"error": "HUMAN_VERIFICATION_REQUIRED"})
        self.assertEqual(page.approval_polls, 1)
        progress = await main.progress("1")
        self.assertNotEqual(progress["phase"], "AWAITING_APPROVAL")
        self.assertNotIn("remainingSeconds", progress)
        self.assertNotIn("elapsedSeconds", progress)

    async def test_approval_without_a_captcha_keeps_existing_timeout(self):
        page = FakePage({2: _show_passcode})

        result = await _run_sync(self, page, [], approval_after_polls=float("inf"))

        self.assertEqual(result.status_code, 408)
        self.assertEqual(json.loads(result.body), {"error": "APPROVAL_TIMEOUT"})
        self.assertEqual(page.approval_polls, main.ENROLMENT_APPROVE_WAIT_S * 1000 // main.POLL_MS)


def _add_cases(prefix, helper, cases):
    for label, arg in cases:
        async def case(self, _helper=helper, _arg=arg):
            await getattr(self, _helper)(_arg)
        case.__name__ = f"{prefix}_{label}"
        setattr(LoginScreenTest, case.__name__, case)


_add_cases("test_single_passcode_field_counts_as_the_passcode_screen",
           "_assert_single_passcode_field_counts_as_the_passcode_screen",
           [("password_input", main._PASSCODE_FIELD_INPUTS[0]), ("numeric_input", main._PASSCODE_FIELD_INPUTS[1])])
_add_cases("test_captcha_blocks_visible_passcode_inputs",
           "_assert_captcha_blocks_visible_passcode_input",
           [("textbox", "textbox"), ("password_input", main._PASSCODE_FIELD_INPUTS[0]),
            ("numeric_input", main._PASSCODE_FIELD_INPUTS[1])])


if __name__ == "__main__":
    unittest.main()
