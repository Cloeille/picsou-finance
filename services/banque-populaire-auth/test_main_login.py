"""HTTP contract of POST /initiate for Banque Populaire (the login itself is faked).

`/keypad` and `/complete` are the Caisse d'Epargne routes unchanged (tested there);
here only what this sidecar adds: the strict `{customerId, region}` body.
"""

import io
import logging
import os
import unittest
from unittest.mock import AsyncMock, patch

from bpce import login
from fastapi.testclient import TestClient

import main

TEST_KEY = "banque-populaire-sidecar-test-key"
HEADERS = {"X-Picsou-Sidecar-Key": TEST_KEY}
CUSTOMER_ID = "7305918264"
OK_INITIATE = {
    "processId": "p" * 32,
    "keypad": {"images": [f"data:image/png;base64,AAAA{i}" for i in range(10)], "columns": 5},
    "expiresInSeconds": 90,
}
CODES = [
    "ALSACE_LORRAINE_CHAMPAGNE", "AQUITAINE_CENTRE_ATLANTIQUE", "AUVERGNE_RHONE_ALPES",
    "BOURGOGNE_FRANCHE_COMTE", "BRED", "GRAND_OUEST", "MEDITERRANEE", "NORD", "OCCITANE",
    "RIVES_DE_PARIS", "SUD", "VAL_DE_FRANCE",
]


class InitiateContractTest(unittest.TestCase):
    def setUp(self):
        key = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": TEST_KEY})
        key.start()
        self.addCleanup(key.stop)

    def post(self, body):
        with TestClient(main.app) as client:
            return client.post("/initiate", json=body, headers=HEADERS)

    def test_the_twelve_region_codes_and_their_exact_labels(self):
        self.assertEqual(list(main.REGION_CODES), CODES)
        self.assertEqual(dict(main.REGIONS), {
            "ALSACE_LORRAINE_CHAMPAGNE": "ALSACE LORRAINE CHAMPAGNE",
            "AQUITAINE_CENTRE_ATLANTIQUE": "AQUITAINE CENTRE ATLANTIQUE",
            "AUVERGNE_RHONE_ALPES": "AUVERGNE RHONE ALPES",
            "BOURGOGNE_FRANCHE_COMTE": "BOURGOGNE FRANCHE-COMTÉ",
            "BRED": "BRED",
            "GRAND_OUEST": "GRAND OUEST",
            "MEDITERRANEE": "MEDITERRANEE",
            "NORD": "NORD",
            "OCCITANE": "OCCITANE",
            "RIVES_DE_PARIS": "RIVES DE PARIS",
            "SUD": "SUD",
            "VAL_DE_FRANCE": "VAL DE FRANCE",
        })
        self.assertEqual(main.SITE.regions, main.REGIONS)

    def test_a_valid_body_hands_the_site_the_identifier_and_the_region_code(self):
        for code in CODES:
            with self.subTest(code=code), \
                    patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)) as fake:
                response = self.post({"customerId": CUSTOMER_ID, "region": code})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json(), OK_INITIATE)
                fake.assert_awaited_once_with(main.SITE, CUSTOMER_ID, region=code)

    def test_a_letter_and_digits_identifier_reaches_the_login_unchanged(self):
        with patch.object(login, "initiate", AsyncMock(return_value=OK_INITIATE)) as fake:
            response = self.post({"customerId": "A1234567", "region": "RIVES_DE_PARIS"})
        self.assertEqual(response.status_code, 200)
        fake.assert_awaited_once_with(main.SITE, "A1234567", region="RIVES_DE_PARIS")

    def test_an_invalid_body_is_400_invalid_request_and_never_reaches_the_login(self):
        bodies = {
            "no region": {"customerId": CUSTOMER_ID},
            "null region": {"customerId": CUSTOMER_ID, "region": None},
            "unknown code": {"customerId": CUSTOMER_ID, "region": "ATLANTIS"},
            "label instead of code": {"customerId": CUSTOMER_ID, "region": "RIVES DE PARIS"},
            "lower case code": {"customerId": CUSTOMER_ID, "region": "rives_de_paris"},
            "padded code": {"customerId": CUSTOMER_ID, "region": " BRED"},
            "number region": {"customerId": CUSTOMER_ID, "region": 10207},
            "list region": {"customerId": CUSTOMER_ID, "region": ["BRED"]},
            "extra field": {"customerId": CUSTOMER_ID, "region": "BRED", "password": "48201735"},
            "number identifier": {"customerId": 7305918264, "region": "BRED"},
            "empty identifier": {"customerId": "", "region": "BRED"},
            "no identifier": {"region": "BRED"},
        }
        for name, body in bodies.items():
            with self.subTest(name), patch.object(login, "initiate", AsyncMock()) as fake:
                response = self.post(body)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json(), {"detail": "INVALID_REQUEST"})
                fake.assert_not_awaited()

    def test_a_validation_error_echoes_and_logs_nothing_of_the_input(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        logging.getLogger().addHandler(handler)
        self.addCleanup(logging.getLogger().removeHandler, handler)
        response = self.post({"customerId": "SECRET-IDENTIFIER-1", "region": "SECRET-REGION-2"})
        self.assertEqual(response.status_code, 400)
        for secret in ("SECRET-IDENTIFIER-1", "SECRET-REGION-2"):
            self.assertNotIn(secret, response.text)
            self.assertNotIn(secret, stream.getvalue())

    def test_a_login_error_maps_to_its_status_and_code(self):
        cases = [
            (login.LoginError(502, "UPSTREAM_FORMAT_CHANGED"), 502, "UPSTREAM_FORMAT_CHANGED"),
            (login.LoginError(401, "INVALID_CREDENTIALS"), 401, "INVALID_CREDENTIALS"),
            (RuntimeError(f"boom {CUSTOMER_ID}"), 500, "INTERNAL_ERROR"),
        ]
        for error, status, code in cases:
            with self.subTest(code=code), patch.object(login, "initiate", AsyncMock(side_effect=error)):
                response = self.post({"customerId": CUSTOMER_ID, "region": "BRED"})
                self.assertEqual((response.status_code, response.json()), (status, {"detail": code}))
                self.assertNotIn(CUSTOMER_ID, response.text)

    def test_the_session_routes_answer_invalid_session_state(self):
        with TestClient(main.app) as client:
            for path in ("/token-check", "/accounts"):
                with self.subTest(path=path):
                    response = client.post(path, json={"sessionState": 1}, headers=HEADERS)
                    self.assertEqual(response.json(), {"detail": "INVALID_SESSION_STATE"})


if __name__ == "__main__":
    unittest.main()
