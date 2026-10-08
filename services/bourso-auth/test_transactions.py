"""Synthetic BoursoBank movements based on woob's published HTML selectors.

No fixture in this module is a capture from a customer account.
"""
from datetime import date
from decimal import Decimal
import unittest
from unittest import TestCase

import httpx

from accounts_parser import AccountsFormatError, parse_iban_page, parse_operations
from fixtures import RIB_HTML
from main import MAX_MOVEMENT_PAGES, _fetch_iban, _fetch_operations


class ParseOperationsTest(TestCase):
    def test_reads_an_iban_only_from_the_verified_rib_selector(self):
        self.assertEqual(parse_iban_page(RIB_HTML), "FR7630006000011234567890189")
        self.assertIsNone(parse_iban_page("<html>no rib</html>"))

    def test_signed_transactions_use_header_date_and_known_ids(self):
        html = """
        <ul class="list__movement">
          <li class="list__movement__range-summary" data-operations-next-pagination="token-2"></li>
          <li class="date-line">12 octobre 2026</li>
          <li data-id="op-1"><div class="list-operation-item__amount">− 24,90 €</div>
            <div class="list-operation-item__label-name">PAIEMENT CARTE BOULANGERIE</div>
            <span class="category">Carte bancaire</span></li>
          <li class="date-line">11 octobre 2026</li>
          <li data-custom-id="op-2"><div class="list-operation-item__amount">1 250,00 €</div>
            <div class="list-operation-item__label-name">VIR SALAIRE</div>
          </li>
        </ul>"""

        transactions, next_token = parse_operations(html, date(2026, 10, 1), date(2026, 10, 31))

        self.assertEqual(next_token, "token-2")
        self.assertEqual(
            transactions,
            [
                {
                    "externalId": "op-1",
                    "date": "2026-10-12",
                    "amount": Decimal("-24.90"),
                    "description": "PAIEMENT CARTE BOULANGERIE",
                    "counterparty": None,
                    "kind": "CARD",
                },
                {"externalId": "op-2", "date": "2026-10-11", "amount": Decimal("1250.00"),
                 "description": "VIR SALAIRE", "counterparty": None, "kind": "OTHER"},
            ],
        )

    def test_filters_by_date_and_refuses_an_unparseable_operation(self):
        html = """
        <ul class="list__movement">
          <li class="date-line">30 juin 2026</li>
          <li data-id="old"><div class="list-operation-item__amount">1,00 €</div>
            <div class="list-operation-item__label-name">OLD</div></li>
        </ul>"""
        transactions, _ = parse_operations(html, date(2026, 7, 1), date(2026, 10, 1))
        self.assertEqual(transactions, [])

        broken = '<ul class="list__movement"><li class="date-line">1 octobre 2026</li><li data-id="bad">x</li></ul>'
        with self.assertRaises(ValueError):
            parse_operations(broken, date(2026, 10, 1), date(2026, 10, 31))

    def test_requires_the_expected_container_but_accepts_an_explicit_empty_history(self):
        with self.assertRaises(AccountsFormatError):
            parse_operations("<html>logged in, but no movements widget</html>", date(2026, 10, 1), date(2026, 10, 31))

        self.assertEqual(
            parse_operations('<ul class="list__movement"></ul>', date(2026, 10, 1), date(2026, 10, 31)),
            ([], None),
        )

    def test_does_not_import_deferred_or_user_split_movements(self):
        html = """<ul class="list__movement">
          <li class="date-line">12 octobre 2026</li>
          <li class="list__movement__line--deffered" data-id="future"><div class="list-operation-item__amount">1,00 €</div>
            <div class="list-operation-item__label-name">VIR À VENIR</div></li>
          <li data-id="split"><div class="list__movement__line--block__split"></div>
            <div class="list-operation-item__amount">2,00 €</div>
            <div class="list-operation-item__label-name">ACHAT FRACTIONNÉ</div></li>
          <li data-id="booked"><div class="list-operation-item__amount">3,00 €</div>
            <div class="list-operation-item__label-name">VIR COMPTABILISÉ</div></li>
        </ul>"""

        transactions, _ = parse_operations(html, date(2026, 10, 1), date(2026, 10, 31))

        self.assertEqual([item["externalId"] for item in transactions], ["booked"])


class FetchOperationsTest(unittest.IsolatedAsyncioTestCase):
    async def test_requests_the_ninety_day_window_and_follows_same_origin_tokens(self):
        requests = []

        def handler(request):
            requests.append(request)
            if "continuationToken" in request.url.query.decode():
                return httpx.Response(200, text='''<ul class="list__movement">
                    <li class="date-line">6 octobre 2026</li><li data-id="one">
                    <div class="list-operation-item__amount">1,00 €</div>
                    <div class="list-operation-item__label-name">VIR TEST</div></li></ul>''')
            return httpx.Response(200, text='''<ul class="list__movement">
                <li class="list__movement__range-summary" data-operations-next-pagination="token-2"></li>
                <li class="date-line">6 octobre 2026</li><li data-id="one">
                <div class="list-operation-item__amount">1,00 €</div>
                <div class="list-operation-item__label-name">VIR TEST</div></li></ul>''')

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            transactions, truncated = await _fetch_operations(
                client, "/compte/cav/" + "a" * 32 + "/", today=date(2026, 10, 6)
            )

        self.assertFalse(truncated)
        self.assertEqual(len(transactions), 1)
        self.assertEqual(len(requests), 2)
        self.assertTrue(requests[0].url.path.endswith("/mouvements"))
        self.assertEqual(requests[0].url.params.get("movementSearch[fromDate]"), "08/07/2026")
        self.assertEqual(requests[0].url.params.get("movementSearch[toDate]"), "06/10/2026")
        self.assertEqual(requests[0].url.params.get("movementSearch[advanced]"), "1")
        self.assertEqual(requests[1].url.params.get("continuationToken"), "token-2")
        self.assertEqual(requests[1].url.params.get("rumroute"), "accounts.bank.movements")
        self.assertEqual(requests[1].url.host, "clients.boursobank.com")

    async def test_repeated_continuation_token_stops_before_page_cap(self):
        requests = []
        page = '<ul class="list__movement"><li class="list__movement__range-summary" data-operations-next-pagination="loop"></li></ul>'

        def handler(request):
            requests.append(request)
            return httpx.Response(200, text=page)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            _, incomplete = await _fetch_operations(
                client, "/compte/cav/" + "a" * 32 + "/", today=date(2026, 10, 6)
            )

        self.assertTrue(incomplete)
        self.assertEqual(len(requests), 2)

    async def test_operations_and_rib_do_not_follow_cross_origin_redirects(self):
        requests = []

        def handler(request):
            requests.append(request)
            return httpx.Response(302, headers={"Location": "https://evil.example/collect"})

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler), follow_redirects=True
        ) as client:
            with self.assertRaises(httpx.HTTPStatusError):
                await _fetch_operations(client, "/compte/cav/" + "a" * 32 + "/", today=date(2026, 10, 6))
            with self.assertRaises(httpx.HTTPStatusError):
                await _fetch_iban(client, "/compte/cav/" + "a" * 32 + "/")

        self.assertEqual(len(requests), 2)
        self.assertTrue(all(request.url.host == "clients.boursobank.com" for request in requests))

    async def test_page_cap_is_reported_and_foreign_links_are_refused(self):
        requests = []

        def handler(request):
            requests.append(request)
            page = (
                '<ul class="list__movement"><li class="list__movement__range-summary" '
                f'data-operations-next-pagination="token-{len(requests)}"></li></ul>'
            )
            return httpx.Response(200, text=page)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            _, truncated = await _fetch_operations(
                client, "/compte/cav/" + "a" * 32 + "/", today=date(2026, 10, 6)
            )
            self.assertTrue(truncated)
            self.assertEqual(len(requests), MAX_MOVEMENT_PAGES)
            with self.assertRaises(ValueError):
                await _fetch_operations(client, "https://evil.example/compte/mouvements")
