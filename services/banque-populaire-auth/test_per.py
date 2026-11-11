"""Offline synthetic tests for the Banque Populaire PER insurer chain; no real value, no network."""

import json
import unittest

import httpx
from bpce import per_client, replay

import main
from fixtures_per import (
    CONTRACT_ID, ENTITY, FakeInsurer, GATEWAY_HOST, INSURER_HOST, JWT, MAIN_TOKEN,
    PRODUCER_ID, RAW_DETAIL, REFUSED_CONTRACT_IDS, REFUSED_PRODUCER_IDS, TOKEN,
    bp_asv_routing_body, capture_successful_fetch_logs, fetch, refused_log, routing_body,
)


class PerIbChainTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_perib_contract_goes_through_the_chain_on_the_banque_populaire_hosts(self):
        insurer = FakeInsurer()
        self.assertEqual(await fetch(insurer), RAW_DETAIL)
        self.assertEqual([str(r.url) for r in insurer.requests], [
            f"https://{GATEWAY_HOST}/bapi/contract/v1/contracts/12345-PERIB123456789/prepareRouting",
            f"https://{GATEWAY_HOST}/natixis/assurance/assuranceVieAuth/v1/clientToken/{PRODUCER_ID}",
            f"https://{INSURER_HOST}/etna-web/rest/v2/contratVie/load/PER/IB/876543",
        ])
        for r in insurer.requests[1:]:
            self.assertEqual((r.headers["iw4-product-code"], r.headers["iw4-company-code"],
                              r.headers["authorization"], "cookie" in r.headers),
                             ("PER", ENTITY, f"Bearer {TOKEN}", False))
        self.assertEqual(insurer.requests[0].headers["authorization"], f"Bearer {MAIN_TOKEN}")
        self.assertEqual(insurer.requests[2].headers["iw4-jwt-token"], JWT)

    async def test_prefixed_and_asv_perib_contract_ids_are_accepted(self):
        for cid, enc in (("00000     PERIB12345678", "00000%20%20%20%20%20PERIB12345678"), ("ASVPERIB123456", "ASVPERIB123456")):
            with self.subTest(cid=cid):
                insurer = FakeInsurer()
                self.assertEqual(await fetch(insurer, contract_id=cid), RAW_DETAIL)
                self.assertEqual(str(insurer.requests[0].url), f"https://{GATEWAY_HOST}/bapi/contract/v1/contracts/12345-{enc}/prepareRouting")

    async def test_bp_routing_body_with_producer_contract_id_and_asv_echo(self):
        insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json=bp_asv_routing_body()))
        self.assertEqual(await fetch(insurer, contract_id=f"ASV{PRODUCER_ID}"), RAW_DETAIL)
        self.assertEqual(str(insurer.requests[1].url), f"https://{GATEWAY_HOST}/natixis/assurance/assuranceVieAuth/v1/clientToken/{PRODUCER_ID}")
        self.assertEqual(str(insurer.requests[2].url), f"https://{INSURER_HOST}/etna-web/rest/v2/contratVie/load/PER/IB/876543")

    async def test_the_insurer_number_keeps_its_leading_zeros(self):
        insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json=routing_body(producer="PERIB0001")))
        await fetch(insurer)
        self.assertTrue(str(insurer.requests[2].url).endswith("/load/PER/IB/0001"))

    async def test_a_routing_answer_for_another_product_or_bank_code_is_refused_before_the_token_step(self):
        for bank_id, producer in (
            (CONTRACT_ID, "PERIC876543"), (CONTRACT_ID, "ESSEN876543"), ("00000     PERIC12345678", PRODUCER_ID),
        ):
            with self.subTest(bank_id=bank_id, producer=producer):
                insurer = FakeInsurer(routing=lambda _r, p=producer: httpx.Response(201, json=routing_body(producer=p)))
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer, contract_id=bank_id)
                self.assertEqual(len(insurer.requests), 1)

    async def test_producer_ids_outside_the_pinned_pairs_and_digit_shape_are_refused(self):
        for name, producer in REFUSED_PRODUCER_IDS.items():
            with self.subTest(name):
                insurer = FakeInsurer(routing=lambda _r, p=producer: httpx.Response(201, json=routing_body(producer=p)))
                with self.assertRaises(replay.UpstreamError):
                    await fetch(insurer)
                self.assertEqual(len(insurer.requests), 1)

    async def test_bank_contract_ids_not_observed_are_refused_without_a_request(self):
        for name, contract_id in REFUSED_CONTRACT_IDS.items():
            with self.subTest(name):
                with self.assertRaises(per_client.UnknownPerContract):
                    await fetch(FakeInsurer(), contract_id=contract_id)


class RefusedShapeLogTest(unittest.IsolatedAsyncioTestCase):
    async def test_shape_masking(self):
        text = await refused_log(self, "PERIB87AB43XYZ")
        self.assertEqual(text.split("shape=")[1].split()[0], "9" * 14)
        text = await refused_log(self, "PERIB87ab43XYZ", details=True)
        self.assertEqual(text.split("shape=")[1].split()[0], "PERIB999999XYZ")
        text = await refused_log(self, "PERIX" + "7" * 60 + TOKEN)
        self.assertEqual(text.split("shape=")[1].split()[0], "9" * 32)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(MAIN_TOKEN, text)

    async def test_no_secret_or_payload_reaches_the_logs_on_a_successful_chain(self):
        text = await capture_successful_fetch_logs()
        for secret in (TOKEN, MAIN_TOKEN, JWT, PRODUCER_ID[5:], json.dumps(RAW_DETAIL)):
            self.assertNotIn(secret, text)


class PerSiteTest(unittest.TestCase):
    def test_site_per_configuration(self):
        per = main.SITE.per
        self.assertEqual((per.gateway_host, per.insurer_host), (GATEWAY_HOST, INSURER_HOST))
        self.assertEqual((per.chain.name, per.chain.drop), ("per", ("claims",)))
        self.assertEqual(dict(per.chain.overrides), {
            "client_id": "dcd51401-debb-47e4-ad8d-12a72e529f08",
            "scope": "",
            "redirect_uri": f"https://{INSURER_HOST}/postMessage/index.html",
        })
        self.assertIs(main.PER_CHAIN, per.chain)
        self.assertNotIn(("4", "05"), main.SITE.unsupported_families)
        for host in (GATEWAY_HOST, INSURER_HOST):
            self.assertTrue(replay.is_allowed_url(main.SITE, f"https://{host}/"), host)
        for host in ("www.rs-ext-bad-ce.caisse-epargne.fr", "www.espace-assurances-de-personnes.caisse-epargne.fr",
                     "www.espace-assurances-de-personnes.banquepopulaire.fr.evil.example"):
            self.assertFalse(replay.is_allowed_url(main.SITE, f"https://{host}/"), host)


if __name__ == "__main__":
    unittest.main()
