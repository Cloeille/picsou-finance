"""AMEX parsers and the sessionState round-trip, on fake payloads."""

import asyncio
import json
import unittest
from decimal import Decimal

import httpx

from main import (
    BALANCES_PATH,
    TRANSACTIONS_PATH,
    AccountPayload,
    _account_token_from_member_payload,
    _collect_accounts,
    _entry_diagnostic_summary,
    _merge_transactions,
    _normalize_category,
    _parse_balance,
    _parse_dashboard_enrichment,
    _parse_loyalty_earned,
    _parse_scheduled_due_date,
    _parse_transactions,
    _pick_channel_index,
    _restore_cookies,
    _safe_error_type,
    _serialize_cookies,
    _shape_summary,
)


class AmexParsersTest(unittest.TestCase):
    def test_session_state_round_trips_with_and_without_the_account_token(self):
        sample_cookies = [{"name": "session", "value": "abc", "domain": "americanexpress.com", "path": "/"}]

        legacy_state = json.dumps({"cookies": sample_cookies}, separators=(",", ":"))
        cookies, token, enrichment = _restore_cookies(legacy_state)
        assert cookies == sample_cookies
        assert token is None
        assert enrichment == {}

        new_state = _serialize_cookies(sample_cookies, "tok-123", {"dueDate": "2026-03-04", "rewardPoints": 350})
        cookies, token, enrichment = _restore_cookies(new_state)
        assert cookies == sample_cookies
        assert token == "tok-123"
        assert enrichment == {"dueDate": "2026-03-04", "rewardPoints": 350}

        no_token_state = _serialize_cookies(sample_cookies, None)
        assert "accountToken" not in json.loads(no_token_state)

    def test_member_dashboard_schedule_and_loyalty_parsers(self):
        assert _account_token_from_member_payload(
            {"accounts": [{"account_token": "abc123"}]}
        ) == "abc123"
        assert _account_token_from_member_payload({"accounts": []}) is None
        assert _account_token_from_member_payload({}) is None
        assert _parse_dashboard_enrichment({"nested": {"paymentDueDate": "2026-03-04", "points_balance": 350}}) == ("2026-03-04", 350)
        # Regression: generic "balance" must not be mistaken for reward points
        # (it's a monetary field on countless AMEX JSON payloads).
        assert _parse_dashboard_enrichment({"balance": 123.45}) == (None, None)
        assert _parse_scheduled_due_date([{"account_token": "x", "direct_debit_details": {"direct_debit_date": "2026-04-05T00:00:00Z"}, "payment_due_details": {"payment_due_date": "2026-04-06"}}], "x") == "2026-04-05"
        assert _parse_scheduled_due_date([{"account_token": "x", "payment_due_details": {"payment_due_date": "2026-04-06"}}], "x") == "2026-04-06"
        assert _parse_scheduled_due_date([{"account_token": "x"}, {"account_token": "y"}], "y") is None
        assert _parse_scheduled_due_date({"junk": []}) is None
        assert _parse_loyalty_earned({"totals": [{"type": "EARNED_BASE", "total": {"value": 4}}, {"type": "EARNED", "total": {"value": 13}}]}) == 13
        assert _parse_loyalty_earned({"totals": []}) is None
        assert _parse_loyalty_earned({"totals": [{"type": "EARNED", "total": {"value": "junk"}}]}) is None

    def test_balance_shapes_and_account_token_matching(self):
        # Legacy-shape regression: totalBalance-only snake_case entry (no
        # statement_balance_amount/total_debits_balance_amount) falls back to
        # the plain current-balance keys.
        list_balance = _parse_balance(
            [
                {
                    "account_token": "tok-1",
                    "total_balance_amount": "123.45",
                    "minimum_payment_due": "25.00",
                    "dueDate": "2026-01-15",
                    "rewardPoints": 125,
                    "product_name": "Gold Card",
                }
            ]
        )
        assert list_balance["balanceEur"] == Decimal("-123.45")
        assert list_balance["accountToken"] == "tok-1"
        assert list_balance["name"] == "Gold Card"

        # A list root with a matching account_token must pick that entry, not
        # just the first one.
        matched_balance = _parse_balance(
            [
                {"account_token": "other", "total_balance_amount": "1.00"},
                {"account_token": "tok-2", "total_balance_amount": "2.00"},
            ],
            account_token="tok-2",
        )
        assert matched_balance["balanceEur"] == Decimal("-2.00")

        # Dict root with camelCase keys (older/alternate shape) must still work.
        dict_balance = _parse_balance(
            {"accounts": [{"accountToken": "tok-3", "totalBalance": 50, "accountName": "Card"}]}
        )
        assert dict_balance["balanceEur"] == Decimal("-50")
        assert dict_balance["name"] == "Card"

    def test_transactions_and_categories(self):
        # Snake_case transaction with a nested amount dict.
        transactions = _parse_transactions(
            {
                "transactions": [
                    {
                        "charge_date": "2026-01-02T00:00:00Z",
                        "description": "Coffee Shop",
                        "amount": {"amount": "4.50"},
                        "extended_details": {"category": "Dining"},
                    }
                ]
            }
        )
        assert len(transactions) == 1
        assert transactions[0]["amount"] == Decimal("-4.50")
        assert transactions[0]["category"] == "Dining"
        assert _normalize_category({"category_name": "Restaurant", "subcategory_name": "x"}) == "Restaurant"
        assert _normalize_category({"unknown": "ignored"}) is None
        category_account = AccountPayload.model_validate({
            "externalId": "card",
            "name": "Card",
            "balanceEur": Decimal("-1"),
            "transactions": _merge_transactions(_parse_transactions({"transactions": [{
                "charge_date": "2026-01-02",
                "description": "Cafe",
                "amount": "1.00",
                "extended_details": {"category": {"category_name": "Restaurant", "subcategory_name": "x"}},
            }]}), []),
            "snapshotComplete": True,
        })
        assert category_account.transactions[0].category == "Restaurant"

    def test_real_balances_shape_and_account_contract(self):
        # Real AMEX /financials/balances shape: list root, one entry. The entry
        # carries remaining_statement_balance_amount=63.50 against a statement of
        # 100.00, i.e. 36.50 of the statement has already been paid, so the
        # current balance is what remains on the statement (63.50) plus the new
        # debits since the statement date (0) = 63.50 -- not the full statement.
        real_shape_balance = _parse_balance(
            [
                {
                    "account_token": "tok-real",
                    "cash_advance_balance_amount": 0.0,
                    "charges_amount": 0.0,
                    "dispute_details": {"total_amount": 0.0},
                    "disputed_amount": 0.0,
                    "extended_details": {
                        "deferred": 0.0,
                        "early_pay": 0.0,
                        "non_deferred": 0.0,
                        "pay_in_full": 0.0,
                        "pay_over_time": 0.0,
                    },
                    "interest_charge_amount": 0.0,
                    "interest_saver_amount": 0.0,
                    "last_statement_balance_amount": 100.0,
                    "pay_flex_disclosure_indicator": False,
                    "pay_flex_indicator": False,
                    "qualified": True,
                    "remaining_statement_balance_amount": 63.50,
                    "statement_balance_amount": 100.0,
                    "total_debits_balance_amount": 0.0,
                    "total_payments_credits_amount": 36.50,
                },
                {"account_token": "tok-other", "total_debits_balance_amount": 999.0},
            ],
            account_token="tok-real",
        )
        assert real_shape_balance["balanceEur"] == Decimal("-63.50")
        assert real_shape_balance["statementBalance"] == Decimal("100.00")
        assert real_shape_balance["amountDue"] == Decimal("63.50")
        assert real_shape_balance["dueDate"] is None
        assert real_shape_balance["rewardPoints"] is None
        assert real_shape_balance["accountToken"] == "tok-real"

        # API discovery helpers tolerate nested names and the final account DTO keeps one contract.
        assert _parse_dashboard_enrichment({"loyalty": {"points_balance": 350}, "payment_due_date": "2026-03-04"}) == ("2026-03-04", 350)
        contract = AccountPayload.model_validate({
            **{key: value for key, value in real_shape_balance.items() if key != "accountToken"},
            "dueDate": "2026-03-04",
            "rewardPoints": 350,
            "transactions": [],
            "snapshotComplete": True,
        }).model_dump()
        assert contract["amountDue"] == Decimal("63.50")
        assert contract["dueDate"] == "2026-03-04"
        assert contract["rewardPoints"] == 350
        assert "paymentDueDate" not in contract and "rewardsPoints" not in contract

        # Same real shape, with a non-zero debits and a payment/credit --
        # statement + debits - credits.
        real_shape_balance_with_activity = _parse_balance(
            [
                {
                    "account_token": "tok-real2",
                    "statement_balance_amount": 100.0,
                    "total_debits_balance_amount": 23.45,
                    "total_payments_credits_amount": 10.0,
                },
            ],
            account_token="tok-real2",
        )
        assert real_shape_balance_with_activity["balanceEur"] == Decimal("-113.45")

    def test_statement_payment_balance_semantics(self):
        # ─── Statement-payment balance semantics (issue #196) ────────────────
        # When remaining_statement_balance_amount is present it already nets the
        # payments/credits against the statement, so the current balance is
        # remaining + new debits -- a paid statement must not be double-counted.

        # No payment yet: remaining == statement, so balance = statement + debits.
        no_payment_balance = _parse_balance(
            [{
                "account_token": "tok-nopay",
                "statement_balance_amount": 500.0,
                "remaining_statement_balance_amount": 500.0,
                "total_debits_balance_amount": 20.0,
                "total_payments_credits_amount": 0.0,
            }],
            account_token="tok-nopay",
        )
        assert no_payment_balance["balanceEur"] == Decimal("-520.00")

        # Full payment: the statement is paid in full (remaining = 0), only the
        # new debits since the statement date remain due. Real case from the
        # issue: statement 2122.00 paid, 133.00 of new purchases -> -133.00.
        full_payment_balance = _parse_balance(
            [{
                "account_token": "tok-fullpay",
                "statement_balance_amount": 2122.0,
                "last_statement_balance_amount": 2122.0,
                "remaining_statement_balance_amount": 0.0,
                "total_debits_balance_amount": 133.0,
                "total_payments_credits_amount": 2122.0,
                "charges_amount": 133.0,
            }],
            account_token="tok-fullpay",
        )
        assert full_payment_balance["balanceEur"] == Decimal("-133.00")
        assert full_payment_balance["amountDue"] == Decimal("0.00")

        # Partial payment: 100.00 statement, 36.50 paid -> 63.50 still owed on
        # the statement, plus 10.00 of new debits -> -73.50.
        partial_payment_balance = _parse_balance(
            [{
                "account_token": "tok-partial",
                "statement_balance_amount": 100.0,
                "remaining_statement_balance_amount": 63.50,
                "total_debits_balance_amount": 10.0,
                "total_payments_credits_amount": 36.50,
            }],
            account_token="tok-partial",
        )
        assert partial_payment_balance["balanceEur"] == Decimal("-73.50")

        # Issue #196 repro: the 2122.00 direct debit paid the statement
        # (remaining 0.00) but is NOT counted in total_payments_credits_amount,
        # which only holds a small 2.10 refund. The legacy formula would return
        # 2122.00 + 135.00 - 2.10 = -2254.90 here (the reported bug). With
        # remaining 0 the whole statement is applied, so the 2.10 is treated as
        # already absorbed and only the new debits remain: -135.00.
        issue_196_balance = _parse_balance(
            [{
                "account_token": "tok-issue196",
                "statement_balance_amount": 2122.0,
                "last_statement_balance_amount": 2122.0,
                "remaining_statement_balance_amount": 0.0,
                "total_debits_balance_amount": 135.0,
                "total_payments_credits_amount": 2.10,
            }],
            account_token="tok-issue196",
        )
        assert issue_196_balance["balanceEur"] == Decimal("-135.00")
        assert issue_196_balance["amountDue"] == Decimal("0.00")

        # Issue #245: the balance a card showed was statement + 2 x (posted purchases since the
        # statement), to the cent on two syncs. `statement + debits - credits` (the formula
        # before #196) produces exactly that when total_payments_credits_amount carries the
        # same purchases as a NEGATIVE figure: they are counted once as debits and once more
        # by subtracting the negative credits. The raw payload was never captured, so this
        # shape is inferred from those numbers, not observed. Invented figures: a 1000.00
        # statement paid in full, 40.00 of purchases since. The old formula gave -1080.00;
        # the remaining-based formula must give the 40.00 actually spent.
        credits_carry_the_purchases = _parse_balance(
            [{
                "account_token": "tok-issue245",
                "statement_balance_amount": 1000.0,
                "remaining_statement_balance_amount": 0.0,
                "total_debits_balance_amount": 40.0,
                "total_payments_credits_amount": -40.0,
            }],
            account_token="tok-issue245",
        )
        assert credits_carry_the_purchases["balanceEur"] == Decimal("-40.00")
        assert credits_carry_the_purchases["amountDue"] == Decimal("0.00")

        # Statement balance absent (only remaining + debits): nothing is proven
        # applied, so the amount owed stays whole: 500 + 20 = -520.00, and
        # credits (30) still reduce the debt: 500 + 20 - 30 = -490.00.
        no_statement_balance = _parse_balance(
            [{
                "account_token": "tok-nostmt",
                "remaining_statement_balance_amount": 500.0,
                "total_debits_balance_amount": 20.0,
                "total_payments_credits_amount": 0.0,
            }],
            account_token="tok-nostmt",
        )
        assert no_statement_balance["balanceEur"] == Decimal("-520.00")
        no_statement_credit_balance = _parse_balance(
            [{
                "account_token": "tok-nostmt-credit",
                "remaining_statement_balance_amount": 500.0,
                "total_debits_balance_amount": 20.0,
                "total_payments_credits_amount": 30.0,
            }],
            account_token="tok-nostmt-credit",
        )
        assert no_statement_credit_balance["balanceEur"] == Decimal("-490.00")

        # Refund/overpayment, remaining clamped at 0: credits (120) exceed the
        # statement (100), so a surplus credit of 20 offsets the 5 of new debits
        # and the card is in credit (+15).
        refund_credit_balance = _parse_balance(
            [{
                "account_token": "tok-refund",
                "statement_balance_amount": 100.0,
                "remaining_statement_balance_amount": 0.0,
                "total_debits_balance_amount": 5.0,
                "total_payments_credits_amount": 120.0,
            }],
            account_token="tok-refund",
        )
        assert refund_credit_balance["balanceEur"] == Decimal("15.00")

        # Same refund when AMEX does not clamp remaining (-20): same +15.
        refund_unclamped_balance = _parse_balance(
            [{
                "account_token": "tok-refund-unclamped",
                "statement_balance_amount": 100.0,
                "remaining_statement_balance_amount": -20.0,
                "total_debits_balance_amount": 5.0,
                "total_payments_credits_amount": 120.0,
            }],
            account_token="tok-refund-unclamped",
        )
        assert refund_unclamped_balance["balanceEur"] == Decimal("15.00")

        # The legacy formula still applies when remaining_statement_balance_amount
        # is absent (already covered above by real_shape_balance_with_activity:
        # statement + debits - credits = 100 + 23.45 - 10 = 113.45).

    def test_posted_and_pending_transactions_merge_by_identifier(self):
        # Merge posted + pending transactions, deduping by identifier (posted wins).
        posted_for_merge = [
            {"date": "2026-01-01", "description": "A", "amount": Decimal("-1"), "status": "posted",
             "category": None, "identifier": "id-1"},
        ]
        pending_for_merge = [
            {"date": "2026-01-01", "description": "A", "amount": Decimal("-1"), "status": "pending",
             "category": None, "identifier": "id-1"},
            {"date": "2026-01-02", "description": "B", "amount": Decimal("-2"), "status": "pending",
             "category": None, "identifier": "id-2"},
        ]
        merged = _merge_transactions(posted_for_merge, pending_for_merge)
        assert len(merged) == 2
        assert merged[0]["status"] == "posted"  # posted wins over duplicate identifier
        assert merged[1]["description"] == "B"
        assert all("identifier" not in t for t in merged)

    def test_pending_feed_failure_leaves_pending_incomplete(self):
        # The backend deletes stored pending rows only when the pending feed answered.
        def collect_with_pending(pending_status: int | type[httpx.HTTPError]) -> AccountPayload:
            def handler(request: httpx.Request) -> httpx.Response:
                if request.url.path == BALANCES_PATH.split("?")[0]:
                    return httpx.Response(200, json=[{"account_token": "tok", "statement_balance_amount": 10.0}])
                if request.url.path == TRANSACTIONS_PATH:
                    status = request.url.params["status"]
                    if status == "pending" and not isinstance(pending_status, int):
                        raise pending_status("pending feed down", request=request)
                    if status == "pending" and pending_status != 200:
                        return httpx.Response(pending_status)
                    return httpx.Response(200, json={"transactions": [
                        {"charge_date": "2026-01-02", "description": status, "amount": "1.00", "status": "other"},
                    ]})
                return httpx.Response(404)

            async def run() -> AccountPayload:
                async with httpx.AsyncClient(base_url="https://amex.test", transport=httpx.MockTransport(handler)) as fake:
                    return (await _collect_accounts(fake, "tok"))[0]

            return asyncio.run(run())

        answered = collect_with_pending(200)
        assert answered.pendingComplete is True
        assert [(t.description, t.status) for t in answered.transactions] == [("posted", "posted"), ("pending", "pending")]
        for failure in (429, 503, 404, httpx.ReadTimeout, httpx.ConnectError):
            failed = collect_with_pending(failure)
            assert failed.pendingComplete is False, failure
            assert [(t.description, t.status) for t in failed.transactions] == [("posted", "posted")]

    def test_diagnostics_never_leak_values(self):
        # Exception diagnostics retain only the type, never unsafe exception text.
        synthetic_secret = "private-page-text?token=secret-alert"
        error = RuntimeError(synthetic_secret)
        assert _safe_error_type(error) == "RuntimeError"
        assert synthetic_secret not in _safe_error_type(error)

        # Shape-summary diagnostics must never leak a value, only key names/types.
        secret = "super-secret-balance-value-999"
        summary = _shape_summary([{"total_balance_amount": secret, "nested": {"amount": secret}}])
        assert secret not in summary
        assert "total_balance_amount:str" in summary
        assert "nested:dict[amount]" in summary

        # Nonzero-flag diagnostics must never leak a value either -- a secret
        # numeric string is reduced to a nz/zero flag, never echoed back.
        secret_amount = "918273.45"
        entry_summary = _entry_diagnostic_summary(
            {"charges_amount": secret_amount, "extended_details": {"amount": secret_amount, "flag": True}}
        )
        assert secret_amount not in entry_summary
        assert "charges_amount:num:nz" in entry_summary
        assert "extended_details:dict[amount:num:nz,flag:bool:True]" in entry_summary

    def test_verify_page_channel_picker(self):
        # Verify-page channel picker (radio group): fake values only.
        sms_c = "*******12345 channel_*******12345-uuid"
        mail_c = "b****d@example.com channel_b****d@example.com-uuid"
        assert _pick_channel_index([sms_c, mail_c], "sms") == 0
        assert _pick_channel_index([sms_c, mail_c], "email") == 1
        assert _pick_channel_index([mail_c, sms_c], "sms") == 1
        assert _pick_channel_index([mail_c, sms_c], "email") == 0
        assert _pick_channel_index([mail_c], "sms") is None
        assert _pick_channel_index([sms_c], "email") is None
        assert _pick_channel_index([], "sms") is None
        assert _pick_channel_index([], "email") is None
        assert _pick_channel_index([sms_c, "*******99999 channel_*******99999-uuid"], "sms") == 0


if __name__ == "__main__":
    unittest.main()
