"""Browser login flow, played against a fake Playwright (see fake_browser.py).

No browser, no network, no real credential, no real bank data. The identifier,
key positions and images below are synthetic.

Flow under test: `/initiate` (identifier only, returns the 10 key images, never
clicks the pad), `/keypad` (user-chosen positions, digests re-checked before
every click), `/complete` (unchanged).
"""

import asyncio
import json
import logging
import re
import unittest
from unittest.mock import patch

from playwright.async_api import TimeoutError as PlaywrightTimeoutError

import login
import replay
from fake_browser import (
    AUTHORIZE_URL,
    OVERSIZE_PNG,
    UNKNOWN_PNG,
    World,
    data_uri,
    playwright_error,
)

CUSTOMER_ID = "7305918264"
POSITIONS = [3, 0, 5, 2, 7, 9, 1]
EXPECTED_AUTHORIZE_PARAMS = {
    "client_id": "test-client-id",
    "redirect_uri": "https://www.example-app.caisse-epargne.fr/callback",
    "scope": "openid",
    "response_type": "code",
    "login_hint": "SECRET-LOGIN-HINT",
}


class _Capture(logging.Handler):
    def __init__(self) -> None:
        super().__init__(logging.DEBUG)
        self.lines: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.lines.append(self.format(record))  # includes any traceback text


class LoginTestBase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # Real defaults, read before the timing patches below shorten them.
        self.real_complete_wait = login.COMPLETE_WAIT_SECONDS
        self.world = World()
        self.logs = _Capture()
        root = logging.getLogger()
        root.addHandler(self.logs)
        self._old_level = root.level
        root.setLevel(logging.DEBUG)

        patches = [
            patch.object(login, "_playwright_factory", lambda: self.world.factory()),
            patch.object(login, "STEP_TIMEOUT_SECONDS", 0.3),
            patch.object(login, "COMPLETE_WAIT_SECONDS", 0.5),
            patch.object(login, "AUTHORIZE_WAIT_SECONDS", 0.1),
            patch.object(login, "POLL_INTERVAL_SECONDS", 0.001),
            patch.object(login, "RESOURCE_CLOSE_TIMEOUT_SECONDS", 0.5),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        login._pending.clear()
        login._reset_slots_for_tests()

    async def asyncTearDown(self):
        await login.close_all()
        root = logging.getLogger()
        root.removeHandler(self.logs)
        root.setLevel(self._old_level)

    # --- helpers ---
    async def initiate(self, customer_id=CUSTOMER_ID):
        return await login.initiate(customer_id)

    async def keypad(self, process_id, positions=None):
        return await login.keypad(process_id, POSITIONS if positions is None else positions)

    async def securpass(self) -> str:
        """initiate + keypad: a process waiting for the human Sécur'Pass approval."""
        process_id = (await self.initiate())["processId"]
        await self.keypad(process_id)
        return process_id

    async def assertFails(self, status: int, code: str, coro):
        with self.assertRaises(login.LoginError) as ctx:
            await coro
        self.assertEqual((ctx.exception.status, ctx.exception.code), (status, code))
        return ctx.exception

    def assertClean(self):
        """Nothing left open: no slot, no pending process, every resource closed."""
        self.assertEqual(login.browsers_in_use(), 0)
        self.assertEqual(len(login._pending), 0)
        self.assertEqual(self.world.still_open(), [])

    def log_text(self) -> str:
        return "\n".join(self.logs.lines)


class HappyPathTest(LoginTestBase):
    async def test_initiate_returns_the_keypad_images_in_dom_order(self):
        result = await self.initiate()

        self.assertEqual(set(result), {"processId", "keypad", "expiresInSeconds"})
        self.assertIsInstance(result["processId"], str)
        self.assertGreaterEqual(len(result["processId"]), 32)
        self.assertEqual(
            result["keypad"], {"images": self.world.all_data_uris(), "columns": 5}
        )
        self.assertEqual(len(result["keypad"]["images"]), 10)
        for image in result["keypad"]["images"]:
            self.assertTrue(image.startswith("data:image/png;base64,"))
        self.assertEqual(result["expiresInSeconds"], 90)
        self.assertEqual(login.browsers_in_use(), 1)
        self.assertEqual(len(login._pending), 1)

    async def test_initiate_keeps_the_ten_raw_digests_in_the_pending_session(self):
        pid = (await self.initiate())["processId"]

        self.assertEqual(
            list(login._pending[pid].digests), self.world.all_digests_hex()
        )
        self.assertEqual(len(set(login._pending[pid].digests)), 10)

    async def test_images_follow_the_page_order_not_the_digit_order(self):
        self.world.layout = "9876543210"
        result = await self.initiate()

        self.assertEqual(result["keypad"]["images"], self.world.all_data_uris())

    async def test_initiate_has_no_password_and_no_mfa_fields(self):
        result = await self.initiate()

        for absent in ("mfaRequired", "mfaType", "password"):
            self.assertNotIn(absent, result)

    async def test_initiate_never_clicks_the_pad_nor_the_second_valider(self):
        await self.initiate()

        self.assertEqual(self.world.key_clicks, [])
        self.assertEqual(self.world.page_clicks, [login.NEXT_BUTTON])
        self.assertNotIn(login.SUBMIT_BUTTON, self.world.page_clicks)

    async def test_contract_constants(self):
        self.assertEqual(login.MAX_CONCURRENT_BROWSERS, 2)
        self.assertEqual(login.PENDING_TTL_SECONDS, 300)
        self.assertEqual(login.KEYPAD_TTL_SECONDS, 90)
        self.assertEqual(login.KEYPAD_DEADLINE_SECONDS, 60)
        self.assertEqual(login.INITIATE_DEADLINE_SECONDS, 80)
        self.assertEqual(self.real_complete_wait, 150)

    async def test_opens_the_sso_page_through_the_allow_list_route(self):
        await self.initiate()

        self.assertEqual(
            self.world.gotos, ["https://www.icgauth.caisse-epargne.fr/se-connecter/sso?service=dei"]
        )
        self.assertLess(self.world.events.index("route"), self.world.events.index("goto"))

    async def test_initiate_types_only_the_identifier(self):
        await self.initiate()

        self.assertEqual(self.world.fills, [("#neo-input-1", CUSTOMER_ID)])

    async def test_keypad_clicks_the_positions_then_valider_once(self):
        pid = (await self.initiate())["processId"]

        result = await self.keypad(pid)

        self.assertEqual(result, {"processId": pid, "status": "SECURPASS_PENDING"})
        self.assertEqual(self.world.key_clicks, POSITIONS)
        clicks = [e for e in self.world.events if e.startswith(("click:", "key:"))]
        self.assertEqual(
            clicks,
            [f"click:{login.NEXT_BUTTON}"]
            + [f"key:{p}" for p in POSITIONS]
            + [f"click:{login.SUBMIT_BUTTON}"],
        )
        self.assertEqual(login.browsers_in_use(), 1)
        self.assertIn(pid, login._pending)

    async def test_keypad_clicks_by_position_whatever_the_layout(self):
        self.world.layout = "9876543210"
        pid = (await self.initiate())["processId"]
        await self.keypad(pid, [0, 1, 2, 3, 4, 5])

        self.assertEqual(self.world.key_clicks, [0, 1, 2, 3, 4, 5])
        self.assertEqual(self.world.typed, ["9", "8", "7", "6", "5", "4"])

    async def test_the_same_position_may_be_clicked_several_times(self):
        pid = (await self.initiate())["processId"]
        await self.keypad(pid, [4, 4, 4, 4, 4, 4])

        self.assertEqual(self.world.key_clicks, [4] * 6)

    async def test_positions_length_boundaries_are_accepted(self):
        for length in (6, 12):
            with self.subTest(length=length):
                self.world = World()
                pid = (await self.initiate())["processId"]
                await self.keypad(pid, [i % 10 for i in range(length)])
                self.assertEqual(len(self.world.key_clicks), length)
                await login.close_all()

    async def test_launch_is_headless_and_nothing_records_the_page(self):
        await self.initiate()

        self.assertEqual(len(self.world.launch_kwargs), 1)
        self.assertIs(self.world.launch_kwargs[0].get("headless"), True)
        for kwargs in self.world.launch_kwargs + self.world.context_kwargs:
            for forbidden in ("record_video_dir", "record_har_path", "traces_dir"):
                self.assertNotIn(forbidden, kwargs)
        # fake_browser raises if screenshot() / video are touched; reaching here proves not.

    async def test_complete_returns_a_session_state_replay_accepts(self):
        pid = await self.securpass()

        raw = await login.complete(pid)

        state = replay.parse_session_state(raw)
        self.assertEqual(state.authorize_params, EXPECTED_AUTHORIZE_PARAMS)
        self.assertEqual(
            sorted((c["name"], c["domain"], c["path"]) for c in state.cookies),
            [
                ("SESSION", "www.caisse-epargne.fr", "/espace-client"),
                ("SSO", ".caisse-epargne.fr", "/"),
            ],
        )
        decoded = json.loads(raw)
        self.assertEqual(set(decoded), {"cookies", "authorizeParams"})
        for cookie in decoded["cookies"]:
            self.assertEqual(set(cookie), {"name", "value", "domain", "path"})
        self.assertClean()

    async def test_challenge_and_nonce_are_dropped_from_the_authorize_params(self):
        pid = await self.securpass()
        params = json.loads(await login.complete(pid))["authorizeParams"]

        for dropped in ("code_challenge", "code_challenge_method", "nonce"):
            self.assertNotIn(dropped, params)

    async def test_only_caisse_epargne_cookies_are_kept(self):
        pid = await self.securpass()
        raw = await login.complete(pid)

        self.assertNotIn("other-site", raw)  # .example.com
        self.assertNotIn("evil", raw)  # evilcaisse-epargne.fr is not a subdomain

    async def test_authorize_request_made_before_the_client_space_is_ignored(self):
        self.world.early_authorize = True
        pid = await self.securpass()
        params = json.loads(await login.complete(pid))["authorizeParams"]

        self.assertEqual(params["client_id"], "test-client-id")

    async def test_authorize_not_seen_yet_reloads_once_and_waits(self):
        self.world.authorize_on = "reload"
        pid = await self.securpass()

        raw = await login.complete(pid)

        self.assertEqual(self.world.pages[0].reloads, 1)
        self.assertEqual(replay.parse_session_state(raw).authorize_params, EXPECTED_AUTHORIZE_PARAMS)

    async def test_authorize_never_seen_is_a_format_change(self):
        self.world.authorize_on = "never"
        pid = await self.securpass()

        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", login.complete(pid))
        self.assertEqual(self.world.pages[0].reloads, 1)
        self.assertClean()

    async def test_unusable_state_is_a_format_change_not_returned(self):
        self.world.cookies = [{"name": "x", "value": "y", "domain": ".example.com", "path": "/"}]
        pid = await self.securpass()

        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", login.complete(pid))
        self.assertClean()

    async def test_securpass_screen_without_the_url_marker_is_also_pending(self):
        self.world.after_valider = "securpass_text"
        pid = (await self.initiate())["processId"]
        result = await self.keypad(pid)

        self.assertEqual(result["status"], "SECURPASS_PENDING")

    async def test_the_pad_is_read_again_before_each_click(self):
        reads: list[int] = []
        original = self.world.key_css
        pid = (await self.initiate())["processId"]

        def counting_key_css(index):
            reads.append(index)
            return original(index)

        self.world.key_css = counting_key_css
        await self.keypad(pid)

        self.assertGreaterEqual(len(reads), 10 * len(POSITIONS))


class KeypadStepTest(LoginTestBase):
    async def test_unknown_process_is_expired(self):
        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", self.keypad("does-not-exist"))

    async def test_a_process_accepts_the_keypad_only_once(self):
        pid = (await self.initiate())["processId"]
        await self.keypad(pid)
        clicks = list(self.world.key_clicks)

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        self.assertEqual(self.world.key_clicks, clicks)  # not a single extra click
        self.assertEqual(self.world.page_clicks.count(login.SUBMIT_BUTTON), 1)
        # The first call's login goes on: the second call did not release it.
        self.assertIn(pid, login._pending)
        await login.complete(pid)
        self.assertClean()

    async def test_two_concurrent_keypad_calls_click_once(self):
        pid = (await self.initiate())["processId"]

        results = await asyncio.gather(
            self.keypad(pid), self.keypad(pid), return_exceptions=True
        )

        ok = [r for r in results if isinstance(r, dict)]
        refused = [r for r in results if isinstance(r, login.LoginError)]
        self.assertEqual((len(ok), len(refused)), (1, 1))
        self.assertEqual((refused[0].status, refused[0].code), (409, "KEYPAD_CHANGED"))
        self.assertEqual(self.world.key_clicks, POSITIONS)
        self.assertEqual(self.world.page_clicks.count(login.SUBMIT_BUTTON), 1)

    async def test_complete_cannot_dispose_a_session_during_keypad_execution(self):
        pid = (await self.initiate())["processId"]
        entered = asyncio.Event()
        release = asyncio.Event()

        async def blocked_click_positions(page, positions, pinned):
            entered.set()
            await release.wait()

        with patch.object(login, "_click_positions", blocked_click_positions):
            keypad_task = asyncio.create_task(self.keypad(pid))
            await entered.wait()

            await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete(pid))
            self.assertEqual(login.browsers_in_use(), 1)
            self.assertEqual(len(self.world.still_open()), 3)

            release.set()
            await keypad_task

        self.assertIn(pid, login._pending)
        await login.complete(pid)
        self.assertClean()

    async def test_close_all_waits_for_keypad_and_does_not_restore_its_session(self):
        pid = (await self.initiate())["processId"]
        entered = asyncio.Event()
        release = asyncio.Event()

        async def blocked_click_positions(page, positions, pinned):
            entered.set()
            await release.wait()

        with patch.object(login, "_click_positions", blocked_click_positions):
            keypad_task = asyncio.create_task(self.keypad(pid))
            await entered.wait()
            close_task = asyncio.create_task(login.close_all())
            await asyncio.sleep(0)

            self.assertFalse(close_task.done())
            self.assertEqual(login.browsers_in_use(), 1)
            self.assertEqual(len(self.world.still_open()), 3)

            release.set()
            await keypad_task
            await close_task

        self.assertNotIn(pid, login._pending)
        self.assertNotIn(pid, login._keypad_inflight)
        self.assertNotIn(pid, login._keypad_tasks)
        self.assertEqual(login.browsers_in_use(), 0)
        self.assertEqual(self.world.still_open(), [])
        self.assertCountEqual(self.world.closed, self.world.opened)

    async def test_sweeper_cannot_dispose_a_session_during_keypad_execution(self):
        pid = (await self.initiate())["processId"]
        entered = asyncio.Event()
        release = asyncio.Event()
        session = login._pending[pid]

        async def blocked_click_positions(page, positions, pinned):
            entered.set()
            await release.wait()

        with patch.object(login, "_click_positions", blocked_click_positions):
            keypad_task = asyncio.create_task(self.keypad(pid))
            await entered.wait()
            session.created_at = 0
            # The active session must not be visible to expiry cleanup.
            await login.cleanup_expired()
            self.assertEqual(login.browsers_in_use(), 1)
            self.assertEqual(len(self.world.still_open()), 3)

            release.set()
            await keypad_task

        self.assertIn(pid, login._pending)
        await login.complete(pid)
        self.assertClean()

    async def test_invalid_second_keypad_cannot_dispose_an_active_session(self):
        pid = (await self.initiate())["processId"]
        entered = asyncio.Event()
        release = asyncio.Event()

        async def blocked_click_positions(page, positions, pinned):
            entered.set()
            await release.wait()

        with patch.object(login, "_click_positions", blocked_click_positions):
            keypad_task = asyncio.create_task(self.keypad(pid))
            await entered.wait()

            await self.assertFails(422, "INVALID_POSITIONS", self.keypad(pid, [99]))
            self.assertEqual(login.browsers_in_use(), 1)
            self.assertEqual(len(self.world.still_open()), 3)

            release.set()
            await keypad_task

        await login.complete(pid)
        self.assertClean()

    async def test_a_second_call_after_a_failed_first_call_finds_nothing(self):
        pid = (await self.initiate())["processId"]
        self.world.after_valider = "error"
        await self.assertFails(401, "INVALID_CREDENTIALS", self.keypad(pid))

        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", self.keypad(pid))
        self.assertEqual(self.world.key_clicks, POSITIONS)

    async def test_invalid_positions_are_refused_before_any_click_and_release_the_session(self):
        bad = [
            [],
            [1] * 5,
            [1] * 13,
            [10] * 6,
            [-1] * 6,
            [1, 2, 3, 4, 5, 10],
            ["1"] * 6,
            [1.0] * 6,
            [True] * 6,
            [None] * 6,
            [[1]] * 6,
            "123456",
            None,
            {"a": 1},
            5,
        ]
        for positions in bad:
            with self.subTest(positions=repr(positions)):
                self.world = World()
                pid = (await self.initiate())["processId"]
                await self.assertFails(
                    422, "INVALID_POSITIONS", login.keypad(pid, positions)
                )
                self.assertEqual(self.world.key_clicks, [])
                self.assertNotIn(login.SUBMIT_BUTTON, self.world.page_clicks)
                self.assertClean()

    async def test_invalid_positions_on_an_unknown_process_are_still_422(self):
        await self.assertFails(422, "INVALID_POSITIONS", login.keypad("nope", [1]))

    async def test_an_expired_keypad_step_is_refused_and_its_browser_closed(self):
        pid = (await self.initiate())["processId"]
        login._pending[pid].created_at -= login.KEYPAD_TTL_SECONDS + 1

        await self.assertFails(408, "KEYPAD_EXPIRED", self.keypad(pid))

        self.assertEqual(self.world.key_clicks, [])
        self.assertClean()

    async def test_a_keypad_step_inside_the_ttl_is_accepted(self):
        pid = (await self.initiate())["processId"]
        login._pending[pid].created_at -= login.KEYPAD_TTL_SECONDS - 5

        await self.keypad(pid)

        self.assertEqual(self.world.key_clicks, POSITIONS)

    async def test_the_securpass_wait_gets_a_fresh_pending_ttl(self):
        pid = (await self.initiate())["processId"]
        login._pending[pid].created_at -= login.KEYPAD_TTL_SECONDS - 5
        await self.keypad(pid)

        # complete() measures PENDING_TTL_SECONDS from the Valider, not from /initiate.
        login._pending[pid].created_at -= login.PENDING_TTL_SECONDS - 10
        await login.complete(pid)
        self.assertClean()

    async def test_complete_before_the_keypad_step_is_refused_and_releases_the_session(self):
        pid = (await self.initiate())["processId"]

        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete(pid))

        self.assertEqual(self.world.key_clicks, [])
        self.assertClean()

    async def test_complete_never_clicks_anything(self):
        pid = await self.securpass()
        clicks_before = list(self.world.events)

        await login.complete(pid)

        new_events = self.world.events[len(clicks_before):]
        self.assertEqual([e for e in new_events if e.startswith(("click:", "key:"))], [])


class KeypadFailClosedTest(LoginTestBase):
    """Initiate: the pad must be 10 distinct small PNG data URIs, else nothing is returned."""

    async def assertKeypadChangedAtInitiate(self):
        await self.assertFails(409, "KEYPAD_CHANGED", self.initiate())
        self.assertEqual(self.world.key_clicks, [])
        self.assertNotIn(f"click:{login.SUBMIT_BUTTON}", self.world.events)
        self.assertClean()

    async def test_refusal_logs_counts_only_never_a_digest(self):
        # Seen live 2026-10-07: a KEYPAD_CHANGED with nothing in the logs to say why.
        self.world.css_by_key = {6: "none"}
        with self.assertLogs(login.log, level="WARNING") as captured:
            await self.assertFails(409, "KEYPAD_CHANGED", self.initiate())
        text = "\n".join(captured.output)
        self.assertIn("keys=7", text)
        self.assertIn("with_image=6", text)
        self.assertIn("distinct=6", text)
        self.assertIsNone(re.search(r"[0-9a-f]{16,}", text), "a digest leaked into the logs")

    async def test_fewer_than_ten_keys(self):
        self.world.key_count = 9
        await self.assertKeypadChangedAtInitiate()

    async def test_more_than_ten_keys(self):
        self.world.key_count = 11
        await self.assertKeypadChangedAtInitiate()

    async def test_two_keys_showing_the_same_image(self):
        self.world.same_image = (2, 5)
        await self.assertKeypadChangedAtInitiate()

    async def test_key_without_a_data_uri_background(self):
        self.world.css_override = "none"
        await self.assertKeypadChangedAtInitiate()

    async def test_a_non_png_data_uri_is_refused(self):
        b64png = data_uri(UNKNOWN_PNG, "image/svg+xml")
        self.world.css_by_key = {3: f'url("{b64png}")'}
        await self.assertKeypadChangedAtInitiate()

    async def test_an_image_above_16_kb_is_refused(self):
        self.world.oversize_key = 4
        await self.assertKeypadChangedAtInitiate()

    async def test_no_keys_at_all_is_a_format_change(self):
        self.world.key_count = 0
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertEqual(self.world.key_clicks, [])
        self.assertClean()

    async def test_an_unknown_looking_image_is_fine_at_initiate(self):
        # No table any more: the user reads the pad, the sidecar only pins it.
        self.world.unknown_key = 6
        result = await self.initiate()

        self.assertEqual(len(result["keypad"]["images"]), 10)


class KeypadStabilityTest(LoginTestBase):
    """Keypad step: the 10 digests are compared to the stored ones before EVERY click."""

    async def assertChangedAfter(self, clicks: int):
        self.assertEqual(len(self.world.key_clicks), clicks)
        self.assertNotIn(login.SUBMIT_BUTTON, self.world.page_clicks)
        self.assertClean()

    async def test_a_reshuffle_before_the_first_click_clicks_nothing(self):
        pid = (await self.initiate())["processId"]
        self.world.layout = "0123456789"  # differs from 3817250649

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(0)

    async def test_a_reshuffle_after_the_second_click_stops_with_two_clicks(self):
        self.world.reshuffle_after = 2
        self.world.reshuffled_layout = "0123456789"
        pid = (await self.initiate())["processId"]

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(2)

    async def test_a_reshuffle_to_an_unknown_image_also_stops(self):
        pid = (await self.initiate())["processId"]
        original_key_png = self.world.key_png
        self.world.key_png = lambda i: (
            original_key_png(i) if len(self.world.key_clicks) < 3 else UNKNOWN_PNG
        )

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(3)

    async def test_a_single_key_image_change_is_enough(self):
        pid = (await self.initiate())["processId"]
        self.world.unknown_key = 9

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(0)

    async def test_a_key_that_loses_its_image_stops(self):
        pid = (await self.initiate())["processId"]
        self.world.css_by_key = {6: "none"}

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(0)

    async def test_an_image_that_grows_above_16_kb_stops(self):
        pid = (await self.initiate())["processId"]
        self.world.oversize_key = 2

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(0)

    async def test_an_extra_key_stops(self):
        pid = (await self.initiate())["processId"]
        self.world.key_count = 11

        await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))

        await self.assertChangedAfter(0)

    async def test_the_refusal_logs_counts_only(self):
        pid = (await self.initiate())["processId"]
        self.world.layout = "0123456789"
        with self.assertLogs(login.log, level="WARNING") as captured:
            await self.assertFails(409, "KEYPAD_CHANGED", self.keypad(pid))
        text = "\n".join(captured.output)
        self.assertIn("keys=10", text)
        self.assertIn("with_image=10", text)
        self.assertIn("matching=2", text)  # only keys 5 and 9 keep their image
        self.assertIn("distinct=10", text)
        self.assertIsNone(re.search(r"[0-9a-f]{16,}", text), "a digest leaked into the logs")


class CredentialShapeTest(LoginTestBase):
    async def test_bad_identifiers_are_refused_before_any_browser_work(self):
        for customer_id in ("abc", "", "1" * 21, "73059\n18264", " 7305918264", "７３０５"):
            with self.subTest(customer=customer_id):
                await self.assertFails(401, "INVALID_CREDENTIALS", self.initiate(customer_id))

        self.assertEqual(self.world.launches, 0)
        self.assertEqual(self.world.opened, [])
        self.assertClean()

    async def test_the_boundary_lengths_are_accepted(self):
        for customer_id in ("1", "1" * 20):
            with self.subTest(customer=len(customer_id)):
                self.world = World()
                result = await self.initiate(customer_id)
                await login.close_all()
                self.assertEqual(len(result["keypad"]["images"]), 10)

    async def test_initiate_takes_no_password(self):
        with self.assertRaises(TypeError):
            await login.initiate(CUSTOMER_ID, "482017")  # type: ignore[call-arg]


class LiveSelectorTest(unittest.TestCase):
    # Observed live 2026-10-07 (test connection #1): the identifier page has a
    # carousel button labelled `Suivant` (type=button) BEFORE the form's
    # `Valider` (type=submit). Clicking `Suivant` submits nothing.
    def test_identifier_step_clicks_the_form_submit_button_not_the_carousel(self):
        self.assertNotIn("Suivant", login.NEXT_BUTTON)
        self.assertIn('type="submit"', login.NEXT_BUTTON)
        self.assertIn("Valider", login.NEXT_BUTTON)

    def test_password_step_clicks_a_submit_button(self):
        self.assertIn('type="submit"', login.SUBMIT_BUTTON)
        self.assertIn("Valider", login.SUBMIT_BUTTON)


class BankRefusalTest(LoginTestBase):
    async def test_identifier_refused_stops_before_the_keypad(self):
        self.world.identifier_ok = False
        await self.assertFails(401, "INVALID_CREDENTIALS", self.initiate())

        self.assertEqual(self.world.key_clicks, [])
        self.assertEqual(self.world.page_clicks, [login.NEXT_BUTTON])
        self.assertClean()

    async def test_password_error_banner_is_invalid_credentials_without_a_second_attempt(self):
        self.world.after_valider = "error"
        pid = (await self.initiate())["processId"]
        await self.assertFails(401, "INVALID_CREDENTIALS", self.keypad(pid))

        self.assertEqual(self.world.page_clicks.count(login.SUBMIT_BUTTON), 1)
        self.assertEqual(self.world.launches, 1)
        self.assertEqual(self.world.key_clicks, POSITIONS)  # clicked once, never again
        self.assertClean()

    async def test_nothing_recognisable_after_valider_is_a_format_change(self):
        self.world.after_valider = "nothing"
        pid = (await self.initiate())["processId"]
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.keypad(pid))

        self.assertEqual(self.world.page_clicks.count(login.SUBMIT_BUTTON), 1)
        self.assertClean()

    async def test_never_reaching_the_keypad_page_is_a_format_change(self):
        self.world.identifier_ok = False
        self.world.identifier_error = False  # no banner, no navigation either
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())

        self.assertEqual(self.world.key_clicks, [])
        self.assertClean()


class ResourceSafetyTest(LoginTestBase):
    async def test_launch_failure_releases_the_slot_and_stops_playwright(self):
        self.world.launch_error = playwright_error("launch failed")
        await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.initiate())

        self.assertClean()
        self.assertIn("playwright#0", self.world.closed)

    async def test_navigation_failure_is_upstream_unavailable(self):
        self.world.fail_on["goto"] = playwright_error("net::ERR_NAME_NOT_RESOLVED")
        await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.initiate())
        self.assertClean()

    async def test_form_not_found_is_a_format_change(self):
        self.world.fail_on["fill"] = PlaywrightTimeoutError("waiting for locator")
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertClean()

    async def test_context_creation_failure_closes_the_browser(self):
        self.world.fail_on["new_context"] = playwright_error("context failed")
        await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.initiate())
        self.assertClean()

    async def test_key_click_failure_stops_without_valider_or_retry(self):
        pid = (await self.initiate())["processId"]
        self.world.fail_on["key_click"] = playwright_error("detached")
        await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.keypad(pid))

        self.assertEqual(len(self.world.key_clicks), 1)
        self.assertNotIn(login.SUBMIT_BUTTON, self.world.page_clicks)
        self.assertClean()

    async def test_unexpected_error_is_internal_and_leaks_nothing(self):
        self.world.fail_on["evaluate"] = RuntimeError(f"boom {CUSTOMER_ID}")
        error = await self.assertFails(500, "INTERNAL_ERROR", self.initiate())

        self.assertEqual(str(error), "INTERNAL_ERROR")
        self.assertNotIn(CUSTOMER_ID, self.log_text())
        self.assertClean()

    async def test_unexpected_error_during_the_keypad_step_is_internal_and_releases(self):
        pid = (await self.initiate())["processId"]
        self.world.fail_on["evaluate"] = RuntimeError(f"boom {POSITIONS} {CUSTOMER_ID}")
        error = await self.assertFails(500, "INTERNAL_ERROR", self.keypad(pid))

        self.assertEqual(str(error), "INTERNAL_ERROR")
        self.assertNotIn(CUSTOMER_ID, self.log_text())
        self.assertNotIn(str(POSITIONS), self.log_text())
        self.assertClean()

    async def test_cancellation_releases_everything(self):
        self.world.fail_on["evaluate"] = asyncio.CancelledError()
        with self.assertRaises(asyncio.CancelledError):
            await self.initiate()
        self.assertClean()

    async def test_cancellation_during_the_keypad_step_releases_everything(self):
        pid = (await self.initiate())["processId"]
        self.world.fail_on["evaluate"] = asyncio.CancelledError()
        with self.assertRaises(asyncio.CancelledError):
            await self.keypad(pid)
        self.assertClean()

    async def test_capacity_refuses_the_third_pending_login_without_launching(self):
        await self.initiate()
        await self.initiate()
        launches = self.world.launches

        await self.assertFails(429, "TOO_MANY_PENDING", self.initiate())

        self.assertEqual(self.world.launches, launches)
        self.assertEqual(login.browsers_in_use(), 2)

    async def test_a_finished_process_frees_its_slot(self):
        first = await self.securpass()
        await self.initiate()
        await self.assertFails(429, "TOO_MANY_PENDING", self.initiate())

        await login.complete(first)

        self.assertEqual(login.browsers_in_use(), 1)
        await self.initiate()  # a slot is free again

    async def test_concurrent_initiates_never_exceed_the_cap(self):
        results = await asyncio.gather(
            self.initiate(), self.initiate(), self.initiate(), return_exceptions=True
        )

        refused = [r for r in results if isinstance(r, login.LoginError)]
        self.assertEqual(len(refused), 1)
        self.assertEqual(refused[0].code, "TOO_MANY_PENDING")
        self.assertEqual(self.world.launches, 2)

    async def test_slot_is_released_on_every_failure_path(self):
        scenarios = {
            "keypad no image": lambda w: setattr(w, "css_override", "none"),
            "identifier refused": lambda w: setattr(w, "identifier_ok", False),
            "launch error": lambda w: setattr(w, "launch_error", playwright_error("x")),
        }
        for name, configure in scenarios.items():
            with self.subTest(name):
                self.world = World()
                configure(self.world)
                with self.assertRaises(login.LoginError):
                    await self.initiate()
                self.assertClean()

    async def test_slot_is_released_on_every_keypad_step_failure(self):
        scenarios = {
            "reshuffle": lambda w: setattr(w, "layout", "0123456789"),
            "password refused": lambda w: setattr(w, "after_valider", "error"),
            "nothing after valider": lambda w: setattr(w, "after_valider", "nothing"),
        }
        for name, configure in scenarios.items():
            with self.subTest(name):
                self.world = World()
                pid = (await self.initiate())["processId"]
                configure(self.world)
                with self.assertRaises(login.LoginError):
                    await self.keypad(pid)
                self.assertClean()

    async def test_close_all_closes_every_pending_browser(self):
        await self.initiate()
        await self.initiate()

        await login.close_all()

        self.assertClean()

    async def test_a_resource_that_fails_to_close_still_frees_the_slot(self):
        pid = await self.securpass()

        async def broken_close():
            raise playwright_error("close failed")

        self.world.contexts[0].close = broken_close
        await login.complete(pid)

        self.assertEqual(login.browsers_in_use(), 0)
        self.assertIn("browser#1", self.world.closed)  # the rest was still closed
        self.assertIn("playwright#0", self.world.closed)


class NavigationAllowListTest(LoginTestBase):
    async def test_only_https_caisse_epargne_hosts_pass_the_route(self):
        await self.initiate()
        context = self.world.contexts[0]
        self.assertEqual(context.route_pattern, "**/*")

        allowed = [
            "https://www.icgauth.caisse-epargne.fr/se-connecter/sso",
            "https://www.caisse-epargne.fr/espace-client/",
            "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/authorize",
        ]
        blocked = [
            "https://www.example.com/",
            "https://evilcaisse-epargne.fr/",
            "https://caisse-epargne.fr.evil.example/",
            "http://www.caisse-epargne.fr/",
            "https://www.caisse-epargne.fr@evil.example/",
        ]
        for url in allowed:
            with self.subTest(url=url):
                route = await context.request_through_route(url)
                self.assertTrue(route.continued and not route.aborted)
        for url in blocked:
            with self.subTest(url=url):
                route = await context.request_through_route(url)
                self.assertTrue(route.aborted and not route.continued)


class CompleteTest(LoginTestBase):
    async def test_unknown_process_is_expired(self):
        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete("does-not-exist"))

    async def test_a_process_is_single_use(self):
        pid = await self.securpass()
        await login.complete(pid)

        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete(pid))
        self.assertClean()

    async def test_two_concurrent_completes_run_the_wait_once(self):
        pid = await self.securpass()

        results = await asyncio.gather(
            login.complete(pid), login.complete(pid), return_exceptions=True
        )

        ok = [r for r in results if isinstance(r, str)]
        refused = [r for r in results if isinstance(r, login.LoginError)]
        self.assertEqual((len(ok), len(refused)), (1, 1))
        self.assertEqual(refused[0].code, "AUTH_ATTEMPT_EXPIRED")
        self.assertClean()

    async def test_an_expired_process_is_refused_and_its_browser_closed(self):
        pid = await self.securpass()
        login._pending[pid].created_at -= login.PENDING_TTL_SECONDS + 1

        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete(pid))

        self.assertClean()

    async def test_nobody_approving_is_a_timeout_and_the_browser_is_closed(self):
        self.world.after_push = "never"
        pid = await self.securpass()

        await self.assertFails(408, "APP_VALIDATION_TIMEOUT", login.complete(pid))

        self.assertClean()
        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete(pid))

    async def test_a_banner_shown_during_the_wait_does_not_decide_only_the_timeout_does(self):
        # During /complete only the client-space URL or the timeout decide: a banner
        # read as INVALID_CREDENTIALS would make the user retype and risk a lock.
        self.world.after_push = "refuse"
        pid = await self.securpass()

        await self.assertFails(408, "APP_VALIDATION_TIMEOUT", login.complete(pid))
        self.assertClean()

    async def test_the_error_page_is_not_a_connected_session(self):
        self.world.after_push = "erreur"
        pid = await self.securpass()

        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", login.complete(pid))
        self.assertClean()

    async def test_complete_never_clicks_anything(self):
        pid = await self.securpass()
        clicks_before = list(self.world.events)

        await login.complete(pid)

        new_events = self.world.events[len(clicks_before):]
        self.assertEqual([e for e in new_events if e.startswith(("click:", "key:"))], [])

    async def test_a_playwright_error_while_waiting_is_unavailable(self):
        pid = await self.securpass()

        async def broken_cookies(*_a):
            raise playwright_error("target closed")

        self.world.contexts[0].cookies = broken_cookies
        await self.assertFails(502, "UPSTREAM_UNAVAILABLE", login.complete(pid))
        self.assertClean()


class SweeperTest(LoginTestBase):
    async def test_cleanup_closes_expired_processes_and_keeps_fresh_ones(self):
        old = await self.securpass()
        fresh = await self.securpass()
        login._pending[old].created_at -= login.PENDING_TTL_SECONDS + 1

        await login.cleanup_expired()

        self.assertNotIn(old, login._pending)
        self.assertIn(fresh, login._pending)
        self.assertEqual(login.browsers_in_use(), 1)
        self.assertEqual(len(self.world.still_open()), 3)  # fresh playwright+browser+context

    async def test_a_session_waiting_for_the_keypad_is_swept_after_the_keypad_ttl(self):
        waiting = (await self.initiate())["processId"]
        approving = await self.securpass()
        for pid in (waiting, approving):
            login._pending[pid].created_at -= login.KEYPAD_TTL_SECONDS + 1

        await login.cleanup_expired()

        self.assertNotIn(waiting, login._pending)  # nobody can use it any more
        self.assertIn(approving, login._pending)  # the Sécur'Pass wait lasts longer
        self.assertEqual(login.browsers_in_use(), 1)

    async def test_initiate_sweeps_before_counting_capacity(self):
        a = await self.securpass()
        await self.initiate()
        login._pending[a].created_at -= login.PENDING_TTL_SECONDS + 1

        await self.initiate()  # would be 429 if the expired one still held its slot

        self.assertEqual(login.browsers_in_use(), 2)

    async def test_the_sweeper_task_runs_cleanup_periodically(self):
        pid = await self.securpass()
        login._pending[pid].created_at -= login.PENDING_TTL_SECONDS + 1

        with patch.object(login, "PENDING_SWEEP_SECONDS", 0.01):
            task = asyncio.create_task(login.sweeper())
            try:
                for _ in range(100):
                    if pid not in login._pending:
                        break
                    await asyncio.sleep(0.01)
            finally:
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task

        self.assertClean()


class NoSecretLeakTest(LoginTestBase):
    SECRETS = (
        CUSTOMER_ID,
        "CE-COOKIE-SECRET-1",
        "CE-COOKIE-SECRET-2",
        "SECRET-LOGIN-HINT",
        "stale-challenge",
        "stale-nonce",
    )

    def assertNoSecrets(self, text: str):
        for secret in self.SECRETS:
            self.assertNotIn(secret, text)
        for digest in self.world.all_digests_hex():
            self.assertNotIn(digest, text)
        for uri in self.world.all_data_uris():
            self.assertNotIn(uri, text)
            self.assertNotIn(uri.split(",", 1)[1], text)
        self.assertNotIn(str(POSITIONS), text)
        self.assertNotIn(",".join(map(str, POSITIONS)), text)

    async def test_logs_and_errors_hold_no_secret_on_any_path(self):
        scenarios = {
            "success": lambda w: None,
            "keypad": lambda w: setattr(w, "css_by_key", {3: "none"}),
            "identifier": lambda w: setattr(w, "identifier_ok", False),
            "password": lambda w: setattr(w, "after_valider", "error"),
            "reshuffle": lambda w: setattr(w, "reshuffle_after", 2),
            "launch": lambda w: setattr(w, "launch_error", playwright_error(f"x {CUSTOMER_ID}")),
            "timeout": lambda w: setattr(w, "after_push", "never"),
            "unexpected": lambda w: w.fail_on.update(
                key_click=RuntimeError(f"{POSITIONS} {CUSTOMER_ID} CE-COOKIE-SECRET-1")
            ),
        }
        for name, configure in scenarios.items():
            with self.subTest(name):
                self.world = World()
                configure(self.world)
                bodies = []
                try:
                    initiated = await self.initiate()
                    bodies.append(json.dumps(initiated["processId"]))
                    await login.keypad(initiated["processId"], POSITIONS)
                    bodies.append(await login.complete(initiated["processId"]))
                except login.LoginError as exc:
                    bodies.append(str(exc))
                    bodies.append(repr(exc))
                await login.close_all()
                self.assertNoSecrets(self.log_text())
                if name == "success":
                    continue  # the returned state legitimately carries the cookies
                for body in bodies:
                    self.assertNoSecrets(body)

    async def test_login_error_carries_only_its_code(self):
        self.world.css_by_key = {1: "none"}
        error = await self.assertFails(409, "KEYPAD_CHANGED", self.initiate())

        self.assertEqual(str(error), "KEYPAD_CHANGED")
        self.assertEqual(error.args, ("KEYPAD_CHANGED",))
        self.assertIsNone(error.__cause__)


class CleanupCancellationTest(LoginTestBase):
    async def test_cancelling_the_sweep_mid_dispose_leaks_no_slot(self):
        first = await self.securpass()
        second = await self.securpass()
        for pid in (first, second):
            login._pending[pid].created_at -= login.PENDING_TTL_SECONDS + 1

        async def stuck_close():
            await asyncio.Event().wait()

        self.world.contexts[0].close = stuck_close  # the first dispose hangs
        sweep = asyncio.create_task(login.cleanup_expired())
        await asyncio.sleep(0.05)
        sweep.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await sweep

        # Whatever was not yet disposed is still reachable by close_all.
        await login.close_all()

        self.assertEqual(login.browsers_in_use(), 0)
        self.assertEqual(len(login._pending), 0)
        for name in ("context#2", "browser#2", "playwright#1", "browser#1", "playwright#0"):
            self.assertIn(name, self.world.closed)

    async def test_expired_sessions_are_disposed_one_at_a_time(self):
        first = await self.securpass()
        second = await self.securpass()
        for pid in (first, second):
            login._pending[pid].created_at -= login.PENDING_TTL_SECONDS + 1
        seen: list[int] = []
        original = login._dispose

        async def spying_dispose(session):
            seen.append(len(login._pending))  # still-pending sessions at dispose time
            await original(session)

        with patch.object(login, "_dispose", spying_dispose):
            await login.cleanup_expired()

        self.assertEqual(seen, [1, 0])
        self.assertClean()


class DeadlineTest(LoginTestBase):
    async def test_contract_constants(self):
        self.assertEqual(login.INITIATE_DEADLINE_SECONDS, 80)  # backend gives 90 s
        self.assertEqual(login.COMPLETE_DEADLINE_SECONDS, 160)  # backend gives 170 s
        self.assertEqual(self.real_complete_wait, 150)  # the human part is unchanged

    async def test_a_hanging_initiate_is_cut_off_and_cleaned_up(self):
        self.world.hang_on = {"goto"}
        with patch.object(login, "INITIATE_DEADLINE_SECONDS", 0.2):
            await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.initiate())

        self.assertClean()  # browser closed, slot released, nothing pending
        self.assertEqual(self.world.key_clicks, [])

    async def test_a_hang_in_valider_is_cut_off_without_a_second_attempt(self):
        pid = (await self.initiate())["processId"]

        async def hanging_valider(page):
            await asyncio.Event().wait()

        with (
            patch.object(login, "KEYPAD_DEADLINE_SECONDS", 0.2),
            patch.object(login, "_click_valider", hanging_valider),
        ):
            await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.keypad(pid))

        self.assertEqual(self.world.key_clicks, POSITIONS)  # clicked once
        self.assertEqual(self.world.launches, 1)
        self.assertClean()

    async def test_a_hanging_key_click_is_cut_off_by_the_keypad_deadline(self):
        pid = (await self.initiate())["processId"]
        self.world.hang_on = {"key_click"}

        with patch.object(login, "KEYPAD_DEADLINE_SECONDS", 0.2):
            await self.assertFails(502, "UPSTREAM_UNAVAILABLE", self.keypad(pid))

        self.assertEqual(len(self.world.key_clicks), 1)
        self.assertNotIn(login.SUBMIT_BUTTON, self.world.page_clicks)
        self.assertClean()

    async def test_a_hanging_reload_in_complete_is_cut_off_and_cleaned_up(self):
        self.world.authorize_on = "reload"
        self.world.hang_on = {"reload"}
        pid = await self.securpass()

        with (
            patch.object(login, "COMPLETE_WAIT_SECONDS", 0.2),
            patch.object(login, "COMPLETE_DEADLINE_SECONDS", 0.5),
        ):
            await self.assertFails(502, "UPSTREAM_UNAVAILABLE", login.complete(pid))

        self.assertClean()
        await self.assertFails(410, "AUTH_ATTEMPT_EXPIRED", login.complete(pid))

    async def test_a_hang_during_the_human_wait_is_an_app_validation_timeout(self):
        self.world.after_push = "never"
        pid = await self.securpass()

        async def stuck_visible(page, selector):
            await asyncio.Event().wait()

        with (
            patch.object(login, "COMPLETE_WAIT_SECONDS", 5),
            patch.object(login, "COMPLETE_DEADLINE_SECONDS", 0.3),
            patch.object(login, "_visible", stuck_visible),
        ):
            await self.assertFails(408, "APP_VALIDATION_TIMEOUT", login.complete(pid))

        self.assertClean()

    async def test_post_wait_work_is_clamped_to_the_remaining_deadline(self):
        # The human can approve at the very end of the 150 s: reload and authorize
        # wait must then fit in the 10 s left, not in 30 s + 15 s.
        self.world.authorize_on = "reload"
        pid = await self.securpass()

        with (
            patch.object(login, "STEP_TIMEOUT_SECONDS", 30),
            patch.object(login, "AUTHORIZE_WAIT_SECONDS", 15),
            patch.object(login, "COMPLETE_WAIT_SECONDS", 1),
            patch.object(login, "COMPLETE_DEADLINE_SECONDS", 2),
        ):
            await login.complete(pid)

        reload_timeout_ms = self.world.pages[0].reload_kwargs[0]["timeout"]
        self.assertLessEqual(reload_timeout_ms, 2000)
        self.assertGreater(reload_timeout_ms, 0)

    async def test_the_default_budget_leaves_room_after_the_human_wait(self):
        self.assertGreaterEqual(
            login.COMPLETE_DEADLINE_SECONDS - self.real_complete_wait, 10
        )


class ErrorBannerScopeTest(LoginTestBase):
    async def test_the_generic_alert_role_is_not_an_error_selector(self):
        self.assertNotIn("role", login.ERROR_SELECTOR)

    async def test_an_informational_alert_during_the_securpass_wait_is_not_a_refusal(self):
        self.world.info_alert = True
        pid = await self.securpass()

        raw = await login.complete(pid)  # the user approves; the alert changes nothing

        replay.parse_session_state(raw)
        self.assertClean()

    async def test_an_informational_alert_on_the_identifier_step_does_not_stop_the_login(self):
        self.world.info_alert = True
        result = await self.initiate()

        # No `mfaType` any more: the pad comes back for the user to click himself.
        self.assertEqual(len(result["keypad"]["images"]), 10)

    async def test_complete_never_looks_for_an_error_banner(self):
        pid = await self.securpass()
        self.world.visible_queries.clear()

        await login.complete(pid)

        self.assertNotIn(login.ERROR_SELECTOR, self.world.visible_queries)

    async def test_the_banner_is_only_read_on_the_password_page_after_valider(self):
        self.world.after_valider = "securpass"
        await self.initiate()

        # The identifier success path and the happy outcome never query it.
        self.assertNotIn(login.ERROR_SELECTOR, self.world.visible_queries)


class ContextHardeningTest(LoginTestBase):
    async def test_service_workers_are_blocked(self):
        await self.initiate()

        self.assertEqual(self.world.context_kwargs[0].get("service_workers"), "block")

    async def test_web_sockets_are_routed_through_the_allow_list_before_navigation(self):
        await self.initiate()

        context = self.world.contexts[0]
        self.assertIsNotNone(context.ws_handler)
        self.assertLess(
            self.world.events.index("route_web_socket"), self.world.events.index("goto")
        )

    async def test_only_allow_listed_web_sockets_connect(self):
        await self.initiate()
        context = self.world.contexts[0]

        for url in ("wss://www.caisse-epargne.fr/live", "wss://x.icgauth.caisse-epargne.fr/s"):
            with self.subTest(url=url):
                ws = await context.open_web_socket(url)
                self.assertTrue(ws.connected and not ws.closed)
        for url in (
            "wss://evil.example/",
            "wss://evilcaisse-epargne.fr/",
            "wss://caisse-epargne.fr.evil.example/",
            "ws://www.caisse-epargne.fr/live",  # cleartext
            "wss://user@www.caisse-epargne.fr@evil.example/",
        ):
            with self.subTest(url=url):
                ws = await context.open_web_socket(url)
                self.assertTrue(ws.closed and not ws.connected)
