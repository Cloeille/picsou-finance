"""Offline synthetic tests for the Banque Populaire PER insurer chain; no real value, no network.

The chain is the Caisse d'Epargne one (`bpce.per_client`) on this bank's hosts. Only what
issue #524 confirms is pinned: the `PERIB` product code, the `PER` + `IB` producer pair and
the hosts; the id shapes are the digits-only ones of `PERIC`, and anything else is refused.
Every identifier below is synthetic.
"""

import io
import json
import logging
import os
import unittest
from unittest.mock import patch

import httpx
from bpce import per_client, replay

import main

GATEWAY_HOST = "www.rs-ext-bad-ib.banquepopulaire.fr"
INSURER_HOST = "www.espace-assurances-de-personnes.banquepopulaire.fr"
ENTITY = "12345"
CONTRACT_ID = "PERIB123456789"
PRODUCER_ID = "PERIB876543"
TOKEN = "synthetic-access-token-DO-NOT-LEAK"
MAIN_TOKEN = "synthetic-main-token-DO-NOT-LEAK"
JWT = "eyJzeW50aGV0aWMi.eyJET05PVExFQUsi.c2lnbmF0dXJlLURPLU5PVC1MRUFL"
RAW_DETAIL = {"detailContratVie": {"supports": []}, "synthetic": True}


def routing_body(*, producer=PRODUCER_ID, cdetab=ENTITY, entity=ENTITY, contract=None):
    query = {"codeTransaction": "synthetic", "cdEtab": cdetab, "producerContratId": producer,
             "urlRetour": "https://www.banquepopulaire.fr/synthetic"}
    return {
        "contractId": {"entityCode": entity, "id": producer if contract is None else contract},
        "routingMode": {"code": "1", "label": "synthetic"},
        "routingUrl": {"href": f"https://{INSURER_HOST}/"},
        "routingContext": {"suppliedName": None, "value": {"queryParameters": query}},
    }


class FakeInsurer:
    def __init__(self, *, routing=None):
        self.requests: list[httpx.Request] = []
        self.routing = routing

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if request.url.host == GATEWAY_HOST and request.url.path.endswith("/prepareRouting"):
            if self.routing is not None:
                return self.routing(request)
            return httpx.Response(201, json=routing_body())
        if request.url.host == GATEWAY_HOST and "/clientToken/" in request.url.path:
            return httpx.Response(200, headers={"iw4-jwt-token": JWT}, json={})
        if request.url.host == INSURER_HOST and "/contratVie/load/" in request.url.path:
            return httpx.Response(200, json=RAW_DETAIL)
        return httpx.Response(599)


async def fetch(insurer: FakeInsurer, **override):
    args = {"contract_id": CONTRACT_ID, "entity_code": ENTITY, **override}
    return await per_client.fetch_per_detail(
        main.SITE, TOKEN, args["contract_id"], entity_code=args["entity_code"],
        routing_token=MAIN_TOKEN, transport=insurer.transport(),
    )


class PerIbChainTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_perib_contract_goes_through_the_chain_on_the_banque_populaire_hosts(self):
        insurer = FakeInsurer()

        result = await fetch(insurer)

        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual([str(r.url) for r in insurer.requests], [
            f"https://{GATEWAY_HOST}/bapi/contract/v1/contracts/"
            "12345-PERIB123456789/prepareRouting",
            f"https://{GATEWAY_HOST}/natixis/assurance/assuranceVieAuth/v1/clientToken/{PRODUCER_ID}",
            f"https://{INSURER_HOST}/etna-web/rest/v2/contratVie/load/PER/IB/876543",
        ])
        for request in insurer.requests[1:]:
            self.assertEqual(request.headers["iw4-product-code"], "PER")
            self.assertEqual(request.headers["iw4-company-code"], ENTITY)
            self.assertEqual(request.headers["authorization"], f"Bearer {TOKEN}")
            self.assertNotIn("cookie", request.headers)
        self.assertEqual(insurer.requests[0].headers["authorization"], f"Bearer {MAIN_TOKEN}")
        self.assertEqual(insurer.requests[2].headers["iw4-jwt-token"], JWT)

    async def test_a_prefixed_perib_contract_id_is_also_accepted(self):
        insurer = FakeInsurer()
        result = await fetch(insurer, contract_id="00000     PERIB12345678")
        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual(
            str(insurer.requests[0].url),
            f"https://{GATEWAY_HOST}/bapi/contract/v1/contracts/12345-00000%20%20%20%20%20PERIB12345678/prepareRouting",
        )

    async def test_an_asv_prefixed_perib_contract_id_is_also_accepted(self):
        insurer = FakeInsurer()
        result = await fetch(insurer, contract_id="ASVPERIB123456")
        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual(
            str(insurer.requests[0].url),
            f"https://{GATEWAY_HOST}/bapi/contract/v1/contracts/12345-ASVPERIB123456/prepareRouting",
        )

    async def test_bp_routing_body_with_producer_contract_id_and_asv_echo(self):
        insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json={
            "characteristics": {
                "contractId": {"entityCode": ENTITY, "id": f"ASV{PRODUCER_ID}"},
                "routingMode": {"code": "1", "label": "URL"},
                "routingUrl": {"href": f"https://{INSURER_HOST}/"},
                "routingContext": {"value": {"queryParameters": {
                    "cdetab": ENTITY, "urlRetour": "https://www.banquepopulaire.fr/espace-client/compte",
                    "codeTransaction": "MONCT", "producerContractId": PRODUCER_ID,
                }}},
            }
        }))
        result = await fetch(insurer, contract_id=f"ASV{PRODUCER_ID}")
        self.assertEqual(result, RAW_DETAIL)
        self.assertEqual(str(insurer.requests[1].url),
                         f"https://{GATEWAY_HOST}/natixis/assurance/assuranceVieAuth/v1/clientToken/{PRODUCER_ID}")
        self.assertEqual(str(insurer.requests[2].url),
                         f"https://{INSURER_HOST}/etna-web/rest/v2/contratVie/load/PER/IB/876543")

    async def test_the_insurer_number_keeps_its_leading_zeros(self):
        insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json=routing_body(producer="PERIB0001")))

        await fetch(insurer)

        self.assertTrue(str(insurer.requests[2].url).endswith("/load/PER/IB/0001"))

    async def test_a_routing_answer_for_another_product_or_bank_code_is_refused_before_the_token_step(self):
        # The bank id and the producer id must carry the same five letters: `PERIB` with a
        # Caisse d'Epargne `PERIC` answer (or an assurance vie one) is another product.
        for bank_id, producer in (
            (CONTRACT_ID, "PERIC876543"),
            (CONTRACT_ID, "ESSEN876543"),
            ("00000     PERIC12345678", PRODUCER_ID),
        ):
            with self.subTest(bank_id=bank_id, producer=producer):
                insurer = FakeInsurer(
                    routing=lambda _r, p=producer: httpx.Response(201, json=routing_body(producer=p)))
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer, contract_id=bank_id)
                self.assertEqual(len(insurer.requests), 1)

    async def test_producer_ids_outside_the_pinned_pairs_and_digit_shape_are_refused(self):
        for name, producer in {
            "unpinned company code": "PERIX876543",
            "company code wildcard": "PERIBB876543",
            "no number": "PERIB",
            "letters in the number": "PERIB87AB43",
            "lowercase": "perib876543",
            "path": "PERIB876543/../x",
            "too long": "PERIB" + "9" * 33,
            "another product": "AVIIB876543",
        }.items():
            with self.subTest(name):
                insurer = FakeInsurer(
                    routing=lambda _r, p=producer: httpx.Response(201, json=routing_body(producer=p)))
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer)
                self.assertEqual(len(insurer.requests), 1)

    async def test_bank_contract_ids_not_observed_are_refused_without_a_request(self):
        for name, contract_id in {
            "entity prefix and asv prefix": "00000     ASVPERIB12345678",
            "other company letter": "00000     PERIA12345678",
            "compact other company letter": "PERIA12345678",
            "letters in the number": "00000     PERIB12AB",
            "compact letters in the number": "PERIB12AB",
            "lowercase": "00000     perib12345678",
            "compact lowercase": "perib12345678",
            "compact no number": "PERIB",
            "compact too long": "PERIB" + "1" * 65,
        }.items():
            with self.subTest(name):
                insurer = FakeInsurer()
                with self.assertRaises(per_client.UnknownPerContract):
                    await fetch(insurer, contract_id=contract_id)
                self.assertEqual(insurer.requests, [])


class RefusedShapeLogTest(unittest.IsolatedAsyncioTestCase):
    """What a live run logs when the producer id is not a pinned shape: its masked shape."""

    async def refused_log(self, producer, *, details=False) -> str:
        insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json=routing_body(producer=producer)))
        env = {"CE_LOG_PRODUCT_DETAILS": "1"} if details else {}
        with patch.dict(os.environ, env), self.assertLogs("bpce", "WARNING") as logs:
            with self.assertRaises(replay.UpstreamError):
                await fetch(insurer)
        return "\n".join(logs.output)

    async def test_the_shape_shows_no_digit_and_by_default_no_letter(self):
        text = await self.refused_log("PERIB87AB43XYZ")
        self.assertEqual(text.split("shape=")[1].split()[0], "9" * 14)

    async def test_with_the_details_switch_only_the_capitals_show(self):
        text = await self.refused_log("PERIB87ab43XYZ", details=True)
        self.assertEqual(text.split("shape=")[1].split()[0], "PERIB999999XYZ")

    async def test_the_shape_is_cut_and_never_carries_a_secret(self):
        text = await self.refused_log("PERIX" + "7" * 60 + TOKEN)
        self.assertEqual(text.split("shape=")[1].split()[0], "9" * 32)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(MAIN_TOKEN, text)

    async def test_no_secret_or_payload_reaches_the_logs_on_a_successful_chain(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        root = logging.getLogger()
        previous = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        try:
            await fetch(FakeInsurer())
        finally:
            root.removeHandler(handler)
            root.setLevel(previous)

        text = stream.getvalue()
        for secret in (TOKEN, MAIN_TOKEN, JWT, PRODUCER_ID[5:], json.dumps(RAW_DETAIL)):
            self.assertNotIn(secret, text)


class PerSiteTest(unittest.TestCase):
    def test_the_hosts_and_the_client_confirmed_in_issue_524(self):
        per = main.SITE.per

        self.assertEqual((per.gateway_host, per.insurer_host), (GATEWAY_HOST, INSURER_HOST))
        self.assertEqual(per.chain.name, "per")
        self.assertEqual(dict(per.chain.overrides), {
            "client_id": "dcd51401-debb-47e4-ad8d-12a72e529f08",
            "scope": "",
            "redirect_uri": f"https://{INSURER_HOST}/postMessage/index.html",
        })
        self.assertEqual(per.chain.drop, ("claims",))
        self.assertIs(main.PER_CHAIN, per.chain)

    def test_both_hosts_are_inside_the_banque_populaire_allow_list_and_no_other_is(self):
        for host in (GATEWAY_HOST, INSURER_HOST):
            self.assertTrue(replay.is_allowed_url(main.SITE, f"https://{host}/"), host)
        for host in (
            "www.rs-ext-bad-ce.caisse-epargne.fr",
            "www.espace-assurances-de-personnes.caisse-epargne.fr",
            "www.espace-assurances-de-personnes.banquepopulaire.fr.evil.example",
        ):
            self.assertFalse(replay.is_allowed_url(main.SITE, f"https://{host}/"), host)

    def test_the_per_family_is_no_longer_skipped(self):
        self.assertNotIn(("4", "05"), main.SITE.unsupported_families)


if __name__ == "__main__":
    unittest.main()
