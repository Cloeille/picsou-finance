"""The login page's client-rendered form: reload once, classify a missing form (#482)."""

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import HTTPException

import main

BASE = "https://www.aexp-static.com/cdaas/one-app/modules/one-identity-login/6.74.0/"
MODULE_JS = BASE + "one-identity-login.browser.js"
OTHER_ASSET = BASE + "assets/icon-unused.svg"


def _response(url, status):
    """What Playwright gives: `ok` is true for 2xx only, so a 304 is not ok."""
    return SimpleNamespace(url=url, status=status, ok=200 <= status < 300)


class _Locator:
    def __init__(self, page, selector):
        self.page, self.selector = page, selector
        self.first = self

    async def is_visible(self):
        if self.selector == main.COOKIE_BANNER_DECLINE_SELECTOR:
            return False
        self.page.polls += 1
        return self.page.form_visible or (
            self.page.show_after_polls is not None and self.page.polls >= self.page.show_after_polls
        )

    async def click(self, timeout=None):
        pass


class FakeLoginPage:
    """Each load plays a list of network events. "form" shows the form at once,
    "late_form" only after several visibility checks, as a slow module does."""

    def __init__(self, *loads, on_reload_abort=False):
        self.loads = list(loads)
        self.handlers = {}
        self.form_visible = False
        self.show_after_polls = None
        self.polls = 0
        self.reloads = 0
        self.on_reload_abort = on_reload_abort

    def on(self, event, handler):
        self.handlers.setdefault(event, []).append(handler)

    def locator(self, selector):
        return _Locator(self, selector)

    def _emit(self, event, obj):
        for h in self.handlers.get(event, []):
            h(obj)

    async def goto(self, url, **_):
        self._play()

    async def reload(self, **_):
        self.reloads += 1
        if self.on_reload_abort:
            # The previous document's pending module request is aborted by the reload.
            self._emit("requestfailed", SimpleNamespace(url=MODULE_JS, failure="NS_BINDING_ABORTED"))
        self._play()

    def _play(self):
        self.form_visible = False
        self.show_after_polls = None
        self.polls = 0
        for event in self.loads.pop(0):
            if event == "form":
                self.form_visible = True
            elif event == "late_form":
                self.show_after_polls = 8
            elif event == "module_ok":
                self._emit("response", _response(MODULE_JS, 200))
            elif event == "module_304":
                self._emit("response", _response(MODULE_JS, 304))
            elif event == "module_404":
                self._emit("response", _response(MODULE_JS, 404))
            elif event == "module_failed":
                self._emit("requestfailed", SimpleNamespace(url=MODULE_JS, failure="NS_ERROR_NET_RESET"))
            elif event == "asset_404":
                self._emit("response", _response(OTHER_ASSET, 404))


class OpenLoginFormTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        patcher = patch.multiple(main, LOGIN_FORM_TIMEOUT_MS=300, LOGIN_FORM_POLL_SECONDS=0.01)
        patcher.start()
        self.addCleanup(patcher.stop)

    async def test_form_shown_on_first_load_does_not_reload(self):
        page = FakeLoginPage(["module_ok", "form"])
        await main._open_login_form(page)
        self.assertEqual(page.reloads, 0)

    async def test_form_rendered_after_several_polls_is_waited_for(self):
        page = FakeLoginPage(["late_form"])
        await main._open_login_form(page)
        self.assertEqual(page.reloads, 0)
        self.assertGreaterEqual(page.polls, 8)

    async def test_failed_module_load_is_reloaded_and_login_proceeds(self):
        page = FakeLoginPage(["module_failed"], ["module_ok", "form"])
        with self.assertLogs("amex-auth", "INFO") as logs:
            await main._open_login_form(page)
        self.assertEqual(page.reloads, 1)
        self.assertIn("AMEX login form shown after reload attempt=2", "\n".join(logs.output))

    async def test_module_answering_404_is_a_failed_load(self):
        page = FakeLoginPage(["module_404"], ["module_404"])
        with self.assertRaises(HTTPException) as caught:
            await main._open_login_form(page)
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (503, "UPSTREAM_UNAVAILABLE"))
        self.assertEqual(page.reloads, 1)

    async def test_module_failing_on_every_attempt_is_retryable_unavailable(self):
        page = FakeLoginPage(["module_failed"], ["module_failed"])
        with self.assertLogs("amex-auth", "WARNING") as logs, self.assertRaises(HTTPException) as caught:
            await main._open_login_form(page)
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (503, "UPSTREAM_UNAVAILABLE"))
        self.assertEqual(page.reloads, 1)
        self.assertIn("attempts=2 module_loaded=False module_failed=True", "\n".join(logs.output))

    async def test_loaded_module_without_the_fields_twice_is_format_changed(self):
        page = FakeLoginPage(["module_ok"], ["module_ok"])
        with self.assertLogs("amex-auth", "WARNING") as logs, self.assertRaises(HTTPException) as caught:
            await main._open_login_form(page)
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (502, "UPSTREAM_FORMAT_CHANGED"))
        self.assertEqual(page.reloads, 1)
        self.assertIn("attempts=2 module_loaded=True module_failed=False", "\n".join(logs.output))

    async def test_module_revalidated_with_a_304_is_a_loaded_module(self):
        # A conditional reload answers 304: the cached module is in use, not a failure.
        page = FakeLoginPage(["module_failed"], ["module_304", "late_form"])
        await main._open_login_form(page)
        self.assertEqual(page.reloads, 1)

    async def test_a_304_module_without_the_fields_is_format_changed_not_unavailable(self):
        page = FakeLoginPage(["module_304"], ["module_304"])
        with self.assertRaises(HTTPException) as caught:
            await main._open_login_form(page)
        self.assertEqual(caught.exception.status_code, 502)

    async def test_failure_of_the_first_load_does_not_taint_the_second_attempt(self):
        # The reset before the reload: a module that loads on the retry but renders
        # no form is a format change, not an outage.
        page = FakeLoginPage(["module_failed"], ["module_ok"])
        with self.assertRaises(HTTPException) as caught:
            await main._open_login_form(page)
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (502, "UPSTREAM_FORMAT_CHANGED"))

    async def test_a_failed_module_ends_each_attempt_without_waiting_for_the_deadline(self):
        with patch.object(main, "LOGIN_FORM_TIMEOUT_MS", 60_000):
            page = FakeLoginPage(["module_failed"], ["module_failed"])
            with self.assertRaises(HTTPException) as caught:
                await asyncio.wait_for(main._open_login_form(page), timeout=5)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(page.reloads, 1)

    async def test_loaded_module_with_a_form_too_slow_is_reloaded(self):
        # Seen live: the module loads and the form still misses the first wait.
        page = FakeLoginPage(["module_ok"], ["module_ok", "late_form"])
        await main._open_login_form(page)
        self.assertEqual(page.reloads, 1)

    async def test_request_aborted_by_the_reload_does_not_cut_the_second_wait(self):
        # Attempt 1 times out with the module still pending; the reload aborts it.
        page = FakeLoginPage([], ["late_form"], on_reload_abort=True)
        await main._open_login_form(page)
        self.assertEqual(page.reloads, 1)

    async def test_failing_unrelated_asset_does_not_cut_the_wait(self):
        page = FakeLoginPage(["asset_404", "late_form"])
        await main._open_login_form(page)
        self.assertEqual(page.reloads, 0)

    async def test_no_module_signal_reloads_then_is_unavailable(self):
        page = FakeLoginPage([], [])
        with self.assertRaises(HTTPException) as caught:
            await main._open_login_form(page)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(page.reloads, 1)

    def test_only_the_module_files_are_tracked(self):
        self.assertTrue(main._LoginModuleWatch.is_module(MODULE_JS + "?v=1"))
        self.assertTrue(main._LoginModuleWatch.is_module(BASE + "fr-fr/one-identity-login.json"))
        self.assertFalse(main._LoginModuleWatch.is_module(OTHER_ASSET))


if __name__ == "__main__":
    unittest.main()
