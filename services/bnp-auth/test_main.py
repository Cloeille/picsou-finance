"""The sidecar's HTTP contract and request validation, with a fake bank (synthetic data only)."""

import os
import unittest
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient
from picsou_sidecar import KEY_HEADER

import main

KEY = "bnp-contract-test-key"
HEADERS = {KEY_HEADER: KEY}


class FakeBank:
    def __init__(self):
        self.calls = []
        self.fail = None
        self.pending = main.bank_client.pending

    def _maybe_fail(self):
        if self.fail:
            raise self.fail

    async def initiate(self, customer_id, brand):
        self.calls.append(("initiate", customer_id, brand))
        self._maybe_fail()
        return {"processId": "p1", "keypad": {"keys": [str(i) for i in range(10)], "columns": 5}, "expiresInSeconds": 300}

    async def keypad(self, process_id, positions):
        self.calls.append(("keypad", process_id, list(positions)))
        self._maybe_fail()
        return "CODE_REQUIRED"

    async def complete(self, process_id, code):
        self.calls.append(("complete", process_id, code))
        self._maybe_fail()
        return "opaque-jar"

    async def accounts(self, session_state):
        self.calls.append(("accounts", session_state))
        self._maybe_fail()
        return {"brand": "bnp", "accounts": [], "unsupported": [], "sessionState": "rotated"}


class BnpSidecarContractTest(unittest.TestCase):
    def setUp(self):
        key = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": KEY})
        key.start()
        self.addCleanup(key.stop)
        self._client = TestClient(main.app)
        self.client = self._client.__enter__()
        self.addCleanup(self._client.__exit__, None, None, None)
        self.fake = FakeBank()
        patcher = patch.object(main, "bank_client", self.fake)
        patcher.start()
        self.addCleanup(patcher.stop)

    def post(self, path, body):
        return self.client.post(path, headers=HEADERS, json=body)

    def test_the_login_is_three_calls_and_never_echoes_the_positions(self):
        started = self.post("/initiate", {"customerId": "1234567890", "brand": "hellobank"})
        self.assertEqual(started.status_code, 200)
        self.assertEqual(len(started.json()["keypad"]["keys"]), 10)
        keypad = self.post("/keypad", {"processId": "p1", "positions": [0, 1, 2, 3, 4, 9]})
        self.assertEqual(keypad.json(), {"processId": "p1", "status": "CODE_REQUIRED"})
        done = self.post("/complete", {"processId": "p1", "code": "123456"})
        self.assertEqual(done.json(), {"sessionState": "opaque-jar"})
        self.assertEqual(self.fake.calls[0], ("initiate", "1234567890", "hellobank"))

    def test_the_app_approval_login_sends_no_code(self):
        self.post("/complete", {"processId": "p1"})
        self.assertEqual(self.fake.calls[-1], ("complete", "p1", None))

    def test_invalid_login_bodies_are_refused_before_any_bank_call(self):
        bad = [
            ("/initiate", {"customerId": "12 34", "brand": "bnp"}),
            ("/initiate", {"customerId": "123", "brand": "bnp"}),
            ("/initiate", {"customerId": "1234567890", "brand": "other"}),
            ("/initiate", {"customerId": "1234567890", "brand": "bnp", "password": "x"}),
            ("/keypad", {"processId": "p1", "positions": [0, 1, 2, 3, 4]}),
            ("/keypad", {"processId": "p1", "positions": [0, 1, 2, 3, 4, 10]}),
            ("/keypad", {"processId": "p1", "positions": [0, 1, 2, 3, 4, "5"]}),
            ("/keypad", {"processId": "p1", "positions": [0, 1, 2, 3, 4, True]}),
            ("/complete", {"processId": "p1", "code": "12ab"}),
            ("/accounts", {"sessionState": ""}),
        ]
        for path, body in bad:
            with self.subTest(path=path, body=body):
                response = self.post(path, body)
                self.assertEqual(response.status_code, 400)
                self.assertNotIn("1234567890", response.text)
        self.assertEqual(self.fake.calls, [])

    def test_a_malformed_code_is_an_invalid_otp_and_other_login_errors_are_generic(self):
        self.assertEqual(self.post("/complete", {"processId": "p1", "code": "x"}).json(), {"detail": "INVALID_OTP"})
        self.assertEqual(self.post("/initiate", {}).json(), {"detail": "INVALID_REQUEST"})
        self.assertEqual(self.post("/accounts", {}).json(), {"detail": "INVALID_SESSION_STATE"})

    def test_a_typed_failure_keeps_its_status_and_code(self):
        self.fake.fail = HTTPException(status_code=429, detail="RATE_LIMITED")
        response = self.post("/keypad", {"processId": "p1", "positions": [0, 1, 2, 3, 4, 5]})
        self.assertEqual((response.status_code, response.json()), (429, {"detail": "RATE_LIMITED"}))

    def test_an_unexpected_error_never_leaks_its_text(self):
        self.fake.fail = RuntimeError("customer 1234567890 at https://secret.example")
        with self.assertLogs("bnp-auth", level="ERROR") as logs:
            response = self.post("/initiate", {"customerId": "1234567890", "brand": "bnp"})
        self.assertEqual((response.status_code, response.json()), (500, {"detail": "INTERNAL_ERROR"}))
        self.assertNotIn("1234567890", response.text + "".join(logs.output))
        self.assertNotIn("secret.example", response.text + "".join(logs.output))


if __name__ == "__main__":
    unittest.main()
