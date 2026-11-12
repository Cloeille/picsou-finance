import io
import json
import logging
import os
from datetime import datetime, timezone
from unittest.mock import patch

import httpx
from bpce import per_client, replay

import main

GATEWAY_HOST = "www.rs-ext-bad-ib.banquepopulaire.fr"
INSURER_HOST = "www.espace-assurances-de-personnes.banquepopulaire.fr"
DATA_HOST = "www.rs-ext-bad-ib.banquepopulaire.fr"
AS_HOST = "www.as-ext-bad-ib.banquepopulaire.fr"
ENTITY = "12345"
CONTRACT_ID = "PERIB123456789"
PRODUCER_ID = "PERIB876543"
TOKEN = "synthetic-access-token-DO-NOT-LEAK"
MAIN_TOKEN = "synthetic-main-token-DO-NOT-LEAK"
JWT = "eyJzeW50aGV0aWMi.eyJET05PVExFQUsi.c2lnbmF0dXJlLURPLU5PVC1MRUFL"
RAW_DETAIL = {"detailContratVie": {"supports": []}, "synthetic": True}

PER_ACCESS_TOKEN = "synthetic-per-access-token"
PER_CLIENT_ID = "dcd51401-debb-47e4-ad8d-12a72e529f08"
PER_BANK_ID = "PERIB123456789"
PER_PRODUCER_ID = "PERIB876543"
PER_JWT = "eyJzeW50aGV0aWMi.eyJET05PVExFQUsi.c2lnbmF0dXJlLURPLU5PVC1MRUFL"
_STAMP = int(datetime(2026, 10, 1, tzinfo=timezone.utc).timestamp() * 1000)

PER_DETAIL = {
    "detailContratVie": {
        "valorisation": {
            "montant": 1700,
            "date": _STAMP,
            "globalInvestCalcRest": {"globalAmountFondEuros": 1000, "globalAmountUnitesCompte": 700},
            "supports": [
                {
                    "nom": "Synthetic fund",
                    "codeIsin": "XX0000000001",
                    "nombreUnitesCompte": 10,
                    "valeurUniteCompte": 70,
                    "montant": 700,
                    "dateValeurUniteCompte": _STAMP,
                    "idCompartment": 1,
                    "familleFondsGeneraux": False,
                }
            ],
        }
    }
}


def routing_body(*, producer=PRODUCER_ID, cdetab=ENTITY, entity=ENTITY, contract=None):
    query = {
        "codeTransaction": "synthetic",
        "cdEtab": cdetab,
        "producerContratId": producer,
        "urlRetour": "https://www.banquepopulaire.fr/synthetic",
    }
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
        main.SITE,
        TOKEN,
        args["contract_id"],
        entity_code=args["entity_code"],
        routing_token=MAIN_TOKEN,
        transport=insurer.transport(),
    )


REFUSED_PRODUCER_IDS = {
    "unpinned company code": "PERIX876543",
    "company code wildcard": "PERIBB876543",
    "no number": "PERIB",
    "letters in the number": "PERIB87AB43",
    "lowercase": "perib876543",
    "path": "PERIB876543/../x",
    "too long": "PERIB" + "9" * 33,
    "another product": "AVIIB876543",
}

REFUSED_CONTRACT_IDS = {
    "entity prefix and asv prefix": "00000     ASVPERIB12345678",
    "other company letter": "00000     PERIA12345678",
    "compact other company letter": "PERIA12345678",
    "letters in the number": "00000     PERIB12AB",
    "compact letters in the number": "PERIB12AB",
    "lowercase": "00000     perib12345678",
    "compact lowercase": "perib12345678",
    "compact no number": "PERIB",
    "compact too long": "PERIB" + "1" * 65,
}


class PerFakeBank:
    def __init__(self):
        self.routing_producer = PER_PRODUCER_ID
        self.client_token_401_count = 0

    def routing_answer(self, path: str) -> dict:
        query = {
            "codeTransaction": "synthetic",
            "cdEtab": "00000",
            "producerContratId": self.routing_producer,
            "urlRetour": "https://www.banquepopulaire.fr/synthetic",
        }
        return {
            "contractId": {"entityCode": "00000", "id": self.routing_producer},
            "routingMode": {"code": "1", "label": "synthetic"},
            "routingUrl": {"href": f"https://{INSURER_HOST}/"},
            "routingContext": {"suppliedName": None, "value": {"queryParameters": query}},
        }

    def handle_per(self, request: httpx.Request, access_token: str) -> httpx.Response | None:
        host, path = request.url.host, request.url.path
        authorization = request.headers.get("authorization")
        if host == DATA_HOST and path.endswith("/prepareRouting"):
            if authorization != f"Bearer {access_token}":
                return httpx.Response(401)
            return httpx.Response(201, json=self.routing_answer(path))
        if host == DATA_HOST and "/clientToken/" in path:
            if self.client_token_401_count > 0:
                self.client_token_401_count -= 1
                return httpx.Response(401)
            if authorization != f"Bearer {PER_ACCESS_TOKEN}" or not path.endswith(f"/{PER_PRODUCER_ID}"):
                return httpx.Response(401)
            return httpx.Response(200, headers={"iw4-jwt-token": PER_JWT}, json={})
        if host == INSURER_HOST:
            if authorization != f"Bearer {PER_ACCESS_TOKEN}" or request.headers.get("iw4-jwt-token") != PER_JWT:
                return httpx.Response(401)
            assert path == "/etna-web/rest/v2/contratVie/load/PER/IB/876543", path
            return httpx.Response(200, json=PER_DETAIL)
        return None


def bp_asv_routing_body():
    return {
        "characteristics": {
            "contractId": {"entityCode": ENTITY, "id": f"ASV{PRODUCER_ID}"},
            "routingMode": {"code": "1", "label": "URL"},
            "routingUrl": {"href": f"https://{INSURER_HOST}/"},
            "routingContext": {"value": {"queryParameters": {
                "cdetab": ENTITY, "urlRetour": "https://www.banquepopulaire.fr/espace-client/compte",
                "codeTransaction": "MONCT", "producerContractId": PRODUCER_ID,
            }}},
        }
    }


def make_bad_per_contracts(contract_fn, per_bank_id):
    bad_ident = contract_fn(None, "4", "05", "PER", balance=1700.0)
    bad_ident["identification"] = None
    return {
        "null balance": contract_fn(None, "4", "05", "PER", balance=None, contract_id=per_bank_id),
        "negative balance": contract_fn(None, "4", "05", "PER", balance=-500.0, contract_id=per_bank_id),
        "null identification": bad_ident,
    }


async def refused_log(test_case, producer, *, details=False) -> str:
    insurer = FakeInsurer(routing=lambda _r: httpx.Response(201, json=routing_body(producer=producer)))
    env = {"CE_LOG_PRODUCT_DETAILS": "1"} if details else {}
    with patch.dict(os.environ, env), test_case.assertLogs("bpce", "WARNING") as logs:
        with test_case.assertRaises(replay.UpstreamError):
            await fetch(insurer)
    return "\n".join(logs.output)


async def capture_successful_fetch_logs() -> str:
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
    return stream.getvalue()


