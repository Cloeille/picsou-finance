"""Fresh-login flow must wait for the first SSO screen instead of sniffing the URL once.

app.revolut.com redirects to sso.revolut.com/signin; a known device only reaches /passcode
after a delay. These tests drive `sync()` with a fake page (no real browser).
"""

import contextlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


class FakeLocator:
    def __init__(self, page, kind):
        self.page, self.kind = page, kind

    async def count(self):
        return self.page.counts[self.kind]


class FakePage:
    """`script` maps a wait_for_timeout call number to a state update."""

    def __init__(self, script):
        self.url = "https://sso.revolut.com/signin?x=1"
        self.counts = {"phone": 0, "textbox": 0}
        self.script = script
        self.waits = 0

    async def goto(self, *_a, **_k):
        pass

    async def wait_for_timeout(self, _ms):
        self.waits += 1
        update = self.script.get(self.waits)
        if update:
            update(self)

    def locator(self, selector):
        assert selector == main._PHONE_INPUT
        return FakeLocator(self, "phone")

    def get_by_role(self, role):
        assert role == "textbox"
        return FakeLocator(self, "textbox")


def _show_passcode(page):
    page.url = "https://sso.revolut.com/passcode?x=1"
    page.counts["textbox"] = 6


def _show_phone(page):
    page.counts["phone"] = 1


@contextlib.asynccontextmanager
async def _fake_camoufox(page):
    class Ctx:
        async def new_page(self):
            return page

    yield Ctx()


async def _run_sync(monkeypatch, page, calls):
    async def no_reuse(_member_id):
        return None

    async def fill_phone(p, _phone):
        calls.append("phone")
        _show_passcode(p)  # submitting the phone leads to the passcode screen

    async def fill_passcode(_p, _passcode):
        calls.append("passcode")

    async def logged_in(_p):
        return True

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
    monkeypatch.setattr(main, "_logged_in", logged_in)
    monkeypatch.setattr(main, "_device_id", device_id)
    monkeypatch.setattr(main, "_settle", settle)
    monkeypatch.setattr(main, "harvest_accounts", harvest)
    main._member_locks.clear()
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
