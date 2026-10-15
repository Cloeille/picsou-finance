"""Offline synthetic tests for PER read transport; no real bank values or network."""

import asyncio
import io
import json
import logging
import unittest
import urllib.parse
from unittest.mock import patch

import httpx

import replay
import per_client


API_HOST = "www.rs-ext-bad-ce.caisse-epargne.fr"
INSURER_HOST = "www.espace-assurances.caisse-epargne.fr"
API_URL = f"https://{API_HOST}/bapi/contract/v1/contracts/12345-00000%20%20%20%20%20PERIC12345678/prepareRouting"
BOOTSTRAP_URL = f"https://{INSURER_HOST}/bootstrap"
DETAIL_URL = f"https://{INSURER_HOST}/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678"
CONTRACT_ID = "00000     PERIC12345678"
TOKEN = "synthetic-access-token-DO-NOT-LEAK"
COOKIE = "synthetic-session-cookie-DO-NOT-LEAK"
PAYLOAD_MARKER = "synthetic-per-payload-DO-NOT-LOG"
RAW_DETAIL = {"detailContratVie": {"supports": [{"name": PAYLOAD_MARKER}]}, "synthetic": True}


def session_state(*, entity_code="12345", extra_params=None):
    params = {
        "client_id": "synthetic-client",
        "redirect_uri": "https://www.example-app.caisse-epargne.fr/callback",
        "cdetab": entity_code,
    }
    params.update(extra_params or {})
    return json.dumps(
        {
            "cookies": [
                {"name": "SYNTHETIC", "value": COOKIE, "domain": ".caisse-epargne.fr", "path": "/"}
            ],
            "authorizeParams": params,
        }
    )


def routing_response(
    *,
    mode="0",
    href=BOOTSTRAP_URL,
    query_parameters=None,
    form_parameters=None,
    auth_name=None,
    auth_value=None,
    wrapped=True,
):
    characteristics = {
        "routingMode": {"code": mode},
        "routingUrl": {"href": href},
        "routingContext": {
            "value": {
                "queryParameters": query_parameters or {},
                "formParameters": form_parameters or {},
            }
        },
    }
    if auth_name is not None:
        characteristics["authenticationKeyName"] = auth_name
        characteristics["authenticationKeyValue"] = auth_value
    result = {"characteristics": characteristics}
    return {"prepareRouting": result} if wrapped else result


class PerClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_synthesis_entity_code_does_not_require_cdetab_in_the_login_query(self):
        state = json.loads(session_state())
        del state["authorizeParams"]["cdetab"]
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            return httpx.Response(200, json=RAW_DETAIL)

        self.assertEqual(await per_client.fetch_per_detail(
            TOKEN, json.dumps(state), CONTRACT_ID, entity_code="12345",
            transport=httpx.MockTransport(handle),
        ), RAW_DETAIL)
        self.assertEqual(str(requests[0].url), API_URL)

    async def test_contract_bank_prefix_is_not_limited_to_the_synthetic_zero_code(self):
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            if request.url.path == "/bootstrap":
                return httpx.Response(200)
            self.assertEqual(request.url.path, "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/87654321")
            return httpx.Response(200, json=RAW_DETAIL)

        result = await per_client.fetch_per_detail(
            TOKEN, session_state(entity_code="54321"), "54321     PERIC87654321",
            transport=httpx.MockTransport(handle),
        )
        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual(requests[0].url.path,
                         "/bapi/contract/v1/contracts/54321-54321     PERIC87654321/prepareRouting")

    async def assertReplayError(self, code, awaitable):
        with self.assertRaises(replay.ReplayError) as caught:
            await awaitable
        self.assertEqual(caught.exception.code, code)
        return caught.exception

    async def test_mode_zero_posts_routing_form_then_returns_raw_detail(self):
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response(
                    mode="0",
                    form_parameters={"relayField": "synthetic-relay-value"},
                    auth_name="syntheticAuthKey",
                    auth_value="synthetic-auth-value",
                ))
            if request.url.path == "/bootstrap":
                return httpx.Response(200, headers={"Set-Cookie": "bootstrap=synthetic; Path=/"})
            if request.url.path == "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678":
                return httpx.Response(200, json=RAW_DETAIL)
            return httpx.Response(404)

        result = await per_client.fetch_per_detail(
            TOKEN,
            session_state(),
            CONTRACT_ID,
            transport=httpx.MockTransport(handle),
        )

        self.assertEqual(result, RAW_DETAIL)
        self.assertIsNot(result, RAW_DETAIL)
        self.assertEqual(requests[0].method, "POST")
        self.assertEqual(str(requests[0].url), API_URL)
        self.assertEqual(requests[0].headers.get("authorization"), f"Bearer {TOKEN}")
        self.assertEqual(json.loads(requests[0].content), {
            "characteristics": {
                "contractActionType": {"code": "", "label": ""},
                "productFamilyPFM": {"code": "", "label": ""},
                "returnUrl": {},
                "routingContext": {
                    "suppliedName": "routingContext",
                    "value": {"queryParameters": {}, "formParameters": {}},
                },
            },
            "response": {"code": "", "interactionId": "", "label": ""},
        })
        self.assertEqual(requests[1].method, "POST")
        self.assertEqual(urllib.parse.parse_qs(requests[1].content.decode()), {
            "relayField": ["synthetic-relay-value"],
            "syntheticAuthKey": ["synthetic-auth-value"],
        })
        self.assertEqual(requests[2].method, "GET")
        self.assertEqual(str(requests[2].url), DETAIL_URL)
        for request in requests[1:]:
            self.assertNotIn("authorization", request.headers)
        self.assertIn(COOKIE, requests[0].headers.get("cookie", ""))
        self.assertIn(COOKIE, requests[1].headers.get("cookie", ""))
        self.assertIn("bootstrap=synthetic", requests[2].headers.get("cookie", ""))

    async def test_mode_one_appends_query_parameters_and_accepts_unwrapped_response(self):
        requests = []
        href = f"https://{INSURER_HOST}/bootstrap?existing=synthetic"

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response(
                    mode="1",
                    href=href,
                    query_parameters={"routeKey": "synthetic value"},
                    auth_name="routingAuth",
                    auth_value="synthetic-auth-value",
                    wrapped=False,
                ))
            if request.url.path == "/bootstrap":
                return httpx.Response(200, headers={"Set-Cookie": "bootstrap=synthetic; Path=/"})
            if request.url.path == "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678":
                return httpx.Response(200, json=RAW_DETAIL)
            return httpx.Response(404)

        result = await per_client.fetch_per_detail(
            TOKEN,
            session_state(),
            CONTRACT_ID,
            transport=httpx.MockTransport(handle),
        )

        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual([request.method for request in requests], ["POST", "GET", "GET"])
        self.assertEqual(urllib.parse.parse_qs(requests[1].url.query.decode()), {
            "existing": ["synthetic"],
            "routeKey": ["synthetic value"],
            "routingAuth": ["synthetic-auth-value"],
        })
        for request in requests[1:]:
            self.assertNotIn("authorization", request.headers)
        self.assertIn("bootstrap=synthetic", requests[2].headers.get("cookie", ""))

    async def test_literal_null_authentication_key_is_not_sent(self):
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response(
                    auth_name="null", auth_value="synthetic-must-not-be-sent"
                ))
            if request.url.path == "/bootstrap":
                return httpx.Response(200)
            return httpx.Response(200, json=RAW_DETAIL)

        await per_client.fetch_per_detail(
            TOKEN,
            session_state(),
            CONTRACT_ID,
            transport=httpx.MockTransport(handle),
        )

        self.assertNotIn("synthetic-must-not-be-sent", requests[1].content.decode())
        self.assertEqual(urllib.parse.parse_qs(requests[1].content.decode()), {})

    async def test_routing_post_has_request_timeout(self):
        observed = []

        def handle(request):
            observed.append(request.extensions.get("timeout"))
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            if request.url.path == "/bootstrap":
                return httpx.Response(200)
            return httpx.Response(200, json=RAW_DETAIL)

        with patch.object(replay, "REQUEST_TIMEOUT_SECONDS", 0.125), patch.object(
            replay, "CONNECT_TIMEOUT_SECONDS", 0.125
        ):
            await per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(handle),
            )

        self.assertTrue(observed)
        self.assertTrue(all(timeout["read"] == 0.125 for timeout in observed))

    async def test_transport_timeout_is_fixed_and_not_retried(self):
        requests = []

        def timeout(request):
            requests.append(request)
            raise httpx.ReadTimeout(f"synthetic timeout containing {TOKEN}")

        error = await self.assertReplayError(
            "UPSTREAM_ERROR",
            per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(timeout),
            ),
        )
        self.assertEqual(len(requests), 1)
        self.assertNotIn(TOKEN, str(error))
        self.assertNotIn(TOKEN, repr(error))

    async def test_missing_or_ambiguous_entity_and_malformed_contract_are_refused_early(self):
        requests = []

        def handle(request):
            requests.append(request)
            return httpx.Response(500)

        states = [
            session_state().replace(', "cdetab": "12345"', ""),
            session_state(extra_params={"cdetab": None}),
            session_state().replace('"cdetab": "12345"', '"cdetab": "12345", "cdetab": "54321"'),
        ]
        for state in states:
            with self.subTest(state=state[:20]):
                await self.assertReplayError(
                    "INVALID_SESSION_STATE",
                    per_client.fetch_per_detail(
                        TOKEN, state, CONTRACT_ID, transport=httpx.MockTransport(handle)
                    ),
                )
        for contract in (
            "PERIC12345678",
            "00000 PERIC12345678/../x",
            "00000     PERIC12345678 PERIC99999999",
            "00000     PERIC12345678\\r\\nhttps://evil.invalid",
            "00000     peric12345678",
        ):
            with self.subTest(contract=contract):
                await self.assertReplayError(
                    "INVALID_PER_REQUEST",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        contract,
                        transport=httpx.MockTransport(handle),
                    ),
                )
        self.assertEqual(requests, [])

    async def test_unsafe_routing_urls_and_unknown_method_classification_are_refused(self):
        unsafe_urls = (
            "http://www.espace-assurances.caisse-epargne.fr/bootstrap",
            "https://evil.caisse-epargne.fr/bootstrap",
            "https://www.espace-assurances.caisse-epargne.fr.evil.invalid/bootstrap",
            "https://user@www.espace-assurances.caisse-epargne.fr/bootstrap",
            "https://www.espace-assurances.caisse-epargne.fr:8443/bootstrap",
            "https://www.es...fr\\@evil.invalid/bootstrap",
        )
        for href, mode in [(url, "0") for url in unsafe_urls] + [(BOOTSTRAP_URL, "POST")]:
            requests = []

            def handle(request, *, _href=href, _mode=mode):
                requests.append(request)
                if request.url.host == API_HOST:
                    return httpx.Response(200, json=routing_response(mode=_mode, href=_href))
                return httpx.Response(200, json=RAW_DETAIL)

            with self.subTest(href=href, mode=mode):
                await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertEqual([request.url.host for request in requests], [API_HOST])

    async def test_routing_json_root_and_classification_fail_closed(self):
        route_bodies = (
            b"not-json",
            b"[]",
            b'{"prepareRouting":[]}',
        )
        for body in route_bodies:
            requests = []

            def handle(request, *, _body=body):
                requests.append(request)
                if request.url.host == API_HOST:
                    return httpx.Response(200, content=_body)
                return httpx.Response(200, json=RAW_DETAIL)

            with self.subTest(body=body):
                await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertEqual(len(requests), 1)

        requests = []

        def unsupported(request):
            requests.append(request)
            return httpx.Response(200, json=routing_response(mode="2"))

        await self.assertReplayError(
            "UPSTREAM_ERROR",
            per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(unsupported),
            ),
        )
        self.assertEqual(len(requests), 1)

    async def test_duplicate_and_conflicting_authentication_parameters_are_refused(self):
        cases = (
            routing_response(mode="0", form_parameters={"auth": "different"}, auth_name="auth", auth_value="value"),
            routing_response(mode="1", query_parameters={"auth": "different"}, auth_name="auth", auth_value="value"),
            routing_response(mode="0", auth_name="username", auth_value="synthetic-user"),
            routing_response(mode="0", href=f"{BOOTSTRAP_URL}?auth=existing", auth_name="auth", auth_value="value"),
        )
        missing_auth_value = routing_response(mode="0", auth_name="auth", auth_value="synthetic-value")
        del missing_auth_value["prepareRouting"]["characteristics"]["authenticationKeyValue"]
        cases += (missing_auth_value,)
        for payload in cases:
            requests = []

            def handle(request, *, _payload=payload):
                requests.append(request)
                return httpx.Response(200, json=_payload)

            with self.subTest(payload=payload["prepareRouting"]["characteristics"]["routingMode"]):
                await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertEqual(len(requests), 1)

    async def test_detail_must_be_valid_json_object_with_detail_contrat_vie(self):
        invalid_details = (b"not-json", b"[]", b"{}", b'{"detailContratVie":null}')
        for body in invalid_details:
            requests = []

            def handle(request, *, _body=body):
                requests.append(request)
                if request.url.host == API_HOST:
                    return httpx.Response(200, json=routing_response())
                if request.url.path == "/bootstrap":
                    return httpx.Response(200)
                return httpx.Response(200, content=_body)

            with self.subTest(body=body):
                await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertEqual(requests[-1].url.path, "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678")

    async def test_simple_hidden_autopost_form_is_followed_without_authorization(self):
        requests = []
        form_page = (
            b'<!doctype html><html><body onload="document.forms[0].submit()">'
            b'<form method="post" action="/continue">'
            b'<input type="hidden" name="SAMLResponse" value="synthetic-saml">'
            b'<input type="hidden" name="RelayState" value="synthetic-state">'
            b'</form></body></html>'
        )

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            if request.url.path == "/bootstrap":
                return httpx.Response(200, content=form_page, headers={"content-type": "text/html"})
            if request.url.path == "/continue":
                return httpx.Response(200, headers={"Set-Cookie": "bootstrap=synthetic; Path=/"})
            return httpx.Response(200, json=RAW_DETAIL)

        result = await per_client.fetch_per_detail(
            TOKEN,
            session_state(),
            CONTRACT_ID,
            transport=httpx.MockTransport(handle),
        )

        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual([request.method for request in requests], ["POST", "POST", "POST", "GET"])
        self.assertEqual(urllib.parse.parse_qs(requests[2].content.decode()), {
            "SAMLResponse": ["synthetic-saml"],
            "RelayState": ["synthetic-state"],
        })
        self.assertIn("bootstrap=synthetic", requests[3].headers.get("cookie", ""))
        for request in requests[1:]:
            self.assertNotIn("authorization", request.headers)

    async def test_hidden_form_requires_supported_sso_relay_fields(self):
        requests = []
        form_page = (
            b'<html><body onload="document.forms[0].submit()">'
            b'<form method="post" action="/continue">'
            b'<input type="hidden" name="unknownState" value="synthetic-state">'
            b'</form></body></html>'
        )

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            if request.url.path == "/bootstrap":
                return httpx.Response(200, content=form_page, headers={"content-type": "text/html"})
            return httpx.Response(200, json=RAW_DETAIL)

        with self.assertRaises(replay.ReplayError) as caught:
            await per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(handle),
            )

        self.assertEqual(caught.exception.code, "UPSTREAM_ERROR")
        self.assertEqual(len(requests), 2)

    async def test_challenge_or_unknown_hidden_form_is_refused(self):
        pages = (
            b'<html><body><form method="get" action="/continue">'
            b'<input type="hidden" name="x" value="synthetic"></form></body></html>',
            b'<html><body onload="document.forms[0].submit()"><form method="post" action="/continue">'
            b'<input type="password" name="password" value="synthetic"></form></body></html>',
            b'<html><body onload="doAnything()"><form method="post" action="/continue">'
            b'<input type="hidden" name="x" value="synthetic"></form></body></html>',
            b'<html><body onload="document.forms[0].submit()"><form method="post" action="https://evil.invalid/">'
            b'<input type="hidden" name="x" value="synthetic"></form></body></html>',
        )
        for page in pages:
            requests = []

            def handle(request, *, _page=page):
                requests.append(request)
                if request.url.host == API_HOST:
                    return httpx.Response(200, json=routing_response())
                if request.url.path == "/bootstrap":
                    return httpx.Response(200, content=_page, headers={"content-type": "text/html"})
                return httpx.Response(200, json=RAW_DETAIL)

            with self.subTest(page=page[:45]):
                await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertEqual(len(requests), 2)

    async def test_hidden_credential_aliases_are_rejected_before_post(self):
        marker = "synthetic-hidden-credential-DO-NOT-LEAK"
        for alias in ("pwd", "login", "user", "secret"):
            requests = []
            logs = io.StringIO()
            handler = logging.StreamHandler(logs)
            root_logger = logging.getLogger()
            previous_level = root_logger.level
            root_logger.addHandler(handler)
            root_logger.setLevel(logging.DEBUG)
            form_page = (
                '<html><body onload="document.forms[0].submit()">'
                '<form method="post" action="/continue">'
                f'<input type="hidden" name="{alias}" value="{marker}">'
                '</form></body></html>'
            ).encode()

            def handle(request):
                requests.append(request)
                if request.url.host == API_HOST:
                    return httpx.Response(200, json=routing_response())
                if request.url.path == "/bootstrap":
                    return httpx.Response(200, content=form_page, headers={"content-type": "text/html"})
                return httpx.Response(401)

            try:
                with self.assertRaises(replay.ReplayError) as caught:
                    await per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    )
            finally:
                root_logger.removeHandler(handler)
                root_logger.setLevel(previous_level)
                handler.close()

            self.assertEqual(len(requests), 2)
            self.assertEqual(caught.exception.code, "UPSTREAM_ERROR")
            self.assertNotIn(marker, str(caught.exception))
            self.assertNotIn(marker, repr(caught.exception))
            self.assertNotIn(marker, logs.getvalue())

    async def test_external_redirect_is_refused_before_following(self):
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            return httpx.Response(302, headers={"Location": "https://evil.caisse-epargne.fr/steal"})

        await self.assertReplayError(
            "UPSTREAM_ERROR",
            per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(handle),
            ),
        )
        self.assertEqual([request.url.host for request in requests], [API_HOST, INSURER_HOST])

        requests = []

        def api_redirect(request):
            requests.append(request)
            return httpx.Response(307, headers={"Location": BOOTSTRAP_URL})

        await self.assertReplayError(
            "UPSTREAM_ERROR",
            per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(api_redirect),
            ),
        )
        self.assertEqual([request.url.host for request in requests], [API_HOST])

    async def test_routing_403_is_permission_failure_without_expiring_session(self):
        requests = []

        def handle(request):
            requests.append(request)
            return httpx.Response(403)

        error = await self.assertReplayError(
            "UPSTREAM_ERROR",
            per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(handle),
            ),
        )

        self.assertEqual(str(error), "PER upstream denied access")
        self.assertIsInstance(error, replay.UpstreamError)
        self.assertNotIsInstance(error, replay.SessionExpired)
        self.assertEqual(len(requests), 1)

    async def test_insurer_403_is_permission_failure_at_bootstrap_and_detail(self):
        detail_path = "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678"
        for blocked_path in ("/bootstrap", detail_path):
            requests = []

            def handle(request, *, _blocked_path=blocked_path):
                requests.append(request)
                if request.url.host == API_HOST:
                    return httpx.Response(200, json=routing_response())
                if request.url.path == _blocked_path:
                    return httpx.Response(403)
                return httpx.Response(200)

            with self.subTest(blocked_path=blocked_path):
                error = await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertEqual(str(error), "PER upstream denied access")
                self.assertIsInstance(error, replay.UpstreamError)
                self.assertNotIsInstance(error, replay.SessionExpired)
                self.assertEqual(len(requests), 2 if blocked_path == "/bootstrap" else 3)

    async def test_401_and_403_stop_without_retries(self):
        for host in (API_HOST, INSURER_HOST):
            for status in (401, 403):
                expected_code = "SESSION_EXPIRED" if status == 401 else "UPSTREAM_ERROR"
                requests = []

                def handle(request, *, _host=host, _status=status):
                    requests.append(request)
                    if request.url.host == API_HOST and _host == API_HOST:
                        return httpx.Response(_status)
                    if request.url.host == API_HOST:
                        return httpx.Response(200, json=routing_response())
                    return httpx.Response(_status)

                with self.subTest(host=host, status=status):
                    await self.assertReplayError(
                        expected_code,
                        per_client.fetch_per_detail(
                            TOKEN,
                            session_state(),
                            CONTRACT_ID,
                            transport=httpx.MockTransport(handle),
                        ),
                    )
                    self.assertEqual(len(requests), 1 if host == API_HOST else 2)

    async def test_body_and_redirect_hops_are_bounded(self):
        too_large = b"x" * (replay.MAX_RESPONSE_BYTES + 1)
        for oversized_at in ("api", "detail"):
            requests = []

            def handle(request, *, _at=oversized_at):
                requests.append(request)
                if request.url.host == API_HOST:
                    if _at == "api":
                        return httpx.Response(200, content=too_large)
                    return httpx.Response(200, json=routing_response())
                if request.url.path == "/bootstrap":
                    return httpx.Response(200)
                return httpx.Response(200, content=too_large)

            with self.subTest(oversized_at=oversized_at):
                await self.assertReplayError(
                    "UPSTREAM_ERROR",
                    per_client.fetch_per_detail(
                        TOKEN,
                        session_state(),
                        CONTRACT_ID,
                        transport=httpx.MockTransport(handle),
                    ),
                )
                self.assertLessEqual(len(requests), 3)

        requests = []

        def redirect_loop(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response(href=BOOTSTRAP_URL))
            return httpx.Response(302, headers={"Location": "/loop"})

        await self.assertReplayError(
            "UPSTREAM_ERROR",
            per_client.fetch_per_detail(
                TOKEN,
                session_state(),
                CONTRACT_ID,
                transport=httpx.MockTransport(redirect_loop),
            ),
        )
        self.assertEqual(len(requests), per_client.MAX_SSO_HOPS + 2)

    async def test_token_echo_is_not_returned_and_no_sensitive_values_are_logged_or_raised(self):
        state = session_state()
        requests = []
        logs = io.StringIO()
        handler = logging.StreamHandler(logs)
        root_logger = logging.getLogger()
        previous_level = root_logger.level
        root_logger.addHandler(handler)
        root_logger.setLevel(logging.DEBUG)

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(200, json=routing_response())
            if request.url.path == "/bootstrap":
                return httpx.Response(200)
            return httpx.Response(200, json={"detailContratVie": {"echo": TOKEN, "marker": PAYLOAD_MARKER}})

        try:
            error = await self.assertReplayError(
                "UPSTREAM_ERROR",
                per_client.fetch_per_detail(
                    TOKEN,
                    state,
                    CONTRACT_ID,
                    transport=httpx.MockTransport(handle),
                ),
            )
        finally:
            root_logger.removeHandler(handler)
            root_logger.setLevel(previous_level)
            handler.close()

        for secret in (TOKEN, COOKIE, state, PAYLOAD_MARKER):
            self.assertNotIn(secret, str(error))
            self.assertNotIn(secret, repr(error))
            self.assertNotIn(secret, logs.getvalue())

    async def test_cancellation_closes_the_short_lived_client(self):
        class BlockingTransport(httpx.AsyncBaseTransport):
            def __init__(self):
                self.entered = asyncio.Event()
                self.closed = False

            async def handle_async_request(self, request) -> httpx.Response:
                self.entered.set()
                await asyncio.Future()
                return httpx.Response(200)

            async def aclose(self):
                self.closed = True

        transport = BlockingTransport()
        task = asyncio.create_task(
            per_client.fetch_per_detail(TOKEN, session_state(), CONTRACT_ID, transport=transport)
        )
        await asyncio.wait_for(transport.entered.wait(), timeout=1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(transport.closed)


# --- Live-shape regression fixtures -------------------------------------------
# Structure (keys, statuses, paths, tags, field names) copied from the sanitized
# live trace of 2026-10-15; every value is synthetic.

LIVE_ROUTING_PATH = "/espaceinternet-ce/views/common/routage-itce.xhtml"
LIVE_ROUTING_URL = f"https://{INSURER_HOST}{LIVE_ROUTING_PATH}"
LIVE_LANDING_PATH = "/etna-ihs-ce/"
LIVE_FORM_PARAMETER_NAMES = (
    "applicationVersion", "runEnv", "runCompanyCode", "codeCanal", "codeProcessus",
    "codeReseau", "urlPing", "runChannel", "idEtape", "urlRetour", "codeEtape",
    "applicationOrganisation", "applicationName", "idInstanceProcessus",
)
LIVE_CLICK_SCRIPT = (
    "$(function() { if (false) { $(location).attr('href', '/synthetic'); }"
    " else { $('#formRoutageButton').click(); } });"
)
LIVE_POPIN_SCRIPT = (
    "new RichFaces.ui.PopupPanel('popinWebCallBack', {autosized: true, height: 1, width: 1,"
    " onhide: function(event) { RichFaces.ajax(this, event, {sourceId: this,"
    " parameters: {'javax.faces.behavior.event': 'hide', 'org': 'synthetic'}}); }});"
)


def live_routing_response():
    """`prepareRouting` 201 body: the live trace logged `characteristics` with these keys.

    Mode 0, no `queryParameters`, 14 `formParameters`. Whether the live root also
    carries a `prepareRouting` wrapper was not logged; both forms are accepted.
    """
    return {"characteristics": _live_characteristics()}


def _live_characteristics():
    return {
        "contractId": {"entityCode": "12345", "id": CONTRACT_ID},
        "productFamilyPFM": None,
        "subProductFamilyPFM": None,
        "routingMode": {"code": "0", "label": "synthetic-post"},
        "routingUrl": {"href": LIVE_ROUTING_URL},
        "authenticationKeyType": {"code": "synthetic", "label": "synthetic"},
        "authenticationKeyValue": "synthetic-auth-value",
        "authenticationKeyName": "jeton",
        "routingContext": {
            "suppliedName": "routingContext",
            "value": {
                "formParameters": {
                    name: (f"https://{INSURER_HOST}/synthetic-{name}" if name.startswith("url")
                           else f"synthetic-{name}")
                    for name in LIVE_FORM_PARAMETER_NAMES
                },
            },
        },
        "businessEventType": {"code": None, "label": ""},
    }


def live_jsf_page(*, action=LIVE_ROUTING_PATH, form_fields=None, scripts=None, extra_body=""):
    fields = form_fields if form_fields is not None else (
        '<input type="hidden" name="formRoutage" value="formRoutage">'
        '<input type="submit" name="formRoutageButton" value="OK">'
        '<input type="hidden" name="javax.faces.ViewState" value="synthetic-view-state">'
        '<input type="hidden" name="javax.faces.ClientWindow" value="syn1">'
    )
    inline = scripts if scripts is not None else (LIVE_CLICK_SCRIPT,)
    return (
        '<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml"><head id="j_idt2">'
        '<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />'
        '<title>Synthetic</title>'
        '<meta name="viewport" content="width=device-width" />'
        '<meta http-equiv="Cache-Control" content="no-cache" />'
        '<meta http-equiv="expires" content="0" /><meta http-equiv="pragma" content="no-cache" />'
        '<script type="text/javascript" src="/synthetic/jquery.js"></script>'
        '<link type="text/css" rel="stylesheet" href="/synthetic/a.css" /></head>'
        '<body><link type="text/css" rel="stylesheet" href="/synthetic/b.css" />'
        '<div class="wrap"><div id="hdr"><!-- synthetic header comment --></div></div>'
        '<div class="main"><div class="logo"><img src="/synthetic.png" alt="" class="l" /></div>'
        f'<form id="formRoutage" name="formRoutage" method="post" action="{action}"'
        ' class="f" enctype="application/x-www-form-urlencoded">'
        f'{fields}</form><div class="x"></div></div>'
        '<script type="text/javascript" src="/synthetic/rf.js"></script>'
        '<!-- synthetic footer comment -->'
        + "".join(f"<script>{script}</script>" for script in inline)
        + '<div id="popinWebCallBack" style="display:none">'
        '<button class="close" id="closeBtn" name="closeBtn"></button>'
        '<a href="#" class="link" onclick="return false;"></a>'
        '<iframe src="/synthetic-frame" id="frame" width="1" height="1"></iframe></div>'
        f'<script type="text/javascript">{LIVE_POPIN_SCRIPT}</script>'
        f'{extra_body}</body></html>'
    ).encode()


def live_landing_page(extra_body=""):
    return (
        '<!DOCTYPE html><html lang="fr" data-critters-container><head><meta charset="utf-8">'
        '<meta http-equiv="X-UA-Compatible" content="IE=edge">'
        '<link class="c" rel="icon" href="/favicon.ico" type="image/x-icon">'
        '<meta name="viewport" content="width=device-width">'
        '<script type="text/javascript">var kameleoonLoadingTimeout = 1000;</script>'
        '<script type="text/javascript" src="/synthetic/k.js" async></script>'
        '<script>window._app_base = window.location.pathname;</script>'
        '<style>body{margin:0}</style>'
        '<link rel="stylesheet" href="/styles.css" media="print" onload="this.media=\'all\'">'
        '<noscript><link rel="stylesheet" href="/styles.css"></noscript></head>'
        '<body><div id="sprite" style="display:none"><svg style="display:none">'
        '<symbol viewBox="0 0 1 1" id="i"><path d="M0 0" class="p"></path></symbol></svg></div>'
        f'<app-root></app-root>{extra_body}</body></html>'
    ).encode()


class LiveInsurerNavigationTest(unittest.IsolatedAsyncioTestCase):
    async def run_live_chain(self, *, routing=None, routing_status=201, jsf_page=None,
                             landing=None, detail=None):
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(routing_status, json=routing or live_routing_response(),
                                      headers={"content-type": "application/json;charset=UTF-8"})
            on_routing_page = request.url.path.split(";", 1)[0] == LIVE_ROUTING_PATH
            if on_routing_page and "formRoutageButton" not in request.content.decode():
                return httpx.Response(200, content=jsf_page or live_jsf_page(),
                                      headers={"content-type": "text/html;charset=UTF-8",
                                               "Set-Cookie": "JSESSIONID=synthetic; Path=/"})
            if on_routing_page:
                return httpx.Response(302, headers={
                    "location": f"https://{INSURER_HOST}{LIVE_LANDING_PATH}#/synthetic/client-route?x=1",
                    "content-type": "application/xhtml+xml",
                })
            if request.url.path == LIVE_LANDING_PATH:
                return httpx.Response(200, content=landing or live_landing_page(),
                                      headers={"content-type": "text/html"})
            if request.url.path == "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678":
                return detail or httpx.Response(200, json=RAW_DETAIL)
            return httpx.Response(404)

        return requests, per_client.fetch_per_detail(
            TOKEN, session_state(), CONTRACT_ID, transport=httpx.MockTransport(handle),
        )

    async def assertUpstream(self, awaitable, message=None):
        with self.assertRaises(replay.ReplayError) as caught:
            await awaitable
        self.assertEqual(caught.exception.code, "UPSTREAM_ERROR")
        if message is not None:
            self.assertEqual(str(caught.exception), message)
        return caught.exception

    async def test_live_chain_routes_through_jsf_page_and_landing_before_detail(self):
        requests, call = await self.run_live_chain()

        self.assertEqual(await call, RAW_DETAIL)
        self.assertEqual(
            [(r.method, r.url.host, r.url.path) for r in requests],
            [
                ("POST", API_HOST, "/bapi/contract/v1/contracts/12345-00000     PERIC12345678/prepareRouting"),
                ("POST", INSURER_HOST, LIVE_ROUTING_PATH),
                ("POST", INSURER_HOST, LIVE_ROUTING_PATH),
                ("GET", INSURER_HOST, LIVE_LANDING_PATH),
                ("GET", INSURER_HOST, "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678"),
            ],
        )
        routing_form = urllib.parse.parse_qs(requests[1].content.decode())
        self.assertEqual(set(routing_form), set(LIVE_FORM_PARAMETER_NAMES) | {"jeton"})
        self.assertEqual(routing_form["urlPing"], [f"https://{INSURER_HOST}/synthetic-urlPing"])
        self.assertEqual(urllib.parse.parse_qs(requests[2].content.decode()), {
            "formRoutage": ["formRoutage"],
            "formRoutageButton": ["OK"],
            "javax.faces.ViewState": ["synthetic-view-state"],
            "javax.faces.ClientWindow": ["syn1"],
        })
        self.assertIn("JSESSIONID=synthetic", requests[2].headers.get("cookie", ""))
        self.assertEqual(str(requests[3].url), f"https://{INSURER_HOST}{LIVE_LANDING_PATH}")
        for request in requests[1:]:
            self.assertNotIn("authorization", request.headers)

    async def test_live_routing_shape_is_accepted_with_200_and_null_query_parameters(self):
        routing = live_routing_response()
        routing["characteristics"]["routingContext"]["value"]["queryParameters"] = None
        _, call = await self.run_live_chain(routing=routing, routing_status=200)
        self.assertEqual(await call, RAW_DETAIL)

    async def test_other_routing_statuses_still_fail(self):
        for status in (202, 204, 302, 500):
            with self.subTest(status=status):
                requests, call = await self.run_live_chain(routing_status=status)
                await self.assertUpstream(call, "PER routing request failed")
                self.assertEqual(len(requests), 1)

    async def test_url_ping_is_not_a_pin_but_credential_names_are_still_refused(self):
        self.assertFalse(per_client._is_challenge_name("urlPing"))
        for name in LIVE_FORM_PARAMETER_NAMES:
            with self.subTest(name=name):
                self.assertFalse(per_client._is_challenge_name(name))
        for name in ("pin", "PIN", "userPin", "pin_code", "codePin", "login", "loginId", "user",
                     "userName", "username", "password", "motDePasse_password", "userpassword",
                     "pwd", "pwdField", "otp", "otpCode", "secret", "clientSecret",
                     "securPass", "verificationCode", "oneTimeCode"):
            with self.subTest(name=name):
                self.assertTrue(per_client._is_challenge_name(name))

        for name in ("pin", "userPin", "password"):
            routing = live_routing_response()
            routing["characteristics"]["routingContext"]["value"]["formParameters"][name] = "synthetic"
            with self.subTest(form_parameter=name):
                requests, call = await self.run_live_chain(routing=routing)
                await self.assertUpstream(call, "PER routing parameters are malformed")
                self.assertEqual(len(requests), 1)

    async def test_insurer_461_on_detail_has_a_distinct_message(self):
        requests, call = await self.run_live_chain(
            detail=httpx.Response(461, content=b"x" * 29, headers={"content-type": "application/json"}),
        )
        error = await self.assertUpstream(
            call, "PER insurer refused the request with insurer status 461"
        )
        self.assertNotIsInstance(error, replay.SessionExpired)
        self.assertEqual(len(requests), 5)

    async def test_landing_redirect_fragment_is_dropped_but_routing_href_fragment_is_refused(self):
        requests, call = await self.run_live_chain()
        await call
        self.assertEqual(requests[3].url.fragment, "")
        self.assertEqual(requests[3].url.query, b"")

        routing = live_routing_response()
        routing["characteristics"]["routingUrl"]["href"] = f"{LIVE_ROUTING_URL}#synthetic"
        requests, call = await self.run_live_chain(routing=routing)
        await self.assertUpstream(call)
        self.assertEqual(len(requests), 1)

    async def test_landing_page_with_a_form_or_control_is_refused(self):
        for extra in (
            '<form method="post" action="/x"><input type="hidden" name="a" value="b"></form>',
            '<input type="password" name="code">',
            '<textarea name="t"></textarea>',
            '<meta http-equiv="refresh" content="0;url=/x">',
        ):
            with self.subTest(extra=extra[:30]):
                requests, call = await self.run_live_chain(landing=live_landing_page(extra))
                await self.assertUpstream(call, "PER insurer landing page contains a form")
                self.assertEqual(len(requests), 4)

    async def test_form_less_page_outside_the_landing_path_is_still_refused(self):
        requests = []

        def handle(request):
            requests.append(request)
            if request.url.host == API_HOST:
                return httpx.Response(201, json=routing_response(href=BOOTSTRAP_URL))
            if request.url.path == "/bootstrap":
                return httpx.Response(200, content=live_landing_page(),
                                      headers={"content-type": "text/html"})
            return httpx.Response(200, json=RAW_DETAIL)

        await self.assertUpstream(per_client.fetch_per_detail(
            TOKEN, session_state(), CONTRACT_ID, transport=httpx.MockTransport(handle),
        ), "PER insurer returned unsupported form markup")
        self.assertEqual(len(requests), 2)

    async def test_jsf_routing_page_variants_fail_closed(self):
        standard = (
            '<input type="hidden" name="formRoutage" value="formRoutage">'
            '<input type="submit" name="formRoutageButton" value="OK">'
            '<input type="hidden" name="javax.faces.ViewState" value="v">'
            '<input type="hidden" name="javax.faces.ClientWindow" value="w">'
        )
        cases = {
            "no click script": live_jsf_page(scripts=("var unrelated = 1;",)),
            "click in external script only": live_jsf_page(scripts=()),
            "text input": live_jsf_page(form_fields=standard + '<input type="text" name="codeClient" value="">'),
            "password input": live_jsf_page(form_fields=standard + '<input type="password" name="secret" value="">'),
            "unknown hidden": live_jsf_page(form_fields=standard + '<input type="hidden" name="extra" value="x">'),
            "missing view state": live_jsf_page(form_fields=standard.replace("javax.faces.ViewState", "other")),
            "missing submit": live_jsf_page(form_fields=standard.replace('type="submit"', 'type="hidden"')),
            "action off routing path": live_jsf_page(action="/espaceinternet-ce/elsewhere.xhtml"),
            "action other host": live_jsf_page(action="https://evil.caisse-epargne.fr" + LIVE_ROUTING_PATH),
            "control outside form": live_jsf_page(extra_body='<input type="password" name="p" value="">'),
            "second form": live_jsf_page(extra_body='<form method="post" action="/x"></form>'),
        }
        for label, page in cases.items():
            with self.subTest(case=label):
                requests, call = await self.run_live_chain(jsf_page=page)
                await self.assertUpstream(call)
                self.assertEqual(len(requests), 2)

    async def test_jsf_action_may_carry_session_path_parameter_and_query(self):
        # The live trace truncated the action at 40 chars; JSF commonly appends these.
        action = f"{LIVE_ROUTING_PATH};jsessionid=SYNTHETIC?jfwid=syn1"
        requests, call = await self.run_live_chain(jsf_page=live_jsf_page(action=action))
        self.assertEqual(await call, RAW_DETAIL)
        self.assertEqual(requests[2].url.raw_path, action.encode())

    async def test_live_chain_fits_in_the_hop_budget(self):
        requests, call = await self.run_live_chain()
        with patch.object(per_client, "MAX_SSO_HOPS", 2):
            self.assertEqual(await call, RAW_DETAIL)
        with patch.object(per_client, "MAX_SSO_HOPS", 1):
            requests, call = await self.run_live_chain()
            await self.assertUpstream(call, "PER insurer navigation exceeded its hop limit")


if __name__ == "__main__":
    unittest.main()