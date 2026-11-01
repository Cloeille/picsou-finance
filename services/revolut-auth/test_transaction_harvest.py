"""Artificial parser-contract fixtures for Revolut transaction harvesting.

These fixture dictionaries exercise only fields the sidecar parses; they are not
captured Revolut responses. Run from services/revolut-auth:
python -m unittest test_transaction_harvest
"""

import time
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

from fastapi import HTTPException

import main


def _tx(tx_id, timestamp_ms, amount, tx_type="CARD_PAYMENT"):
    return {
        "id": tx_id,
        "completedDate": timestamp_ms,
        "description": tx_id,
        "amount": amount,
        "type": tx_type,
    }


async def _empty_fiat(_page, _device_id):
    return {"EUR"}


class TransactionHarvestTest(unittest.IsolatedAsyncioTestCase):
    async def test_harvest_fetches_real_pocket_and_vault_ids_without_exposing_mapping(self):
        now_ms = int(time.time() * 1000) - 1000
        requests = []
        fixtures = {
            "wallet": [_tx("same-id-pocket-tx", now_ms, -1250)],
            "pocket-child": [_tx("child-tx", now_ms, 500)],
            "vault-pocket": [_tx("vault-tx", now_ms, -250)],
            "synthetic-pocket": [_tx("synthetic-child-tx", now_ms, 200)],
        }
        pocket_calls = {}

        async def api_call(_page, _path, _device_id, params=None):
            request_params = params if isinstance(params, dict) else {}
            requests.append(dict(request_params))
            pocket_id = request_params.get("internalPocketId")
            pocket_calls[pocket_id] = pocket_calls.get(pocket_id, 0) + 1
            if pocket_calls[pocket_id] > 1:
                return {"status": 200, "data": []}
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
        self.assertEqual(by_id["wallet"]["transactions"][0]["externalId"], "same-id-pocket-tx")
        self.assertEqual(by_id["pocket-child"]["transactions"][0]["externalId"], "child-tx")
        self.assertEqual(by_id["vault-persisted-id"]["transactions"][0]["externalId"], "vault-tx")
        self.assertEqual(set(by_id["vault-persisted-id"]), {
            "externalId", "name", "type", "iban", "balance", "currency", "parentExternalId", "transactions",
        })
        self.assertIsNone(by_id["wallet"]["parentExternalId"])
        self.assertEqual(by_id["synthetic-wallet"]["transactions"], [])
        self.assertEqual(by_id["synthetic-wallet"]["balance"], 2.0)
        self.assertEqual({request.get("internalPocketId") for request in requests}, {
            "wallet", "pocket-child", "vault-pocket", "synthetic-pocket",
        })

    async def test_transfer_requires_unique_opposite_mirror_on_distinct_accounts(self):
        now_ms = int(time.time() * 1000) - 1000
        transfer_out = _tx("mirrored", now_ms, -1000, "TRANSFER")
        transfer_in = _tx("mirrored", now_ms, 1000, "TRANSFER")
        external_transfer = _tx("external", now_ms, -1000, "TRANSFER")
        ambiguous_a = _tx("ambiguous", now_ms, -1000, "TRANSFER")
        ambiguous_b = _tx("ambiguous", now_ms, 500, "TRANSFER")
        ambiguous_c = _tx("ambiguous", now_ms, 500, "TRANSFER")
        card = _tx("card", now_ms, -1000, "CARD_PAYMENT")

        pocket_calls = {}

        async def api_call(_page, _path, _device_id, params=None):
            request_params = params if isinstance(params, dict) else {}
            pocket_id = request_params["internalPocketId"]
            pocket_calls[pocket_id] = pocket_calls.get(pocket_id, 0) + 1
            if pocket_calls[pocket_id] > 1:
                return {"status": 200, "data": []}
            txs = {
                "source-pocket": [transfer_out, external_transfer, ambiguous_a, card],
                "destination-pocket": [transfer_in, ambiguous_b],
                "third-pocket": [ambiguous_c],
            }[pocket_id]
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

        self.assertEqual(transactions[("source-pocket", "mirrored")]["kind"], "TRANSFER")
        self.assertEqual(transactions[("destination-pocket", "mirrored")]["kind"], "TRANSFER")
        for key in (("source-pocket", "external"), ("source-pocket", "card"),
                    ("source-pocket", "ambiguous"), ("destination-pocket", "ambiguous"),
                    ("third-pocket", "ambiguous")):
            self.assertIsNone(transactions[key].get("kind"))
        self.assertTrue(all("_sourceType" not in tx for account in accounts for tx in account["transactions"]))

    async def test_transaction_window_and_page_cap_are_bounded(self):
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

        self.assertEqual([tx["externalId"] for tx in transactions], ["inside-window", "at-window-boundary"])
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(call["internalPocketId"] == "pocket" for call in calls))

        calls.clear()

        async def capped_api_call(_page, _path, _device_id, params=None):
            calls.append(params)
            return {"status": 200, "data": [_tx(f"tx-{len(calls)}", now_ms - len(calls), -100)]}

        with patch.object(main, "api_call", capped_api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(len(calls), main.MAX_TRANSACTION_PAGES)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "PORTFOLIO_INCOMPLETE")

    async def test_page_two_http_error_fails_closed(self):
        now_ms = int(time.time() * 1000)
        calls = 0

        async def api_call(_page, _path, _device_id, params=None):
            nonlocal calls
            calls += 1
            if calls == 1:
                return {"status": 200, "data": [_tx("page-1", now_ms - 1000, -100)]}
            return {"status": 503, "data": None}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(calls, 2)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")

    async def test_malformed_page_fails_closed(self):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": {"unexpected": []}}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_blocked_pagination_cursor_fails_closed(self):
        now_ms = int(time.time() * 1000)

        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [_tx("same-page", now_ms, -100)]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "PORTFOLIO_INCOMPLETE")

    async def _assert_invalid_id_fails_closed(self, invalid_id):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [_tx(invalid_id, int(time.time() * 1000), -100)]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def _assert_invalid_timestamp_fails_closed(self, invalid_timestamp):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [_tx("invalid-ts", invalid_timestamp, -100)]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_missing_transaction_timestamp_fails_closed(self):
        tx = _tx("missing-ts", int(time.time() * 1000), -100)
        del tx["completedDate"]

        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [tx]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def _assert_invalid_merchant_fails_closed(self, invalid_merchant):
        tx = _tx("invalid-merchant", int(time.time() * 1000), -100)
        tx["merchant"] = invalid_merchant

        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [tx]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def _assert_invalid_amount_fails_closed(self, invalid_amount):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [_tx("invalid-amount", int(time.time() * 1000), invalid_amount)]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_exchange_legs_keep_their_own_currency_and_scale(self):
        # Synthetic legs shaped after the community client's EXCHANGE sample (tducret/revolut-python):
        # each leg carries its own `currency`, and crypto amounts are 1e-8 units, not cents.
        now_ms = int(time.time() * 1000) - 1000
        crypto_leg = {**_tx("exchange-id", now_ms, -5_000_000, "EXCHANGE"),
                      "description": "Exchanged to EUR", "currency": "BTC"}
        fiat_leg = {**_tx("card", now_ms, -1250), "currency": "EUR"}
        untagged = _tx("untagged", now_ms, -300)

        calls = []

        async def api_call(_page, _path, _device_id, params=None):
            calls.append(params)
            return {"status": 200, "data": [crypto_leg, fiat_leg, untagged] if len(calls) == 1 else []}

        with patch.object(main, "api_call", api_call):
            rows = {tx["externalId"]: tx for tx in
                    await main._fetch_transactions(object(), "device", "pocket", {"EUR", "USD"})}

        self.assertEqual(rows["exchange-id"]["amount"], -0.05)
        self.assertEqual(rows["exchange-id"]["currency"], "BTC")
        self.assertEqual(rows["card"]["amount"], -12.5)
        self.assertEqual(rows["card"]["currency"], "EUR")
        self.assertEqual(rows["untagged"]["amount"], -3.0)
        self.assertIsNone(rows["untagged"]["currency"])

    async def test_both_exchange_legs_in_one_feed_are_kept_when_the_foreign_one_comes_first(self):
        now_ms = int(time.time() * 1000) - 1000
        foreign = {**_tx("exchange-id", now_ms, -5_000_000, "EXCHANGE"),
                   "description": "Exchanged to EUR", "currency": "BTC"}
        eur = {**_tx("exchange-id", now_ms, 300_000, "EXCHANGE"),
               "description": "Exchanged from BTC", "currency": "EUR"}
        duplicate = dict(eur)
        calls = []

        async def api_call(_page, _path, _device_id, params=None):
            calls.append(params)
            return {"status": 200, "data": [foreign, eur, duplicate] if len(calls) == 1 else []}

        with patch.object(main, "api_call", api_call):
            rows = await main._fetch_transactions(object(), "device", "pocket", {"EUR"})

        self.assertEqual([(row["currency"], row["amount"]) for row in rows], [("BTC", -0.05), ("EUR", 3000.0)])

    async def _assert_fiat_list_normalized(self, payload, expected):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": payload}

        with patch.object(main, "api_call", api_call):
            self.assertEqual(await main._fetch_fiat_currencies(object(), "device"), set(expected))

    async def _assert_invalid_currency_fails_closed(self, invalid_currency):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": [{**_tx("bad-ccy", int(time.time() * 1000), -100),
                                             "currency": invalid_currency}]}

        with patch.object(main, "api_call", api_call):
            with self.assertRaises(HTTPException) as raised:
                await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_empty_transaction_list_is_successful(self):
        async def api_call(_page, _path, _device_id, params=None):
            return {"status": 200, "data": []}

        with patch.object(main, "api_call", api_call):
            self.assertEqual(await main._fetch_transactions(object(), "device", "pocket"), [])


def _add_cases(prefix, helper, cases):
    for label, *args in cases:
        async def case(self, _helper=helper, _args=tuple(args)):
            await getattr(self, _helper)(*_args)
        case.__name__ = f"{prefix}_{label}"
        setattr(TransactionHarvestTest, case.__name__, case)


_add_cases("test_invalid_transaction_ids_fail_closed", "_assert_invalid_id_fails_closed", [
    ("dict", {"id": "nested"}),
    ("list", ["id"]),
    ("true", True),
    ("int", 123),
    ("empty_string", ""),
])
_add_cases("test_invalid_transaction_timestamps_fail_closed", "_assert_invalid_timestamp_fails_closed", [
    ("none", None),
    ("true", True),
    ("false", False),
    ("zero", 0),
    ("negative", -1),
    ("nan", float("nan")),
    ("inf", float("inf")),
    ("negative_inf", -float("inf")),
    ("googol", 10**100),
])
_add_cases("test_invalid_merchant_shapes_fail_closed", "_assert_invalid_merchant_fails_closed", [
    ("string", "merchant"),
    ("list", ["merchant"]),
    ("true", True),
])
_add_cases("test_invalid_transaction_amounts_fail_closed", "_assert_invalid_amount_fails_closed", [
    ("string", "100"),
    ("true", True),
    ("nan", float("nan")),
    ("inf", float("inf")),
    ("negative_inf", -float("inf")),
])
_add_cases("test_fiat_list_is_normalized_and_falls_back_without_eur", "_assert_fiat_list_normalized", [
    ("lowercase_codes", [{"code": "eur"}, {"code": "usd"}], {"EUR", "USD"}),
    ("objects_without_code", [{"id": "3f1c-uuid", "name": "Euro"}], main.FIAT_FALLBACK),
    ("plain_strings", ["USD", "GBP"], main.FIAT_FALLBACK),
    ("empty_list", [], main.FIAT_FALLBACK),
])
_add_cases("test_invalid_transaction_currency_fails_closed", "_assert_invalid_currency_fails_closed", [
    ("int", 42),
    ("empty_string", ""),
    ("too_long", "EURO-LONG-CODE"),
    ("list", ["EUR"]),
])


if __name__ == "__main__":
    unittest.main()
