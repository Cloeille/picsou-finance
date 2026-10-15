"""Offline synthetic tests for the PER insurer transport; no real bank values or network.

Request shape (hosts, paths, header names) copied from the sanitized live trace of
2026-10-15; every value is synthetic.
"""

import asyncio
import io
import json
import logging
import unittest
from unittest.mock import patch

import httpx

import per_client
import replay


API_HOST = "www.rs-ext-bad-ce.caisse-epargne.fr"
INSURER_HOST = "www.espace-assurances-de-personnes.caisse-epargne.fr"
CONTRACT_ID = "00000     PERIC12345678"
ENTITY = "12345"
# The insurer number differs from the bank suffix (observed live): only routing gives it.
INSURER_ID = "PERIC876543"  # live shape: PERIC + 6 digits, bank suffix has 8
ROUTING_URL = (f"https://{API_HOST}/bapi/contract/v1/contracts/"
               "12345-00000%20%20%20%20%20PERIC12345678/prepareRouting")
TOKEN_URL = f"https://{API_HOST}/natixis/assurance/assuranceVieAuth/v1/clientToken/{INSURER_ID}"
DETAIL_URL = f"https://{INSURER_HOST}/etna-web/rest/v2/contratVie/load/PER/IC/876543"
TOKEN = "synthetic-access-token-DO-NOT-LEAK"
MAIN_TOKEN = "synthetic-main-token-DO-NOT-LEAK"
JWT = "eyJzeW50aGV0aWMi.eyJET05PVExFQUsi.c2lnbmF0dXJlLURPLU5PVC1MRUFL"
PAYLOAD_MARKER = "synthetic-per-payload-DO-NOT-LOG"
RAW_DETAIL = {"detailContratVie": {"supports": [{"nom": PAYLOAD_MARKER}]}, "synthetic": True}


def routing_body(*, producer=INSURER_ID, cdetab=ENTITY, contract=None, entity=ENTITY,
                 href=f"https://{INSURER_HOST}/", wrapped=False, drop=()):
    """201 body shape seen live: mode 1, four query parameters, the bank id echoed."""
    query = {"codeTransaction": "synthetic", "cdEtab": cdetab, "producerContratId": producer,
             "urlRetour": "https://www.caisse-epargne.fr/synthetic"}
    for key in drop:
        query.pop(key, None)
    characteristics = {
        # Live: the echoed id is the insurer id, not the bank one.
        "contractId": {"entityCode": entity, "id": producer if contract is None else contract},
        "routingMode": {"code": "1", "label": "synthetic"},
        "routingUrl": {"href": href},
        "authenticationKeyType": None, "authenticationKeyValue": "", "authenticationKeyName": "",
        "routingContext": {"suppliedName": None, "value": {"queryParameters": query}},
    }
    if wrapped == "prepareRouting":
        return {"prepareRouting": {"characteristics": characteristics}}
    if wrapped == "characteristics":
        return {"characteristics": characteristics}
    return characteristics


class FakeInsurer:
    """MockTransport handler: clientToken on the gateway, then contratVie/load."""

    def __init__(self, *, routing=None, token=None, detail=None, jwt=JWT, token_headers=None):
        self.requests: list[httpx.Request] = []
        self.routing = routing
        self.token = token
        self.detail = detail
        self.jwt = jwt
        self.token_headers = token_headers or {}

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(lambda request: self.handle(request))

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.url.host == API_HOST and request.url.path.endswith("/prepareRouting"):
            if self.routing is not None:
                return self.routing(request)
            return httpx.Response(201, json=routing_body())
        if request.url.host == API_HOST and "/clientToken/" in request.url.path:
            if self.token is not None:
                return self.token(request)
            headers = dict(self.token_headers)
            if self.jwt is not None:
                headers["iw4-jwt-token"] = self.jwt
            return httpx.Response(200, headers=headers, json={})
        if request.url.host == INSURER_HOST and "/contratVie/load/" in request.url.path:
            if self.detail is not None:
                return self.detail(request)
            return httpx.Response(200, json=RAW_DETAIL)
        return httpx.Response(599)


async def fetch(insurer: FakeInsurer, **override):
    args = {"access_token": TOKEN, "contract_id": CONTRACT_ID, "entity_code": ENTITY,
            "routing_token": MAIN_TOKEN, **override}
    return await per_client.fetch_per_detail(
        args["access_token"], args["contract_id"], entity_code=args["entity_code"],
        routing_token=args["routing_token"], transport=insurer.transport(),
    )


class HappyPathTest(unittest.IsolatedAsyncioTestCase):
    async def test_token_then_detail_with_the_observed_headers(self):
        insurer = FakeInsurer()

        result = await fetch(insurer)

        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual([str(r.url) for r in insurer.requests], [ROUTING_URL, TOKEN_URL, DETAIL_URL])
        routing = insurer.requests[0]
        self.assertEqual(routing.method, "POST")
        self.assertEqual(routing.headers["authorization"], f"Bearer {MAIN_TOKEN}")
        self.assertNotIn("iw4-user-token", routing.headers)
        self.assertNotIn("cookie", routing.headers)
        self.assertEqual(json.loads(routing.content)["characteristics"]["routingContext"]["suppliedName"],
                         "routingContext")
        for request in insurer.requests[1:]:
            self.assertEqual(request.method, "GET")
            self.assertEqual(request.headers["authorization"], f"Bearer {TOKEN}")
            self.assertEqual(request.headers["iw4-user-token"], TOKEN)
            self.assertEqual(request.headers["iw4-app-code"], "IW4")
            self.assertEqual(request.headers["iw4-company-code"], ENTITY)
            self.assertEqual(request.headers["iw4-product-code"], "PER")
            self.assertNotIn("cookie", request.headers)
            self.assertNotIn("iw4-run-env", request.headers)
            self.assertNotIn(MAIN_TOKEN, str(request.headers))
        self.assertNotIn("iw4-jwt-token", insurer.requests[1].headers)
        self.assertEqual(insurer.requests[2].headers["iw4-jwt-token"], JWT)
        self.assertNotIn(INSURER_ID[5:], CONTRACT_ID)  # really not derivable

    async def test_a_cookie_set_by_the_token_step_is_not_replayed(self):
        insurer = FakeInsurer(token_headers={"Set-Cookie": "GW=synthetic; Domain=.caisse-epargne.fr; Path=/"})

        await fetch(insurer)

        self.assertNotIn("cookie", insurer.requests[2].headers)

    async def test_the_insurer_number_keeps_its_leading_zeros(self):
        insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json=routing_body(producer="PERIC0001")))

        await fetch(insurer)

        self.assertTrue(str(insurer.requests[1].url).endswith("/clientToken/PERIC0001"))
        self.assertTrue(str(insurer.requests[2].url).endswith("/load/PER/IC/0001"))

    async def test_wrapped_routing_answers_are_accepted(self):
        for wrapper in ("characteristics", "prepareRouting"):
            with self.subTest(wrapper):
                insurer = FakeInsurer(routing=lambda _r, w=wrapper: httpx.Response(
                    201, json=routing_body(wrapped=w)))
                self.assertEqual(await fetch(insurer), RAW_DETAIL)

    async def test_requests_carry_the_replay_timeouts(self):
        observed = []
        insurer = FakeInsurer()
        original = insurer.handle

        def handle(request):
            observed.append(request.extensions.get("timeout"))
            return original(request)

        insurer.handle = handle
        with patch.object(replay, "REQUEST_TIMEOUT_SECONDS", 0.125), patch.object(
            replay, "CONNECT_TIMEOUT_SECONDS", 0.125
        ):
            await fetch(insurer)
        self.assertEqual(len(observed), 3)
        self.assertTrue(all(timeout["read"] == 0.125 for timeout in observed))


class StatusTest(unittest.IsolatedAsyncioTestCase):
    async def test_401_on_either_step_is_an_expired_token(self):
        for step in ("token", "detail"):
            with self.subTest(step):
                insurer = FakeInsurer(**{step: lambda _r: httpx.Response(401)})
                with self.assertRaises(replay.SessionExpired):
                    await fetch(insurer)
                self.assertEqual(len(insurer.requests), 2 if step == "token" else 3)

    async def test_403_461_and_500_are_upstream_errors_not_expiry(self):
        for step in ("token", "detail"):
            for status in (403, 461, 500):
                with self.subTest(step=step, status=status):
                    insurer = FakeInsurer(**{step: lambda _r, s=status: httpx.Response(s)})
                    with self.assertRaises(replay.UpstreamError) as caught:
                        await fetch(insurer)
                    self.assertNotIsInstance(caught.exception, replay.SessionExpired)

    async def test_a_redirect_is_never_followed(self):
        for step in ("token", "detail"):
            with self.subTest(step):
                insurer = FakeInsurer(**{step: lambda _r: httpx.Response(
                    302, headers={"Location": "https://evil.example.com/", "iw4-jwt-token": JWT})})
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer)
                self.assertEqual(len(insurer.requests), 2 if step == "token" else 3)
                self.assertTrue(all(r.url.host != "evil.example.com" for r in insurer.requests))


class JwtTest(unittest.IsolatedAsyncioTestCase):
    async def test_missing_or_malformed_jwt_stops_before_the_detail(self):
        for jwt in (None, "", "not-a-jwt", "a.b.c.d", "a b.c.d", "x" * 9000 + ".y.z"):
            with self.subTest(jwt=str(jwt)[:12]):
                insurer = FakeInsurer(jwt=jwt)
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer)
                self.assertEqual(len(insurer.requests), 2)


class RoutingTest(unittest.IsolatedAsyncioTestCase):
    async def test_unusable_routing_answers_stop_before_the_insurer(self):
        cases = {
            "401 is not an expiry": httpx.Response(401),
            "403": httpx.Response(403),
            "500": httpx.Response(500),
            "redirect": httpx.Response(302, headers={"Location": "https://evil.example.com/"}),
            "invalid json": httpx.Response(201, content=b"{"),
            "no characteristics": httpx.Response(201, json={"x": 1}),
            "no producer id": httpx.Response(201, json=routing_body(drop=("producerContratId",))),
            "producer id other product": httpx.Response(201, json=routing_body(producer="AVIIC123")),
            "producer id path": httpx.Response(201, json=routing_body(producer="PERIC1/../x")),
            "producer id not str": httpx.Response(201, json=routing_body(producer=123)),
            "other establishment": httpx.Response(201, json=routing_body(cdetab="99999")),
            "echo disagrees with producer id": httpx.Response(201, json=routing_body(contract="PERIC999999")),
            "echo is the bank id": httpx.Response(201, json=routing_body(contract=CONTRACT_ID)),
            "other entity": httpx.Response(201, json=routing_body(entity="99999")),
            "other insurer host": httpx.Response(201, json=routing_body(href="https://evil.caisse-epargne.fr/")),
        }
        for name, response in cases.items():
            with self.subTest(name):
                insurer = FakeInsurer(routing=lambda _r, resp=response: resp)
                with self.assertRaises(replay.UpstreamError) as caught:
                    await fetch(insurer)
                self.assertNotIsInstance(caught.exception, replay.SessionExpired)
                self.assertEqual(len(insurer.requests), 1)
                self.assertNotIn(TOKEN, str(insurer.requests[0].headers))


class BodyTest(unittest.IsolatedAsyncioTestCase):
    async def test_unusable_detail_bodies_are_upstream_errors(self):
        bodies = {
            "invalid json": b"{",
            "array": b"[]",
            "no detail": b'{"donneesContrat": {}}',
            "detail null": b'{"detailContratVie": null}',
            "detail not object": b'{"detailContratVie": []}',
            "duplicate key": b'{"detailContratVie": {}, "detailContratVie": {}}',
            "nan": b'{"detailContratVie": {"x": NaN}}',
            "deep nesting": b'{"detailContratVie": ' + b"[" * 100000 + b"]" * 100000 + b"}",
            "token echoed": json.dumps({"detailContratVie": {"x": TOKEN}}).encode(),
            "jwt echoed": json.dumps({"detailContratVie": {"x": JWT}}).encode(),
        }
        for name, body in bodies.items():
            with self.subTest(name):
                insurer = FakeInsurer(detail=lambda _r, b=body: httpx.Response(200, content=b))
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer)

    async def test_oversize_bodies_are_refused_on_both_steps(self):
        big = b"x" * (replay.MAX_RESPONSE_BYTES + 1)
        for step in ("token", "detail"):
            with self.subTest(step):
                insurer = FakeInsurer(**{step: lambda _r: httpx.Response(
                    200, content=big, headers={"iw4-jwt-token": JWT})})
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer)
                self.assertEqual(len(insurer.requests), 2 if step == "token" else 3)

    async def test_transport_error_is_not_retried_and_carries_no_secret(self):
        requests = []

        def boom(request):
            requests.append(request)
            raise httpx.ReadTimeout(f"synthetic timeout {request.headers['authorization']}")

        with self.assertRaises(replay.UpstreamError) as caught:
            await per_client.fetch_per_detail(
                TOKEN, CONTRACT_ID, entity_code=ENTITY, routing_token=MAIN_TOKEN,
                transport=httpx.MockTransport(boom),
            )
        self.assertEqual(len(requests), 1)
        self.assertNotIn(MAIN_TOKEN, str(caught.exception))
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertNotIn(TOKEN, repr(caught.exception))
        self.assertIsNone(caught.exception.__cause__)


class InputTest(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_requests_send_nothing(self):
        cases = {
            "contract no bank prefix": {"contract_id": "PERIC12345678"},
            "contract no product": {"contract_id": "00000     12345678"},
            "contract other product": {"contract_id": "00000     PEAIC12345678"},
            "contract lowercase": {"contract_id": "00000     peric12345678"},
            "contract letters": {"contract_id": "00000     PERIC12AB"},
            "contract path": {"contract_id": "00000 PERIC12345678/../x"},
            "contract newline": {"contract_id": "00000     PERIC123\n"},
            "contract too long": {"contract_id": "00000 PERIC" + "1" * 200},
            "contract not str": {"contract_id": 123},
            "entity short": {"entity_code": "1234"},
            "entity letters": {"entity_code": "12a45"},
            "entity none": {"entity_code": None},
            "token empty": {"access_token": ""},
            "token space": {"access_token": "a b"},
            "token newline": {"access_token": "abc\n"},
            "token too long": {"access_token": "a" * 9000},
            "routing token empty": {"routing_token": ""},
            "routing token newline": {"routing_token": "abc\n"},
        }
        for name, override in cases.items():
            with self.subTest(name):
                insurer = FakeInsurer()
                with self.assertRaises(per_client.InvalidPerRequest):
                    await fetch(insurer, **override)
                self.assertEqual(insurer.requests, [])


class LifecycleTest(unittest.IsolatedAsyncioTestCase):
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
            per_client.fetch_per_detail(TOKEN, CONTRACT_ID, entity_code=ENTITY,
                                        routing_token=MAIN_TOKEN, transport=transport)
        )
        await asyncio.wait_for(transport.entered.wait(), timeout=1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(transport.closed)


class LogHygieneTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_secret_reaches_the_logs_or_errors(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        root = logging.getLogger()
        previous = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        try:
            scenarios = [
                FakeInsurer(),
                FakeInsurer(token=lambda _r: httpx.Response(401, text=PAYLOAD_MARKER)),
                FakeInsurer(detail=lambda _r: httpx.Response(500, text=PAYLOAD_MARKER)),
                FakeInsurer(jwt="broken"),
                FakeInsurer(routing=lambda _r: httpx.Response(201, json=routing_body(cdetab="99999"))),
                FakeInsurer(detail=lambda _r: httpx.Response(200, json={"detailContratVie": {"x": TOKEN}})),
            ]
            for insurer in scenarios:
                try:
                    await fetch(insurer)
                except replay.ReplayError as exc:
                    for secret in (TOKEN, MAIN_TOKEN, JWT, PAYLOAD_MARKER):
                        self.assertNotIn(secret, str(exc))
                        self.assertNotIn(secret, repr(exc))
        finally:
            root.removeHandler(handler)
            root.setLevel(previous)
            handler.close()
        logs = stream.getvalue()
        self.assertIn("stage=client-token", logs)
        self.assertIn("stage=contract-detail", logs)
        self.assertIn("stage=routing", logs)
        for secret in (TOKEN, MAIN_TOKEN, JWT, PAYLOAD_MARKER, "12345678", "876543"):
            self.assertNotIn(secret, logs)


class AllowListTest(unittest.TestCase):
    def test_both_insurer_urls_pass_the_replay_allow_list(self):
        self.assertTrue(replay.is_allowed_url(ROUTING_URL))
        self.assertTrue(replay.is_allowed_url(TOKEN_URL))
        self.assertTrue(replay.is_allowed_url(DETAIL_URL))


if __name__ == "__main__":
    unittest.main()
