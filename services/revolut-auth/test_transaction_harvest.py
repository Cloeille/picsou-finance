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


FIXED_NOW = datetime(2026, 6, 15, 12, 0, tzinfo=timezone.utc)
NOW_MS = int(FIXED_NOW.timestamp() * 1000)
DAY_MS = 24 * 3600 * 1000


class _FixedDateTime(datetime):
    @classmethod
    def now(cls, tz=None):
        return FIXED_NOW if tz else FIXED_NOW.replace(tzinfo=None)


def _day(days_ago):
    return datetime.fromtimestamp((NOW_MS - int(days_ago * DAY_MS)) / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


def _row(tx_id, created, started=None, completed=None, currency="EUR"):
    """A synthetic row in the live shape. Ages are days before FIXED_NOW; it settles when created unless told."""
    started = created if started is None else started
    completed = started if completed is None else completed
    return {
        "id": tx_id, "createdDate": NOW_MS - int(created * DAY_MS), "startedDate": NOW_MS - int(started * DAY_MS),
        "completedDate": NOW_MS - int(completed * DAY_MS), "description": tx_id, "amount": -100,
        "currency": currency, "type": "CARD_PAYMENT",
    }


class _Feed:
    """`transactions/last` as seen live: the rows created strictly before `to`, newest created first, a fixed page."""

    def __init__(self, rows, page_size=3):
        self.rows, self.page_size, self.bounds = rows, page_size, []

    async def __call__(self, _page, _path, _device_id, params=None):
        self.bounds.append(params["to"])
        return {"status": 200, "data": [row for row in self.rows if row["createdDate"] < params["to"]][:self.page_size]}


async def _read(feed):
    with patch.object(main, "datetime", _FixedDateTime), patch.object(main, "api_call", feed):
        return await main._fetch_transactions(object(), "device", "pocket")


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

    async def test_pages_follow_the_creation_date_the_endpoint_pages_on(self):
        """A payment that settles days after it was created must not repeat or stall the read."""
        # (id, created days ago, started days ago, completed days ago), newest created first.
        rows = [_row(*spec) for spec in (
            ("t1", 6.0, 5.9, 5.8), ("t2", 7.5, 7.3, 7.1), ("t3", 8.8, 7.6, 7.2), ("t4", 8.9, 8.9, 7.1),
            ("t5", 9.7, 9.7, 8.1), ("t6", 11.5, 11.5, 11.4), ("t7", 12.0, 9.5, 9.0))]
        feed = _Feed(rows)

        transactions = await _read(feed)

        self.assertEqual([tx["externalId"] for tx in transactions], [f"t{n}" for n in range(1, 8)])
        self.assertEqual(transactions[2]["date"], _day(7.2))  # the booking date stays the completion date
        self.assertEqual(feed.bounds[0], NOW_MS)
        # Every later page asks again for the instant the last one ended on; the last call asks strictly before it.
        self.assertEqual(feed.bounds[1:-1], [rows[i]["createdDate"] + 1 for i in (2, 4, 6)])
        self.assertEqual(feed.bounds[-1], rows[6]["createdDate"])

    async def test_rows_created_in_the_same_millisecond_survive_a_page_cut(self):
        """Both legs of an exchange share an id and a creation instant; a three-row page cuts between them."""
        rows = [_row("t1", 2.0), _row("t2", 3.0), _row("exchange", 4.0, currency="EUR"),
                _row("exchange", 4.0, currency="USD"), _row("t5", 5.0)]

        transactions = await _read(_Feed(rows))

        self.assertEqual([(tx["externalId"], tx["currency"]) for tx in transactions],
                         [("t1", "EUR"), ("t2", "EUR"), ("exchange", "EUR"), ("exchange", "USD"), ("t5", "EUR")])

    async def test_more_rows_in_one_millisecond_than_a_page_holds_fails_closed(self):
        rows = [_row("t1", 2.0), _row("t2", 3.0)] + [_row(f"burst-{n}", 4.0) for n in range(5)] + [_row("old", 6.0)]

        with self.assertRaises(HTTPException) as raised:
            await _read(_Feed(rows))

        self.assertEqual((raised.exception.status_code, raised.exception.detail), (502, "PORTFOLIO_INCOMPLETE"))

    async def test_the_window_follows_the_creation_date(self):
        window = main.TRANSACTION_WINDOW_DAYS
        rows = [_row("inside", window - 1, completed=window - 10), _row("created-before", window + 1, completed=window - 2),
                _row("far", window + 30)]
        feed = _Feed(rows, page_size=1)

        transactions = await _read(feed)

        self.assertEqual([tx["externalId"] for tx in transactions], ["inside"])
        self.assertEqual(len(feed.bounds), 2)  # the read stops at the first row created before the window

    async def test_a_malformed_creation_date_fails_closed(self):
        for bad in ("1790000000000", True, 0, -5, float("nan"), float("inf")):
            with self.subTest(created=bad):
                row = _row("t1", 2.0)
                row["createdDate"] = bad

                async def api_call(_page, _path, _device_id, params=None, row=row):
                    return {"status": 200, "data": [row]}

                with self.assertRaises(HTTPException) as raised:
                    await _read(api_call)
                self.assertEqual((raised.exception.status_code, raised.exception.detail), (502, "INVALID_DATA"))

    async def test_rows_without_a_creation_date_page_on_the_booking_date_and_say_so(self):
        async def api_call(_page, _path, _device_id, params=None):
            rows = [_tx("old-shape-1", NOW_MS - 1000, -100), _tx("old-shape-2", NOW_MS - 2000, -100)]
            return {"status": 200, "data": [row for row in rows if row["completedDate"] < params["to"]][:1]}

        with patch.object(main, "datetime", _FixedDateTime), patch.object(main, "api_call", api_call):
            with self.assertLogs("revolut-auth", level="WARNING") as logs:
                transactions = await main._fetch_transactions(object(), "device", "pocket")

        self.assertEqual([tx["externalId"] for tx in transactions], ["old-shape-1", "old-shape-2"])
        self.assertIn("without createdDate", "\n".join(logs.output))

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
