"""Fresh-login flow must wait for the first SSO screen instead of sniffing the URL once.

app.revolut.com redirects to sso.revolut.com/signin; a known device only reaches /passcode
after a delay. After the phone step an hCaptcha challenge or an unknown page must fail fast
instead of entering the mobile-approval wait. These tests drive `sync()` with a fake page
(no real browser).
"""

import contextlib
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from playwright.async_api import TimeoutError as PlaywrightTimeout  # noqa: E402

import main  # noqa: E402
import viewer  # noqa: E402


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
        self.frames = []  # the real page lists its frames; nothing here needs any

    async def goto(self, *_a, **_k):
        if self.goto_error:
            raise self.goto_error

    async def wait_for_timeout(self, _ms):
        self.waits += 1
        update = self.script.get(self.waits)
        if update:
            update(self)

    def locator(self, selector):
        kinds = {main._PHONE_INPUT: "phone", **{sel: sel for sel in main._PASSCODE_FIELD_INPUTS}}
        return FakeLocator(self, kinds[selector])

    def get_by_role(self, role):
        assert role == "textbox"
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


async def _no_viewer():
    raise viewer.ViewerUnavailable("no remote view in this test")


async def _run_sync(monkeypatch, page, calls, *, after_phone=_show_passcode, passcode_typed=True,
                    typed_with_inputs=None, logged_in=None, start_viewer=_no_viewer, keep_state=False):
    """`after_phone` is what the page does once the phone number is submitted. `logged_in` replaces
    the approval check (default: approved at once); `start_viewer` the captcha remote view (default:
    unavailable, so a captcha is reported at once instead of being handed to the user). Per-member
    files survive the call when `keep_state` (to test the next login of the same member)."""
    typed_with_inputs = [] if typed_with_inputs is None else typed_with_inputs

    async def no_reuse(_member_id):
        return None

    async def fill_phone(p, _phone):
        calls.append("phone")
        after_phone(p)

    async def fill_passcode(p, _passcode):
        calls.append("passcode")
        typed_with_inputs.append(p.counts["textbox"] >= 6)
        return passcode_typed

    async def approval_check(p):
        p.approval_polls += 1
        return True if logged_in is None else logged_in(p)

    async def captcha_shown(p):
        return p.counts["captcha"] > 0

    async def device_id(_ctx):
        return "dev"

    async def settle(_p):
        pass

    async def harvest(_p, _device_id, on_progress=None):
        return {"accounts": []}

    monkeypatch.setattr(main, "_harvest_from_profile", no_reuse)
    monkeypatch.setattr(main, "_open_camoufox", lambda *_a, **_k: _fake_camoufox(page))
    monkeypatch.setattr(main, "_fill_phone", fill_phone)
    monkeypatch.setattr(main, "_fill_passcode", fill_passcode)
    monkeypatch.setattr(main, "_logged_in", approval_check)
    monkeypatch.setattr(main.captcha, "visible", captcha_shown)
    monkeypatch.setattr(main.viewer, "start", start_viewer)
    monkeypatch.setattr(main, "_device_id", device_id)
    monkeypatch.setattr(main, "_settle", settle)
    monkeypatch.setattr(main, "harvest_accounts", harvest)
    main._member_locks.clear()
    main._progress.clear()
    if not keep_state:
        main.member_state.clear_login_failure(main._member_file("1", "login-failed"))
    req = main.SyncRequest(phoneNumber="0600000000", passcode="123456", memberId="1")
    return await main.sync(req)


async def test_known_device_lands_on_passcode_after_delay_without_filling_phone(monkeypatch):
    page = FakePage({3: _show_passcode})  # still /signin with no inputs for the first polls
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    assert result == {"accounts": []}
    assert calls == ["passcode"]


async def test_phone_screen_fills_phone_then_passcode(monkeypatch):
    page = FakePage({2: _show_phone})
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    assert result == {"accounts": []}
    assert calls == ["phone", "passcode"]


async def test_no_login_screen_returns_structured_error(monkeypatch):
    page = FakePage({})
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    assert result.status_code == 504
    assert json.loads(result.body) == {"error": "LOGIN_SCREEN_TIMEOUT"}
    assert calls == []
    assert page.waits == main.LOGIN_SCREEN_WAIT_S * 1000 // main.LOGIN_SCREEN_POLL_MS


async def test_passcode_url_waits_for_the_inputs_before_typing(monkeypatch):
    # The SPA switches to /passcode before the 6 boxes render: typing then would hit nothing.
    page = FakePage({3: _show_passcode_boxes}, url="https://sso.revolut.com/passcode")
    calls, typed_with_inputs = [], []

    result = await _run_sync(monkeypatch, page, calls, typed_with_inputs=typed_with_inputs)

    assert result == {"accounts": []}
    assert calls == ["passcode"]
    assert typed_with_inputs == [True]
    assert page.waits >= 3


async def test_passcode_in_query_string_is_not_the_passcode_screen(monkeypatch):
    page = FakePage({2: _show_phone}, url="https://sso.revolut.com/signin?redirect=/passcode")
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    assert result == {"accounts": []}
    assert calls == ["phone", "passcode"]


@pytest.mark.parametrize("selector", main._PASSCODE_FIELD_INPUTS)
async def test_single_passcode_field_counts_as_the_passcode_screen(monkeypatch, selector):
    page = FakePage({2: lambda p: p.counts.update({selector: 1})})
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    assert result == {"accounts": []}
    assert calls == ["passcode"]


def _assert_login_aborted(result, status, error, page, calls, expected_calls):
    """The fail-fast contract: a flat error body, and the mobile-approval wait never started."""
    assert result.status_code == status
    assert json.loads(result.body) == {"error": error}
    assert calls == expected_calls
    assert page.approval_polls == 0
    assert main._progress[main._profile_key("1")]["phase"] != "AWAITING_APPROVAL"


async def test_captcha_after_phone_step_fails_fast_without_waiting_for_approval(monkeypatch):
    page = FakePage({2: _show_phone})
    calls = []

    result = await _run_sync(monkeypatch, page, calls, after_phone=_show_captcha)

    _assert_login_aborted(result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, ["phone"])


async def test_captcha_ends_the_post_phone_wait_without_burning_the_whole_budget(monkeypatch):
    page = FakePage({2: _show_phone})

    await _run_sync(monkeypatch, page, [], after_phone=_show_captcha)

    assert page.waits < 10


async def test_captcha_on_the_first_screen_fails_fast_without_filling_the_phone(monkeypatch):
    page = FakePage({2: _show_captcha})
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    _assert_login_aborted(result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, [])


async def test_unknown_state_after_phone_step_is_upstream_format_changed(monkeypatch):
    page = FakePage({2: _show_phone})
    calls = []

    result = await _run_sync(monkeypatch, page, calls, after_phone=lambda _page: None)

    _assert_login_aborted(result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, ["phone"])
    assert page.waits >= main.LOGIN_SCREEN_WAIT_S * 1000 // main.LOGIN_SCREEN_POLL_MS


async def test_passcode_screen_without_a_passcode_input_is_upstream_format_changed(monkeypatch):
    page = FakePage({3: _show_passcode})
    calls = []

    result = await _run_sync(monkeypatch, page, calls, passcode_typed=False)

    _assert_login_aborted(result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, ["passcode"])


async def test_playwright_timeout_in_a_login_step_is_upstream_format_changed(monkeypatch):
    page = FakePage({2: _show_phone})
    calls = []

    def click_times_out(_page):
        raise PlaywrightTimeout("Locator.click: Timeout 30000ms exceeded.")

    result = await _run_sync(monkeypatch, page, calls, after_phone=click_times_out)

    _assert_login_aborted(result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, ["phone"])


async def test_captcha_wins_over_a_phone_field_on_the_first_screen(monkeypatch):
    # A profile challenged last time shows the phone field with the challenge on top.
    page = FakePage({2: lambda p: (_show_phone(p), _show_captcha(p))})
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    _assert_login_aborted(result, 403, "HUMAN_VERIFICATION_REQUIRED", page, calls, [])


async def test_goto_timeout_is_upstream_format_changed(monkeypatch):
    page = FakePage({})
    page.goto_error = PlaywrightTimeout("Page.goto: Timeout 45000ms exceeded.")
    calls = []

    result = await _run_sync(monkeypatch, page, calls)

    _assert_login_aborted(result, 424, "UPSTREAM_FORMAT_CHANGED", page, calls, [])


# ─── captcha hand-off, late captcha, login pacing ────────────────────────────

class FakeViewer:
    """Stands in for viewer.start(): records its lifecycle, never spawns a process."""

    def __init__(self):
        self.opened = 0
        self.closed = 0
        self.password = "Ab3dEf9Z"

    async def start(self):
        self.opened += 1
        owner = self

        class Session:
            password = owner.password

            async def stop(self):
                owner.closed += 1

        return Session()


def _solve_captcha(page):
    page.counts["captcha"] = 0


async def test_captcha_on_the_first_screen_is_handed_to_the_user_then_the_login_goes_on(monkeypatch):
    fake = FakeViewer()
    page = FakePage({2: _show_captcha, 6: lambda p: (_solve_captcha(p), _show_phone(p))})
    calls, seen = [], []
    real_set = main._set_progress
    monkeypatch.setattr(main, "_set_progress", lambda m, phase, **kw: (seen.append((phase, kw)), real_set(m, phase, **kw)))

    result = await _run_sync(monkeypatch, page, calls, start_viewer=fake.start)

    assert result == {"accounts": []}
    assert calls == ["phone", "passcode"], "the phone was typed only after the captcha was gone"
    assert (fake.opened, fake.closed) == (1, 1)
    human = [kw for phase, kw in seen if phase == "AWAITING_HUMAN"]
    assert human and all(kw["viewerPort"] == viewer.PORT and kw["viewerPassword"] == fake.password for kw in human)
    assert main._profile_key("1") in main._progress
    assert "viewerPassword" not in main._progress[main._profile_key("1")], "the password is dropped when the view closes"


async def test_captcha_after_the_phone_step_is_handed_to_the_user_and_the_push_follows(monkeypatch):
    fake = FakeViewer()
    page = FakePage({2: _show_phone})

    def after_phone(p):
        _show_captcha(p)
        p.script[p.waits + 5] = lambda q: (_solve_captcha(q), _show_passcode(q))

    calls = []
    result = await _run_sync(monkeypatch, page, calls, after_phone=after_phone, start_viewer=fake.start)

    assert result == {"accounts": []}
    assert calls == ["phone", "passcode"]
    assert (fake.opened, fake.closed) == (1, 1)


async def test_a_captcha_that_appears_only_after_the_passcode_is_caught_during_the_approval_wait(monkeypatch):
    """The real failure of 21/10: the form was submitted, the log said 'waiting for mobile approval',
    and the captcha that showed up afterwards was never looked for."""
    fake = FakeViewer()
    page = FakePage({2: _show_passcode})
    polls = []

    def logged_in(p):
        if len(polls) == 2:  # Revolut asks only now, after the passcode; the user solves it 4 waits later
            _show_captcha(p)
            p.script[p.waits + 4] = _solve_captcha
        polls.append(p.counts["captcha"])
        return len(polls) > 3 and p.counts["captcha"] == 0  # the push is answered once it is gone

    calls = []
    result = await _run_sync(monkeypatch, page, calls, logged_in=logged_in, start_viewer=fake.start)

    assert result == {"accounts": []}
    assert (fake.opened, fake.closed) == (1, 1), "the user got the remote view"
    assert calls == ["passcode"], "the passcode is not typed again"
    assert 1 in polls, "the captcha really was on screen during the approval wait"


async def test_an_unsolved_captcha_after_the_passcode_returns_human_verification_without_waiting_out_the_approval(monkeypatch):
    fake = FakeViewer()
    page = FakePage({2: _show_passcode})
    page.approval_checks = 0

    def logged_in(p):
        p.approval_checks += 1
        if p.approval_checks == 2:
            _show_captcha(p)
        return False

    result = await _run_sync(monkeypatch, page, [], logged_in=logged_in, start_viewer=fake.start)

    assert result.status_code == 403
    assert json.loads(result.body) == {"error": "HUMAN_VERIFICATION_REQUIRED"}
    assert (fake.opened, fake.closed) == (1, 1)
    budget = main.ENROLMENT_APPROVE_WAIT_S * 1000 // main.POLL_MS
    assert page.approval_checks < budget, "the 300 s approval wait was not burned on a push that never comes"


async def test_the_hand_off_has_a_bounded_budget_shared_by_the_whole_login(monkeypatch):
    fake = FakeViewer()
    page = FakePage({2: _show_captcha})  # never solved
    budget = main.HUMAN_WAIT_S * 1000 // main.HUMAN_POLL_MS

    result = await _run_sync(monkeypatch, page, [], start_viewer=fake.start)

    assert result.status_code == 403
    assert (fake.opened, fake.closed) == (1, 1)
    assert budget <= page.waits <= budget + 20, "the wait is the hand-off budget, not unbounded"


async def _async(value):
    return value


async def test_a_captcha_that_keeps_coming_back_cannot_reopen_the_view_past_the_budget(monkeypatch):
    fake = FakeViewer()
    page = FakePage({})
    page.counts["captcha"] = 1  # never solved
    monkeypatch.setattr(main, "HUMAN_WAIT_S", 6)
    monkeypatch.setattr(main.viewer, "start", fake.start)
    monkeypatch.setattr(main.captcha, "visible", lambda p: _async(p.counts["captcha"] > 0))
    handoff = main._CaptchaHandoff(page, "1")

    first = await handoff.clear()
    second = await handoff.clear()

    assert (first, second) == (False, False)
    assert fake.opened == 1, "no second view once the shared budget is spent"


async def test_a_challenge_replaced_by_a_new_one_is_not_mistaken_for_a_solved_captcha(monkeypatch):
    fake = FakeViewer()
    page = FakePage({})
    sequence = iter([True, False, True, True, False, False])  # shown, gap, new challenge, ..., really gone
    monkeypatch.setattr(main.viewer, "start", fake.start)
    monkeypatch.setattr(main.captcha, "visible", lambda p: _async(next(sequence)))

    assert await main._CaptchaHandoff(page, "1").clear() is True
    assert next(sequence, "drained") == "drained", "it waited for two quiet polls after the last challenge"


async def test_the_view_is_closed_even_when_the_wait_is_cancelled(monkeypatch):
    import asyncio
    fake = FakeViewer()
    page = FakePage({})

    async def hang(_ms):
        await asyncio.sleep(3600)

    page.wait_for_timeout = hang
    monkeypatch.setattr(main.viewer, "start", fake.start)
    monkeypatch.setattr(main.captcha, "visible", lambda p: _async(True))
    task = asyncio.create_task(main._CaptchaHandoff(page, "1").clear())
    await asyncio.sleep(0.05)

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert (fake.opened, fake.closed) == (1, 1)


async def test_without_a_remote_view_a_captcha_is_reported_at_once_and_the_failure_is_remembered(monkeypatch):
    page = FakePage({2: _show_captcha})

    result = await _run_sync(monkeypatch, page, [])  # default: viewer unavailable

    assert result.status_code == 403
    assert main.member_state.login_failed_on(main._member_file("1", "login-failed"), main._today())


# pacing -----------------------------------------------------------------------

async def test_no_new_login_after_a_failed_one_the_same_day(monkeypatch):
    first = FakePage({2: _show_captcha})
    await _run_sync(monkeypatch, first, [])
    second = FakePage({2: _show_phone})
    opened = []
    calls = []

    result = await _run_sync(monkeypatch, second, calls, after_phone=lambda p: opened.append("x") or _show_passcode(p), keep_state=True)

    assert result.status_code == 429
    assert json.loads(result.body) == {"error": "LOGIN_PACED"}
    assert calls == [] and second.waits == 0 and second.approval_polls == 0, "the browser was never even driven"


async def test_the_next_day_the_login_is_tried_again(monkeypatch):
    from datetime import date
    await _run_sync(monkeypatch, FakePage({2: _show_captcha}), [])
    monkeypatch.setattr(main, "_today", lambda: date(2999, 1, 1))
    calls = []

    result = await _run_sync(monkeypatch, FakePage({2: _show_phone}), calls, keep_state=True)

    assert result == {"accounts": []} and calls == ["phone", "passcode"]


async def test_an_approval_timeout_is_a_failed_login_too(monkeypatch):
    page = FakePage({2: _show_passcode})
    monkeypatch.setattr(main, "ENROLMENT_APPROVE_WAIT_S", 6)
    monkeypatch.setattr(main, "POLL_MS", 3000)

    result = await _run_sync(monkeypatch, page, [], logged_in=lambda p: False)

    assert result.status_code == 408
    assert main.member_state.login_failed_on(main._member_file("1", "login-failed"), main._today())
    again = await _run_sync(monkeypatch, FakePage({2: _show_passcode}), [], keep_state=True)
    assert again.status_code == 429


async def test_a_successful_login_clears_an_earlier_failure(monkeypatch):
    from datetime import date
    main.member_state.record_login_failure(main._member_file("1", "login-failed"), date(2000, 1, 1))

    result = await _run_sync(monkeypatch, FakePage({2: _show_passcode}), [], keep_state=True)

    assert result == {"accounts": []}
    assert not main.member_state.login_failed_on(main._member_file("1", "login-failed"), date(2000, 1, 1))


async def test_the_scheduler_without_login_never_touches_the_pacing_file(monkeypatch):
    main.member_state.record_login_failure(main._member_file("1", "login-failed"), main._today())

    async def no_reuse(_member_id):
        return None

    monkeypatch.setattr(main, "_harvest_from_profile", no_reuse)
    req = main.SyncRequest(phoneNumber="0600000000", passcode="123456", memberId="1", allowLogin=False)
    main._member_locks.clear()

    result = await main.sync(req)

    assert result.status_code == 401, "SESSION_EXPIRED, not LOGIN_PACED: no login was going to be attempted"
    assert json.loads(result.body) == {"error": "SESSION_EXPIRED"}


async def test_a_reusable_session_is_still_synced_on_a_day_a_login_failed(monkeypatch):
    main.member_state.record_login_failure(main._member_file("1", "login-failed"), main._today())

    async def reuse(_member_id):
        return {"accounts": [{"externalId": "x"}]}

    monkeypatch.setattr(main, "_harvest_from_profile", reuse)
    main._member_locks.clear()

    result = await main.sync(main.SyncRequest(phoneNumber="0600000000", passcode="123456", memberId="1"))

    assert result == {"accounts": [{"externalId": "x"}]}
