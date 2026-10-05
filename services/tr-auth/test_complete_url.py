"""/complete builds a TR path from processId and tan: only well-formed values reach it."""

import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi.testclient import TestClient

import main

TEST_KEY = "tr-sidecar-test-key"
PROCESS_ID = "3f2b8c1e-9d4a-4b7e-8f60-2a1c5d9e7b03"
MALICIOUS_PROCESS_IDS = (
    "../../api/v1/account",
    "abc/def",
    "abc?x=1",
    "abc#frag",
    "abc%2Fdef",
    "user@evil",
    "..",
    "",
    "a" * 65,
)
MALICIOUS_TANS = ("12/4", "1234?x", "12#4", "%31%32", "123", "abcd", "١٢٣٤", "1234\n")


class LoginCompleteUrlTest(unittest.TestCase):
    def test_well_formed_values_build_the_tr_path(self):
        self.assertEqual(
            main.login_complete_url(PROCESS_ID, "1234"),
            f"https://api.traderepublic.com/api/v1/auth/web/login/{PROCESS_ID}/1234",
        )

    def test_malicious_process_ids_are_refused(self):
        for value in MALICIOUS_PROCESS_IDS:
            with self.subTest(processId=value), self.assertRaises(ValueError):
                main.login_complete_url(value, "1234")

    def test_malicious_tans_are_refused(self):
        for value in MALICIOUS_TANS:
            with self.subTest(tan=value), self.assertRaises(ValueError):
                main.login_complete_url(PROCESS_ID, value)


class CompleteEndpointTest(unittest.TestCase):
    def setUp(self):
        self.post = AsyncMock(return_value=httpx.Response(
            401, text="denied", request=httpx.Request("POST", "https://api.traderepublic.com/")))
        for p in (
            patch.object(main, "SIDECAR_API_KEY", TEST_KEY),
            patch.object(main, "get_waf_token", AsyncMock(return_value="waf")),
            patch.object(httpx.AsyncClient, "post", self.post),
        ):
            p.start()
            self.addCleanup(p.stop)

    def _complete(self, process_id, tan):
        with TestClient(main.app) as client:
            return client.post("/complete", json={"processId": process_id, "tan": tan},
                               headers={"X-Picsou-Sidecar-Key": TEST_KEY})

    def test_malicious_values_get_422_and_never_reach_tr(self):
        for process_id, tan in [(p, "1234") for p in MALICIOUS_PROCESS_IDS] + [(PROCESS_ID, t) for t in MALICIOUS_TANS]:
            with self.subTest(processId=process_id, tan=tan):
                self.assertEqual(self._complete(process_id, tan).status_code, 422)
        self.post.assert_not_called()

    def test_a_valid_request_calls_the_expected_tr_url(self):
        response = self._complete(PROCESS_ID, "1234")

        self.assertEqual(response.status_code, 401)
        self.assertEqual(
            self.post.call_args.args[0],
            f"https://api.traderepublic.com/api/v1/auth/web/login/{PROCESS_ID}/1234",
        )


if __name__ == "__main__":
    unittest.main()
