"""Artificial parser-contract fixtures for Revolut transaction harvesting.

These fixture dictionaries exercise only fields the sidecar parses; they are not
captured Revolut responses. Run: python tests/test_transaction_harvest.py
"""

import os
import sys
import time
from datetime import datetime, timezone
from unittest.mock import patch

import anyio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


def _tx(tx_id, timestamp_ms, amount, tx_type="CARD_PAYMENT"):
    return {
        "id": tx_id,
        "completedDate": timestamp_ms,
        "description": tx_id,
        "amount": amount,
        "type": tx_type,
    }


async def test_harvest_fetches_real_pocket_and_vault_ids_without_exposing_mapping():
    now_ms = int(time.time() * 1000)
    requests = []
    fixtures = {
        "wallet": [_tx("same-id-pocket-tx", now_ms, -1250)],
        "pocket-child": [_tx("child-tx", now_ms, 500)],
        "vault-pocket": [_tx("vault-tx", now_ms, -250)],
        "synthetic-pocket": [_tx("synthetic-child-tx", now_ms, 200)],
    }

    async def api_call(_page, _path, _device_id, params=None):
        request_params = params if isinstance(params, dict) else {}
        requests.append(dict(request_params))
        pocket_id = request_params.get("internalPocketId")
        return {"status": 200, "data": fixtures.get(pocket_id, [])}

    async def fetch_money_boxes(_page, _device_id):
        return [{
            "id": "vault-persisted-id", "name": "Vault",
            "balance": {"amount": 1000, "currency": "EUR"},
            "pocket": {"id": "vault-pocket"}, "accountId": "wallet",
        }]

    async def fetch_wallets(_page, _device_id):
        return [
            ("wallet", [
                {"id": "wallet", "type": "CURRENT", "currency": "EUR", "balance": 5000},
                {"id": "pocket-child", "type": "SAVINGS", "currency": "EUR", "balance": 1000},
            ]),
            ("synthetic-wallet", [
                {"id": "synthetic-pocket", "type": "CURRENT", "currency": "EUR", "balance": 200},
            ]),
        ]

    async def fetch_iban(*_args):
        return "FR761234"

    with (
        patch.object(main, "api_call", api_call),
        patch.object(main, "_fetch_money_boxes", fetch_money_boxes),
        patch.object(main, "_fetch_fiat_currencies", _empty_fiat),
        patch.object(main, "_fetch_wallets", fetch_wallets),
        patch.object(main, "_fetch_iban", fetch_iban),
    ):
        result = await main.harvest_accounts(object(), "device")

    by_id = {account["externalId"]: account for account in result["accounts"]}
    assert by_id["wallet"]["transactions"][0]["externalId"] == "same-id-pocket-tx"
    assert by_id["pocket-child"]["transactions"][0]["externalId"] == "child-tx"
    assert by_id["vault-persisted-id"]["transactions"][0]["externalId"] == "vault-tx"
    assert set(by_id["vault-persisted-id"]) == {
        "externalId", "name", "type", "iban", "balance", "currency", "parentExternalId", "transactions",
    }
    assert by_id["wallet"]["parentExternalId"] is None
    assert by_id["synthetic-wallet"]["transactions"] == []
    assert by_id["synthetic-wallet"]["balance"] == 2.0
    assert {request.get("internalPocketId") for request in requests} == {
        "wallet", "pocket-child", "vault-pocket", "synthetic-pocket",
    }


async def _empty_fiat(_page, _device_id):
    return {"EUR"}


async def test_transfer_requires_unique_opposite_mirror_on_distinct_accounts():
    now_ms = int(time.time() * 1000)
    transfer_out = _tx("mirrored", now_ms, -1000, "TRANSFER")
    transfer_in = _tx("mirrored", now_ms, 1000, "TRANSFER")
    external_transfer = _tx("external", now_ms, -1000, "TRANSFER")
    ambiguous_a = _tx("ambiguous", now_ms, -1000, "TRANSFER")
    ambiguous_b = _tx("ambiguous", now_ms, 500, "TRANSFER")
    ambiguous_c = _tx("ambiguous", now_ms, 500, "TRANSFER")
    card = _tx("card", now_ms, -1000, "CARD_PAYMENT")

    async def api_call(_page, _path, _device_id, params=None):
        request_params = params if isinstance(params, dict) else {}
        txs = {
            "source-pocket": [transfer_out, external_transfer, ambiguous_a, card],
            "destination-pocket": [transfer_in, ambiguous_b],
            "third-pocket": [ambiguous_c],
        }[request_params["internalPocketId"]]
        return {"status": 200, "data": txs}

    with patch.object(main, "api_call", api_call):
        fetched = {
            "source-pocket": await main._fetch_transactions(object(), "device", "source-pocket"),
            "destination-pocket": await main._fetch_transactions(object(), "device", "destination-pocket"),
            "third-pocket": await main._fetch_transactions(object(), "device", "third-pocket"),
        }

    accounts = [
        {"externalId": pocket_id, "currency": "EUR", "transactions": transactions}
        for pocket_id, transactions in fetched.items()
    ]
    main._classify_internal_transfers(accounts)
    transactions = {
        (account["externalId"], tx["externalId"]): tx
        for account in accounts for tx in account["transactions"]
    }

    assert transactions[("source-pocket", "mirrored")]["kind"] == "TRANSFER"
    assert transactions[("destination-pocket", "mirrored")]["kind"] == "TRANSFER"
    for key in (("source-pocket", "external"), ("source-pocket", "card"),
                ("source-pocket", "ambiguous"), ("destination-pocket", "ambiguous"),
                ("third-pocket", "ambiguous")):
        assert transactions[key].get("kind") is None
    assert all("_sourceType" not in tx for account in accounts for tx in account["transactions"])


async def test_transaction_window_and_page_cap_are_bounded():
    fixed_now = datetime(2026, 10, 6, tzinfo=timezone.utc)
    now_ms = int(fixed_now.timestamp() * 1000)
    cutoff_ms = now_ms - main.TRANSACTION_WINDOW_DAYS * 24 * 3600 * 1000
    calls = []

    async def api_call(_page, _path, _device_id, params=None):
        calls.append(params)
        page = len(calls)
        if page == 1:
            return {"status": 200, "data": [_tx("inside-window", cutoff_ms + 1000, -100)]}
        if page == 2:
            return {"status": 200, "data": [_tx("at-window-boundary", cutoff_ms, -100)]}
        return {"status": 200, "data": [_tx(f"page-{page}", cutoff_ms - 2000 - page, -100)]}

    class FixedDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            return fixed_now if tz else fixed_now.replace(tzinfo=None)

    with patch.object(main, "datetime", FixedDateTime), patch.object(main, "api_call", api_call):
        transactions = await main._fetch_transactions(object(), "device", "pocket")

    assert [tx["externalId"] for tx in transactions] == ["inside-window", "at-window-boundary"]
    assert len(calls) == 2
    assert all(call["internalPocketId"] == "pocket" for call in calls)

    calls.clear()

    async def capped_api_call(_page, _path, _device_id, params=None):
        calls.append(params)
        return {"status": 200, "data": [_tx(f"tx-{len(calls)}", now_ms - len(calls), -100)]}

    with patch.object(main, "api_call", capped_api_call):
        await main._fetch_transactions(object(), "device", "pocket")

    assert len(calls) == main.MAX_TRANSACTION_PAGES


async def _run():
    tests = [
        test_harvest_fetches_real_pocket_and_vault_ids_without_exposing_mapping,
        test_transfer_requires_unique_opposite_mirror_on_distinct_accounts,
        test_transaction_window_and_page_cap_are_bounded,
    ]
    failures = 0
    for test in tests:
        try:
            await test()
            print(f"PASS {test.__name__}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL {test.__name__}: {type(exc).__name__}: {exc}")
    return failures


if __name__ == "__main__":
    sys.exit(1 if anyio.run(_run) else 0)
