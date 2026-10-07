"""HTTP contract of POST /initiate, POST /keypad and POST /complete (login.py is faked)."""

import io
import json
import logging
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import login
import main
from fake_browser import World

TEST_KEY = "caisse-epargne-sidecar-test-key"
HEADERS = {"X-Picsou-Sidecar-Key": TEST_KEY}
# Never accepted any more (the user's browser keeps the password): used to prove
# it is neither accepted, echoed nor logged.
PASSWORD = "48201735"
CUSTOMER_ID = "7305918264"
POSITIONS = [3, 0, 5, 7, 1, 9]
IMAGES = [f"data:image/png;base64,AAAA{i}" for i in range(10)]
OK_INITIATE = {
    "processId": "p" * 32,
    "keypad": {"images": IMAGES, "columns": 5},
    "expiresInSeconds": 90,
}
OK_KEYPAD = {"processId": "p" * 32, "status": "SECURPASS_PENDING"}


class InitiateContractTest(unittest.TestCase):
    def setUp(self):
        key = patch.object(main, "SIDECAR_API_KEY", TEST_KEY)
        key.start()
        self.addCleanup(key.stop)

    def post(self, path, body, headers=HEADERS):
        with TestClient(main.app) as client:
            return client.post(path, json=body, headers=headers)

    def test_initiate_requires_the_sidecar_key(self):
        with patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)) as fake:
            for headers in ({}, {"X-Picsou-Sidecar-Key": "nope"}):
                response = self.post("/initiate", {"customerId": CUSTOMER_ID}, headers)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.json(), {"detail": "UNAUTHORIZED"})
        fake.assert_not_called()

    def test_keypad_requires_the_sidecar_key(self):
        with patch.object(login, "keypad", AsyncMock(return_value=OK_KEYPAD)) as fake:
            response = self.post(
                "/keypad", {"processId": "x", "positions": POSITIONS}, headers={}
            )
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "UNAUTHORIZED"})
        fake.assert_not_called()

    def test_complete_requires_the_sidecar_key(self):
        with patch.object(login, "complete", AsyncMock(return_value="{}")) as fake:
            response = self.post("/complete", {"processId": "x"}, {})
        self.assertEqual(response.status_code, 401)
        fake.assert_not_called()

    def test_initiate_success_shape(self):
        with patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)) as fake:
            response = self.post("/initiate", {"customerId": CUSTOMER_ID})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), OK_INITIATE)
        fake.assert_awaited_once_with(CUSTOMER_ID)

    def test_keypad_success_shape(self):
        with patch.object(login, "keypad", AsyncMock(return_value=OK_KEYPAD)) as fake:
            response = self.post(
                "/keypad", {"processId": "p" * 32, "positions": POSITIONS}
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), OK_KEYPAD)
        fake.assert_awaited_once_with("p" * 32, POSITIONS)

    def test_login_errors_map_to_status_and_detail_only(self):
        for status, code in (
            (401, "INVALID_CREDENTIALS"),
            (409, "KEYPAD_CHANGED"),
            (429, "TOO_MANY_PENDING"),
            (502, "UPSTREAM_UNAVAILABLE"),
            (502, "UPSTREAM_FORMAT_CHANGED"),
        ):
            with self.subTest(code=code):
                error = login.LoginError(status, code)
                with patch.object(login, "initiate", AsyncMock(side_effect=error)):
                    response = self.post("/initiate", {"customerId": CUSTOMER_ID})
                self.assertEqual(response.status_code, status)
                self.assertEqual(response.json(), {"detail": code})

    def test_keypad_errors_map_to_status_and_detail_only(self):
        for status, code in (
            (422, "INVALID_POSITIONS"),
            (408, "KEYPAD_EXPIRED"),
            (409, "KEYPAD_CHANGED"),
            (410, "AUTH_ATTEMPT_EXPIRED"),
            (401, "INVALID_CREDENTIALS"),
            (502, "UPSTREAM_UNAVAILABLE"),
        ):
            with self.subTest(code=code):
                with patch.object(
                    login, "keypad", AsyncMock(side_effect=login.LoginError(status, code))
                ):
                    response = self.post(
                        "/keypad", {"processId": "p", "positions": POSITIONS}
                    )
                self.assertEqual(response.status_code, status)
                self.assertEqual(response.json(), {"detail": code})

    def test_unexpected_failure_is_internal_error_with_no_detail(self):
        with patch.object(
            login, "initiate", AsyncMock(side_effect=RuntimeError(f"boom {CUSTOMER_ID}"))
        ), self.assertLogs(level="ERROR") as logs:
            response = self.post("/initiate", {"customerId": CUSTOMER_ID})

        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json(), {"detail": "INTERNAL_ERROR"})
        self.assertNotIn(CUSTOMER_ID, "\n".join(logs.output))

    def test_a_non_numeric_identifier_is_401_before_any_browser_work(self):
        world = World()
        with patch.object(login, "_playwright_factory", lambda: world.factory()):
            for customer_id in ("12ab", "", "x" * 30, "1" * 21):
                with self.subTest(customer=len(customer_id)):
                    response = self.post("/initiate", {"customerId": customer_id})
                    if customer_id == "":
                        self.assertEqual(response.status_code, 400)
                        continue
                    self.assertEqual(response.status_code, 401)
                    self.assertEqual(response.json(), {"detail": "INVALID_CREDENTIALS"})
        self.assertEqual(world.launches, 0)

    def test_a_password_field_is_not_accepted_any_more(self):
        # The password never leaves the user's browser: a caller sending one is a bug.
        bodies = (
            {"customerId": CUSTOMER_ID, "password": PASSWORD},
            {"password": PASSWORD},
            {"customerId": CUSTOMER_ID, "password": PASSWORD, "extra": 1},
        )
        with patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)) as fake:
            for body in bodies:
                with self.subTest(body=sorted(body)):
                    response = self.post("/initiate", body)
                    self.assertEqual(response.status_code, 400)
                    self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})
                    self.assertNotIn(PASSWORD, response.text)
        fake.assert_not_called()

    def test_malformed_bodies_are_400_and_never_echo_the_input(self):
        bodies = (
            {},
            {"customerId": 7305918264},
            {"customerId": CUSTOMER_ID, "extra": 1},
        )
        with patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)) as fake:
            for body in bodies:
                with self.subTest(body=sorted(body)):
                    response = self.post("/initiate", body)
                    self.assertEqual(response.status_code, 400)
                    self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})
        fake.assert_not_called()

    def test_keypad_rejects_a_body_without_usable_positions(self):
        bodies = (
            {},
            {"processId": "p"},
            {"positions": POSITIONS},
            {"processId": "", "positions": POSITIONS},
            {"processId": "p", "positions": "305719"},
            {"processId": "p", "positions": [3, 0, "5", 7, 1, 9]},
            {"processId": "p", "positions": []},
            {"processId": "p", "positions": POSITIONS, "extra": 1},
        )
        with patch.object(login, "keypad", AsyncMock(return_value=OK_KEYPAD)) as fake:
            for body in bodies:
                with self.subTest(body=sorted(body)):
                    response = self.post("/keypad", body)
                    self.assertEqual(response.status_code, 400)
                    self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})
        fake.assert_not_called()

    def test_complete_success_returns_the_session_state_string(self):
        state = json.dumps({"cookies": [], "authorizeParams": {}})
        with patch.object(login, "complete", AsyncMock(return_value=state)) as fake:
            response = self.post("/complete", {"processId": "abc"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"sessionState": state})
        fake.assert_awaited_once_with("abc")

    def test_complete_errors_map_to_status_and_detail_only(self):
        for status, code in (
            (410, "AUTH_ATTEMPT_EXPIRED"),
            (408, "APP_VALIDATION_TIMEOUT"),
            (401, "INVALID_CREDENTIALS"),
            (502, "UPSTREAM_UNAVAILABLE"),
            (502, "UPSTREAM_FORMAT_CHANGED"),
        ):
            with self.subTest(code=code):
                with patch.object(
                    login, "complete", AsyncMock(side_effect=login.LoginError(status, code))
                ):
                    response = self.post("/complete", {"processId": "abc"})
                self.assertEqual(response.status_code, status)
                self.assertEqual(response.json(), {"detail": code})

    def test_complete_rejects_a_body_without_a_process_id(self):
        for body in ({}, {"processId": ""}, {"processId": 5}, {"processId": "a", "code": "1"}):
            with self.subTest(body=body):
                response = self.post("/complete", body)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})

    def test_request_log_line_carries_no_body(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        logging.getLogger().addHandler(handler)
        self.addCleanup(logging.getLogger().removeHandler, handler)
        with patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)):
            self.post("/initiate", {"customerId": CUSTOMER_ID})
        with patch.object(login, "keypad", AsyncMock(return_value=OK_KEYPAD)):
            self.post("/keypad", {"processId": "p" * 32, "positions": POSITIONS})

        self.assertNotIn(CUSTOMER_ID, stream.getvalue())
        self.assertNotIn(str(POSITIONS), stream.getvalue())


class LifespanTest(unittest.TestCase):
    def test_shutdown_closes_every_pending_browser(self):
        with patch.object(main, "SIDECAR_API_KEY", TEST_KEY), patch.object(
            login, "close_all", AsyncMock()
        ) as close_all:
            with TestClient(main.app):
                close_all.assert_not_awaited()
        close_all.assert_awaited_once()

    def test_the_sweeper_runs_while_the_app_is_up(self):
        started = []

        async def fake_sweeper():
            started.append(True)
            import asyncio

            await asyncio.sleep(3600)

        with patch.object(main, "SIDECAR_API_KEY", TEST_KEY), patch.object(
            login, "sweeper", fake_sweeper
        ), patch.object(login, "close_all", AsyncMock()):
            with TestClient(main.app):
                pass
        self.assertEqual(started, [True])


if __name__ == "__main__":
    unittest.main()