"""Route and response contract, exercised against `main` with a stub transport.

A parser test that passes a dict proves the parser, not the service: it cannot
catch a wrong endpoint path, a missing `/complete`, or a login outcome that falls
through to a success. These tests import the real app and replace only the
transport, so the URLs, the status codes and the payload shapes the Java adapter
depends on are all asserted.

The transport stub answers by URL, which is also what makes the path assertions
here meaningful: a route pointed at a path the stub does not know gets a 404 and
the test fails loudly instead of passing on an empty snapshot.
"""

import json
import unittest
from typing import Any

from fastapi.testclient import TestClient

import main as service

FAKE_SESSION = json.dumps(
    {
        "cookies": [
            {"name": "PHPSESSID", "value": "abc123", "domain": "moncompte.sofidy.com", "path": "/"}
        ]
    }
)


def portfolio_html(rows: str = "", total: str = "") -> str:
    return (
        "<html><body>Portefeuille SCPI au 27/09/2026"
        "<table><thead><tr><th>Nombre de parts</th></tr></thead><tbody>"
        f"{rows}{total}</tbody></table></body></html>"
    )


FUND_ROW = """
<tr class="line_marron">
  <td>FONDS-EXEMPLE</td>
  <td>3.00000</td>
  <td>Pleine propriété</td>
  <td><span>100.00</span> &euro;</td>
  <td><span>300.00</span> &euro;</td>
  <td><a href="scripts/ajax/rapportdetail.php?Code_Produit=XY">Rapport</a></td>
</tr>"""
TOTAL_ROW = """
<tr class="titre_table">
  <td>Total</td><td>3.00000</td><td></td><td></td><td><span>300.00</span></td><td></td>
</tr>"""

LOGIN_PAGE = '<form id="connexion_espace_partenaire"></form>'


class StubResponse:
    def __init__(self, text: str, status_code: int = 200) -> None:
        self.text = text
        self.status_code = status_code


class StubClient:
    """Answers the portal by path, and records what it was asked."""

    def __init__(self, responses: dict[str, StubResponse]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.cookies = _Jar()

    async def _answer(self, method: str, path: str, **kwargs: Any) -> StubResponse:
        self.calls.append((f"{method} {path}", kwargs))
        if path not in self.responses:
            return StubResponse("", 404)
        return self.responses[path]

    async def get(self, path: str, **kwargs: Any) -> StubResponse:
        return await self._answer("GET", path, **kwargs)

    async def post(self, path: str, **kwargs: Any) -> StubResponse:
        return await self._answer("POST", path, **kwargs)

    async def aclose(self) -> None:
        return None


class _Jar:
    """A real cookie jar, because the session round-trip is part of the contract.

    `serialize_cookies` has to emit a name the adapter can restore, so a stub
    that silently swallows `set` would let a session that can never be restored
    pass as a working one.
    """

    def __init__(self) -> None:
        self.jar: list[Any] = []

    def set(self, name: str, value: str, domain: str = "", path: str = "/", **_kwargs: Any) -> None:
        self.jar.append(
            type("C", (), {"name": name, "value": value, "domain": domain, "path": path})()
        )


def logged_in_responses(**overrides: StubResponse) -> dict[str, StubResponse]:
    responses = {
        "/1,accueil.html": StubResponse("<html>accueil connecte</html>"),
        service.LOGIN_PATH: StubResponse("2FA|a***@example.com"),
        service.VERIFY_2FA_PATH: StubResponse("fauxyes"),
        service.PORTFOLIO_PATH: StubResponse(portfolio_html(FUND_ROW, TOTAL_ROW)),
    }
    responses.update(overrides)
    return responses


def stub_client(responses: dict[str, StubResponse]) -> StubClient:
    """A stub that has already served a page, so it holds a session cookie."""
    stub = StubClient(responses)
    stub.cookies.set("PHPSESSID", "abc123", domain="moncompte.sofidy.com", path="/")
    return stub


class RouteContract(unittest.TestCase):
    def setUp(self) -> None:
        self._original = service._new_client
        self.client = TestClient(service.app)

    def tearDown(self) -> None:
        service._new_client = self._original

    def _stub(self, responses: dict[str, StubResponse]) -> StubClient:
        stub = stub_client(responses)
        service._new_client = lambda: stub
        return stub

    # ── initiate ──────────────────────────────────────────────────────────

    def test_second_factor_returns_a_process_id_and_no_session(self):
        stub = self._stub(logged_in_responses())

        response = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "hunter2"}
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["mfaRequired"])
        self.assertEqual(body["mfaType"], "EMAIL_CODE")
        self.assertIsNotNone(body["processId"])
        self.assertIsNone(body["sessionState"])
        # The code must be the one the site actually serves, and the login must
        # be posted to the endpoint the site's own JS calls. The portal issues
        # PHPSESSID on the first page view, so that page has to be fetched first.
        self.assertIn(f"GET /1,accueil.html", [c[0] for c in stub.calls])
        self.assertIn(f"POST {service.LOGIN_PATH}", [c[0] for c in stub.calls])

    def test_login_without_a_second_factor_returns_a_session(self):
        self._stub(logged_in_responses(**{service.LOGIN_PATH: StubResponse("")}))

        body = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "hunter2"}
        ).json()

        self.assertFalse(body["mfaRequired"])
        self.assertIsNone(body["processId"])
        self.assertIn("PHPSESSID", body["sessionState"])

    def test_associate_code_must_be_six_digits(self):
        self._stub(logged_in_responses())

        response = self.client.post(
            "/initiate", json={"associateCode": "12345", "password": "hunter2"}
        )

        # 422 would be translated upstream as a credential problem, sending the
        # operator to look at the portal instead of at the caller.
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_REQUEST")

    def test_wrong_password_is_invalid_credentials(self):
        self._stub(logged_in_responses(**{service.LOGIN_PATH: StubResponse("faux")}))

        response = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "wrong"}
        )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "INVALID_CREDENTIALS")

    def test_brute_force_counter_is_a_rate_limit_not_a_bad_password(self):
        self._stub(logged_in_responses(**{service.LOGIN_PATH: StubResponse("compteur_4")}))

        response = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "hunter2"}
        )

        self.assertEqual(response.status_code, 429)
        self.assertEqual(response.json()["detail"], "RATE_LIMITED")

    def test_dormant_account_is_reported_as_such(self):
        self._stub(logged_in_responses(**{service.LOGIN_PATH: StubResponse("inactif")}))

        response = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "hunter2"}
        )

        self.assertEqual(response.status_code, 403)
        self.assertEqual(response.json()["detail"], "ACCOUNT_INACTIVE")

    def test_unusable_answer_never_becomes_a_stored_session(self):
        # The portal answers 200 with an unrecognised token and no session. Storing
        # it would hand the user a connection that fails on the first sync.
        self._stub(
            logged_in_responses(
                **{service.LOGIN_PATH: StubResponse("somethingnew"),
                   "/1,accueil.html": StubResponse(LOGIN_PAGE)}
            )
        )

        response = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "hunter2"}
        )

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "UPSTREAM_FORMAT_CHANGED")

    # ── complete ──────────────────────────────────────────────────────────

    def _pending_id(self, responses: dict[str, StubResponse] | None = None) -> tuple[str, StubClient]:
        """Starts a login and returns the id with the stub that owns the attempt.

        `/complete` resumes the client the pending attempt pinned, so a second
        `_stub` call would leave that attempt talking to the first stub. The
        returned client is the one to reconfigure.
        """
        stub = self._stub(responses or logged_in_responses())
        process_id = self.client.post(
            "/initiate", json={"associateCode": "000000", "password": "hunter2"}
        ).json()["processId"]
        return process_id, stub

    def test_verification_returns_the_session(self):
        process_id, stub = self._pending_id()

        response = self.client.post("/complete", json={"processId": process_id, "code": "480921"})

        self.assertEqual(response.status_code, 200)
        self.assertIn("PHPSESSID", response.json()["sessionState"])
        posted = [c for c in stub.calls if c[0] == f"POST {service.VERIFY_2FA_PATH}"]
        self.assertTrue(posted, "verification must go to verify2fa.php")

    def test_wrong_code_is_rejected_without_closing_the_portal(self):
        process_id, stub = self._pending_id()
        stub.responses[service.VERIFY_2FA_PATH] = StubResponse("faux")

        response = self.client.post("/complete", json={"processId": process_id, "code": "000000"})

        # 401 here means "try again with another code". A 502 would tell the user
        # Sofidy is down, which is a different problem with a different fix.
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "MFA_INVALID")
        self.assertNotIn("sessionState", response.json())

    def test_a_wrong_code_can_be_retried_on_the_same_attempt(self):
        process_id, stub = self._pending_id()
        stub.responses[service.VERIFY_2FA_PATH] = StubResponse("faux")

        rejected = self.client.post("/complete", json={"processId": process_id, "code": "000000"})
        self.assertEqual(rejected.status_code, 401)

        stub.responses[service.VERIFY_2FA_PATH] = StubResponse("fauxyes")
        retried = self.client.post("/complete", json={"processId": process_id, "code": "480921"})

        self.assertEqual(retried.status_code, 200)
        self.assertIn("PHPSESSID", retried.json()["sessionState"])

    def test_completing_twice_fails(self):
        process_id, _ = self._pending_id()
        first = self.client.post("/complete", json={"processId": process_id, "code": "480921"})
        self.assertEqual(first.status_code, 200)

        response = self.client.post("/complete", json={"processId": process_id, "code": "480921"})

        self.assertEqual(response.status_code, 410)
        self.assertEqual(response.json()["detail"], "AUTH_ATTEMPT_EXPIRED")

    def test_unknown_process_id_fails(self):
        self._stub(logged_in_responses())

        response = self.client.post("/complete", json={"processId": "nope", "code": "480921"})

        self.assertEqual(response.status_code, 410)

    # ── positions ─────────────────────────────────────────────────────────

    def test_positions_reads_the_portfolio(self):
        self._stub(logged_in_responses())

        body = self.client.post("/positions", json={"sessionState": FAKE_SESSION}).json()

        self.assertEqual(body["valuationDate"], "2026-09-27")
        self.assertEqual(body["currency"], "EUR")
        self.assertEqual(float(body["totalEur"]), 300.00)
        self.assertEqual(len(body["holdings"]), 1)
        holding = body["holdings"][0]
        self.assertEqual(holding["fundCode"], "XY")
        self.assertEqual(holding["label"], "FONDS-EXEMPLE")
        self.assertEqual(float(holding["quantity"]), 3.0)
        self.assertEqual(float(holding["withdrawalPrice"]), 100.00)
        self.assertNotIn("shareCount", holding)
        self.assertNotIn("withdrawalPriceEur", holding)
        self.assertEqual(float(holding["totalEur"]), 300.00)
        self.assertTrue(body["snapshotComplete"])

    def test_expired_session_is_not_an_empty_portfolio(self):
        # The portal answers 200 with the login form when the session dies.
        # Reading that as "no funds" would wipe every linked account's balance.
        self._stub(logged_in_responses(**{"/1,accueil.html": StubResponse(LOGIN_PAGE)}))

        response = self.client.post("/positions", json={"sessionState": FAKE_SESSION})

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "SESSION_EXPIRED")

    def test_login_form_on_the_portfolio_page_is_also_expired(self):
        self._stub(logged_in_responses(**{service.PORTFOLIO_PATH: StubResponse(LOGIN_PAGE)}))

        response = self.client.post("/positions", json={"sessionState": FAKE_SESSION})

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "SESSION_EXPIRED")

    def test_portal_outage_is_not_reported_as_a_dead_session(self):
        self._stub(
            logged_in_responses(**{service.PORTFOLIO_PATH: StubResponse("boom", 503)})
        )

        response = self.client.post("/positions", json={"sessionState": FAKE_SESSION})

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "UPSTREAM_UNAVAILABLE")

    def test_incomplete_portfolio_is_refused(self):
        stub = StubClient(
            logged_in_responses(
                **{
                    service.PORTFOLIO_PATH: StubResponse(
                        portfolio_html(FUND_ROW, TOTAL_ROW.replace("300.00", "999.00"))
                    )
                }
            )
        )
        service._new_client = lambda: stub

        response = self.client.post("/positions", json={"sessionState": FAKE_SESSION})

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "PORTFOLIO_INCOMPLETE")

    def test_a_malformed_session_is_a_bad_request(self):
        self._stub(logged_in_responses())

        response = self.client.post("/positions", json={"sessionState": "not-json"})

        self.assertEqual(response.status_code, 400)

    def test_health_is_reachable(self):
        self._stub(logged_in_responses())

        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})


if __name__ == "__main__":
    unittest.main()
