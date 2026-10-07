"""Regression tests for the wallet pocket-nesting bug: harvest_accounts used to emit
pockets/money-boxes with a `parentExternalId` pointing at their wallet, but never
emitted an account FOR that wallet -- so the backend could never resolve the parent and
every pocket/vault surfaced as its own top-level account instead of nesting.

`_with_wallet_parents` is the pure (no I/O) piece of that fix: given already-built child
account dicts plus each wallet's dominant currency and IBAN, it synthesizes one parent
account per wallet and re-parents same-currency children under it. These tests exercise
it directly, without a live `page`.

Run: .venv/bin/python tests/test_harvest_shape.py   (no pytest needed)
"""

import os
import sys

import anyio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


def _pocket(external_id, balance, currency, parent):
    return {
        "externalId": external_id, "name": "Pocket", "type": "CHECKING", "iban": None,
        "balance": balance, "currency": currency, "parentExternalId": parent, "transactions": [],
    }


async def test_wallet_parent_synthesized_with_same_currency_children_summed():
    """2 EUR pockets + 1 GBP pocket under wallet W (EUR): one W parent whose balance is
    the sum of the 2 EUR pockets; the EUR pockets nest under W; the GBP pocket -- a
    different currency, no FX available -- stays top-level rather than being dropped."""
    accounts = [
        _pocket("p1", 100.0, "EUR", "W"),
        _pocket("p2", 50.0, "EUR", "W"),
        _pocket("p3", 20.0, "GBP", "W"),
    ]

    result = main._with_wallet_parents(accounts, {"W": "EUR"}, {"W": "FR7630006000011234567890189"})

    by_id = {a["externalId"]: a for a in result}
    assert len(result) == 4, result

    parent = by_id["W"]
    assert parent["parentExternalId"] is None, parent
    assert parent["type"] == "CHECKING", parent
    assert parent["currency"] == "EUR", parent
    assert parent["balance"] == 150.0, parent
    assert parent["iban"] == "FR7630006000011234567890189", parent
    assert parent["transactions"] == [], parent

    assert by_id["p1"]["parentExternalId"] == "W", by_id["p1"]
    assert by_id["p2"]["parentExternalId"] == "W", by_id["p2"]
    assert by_id["p3"]["parentExternalId"] is None, by_id["p3"]  # different-currency child, not dropped
    assert by_id["p3"]["balance"] == 20.0, by_id["p3"]  # balance preserved, just not nested


async def test_moneybox_with_unresolvable_parent_falls_back_to_top_level():
    """A money-box's parentExternalId (accountId/walletId/podId fallback) that doesn't
    match any actual wallet must not be trusted -- it's demoted to top-level rather than
    silently attached to (or dropped from) the wrong parent."""
    accounts = [_pocket("vault1", 30.0, "EUR", "not-a-real-wallet")]

    result = main._with_wallet_parents(accounts, {"W": "EUR"}, {"W": None})

    by_id = {a["externalId"]: a for a in result}
    assert by_id["vault1"]["parentExternalId"] is None, by_id["vault1"]
    assert by_id["vault1"]["balance"] == 30.0, by_id["vault1"]  # preserved, not dropped
    assert by_id["W"]["balance"] == 0.0, by_id["W"]  # wallet has no matching children


async def test_wallet_with_no_children_still_gets_a_zero_balance_parent():
    """Every wallet from _fetch_wallets must get a parent account even with zero (or
    zero same-currency) children -- the parent is what the backend/dashboard counts for
    net-worth, so an empty wallet must not vanish."""
    result = main._with_wallet_parents([], {"W": "EUR"}, {"W": None})

    assert len(result) == 1, result
    assert result[0]["externalId"] == "W"
    assert result[0]["balance"] == 0.0
    assert result[0]["parentExternalId"] is None


async def test_existing_account_sharing_wallet_id_blocks_duplicate_synthetic_parent():
    """If a child account's own externalId happens to equal its wallet's id (Revolut can
    hand the base pocket the same id as its wallet), synthesizing a second account under
    that same externalId would produce a duplicate -- skip it instead."""
    accounts = [_pocket("W", 40.0, "EUR", None)]  # self-parent already refused upstream

    result = main._with_wallet_parents(accounts, {"W": "EUR"}, {"W": "FR76..."})

    assert len(result) == 1, result  # no duplicate "W" account added
    assert result[0]["externalId"] == "W"
    assert result[0]["balance"] == 40.0  # the original account, untouched


async def _run():
    tests = [
        test_wallet_parent_synthesized_with_same_currency_children_summed,
        test_moneybox_with_unresolvable_parent_falls_back_to_top_level,
        test_wallet_with_no_children_still_gets_a_zero_balance_parent,
        test_existing_account_sharing_wallet_id_blocks_duplicate_synthetic_parent,
    ]
    failures = 0
    for t in tests:
        try:
            await t()
            print(f"PASS {t.__name__}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL {t.__name__}: {type(exc).__name__}: {exc}")
    return failures


if __name__ == "__main__":
    sys.exit(1 if anyio.run(_run) else 0)
