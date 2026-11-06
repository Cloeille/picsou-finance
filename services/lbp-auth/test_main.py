"""The La Banque Postale sidecar's HTTP contract and request validation."""

import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from picsou_sidecar import KEY_HEADER

import main

KEY = "lbp-contract-test-key"
HEADERS = {KEY_HEADER: KEY}

KEYS = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9"]


class FakeBank:
    def __init__(self):
        self.positions = None

    async def initiate(self, customer_id):
        return {
            "processId": "proc-1",
            "keypad": {"keys": KEYS, "columns": 5},
            "expiresInSeconds": 90,
        }

    async def keypad(self, process_id, positions):
        self.positions = list(positions)
        return None

    async def complete(self, process_id):
        return "opaque-cookie-jar"

    async def check(self, session_state):
        return 600

    async def accounts(self, session_state):
        return {"accounts": [{"externalId": "1", "kind": "LIVRET_A"}], "unsupported": []}


class LbpSidecarContractTest(unittest.TestCase):
    def setUp(self):
        key = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": KEY})
        key.start()
        self.addCleanup(key.stop)
        self._client = TestClient(main.app)
        self.client = self._client.__enter__()
        self.addCleanup(self._client.__exit__, None, None, None)
        self._bank = patch.object(main, "bank_client", main.bank.UnverifiedBankClient())
        self._bank.start()
        self.addCleanup(self._bank.stop)

    def post(self, path, body):
        return self.client.post(path, headers=HEADERS, json=body)

    def test_the_stub_refuses_every_bank_call_with_a_typed_code(self):
        calls = [
            ("/initiate", {"customerId": "1234567890"}),
            ("/keypad", {"processId": "proc-1", "positions": [7, 3, 0, 9, 4, 1]}),
            ("/complete", {"processId": "proc-1"}),
            ("/token-check", {"sessionState": "opaque"}),
            ("/accounts", {"sessionState": "opaque"}),
        ]
        for path, body in calls:
            with self.subTest(path=path):
                response = self.post(path, body)
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json(), {"detail": "BANK_FLOW_UNVERIFIED"})

    def test_a_password_is_refused_before_the_stub(self):
        response = self.post("/initiate", {"customerId": "1234567890", "password": "hunter2"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})
        self.assertNotIn("hunter2", response.text)

    def test_keypad_positions_must_be_integers(self):
        response = self.post("/keypad", {"processId": "proc-1", "positions": ["7", 3, 0, 9, 4, 1]})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})

    def test_keypad_requires_exactly_six_positions(self):
        for positions in ([7, 3, 0, 9, 4], [7, 3, 0, 9, 4, 1, 2]):
            with self.subTest(positions=positions):
                response = self.post("/keypad", {"processId": "proc-1", "positions": positions})
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})

    def test_keypad_positions_must_be_zero_to_nine(self):
        response = self.post("/keypad", {"processId": "proc-1", "positions": [7, 3, 0, 9, 4, 10]})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})

    def test_complete_refuses_a_code_field(self):
        # Certicode is not part of the flow: a caller that still sends a code is refused.
        response = self.post("/complete", {"processId": "proc-1", "code": "987654"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})
        self.assertNotIn("987654", response.text)

    def test_a_session_body_is_read_strictly(self):
        response = self.post("/accounts", {"sessionState": "opaque", "password": "x"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_SESSION_STATE"})

    def test_the_normalized_contract_is_passed_through(self):
        fake = FakeBank()
        with patch.object(main, "bank_client", fake):
            initiated = self.post("/initiate", {"customerId": "1234567890"})
            self.assertEqual(initiated.status_code, 200)
            self.assertEqual(initiated.json()["processId"], "proc-1")
            self.assertEqual(initiated.json()["keypad"]["columns"], 5)
            self.assertEqual(len(initiated.json()["keypad"]["keys"]), 10)

            keypad = self.post("/keypad", {"processId": "proc-1", "positions": [7, 3, 0, 9, 4, 1]})
            self.assertEqual(keypad.status_code, 200)
            self.assertEqual(keypad.json(), {"processId": "proc-1", "status": "APPROVAL_PENDING"})
            # The exact order must reach the bank client: a reorder is a wrong password.
            self.assertEqual(fake.positions, [7, 3, 0, 9, 4, 1])

            completed = self.post("/complete", {"processId": "proc-1"})
            self.assertEqual(completed.status_code, 200)
            self.assertEqual(completed.json(), {"sessionState": "opaque-cookie-jar"})

            check = self.post("/token-check", {"sessionState": "opaque"})
            self.assertEqual(check.status_code, 200)
            self.assertEqual(check.json(), {"ok": True, "expiresIn": 600})

            accounts = self.post("/accounts", {"sessionState": "opaque"})
            self.assertEqual(accounts.status_code, 200)
            self.assertEqual(accounts.json()["accounts"][0]["kind"], "LIVRET_A")

    def test_a_bad_key_is_refused_before_any_bank_call(self):
        response = self.client.post(
            "/accounts", headers={"X-Picsou-Sidecar-Key": "wrong"}, json={"sessionState": "opaque"}
        )
        self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
