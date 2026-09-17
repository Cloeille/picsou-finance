import unittest
from unittest.mock import ANY, AsyncMock, MagicMock, patch

from fastapi import HTTPException

from fortuneo_parser import (
    PortfolioFormatError,
    account_balance,
    decimal_value,
    extract_accounts_from_equipment,
    fold_cash_pockets_into_securities_accounts,
    optional_decimal_value,
    parse_bourse_synthese_table,
    parse_equipment_multipart,
    parse_legacy_account_ids,
    parse_portfolio_positions,
    parse_portfolio_summary,
)


class DecimalParsingTest(unittest.TestCase):
    def test_decimal_parses_french_values(self):
        self.assertEqual(str(decimal_value("1 234,56 €")), "1234.56")
        self.assertEqual(str(decimal_value("1.234,56 €")), "1234.56")
        self.assertEqual(str(decimal_value("-12,30 %")), "-12.30")

    def test_decimal_parses_grouped_dots_and_unicode_spaces(self):
        self.assertEqual(str(decimal_value("1.234.567")), "1234567")
        self.assertEqual(str(decimal_value("1\u2009234\u202f567,89")), "1234567.89")

    def test_missing_or_invalid_decimal_is_never_coerced_to_zero(self):
        with self.assertRaises(PortfolioFormatError):
            decimal_value(None, "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("not-a-number", "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("1e3", "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("12oops34", "balance")

    def test_optional_decimal_returns_none_for_blank_input(self):
        self.assertIsNone(optional_decimal_value(None))
        self.assertIsNone(optional_decimal_value("   "))
        self.assertEqual(str(optional_decimal_value("42,00")), "42.00")


class AccountBalanceTest(unittest.TestCase):
    def test_extracts_the_balance_named_amount(self):
        account = {
            "id": "acc-1",
            "productType": "current-account",
            "balances": [{"name": "balance", "amount": {"value": 4.7, "currency": "EUR"}}],
        }
        self.assertEqual(account_balance(account), decimal_value("4.7"))

    def test_returns_none_when_no_balances_array(self):
        # Securities accounts (account-api/v2/accounts) have no `balances` key.
        account = {"id": "acc-2", "productType": "share-savings-plan"}
        self.assertIsNone(account_balance(account))


# Synthetic fixture for the `famille_bourse` HTML structure.
SYNTHESE_BOURSE_FIXTURE = '''
<table cellspacing="0" cellpadding="0" class="synthese_table" id="famille_bourse">
<tbody>
<tr class="l1 synthese_compte_ligne_num_compte">
<td width="5%"><div class="synthese_spacer"></div></td>
<td width="81%">
<a href="/fr/prive/mes-comptes/compte-titres-pea/situation/portefeuille-temps-reel.jsp?ca=CTOWEBID"
   class="synthese_id_compte" title="Valorisation du compte au&nbsp;24/07/2026">
	<span class="synthese_numero_compte">
		N&deg;000000000001
	</span>
	<span class="synthese_compte_tiret">-&nbsp;</span>Compte-titres TEST USER
			<span class="synthese_solde_compte ">
				+ 100,00&nbsp;EUR
			</span>
</a>
</td>
<td width="14%">shortcuts</td>
</tr>
<tr class="l1 synthese_compte_ligne_autre">
<td><div class="synthese_spacer2"></div></td>
<td>
<a href="/fr/prive/mes-comptes/compte-especes/consulter-situation/consulter-solde.jsp?ca=CASHWEBID"
   class="synthese_compte_associe">
	<span class="synthese_compte_tiret">-&nbsp;</span>Compte esp&egrave;ces TEST USER
	<span class="synthese_solde_compte_espece_associe ">
		+ 100,00&nbsp;EUR
	</span>
</a>
</td>
<td></td>
</tr>
<tr class="l2 synthese_compte_ligne_num_compte">
<td width="5%"><div class="synthese_spacer"></div></td>
<td width="81%">
<a href="/fr/prive/mes-comptes/pea/situation/portefeuille-temps-reel.jsp?ca=PEAWEBID"
   class="synthese_id_compte" title="Valorisation du compte au&nbsp;24/07/2026">
	<span class="synthese_numero_compte">
		N&deg;000000000002
	</span>
	<span class="synthese_compte_tiret">-&nbsp;</span>SYNTHETIC PEA
			<span class="synthese_solde_compte ">
				+ 1 000,00&nbsp;EUR
			</span>
</a>
</td>
<td width="14%">shortcuts</td>
</tr>
</tbody>
</table>
'''


class BourseSyntheseTableTest(unittest.TestCase):
    def test_parses_each_securities_account_and_its_linked_cash_pocket(self):
        accounts = parse_bourse_synthese_table(SYNTHESE_BOURSE_FIXTURE)

        self.assertEqual(len(accounts), 2)
        cto, pea = accounts
        self.assertEqual(cto["webId"], "CTOWEBID")
        self.assertEqual(cto["type"], "COMPTE_TITRES")
        self.assertEqual(cto["label"], "Compte-titres TEST USER")
        self.assertEqual(cto["totalEur"], decimal_value("100.00"))
        self.assertEqual(cto["cashWebId"], "CASHWEBID")

        self.assertEqual(pea["webId"], "PEAWEBID")
        self.assertEqual(pea["type"], "PEA")
        self.assertEqual(pea["label"], "SYNTHETIC PEA")
        self.assertEqual(pea["totalEur"], decimal_value("1000.00"))
        self.assertIsNone(pea["cashWebId"])

    def test_unrecognized_url_segment_rejects_the_whole_snapshot(self):
        bogus = SYNTHESE_BOURSE_FIXTURE.replace("/pea/situation/", "/unknown-type/situation/")
        with self.assertRaises(PortfolioFormatError):
            parse_bourse_synthese_table(bogus)


# Synthetic fixture for the `.../situation/` position-table structure. It
# includes blank `<td class="numb"></td>` cells used for
# SRD-related columns this account type doesn't use, and one alternating
# "l2" row -- both are exactly what earlier, naive regexes matched
# incorrectly during development.
PORTFOLIO_POSITIONS_FIXTURE = '''
<tr name="FTN000023XX0000000001princip" class="l1" >
    <td class="txt bourse_table_actions bourse_table_portalonly"></td>
    <td class="txt">
      <a href="/fr/prive/bourse/fiche-valeur.jsp?cdReferentiel=FTN000023XX0000000001&activeTab=resume"><b class="libelle_valeur_tableau">TEST STOCK ONE (TSO)</b></a>
    </td>
    <td class="numb">
        <b>50,00
            <span title="26/07/2026 - 17:35:28"><b>V</b></span>
        </b>
    </td>
    <td class="up evol numb"><b>+1,00%</b></td>
    <td class="numb"><b>10</b></td>
    <td class="numb"></td>
    <td class="numb"></td>
    <td class="numb">40,000</td>
    <td class="numb" id="idValorisation_0_tab__categorie_0" >500,00</td>
    <td class="numb">+100,00&nbsp;<span class="augmente ">+25,00%</span></td>
    <td class="numb" id="idPoid_0_tab__categorie_0">50,0%</td>
</tr>
<tr name="FTN000023XX0000000002princip" class="l2" >
    <td class="txt bourse_table_actions bourse_table_portalonly"></td>
    <td class="txt">
      <a href="/fr/prive/bourse/fiche-valeur.jsp?cdReferentiel=FTN000023XX0000000002&activeTab=resume"><b class="libelle_valeur_tableau">TEST ETF TWO (TET)</b></a>
    </td>
    <td class="numb">
        <b>10,000
            <span title="26/07/2026 - 17:35:28"><b>V</b></span>
        </b>
    </td>
    <td class="down evol numb"><b>-0,10%</b></td>
    <td class="numb"><b>1 000</b></td>
    <td class="numb"></td>
    <td class="numb">10,000</td>
    <td class="numb" id="idValorisation_1_tab__categorie_0" >10 000,00</td>
    <td class="numb" id="idPoid_1_tab__categorie_0">50,0%</td>
</tr>
'''


class PortfolioPositionsTest(unittest.TestCase):
    def test_parses_price_quantity_pru_valuation_and_pnl(self):
        positions = parse_portfolio_positions(PORTFOLIO_POSITIONS_FIXTURE)

        self.assertEqual(len(positions), 2)
        stock = positions[0]
        self.assertEqual(stock["isin"], "XX0000000001")
        self.assertEqual(stock["symbol"], "XX0000000001")
        self.assertEqual(stock["label"], "TEST STOCK ONE")
        self.assertEqual(stock["quantity"], decimal_value("10"))
        self.assertEqual(stock["currentPrice"], decimal_value("50.00"))
        self.assertEqual(stock["buyingPriceEur"], decimal_value("40.000"))
        self.assertEqual(stock["currentValueEur"], decimal_value("500.00"))
        self.assertEqual(stock["pnlEur"], decimal_value("100.00"))
        self.assertEqual(stock["quoteCurrency"], "EUR")

    def test_thousand_separated_quantity_and_missing_pnl_span(self):
        # The second fixture row has no augmente/baisse span at all (a
        # position exactly at cost legitimately renders with no coloured
        # P&L span) and a space-separated quantity ("1 000").
        positions = parse_portfolio_positions(PORTFOLIO_POSITIONS_FIXTURE)

        etf = positions[1]
        self.assertEqual(etf["isin"], "XX0000000002")
        self.assertEqual(etf["label"], "TEST ETF TWO")
        self.assertEqual(etf["quantity"], decimal_value("1000"))
        self.assertEqual(etf["currentValueEur"], decimal_value("10000.00"))
        self.assertIsNone(etf["pnlEur"])

    def test_blank_srd_cells_are_never_mistaken_for_the_real_value(self):
        # Both fixture rows have blank `<td class="numb"></td>` cells before
        # the real PRU cell -- a naive `[\d.,\s]+` pattern (no required
        # leading digit) would match that blank whitespace first and never
        # reach the actual number.
        positions = parse_portfolio_positions(PORTFOLIO_POSITIONS_FIXTURE)

        self.assertEqual(positions[0]["buyingPriceEur"], decimal_value("40.000"))
        self.assertEqual(positions[1]["buyingPriceEur"], decimal_value("10.000"))

    def test_missing_valuation_rejects_the_row(self):
        broken = PORTFOLIO_POSITIONS_FIXTURE.replace(
            '<td class="numb" id="idValorisation_0_tab__categorie_0" >500,00</td>',
            '<td class="numb" id="idValorisation_0_tab__categorie_0" ></td>',
        )
        with self.assertRaises(PortfolioFormatError):
            parse_portfolio_positions(broken)


# Synthetic fixture for the Equipment GraphQL multipart response structure.
EQUIPMENT_MULTIPART_FIXTURE = (
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"data":{"equipment":{"financialPortfolio":{'
    '"lifeInsurance":{"accounts":[]},'
    '"shareSavingsPlan":{"accounts":[{"id":"PEAWEBID","label":"SYNTHETIC PEA",'
    '"type":{"label":"PEA","value":"share-savings-plan"}}]},'
    '"ordinarySecurities":{"accounts":[{"id":"CTOWEBID","label":"SYNTHETIC CTO",'
    '"type":{"label":"Compte-titres","value":"ordinary-securities-account"}}]},'
    '"cash":{"accounts":[{"id":"CASHWEBID","label":"Compte especes TEST USER",'
    '"type":{"label":"Compte especes","value":"cash-account"}}]},'
    '"mortgages":{"accounts":[]},"external":{"accounts":[]}},'
    '"banking":{'
    '"current":{"accounts":[{"id":"CHECKINGWEBID","label":"CC TEST USER",'
    '"type":{"label":"Compte courant","value":"current-account"}}]},'
    '"savings":{"accounts":[]},"external":{"accounts":[]}}}},"hasNext":true}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":true,"incremental":[{"data":{"balance":{"amount":"1000","currency":"EUR"}},'
    '"path":["equipment","financialPortfolio","shareSavingsPlan","accounts",0]}]}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":true,"incremental":[{"data":{"balance":{"amount":"100","currency":"EUR"}},'
    '"path":["equipment","financialPortfolio","ordinarySecurities","accounts",0]}]}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":true,"incremental":[{"data":{"balance":{"amount":"100","currency":"EUR"}},'
    '"path":["equipment","financialPortfolio","cash","accounts",0]}]}\r\n'
    '---\r\n'
    'content-type: application/json; charset=utf-8\r\n\r\n'
    '{"hasNext":false,"incremental":[{"data":{"balance":{"amount":"4.7","currency":"EUR"}},'
    '"path":["equipment","banking","current","accounts",0]}]}\r\n'
    '-----\r\n'
)


class EquipmentGraphqlTest(unittest.TestCase):
    def test_parse_equipment_multipart_merges_every_deferred_fragment(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)

        pea = equipment["financialPortfolio"]["shareSavingsPlan"]["accounts"][0]
        self.assertEqual(pea["balance"]["amount"], "1000")
        cto = equipment["financialPortfolio"]["ordinarySecurities"]["accounts"][0]
        self.assertEqual(cto["balance"]["amount"], "100")
        checking = equipment["banking"]["current"]["accounts"][0]
        self.assertEqual(checking["balance"]["amount"], "4.7")

    def test_malformed_multipart_never_yields_an_empty_snapshot(self):
        with self.assertRaises(PortfolioFormatError):
            parse_equipment_multipart("not a multipart response at all")

    def test_multipart_rejects_non_object_chunks_and_base_data(self):
        with self.assertRaises(PortfolioFormatError):
            parse_equipment_multipart("[]")
        with self.assertRaises(PortfolioFormatError):
            parse_equipment_multipart('{"data": []}')

    def test_multipart_rejects_malformed_incremental_shapes(self):
        base = '{"data":{"equipment":{"banking":{}}}}'
        with self.assertRaises(PortfolioFormatError):
            parse_equipment_multipart(base + '\n---\n{"incremental": {}}')
        with self.assertRaises(PortfolioFormatError):
            parse_equipment_multipart(base + '\n---\n{"incremental": ["bad"]}')

    def test_extract_accounts_rejects_changed_category_and_account_shapes(self):
        with self.assertRaises(PortfolioFormatError):
            extract_accounts_from_equipment({"banking": []})
        with self.assertRaises(PortfolioFormatError):
            extract_accounts_from_equipment({
                "banking": {"current": {"accounts": ["bad"]}},
            })

    def test_extract_accounts_covers_in_scope_categories_and_skips_the_rest(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)

        accounts = extract_accounts_from_equipment(equipment)

        by_web_id = {a["webId"]: a for a in accounts}
        self.assertEqual(set(by_web_id), {"PEAWEBID", "CTOWEBID", "CASHWEBID", "CHECKINGWEBID"})
        self.assertEqual(by_web_id["PEAWEBID"]["type"], "PEA")
        self.assertEqual(by_web_id["CTOWEBID"]["type"], "COMPTE_TITRES")
        self.assertEqual(by_web_id["CHECKINGWEBID"]["type"], "CHECKING")
        self.assertIsNone(by_web_id["CASHWEBID"]["type"])
        self.assertEqual(by_web_id["PEAWEBID"]["balanceEur"], decimal_value("1000"))

    def test_fold_cash_pockets_pairs_the_single_cto_with_the_single_pocket(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)
        accounts = extract_accounts_from_equipment(equipment)

        folded = fold_cash_pockets_into_securities_accounts(accounts)

        self.assertEqual({a["webId"] for a in folded}, {"PEAWEBID", "CTOWEBID", "CHECKINGWEBID"})
        cto = next(a for a in folded if a["webId"] == "CTOWEBID")
        self.assertEqual(cto["cashBalance"], decimal_value("100"))
        checking = next(a for a in folded if a["webId"] == "CHECKINGWEBID")
        self.assertEqual(checking["cashBalance"], checking["balanceEur"])

    def test_pea_and_checking_portfolio_needs_no_cash_pocket(self):
        equipment = {
            "financialPortfolio": {
                "shareSavingsPlan": {"accounts": [{
                    "id": "PEAWEBID",
                    "label": "SYNTHETIC PEA",
                    "type": {"label": "PEA", "value": "share-savings-plan"},
                    "balance": {"amount": "1000", "currency": "EUR"},
                }]},
            },
            "banking": {
                "current": {"accounts": [{
                    "id": "CHECKINGWEBID",
                    "label": "CC TEST USER",
                    "type": {"label": "Compte courant", "value": "current-account"},
                    "balance": {"amount": "4.7", "currency": "EUR"},
                }]},
            },
        }

        folded = fold_cash_pockets_into_securities_accounts(
            extract_accounts_from_equipment(equipment)
        )

        self.assertEqual([account["type"] for account in folded], ["PEA", "CHECKING"])
        self.assertIsNone(folded[0]["cashBalance"])
        self.assertEqual(folded[1]["cashBalance"], decimal_value("4.7"))

    def test_pea_cto_checking_and_cto_cash_pocket_are_all_kept_consistent(self):
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)

        folded = fold_cash_pockets_into_securities_accounts(
            extract_accounts_from_equipment(equipment)
        )

        self.assertEqual(
            [account["type"] for account in folded],
            ["PEA", "COMPTE_TITRES", "CHECKING"],
        )
        cto = next(account for account in folded if account["type"] == "COMPTE_TITRES")
        self.assertEqual(cto["cashBalance"], decimal_value("100"))

    def test_fold_cash_pockets_leaves_pea_cash_balance_unknown(self):
        # A PEA can hold real stock positions and has no separate cash-pocket
        # account (unlike CTO) -- guessing cashBalance = balanceEur would
        # silently misreport a PEA holding positions as 100% cash (confirmed
        # live before this was fixed). It must stay None so the caller
        # rejects the snapshot instead of reporting wrong data.
        equipment = parse_equipment_multipart(EQUIPMENT_MULTIPART_FIXTURE)
        accounts = extract_accounts_from_equipment(equipment)

        folded = fold_cash_pockets_into_securities_accounts(accounts)

        pea = next(a for a in folded if a["webId"] == "PEAWEBID")
        self.assertIsNone(pea["cashBalance"])

    def test_fold_cash_pockets_discards_an_unpaired_pocket(self):
        accounts = [
            {"webId": "CTO1", "type": "COMPTE_TITRES", "label": "CTO 1", "balanceEur": decimal_value("10")},
            {"webId": "CTO2", "type": "COMPTE_TITRES", "label": "CTO 2", "balanceEur": decimal_value("20")},
            {"webId": "POCKET1", "type": None, "label": "Pocket", "balanceEur": decimal_value("10")},
        ]
        folded = fold_cash_pockets_into_securities_accounts(accounts)

        self.assertEqual([account["webId"] for account in folded], ["CTO1", "CTO2"])
        self.assertTrue(all(account["cashBalance"] is None for account in folded))

    def test_fold_cash_pockets_does_not_guess_between_multiple_ctos(self):
        accounts = [
            {"webId": "CTO1", "type": "COMPTE_TITRES", "balanceEur": decimal_value("100")},
            {"webId": "CTO2", "type": "COMPTE_TITRES", "balanceEur": decimal_value("200")},
            {"webId": "POCKET1", "type": None, "balanceEur": decimal_value("10")},
            {"webId": "POCKET2", "type": None, "balanceEur": decimal_value("20")},
        ]

        folded = fold_cash_pockets_into_securities_accounts(accounts)

        self.assertEqual([account["webId"] for account in folded], ["CTO1", "CTO2"])
        self.assertTrue(all(account["cashBalance"] is None for account in folded))

    def test_fold_cash_pockets_rejects_missing_required_fields(self):
        with self.assertRaises(PortfolioFormatError):
            fold_cash_pockets_into_securities_accounts([{"webId": "broken"}])

    def test_unrecognized_url_segment_rejects_the_whole_snapshot(self):
        bogus = SYNTHESE_BOURSE_FIXTURE.replace("/pea/situation/", "/unknown-type/situation/")
        with self.assertRaises(PortfolioFormatError):
            parse_bourse_synthese_table(bogus)


class AccountPayloadContractTest(unittest.TestCase):
    def test_account_payload_accepts_all_four_supported_types(self):
        from main import AccountPayload

        for account_type in ("PEA", "COMPTE_TITRES", "CHECKING", "SAVINGS"):
            payload = AccountPayload.model_validate({
                "externalId": "acc-1",
                "name": "Account",
                "type": account_type,
                "balanceEur": "100.00",
                "cashBalance": "100.00",
                "positions": [],
                "transactions": [],
                "snapshotComplete": True,
            })
            self.assertEqual(payload.type, account_type)

    def test_account_payload_defaults_transactions_to_empty_list(self):
        from main import AccountPayload

        payload = AccountPayload.model_validate({
            "externalId": "acc-1",
            "name": "Account",
            "type": "PEA",
            "balanceEur": "100.00",
            "cashBalance": "0.00",
            "positions": [],
            "snapshotComplete": True,
        })
        self.assertEqual(payload.transactions, [])

    def test_transaction_payload_requires_iso_date(self):
        from pydantic import ValidationError

        from main import TransactionPayload

        with self.assertRaises(ValidationError):
            TransactionPayload.model_validate({
                "date": "26/07/2026",
                "label": "Virement",
                "amount": "10.00",
            })
        with self.assertRaises(ValidationError):
            TransactionPayload.model_validate({
                "date": "2026-13-40",
                "label": "Virement",
                "amount": "10.00",
            })

        valid = TransactionPayload.model_validate({
            "date": "2026-07-26",
            "label": "Virement",
            "amount": "10.00",
        })
        self.assertEqual(valid.label, "Virement")


class SessionStateEnvelopeTest(unittest.TestCase):
    def test_parses_storage_state_api_key_and_session_storage_out_of_the_envelope(self):
        import json

        from main import _parse_session_state

        storage_state, api_key, session_storage = _parse_session_state(json.dumps({
            "storageState": {"cookies": [], "origins": []},
            "apiKey": "test-key",
            "sessionStorage": {"token": "abc"},
        }))

        self.assertEqual(storage_state, {"cookies": [], "origins": []})
        self.assertEqual(api_key, "test-key")
        self.assertEqual(session_storage, {"token": "abc"})

    def test_session_storage_defaults_to_empty_when_absent(self):
        import json

        from main import _parse_session_state

        _, _, session_storage = _parse_session_state(json.dumps({
            "storageState": {"cookies": [], "origins": []},
            "apiKey": "test-key",
        }))

        self.assertEqual(session_storage, {})

    def test_rejects_an_envelope_missing_the_api_key(self):
        import json

        from fastapi import HTTPException

        from main import _parse_session_state

        with self.assertRaises(HTTPException) as raised:
            _parse_session_state(json.dumps({"storageState": {}}))
        self.assertEqual(raised.exception.status_code, 400)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    def test_rejects_a_non_object_payload(self):
        from fastapi import HTTPException

        from main import _parse_session_state

        with self.assertRaises(HTTPException):
            _parse_session_state("[]")


class LegacyHistoryDiscoveryTest(unittest.TestCase):
    """The PEA ledger is absent from the transaction API, so its dividends have to
    come from the legacy site. Its URL shape is not guessable and has already
    changed once, so it is discovered from the site's own links."""

    def test_history_links_are_discovered_from_a_legacy_page(self):
        from fortuneo_parser import find_legacy_history_paths

        html = (
            '<a href="/fr/prive/mes-comptes/pea/consulter-situation/'
            'historique-des-operations.jsp?ca=deadbeef">Historique</a>'
            '<a href="/fr/prive/mes-comptes/pea/situation/?ca=deadbeef">Situation</a>'
        )

        self.assertEqual(
            find_legacy_history_paths(html),
            ["mes-comptes/pea/consulter-situation/historique-des-operations.jsp"],
        )

    def test_account_identifiers_are_never_reported(self):
        from fortuneo_parser import find_legacy_history_paths

        html = (
            '<a href="/fr/prive/mes-comptes/pea/consulter-situation/'
            'historique-des-operations.jsp?ca=0123456789abcdef0123456789abcdef">x</a>'
        )

        paths = find_legacy_history_paths(html)
        self.assertTrue(paths)
        for path in paths:
            self.assertNotIn("0123456789abcdef", path)
            self.assertNotIn("?", path)

    def test_repeated_links_collapse_and_sort(self):
        from fortuneo_parser import find_legacy_history_paths

        html = (
            '<a href="/fr/prive/b/mouvements.jsp?ca=1">b</a>'
            '<a href="/fr/prive/a/historique.jsp?ca=2">a</a>'
            '<a href="/fr/prive/b/mouvements.jsp?ca=3">b again</a>'
        )

        self.assertEqual(
            find_legacy_history_paths(html),
            ["a/historique.jsp", "b/mouvements.jsp"],
        )

    def test_a_page_without_history_links_yields_nothing(self):
        from fortuneo_parser import find_legacy_history_paths

        self.assertEqual(find_legacy_history_paths("<a href='/fr/prive/x/'>x</a>"), [])


class LegacyPageStructureTest(unittest.TestCase):
    """The securities history page has to be parsed from real structure, and this
    connector has already lost iterations to guessing page shapes. The describer
    reports the page's schema so the parser can be written against it -- and
    nothing about the customer."""

    HISTORY_PAGE = (
        '<form action="/fr/prive/mes-comptes/pea/historique/historique-titres.jsp?ca=deadbeef"'
        ' method="post">'
        '<input name="dateDebut"><input name="dateFin">'
        '<select name="typeOperation"></select></form>'
        '<table><tr class="entete"><td>Date</td><td>Libell&eacute;</td><td>Montant</td></tr>'
        '<tr class="ligne1" name="XX0000000001princip">'
        '<td>01/02/2024</td><td>DIVIDENDE SYNTHETIC FUND</td><td>1 234,56</td></tr></table>'
    )

    def test_reports_the_option_vocabulary_of_each_select(self):
        # A closed list belonging to the site, not to the customer -- and the one
        # thing that can say whether a dividend is selectable structurally rather
        # than by matching free text.
        from fortuneo_parser import describe_legacy_page_structure

        html = (
            '<form method="post"><select name="typeOperation">'
            '<option value="">Toutes</option><option value="ACH">Achat</option>'
            '<option value="DIV">Dividende</option></select></form>'
        )

        form = describe_legacy_page_structure(html)["forms"][0]

        self.assertEqual(form["selects"], {"typeOperation": ["", "ACH", "DIV"]})

    def test_a_select_listing_the_customers_holdings_is_never_reported(self):
        # The results page fills `codeReferentiel` with one option per security
        # the account has traded, ISIN included. Only the
        # count may be reported for a select the site does not own.
        from fortuneo_parser import describe_legacy_page_structure

        html = (
            '<form method="post"><select name="codeReferentiel">'
            '<option value="">Toutes</option>'
            '<option value="FTN000023XX0000000001">SYNTHETIC FUND ONE</option>'
            '<option value="FTN000023XX0000000002">SYNTHETIC FUND TWO</option>'
            '</select></form>'
        )

        form = describe_legacy_page_structure(html)["forms"][0]

        self.assertEqual(form["selects"], {"codeReferentiel": "<3 options>"})
        self.assertNotIn("XX0000000001", str(form))
        self.assertNotIn("SYNTHETIC FUND ONE", str(form))

    def test_default_values_are_reduced_to_their_shape(self):
        # "NN/NN/NNNN" tells a parser the field takes dd/mm/yyyy without ever
        # recording which date the page was pre-filled with.
        from fortuneo_parser import describe_legacy_page_structure

        html = (
            '<form method="post"><input name="dateDebut" value="01/02/2024">'
            '<input name="nbResultatsTotal" value="137"></form>'
        )

        form = describe_legacy_page_structure(html)["forms"][0]

        self.assertEqual(
            form["default_shapes"], {"dateDebut": "NN/NN/NNNN", "nbResultatsTotal": "NNN"})
        self.assertNotIn("01/02/2024", str(form))

    def test_reports_the_form_that_selects_a_period(self):
        # Whether this page takes a date range decides whether history older than
        # the transaction API's 13-month retention is reachable at all.
        from fortuneo_parser import describe_legacy_page_structure

        described = describe_legacy_page_structure(self.HISTORY_PAGE)

        self.assertEqual(described["forms"], [{
            "action": "/fr/prive/mes-comptes/pea/historique/historique-titres.jsp",
            "method": "post",
            "fields": ["dateDebut", "dateFin", "typeOperation"],
            "selects": {"typeOperation": []},
            "default_shapes": {},
        }])

    def test_reports_column_labels_and_row_markup(self):
        from fortuneo_parser import describe_legacy_page_structure

        table = describe_legacy_page_structure(self.HISTORY_PAGE)["tables"][0]

        self.assertEqual(table["headers"], ["Date", "Libell\u00e9", "Montant"])
        self.assertEqual(table["cells_per_row"], {3: 2})
        self.assertEqual(table["row_classes"], ["entete", "ligne1"])
        self.assertEqual(table["row_attrs"], ["class", "name"])

    def test_never_reports_cell_values_or_identifiers(self):
        from fortuneo_parser import describe_legacy_page_structure

        described = str(describe_legacy_page_structure(self.HISTORY_PAGE))

        # A holding, an amount, a date, a security code and the account's legacy id.
        for secret in ("SYNTHETIC FUND", "1 234,56", "01/02/2024", "XX0000000001", "deadbeef"):
            self.assertNotIn(secret, described)

    def test_th_headers_are_read_when_the_table_uses_them(self):
        from fortuneo_parser import describe_legacy_page_structure

        html = '<table><tr><th>Date</th><th>Montant</th></tr><tr><td>x</td><td>y</td></tr></table>'

        self.assertEqual(
            describe_legacy_page_structure(html)["tables"][0]["headers"], ["Date", "Montant"])

    def test_iframe_paths_are_reported_without_their_query(self):
        from fortuneo_parser import describe_legacy_page_structure

        html = '<iframe src="/fr/prive/mes-comptes/pea/historique/inner.jsp?ca=deadbeef"></iframe>'

        described = describe_legacy_page_structure(html)
        self.assertEqual(described["iframes"], ["/fr/prive/mes-comptes/pea/historique/inner.jsp"])

    def test_a_page_without_tables_describes_cleanly(self):
        from fortuneo_parser import describe_legacy_page_structure

        described = describe_legacy_page_structure("<p>Aucune operation</p>")

        self.assertEqual(described["tables"], [])
        self.assertEqual(described["forms"], [])


# Synthetic fixture for the securities-history results table structure (10
# columns, alternating l1/l2 body rows, French
# number and date formats, `&nbsp;` thousands separators). Fake names, dates
# and amounts throughout; the dividend row deliberately omits quantity and
# execution price, exactly as a real cash-only line does.
SECURITIES_HISTORY_FIXTURE = '''
<table class="tableau_historique">
<tr><th>Libell&eacute;</th><th>Op&eacute;ration</th><th>Place</th><th>Date</th>
<th>Qt&eacute;</th><th>Prix d'&eacute;x&eacute;</th><th>Montant brut</th>
<th>Courtage/ Pr&eacute;l&egrave;vement</th><th>Montant Net</th><th>Devise</th></tr>
<tr class="l1">
  <td>TEST STOCK ONE</td><td>ACHAT COMPTANT</td><td>EURONEXT</td><td>02/03/2021</td>
  <td>10</td><td>40,000</td><td>400,00</td><td>2,50</td><td>402,50</td><td>EUR</td>
</tr>
<tr class="l2">
  <td>TEST STOCK ONE</td><td>ENCAISSEMENT COUPON</td><td></td><td>15/06/2022</td>
  <td></td><td></td><td>12,34</td><td></td><td>12,34</td><td>EUR</td>
</tr>
<tr class="l1">
  <td>TEST ETF TWO</td><td>VENTE COMPTANT</td><td>EURONEXT</td><td>08/09/2024</td>
  <td>1 000</td><td>10,000</td><td>10&nbsp;000,00</td><td>9,90</td><td>9&nbsp;990,10</td><td>EUR</td>
</tr>
<tr class="l1"><td colspan="10">pagination row that must not be parsed</td></tr>
</table>
'''


class SecuritiesHistoryTest(unittest.TestCase):
    """The legacy history page is the only source for a securities ledger: the
    transaction API answers PEA and CTO with an empty list whatever parameters
    it is given. It is also the only source reaching past that API's ~13-month
    retention, and the only one carrying brokerage fees."""

    def test_parses_a_trade_with_its_fees(self):
        from fortuneo_parser import parse_securities_history

        trade = parse_securities_history(SECURITIES_HISTORY_FIXTURE)[0]

        self.assertEqual(trade["date"], "2021-03-02")
        self.assertEqual(trade["label"], "TEST STOCK ONE")
        self.assertEqual(trade["operation"], "ACHAT COMPTANT")
        self.assertEqual(str(trade["quantity"]), "10")
        self.assertEqual(str(trade["unitPrice"]), "40.000")
        self.assertEqual(str(trade["fees"]), "2.50")
        self.assertEqual(str(trade["netAmount"]), "402.50")
        self.assertEqual(trade["currency"], "EUR")

    def test_parses_a_cash_only_line_without_quantity_or_price(self):
        # A dividend carries neither. Requiring them would reject exactly the
        # rows this page was opened for.
        from fortuneo_parser import parse_securities_history

        coupon = parse_securities_history(SECURITIES_HISTORY_FIXTURE)[1]

        self.assertEqual(coupon["operation"], "ENCAISSEMENT COUPON")
        self.assertIsNone(coupon["quantity"])
        self.assertIsNone(coupon["unitPrice"])
        self.assertIsNone(coupon["fees"])
        self.assertEqual(str(coupon["grossAmount"]), "12.34")

    def test_parses_french_thousands_separators(self):
        from fortuneo_parser import parse_securities_history

        sale = parse_securities_history(SECURITIES_HISTORY_FIXTURE)[2]

        self.assertEqual(str(sale["quantity"]), "1000")
        self.assertEqual(str(sale["grossAmount"]), "10000.00")
        self.assertEqual(str(sale["netAmount"]), "9990.10")

    def test_layout_rows_sharing_the_row_class_are_skipped(self):
        from fortuneo_parser import parse_securities_history

        self.assertEqual(len(parse_securities_history(SECURITIES_HISTORY_FIXTURE)), 3)

    def test_the_operation_label_is_kept_verbatim(self):
        # Classifying it is the caller's decision. This module must not guess at
        # a vocabulary it has not seen.
        from fortuneo_parser import parse_securities_history

        operations = parse_securities_history(SECURITIES_HISTORY_FIXTURE)

        self.assertEqual(
            [o["operation"] for o in operations],
            ["ACHAT COMPTANT", "ENCAISSEMENT COUPON", "VENTE COMPTANT"],
        )

    def test_every_unicode_thousands_separator_is_accepted(self):
        # Fortuneo does not use one space consistently: a plain space, U+00A0,
        # U+202F and U+2009 have all been seen on these pages. Handling them one
        # at a time is how this parser failed twice, silently, on pages that did
        # hold the data -- so the whole family is folded.
        from fortuneo_parser import parse_securities_history

        for separator in (" ", " ", " ", " ", " "):
            with self.subTest(separator=f"U+{ord(separator):04X}"):
                html = SECURITIES_HISTORY_FIXTURE.replace(
                    "10&nbsp;000,00", f"-1{separator}234,56")
                sale = parse_securities_history(html)[2]
                self.assertEqual(str(sale["grossAmount"]), "-1234.56")

    def test_an_unexpected_amount_format_is_reported_by_shape_only(self):
        from fortuneo_parser import PortfolioFormatError, parse_securities_history

        broken = SECURITIES_HISTORY_FIXTURE.replace("10&nbsp;000,00", "12oops34")

        with self.assertRaises(PortfolioFormatError) as raised:
            parse_securities_history(broken)
        message = str(raised.exception)
        self.assertIn("grossAmount", message)
        self.assertIn("NNAAAANN", message)
        self.assertNotIn("12oops34", message)

    def test_an_unparseable_date_stops_the_parse(self):
        # A row that cannot be placed in time is unusable, and dropping it
        # silently would understate the history.
        from fortuneo_parser import PortfolioFormatError, parse_securities_history

        broken = SECURITIES_HISTORY_FIXTURE.replace("02/03/2021", "mars 2021")

        with self.assertRaises(PortfolioFormatError):
            parse_securities_history(broken)

    def test_an_empty_result_page_yields_nothing(self):
        from fortuneo_parser import parse_securities_history

        self.assertEqual(parse_securities_history("<p>Aucune operation</p>"), [])

    def test_data_rows_with_a_changed_column_count_fail_closed(self):
        from fortuneo_parser import PortfolioFormatError, parse_securities_history

        changed_layout = (
            '<table><tr class="l1"><td>TEST STOCK</td><td>ACHAT</td>'
            '<td>02/03/2021</td><td>10</td><td>400,00</td></tr></table>'
        )

        with self.assertRaises(PortfolioFormatError):
            parse_securities_history(changed_layout)

    def test_a_layout_only_row_still_yields_empty_history(self):
        from fortuneo_parser import parse_securities_history

        pagination = '<tr class="l1"><td colspan="10">Page suivante</td></tr>'

        self.assertEqual(parse_securities_history(pagination), [])


class SecuritiesOperationClassificationTest(unittest.TestCase):
    """The labels below come from Fortuneo's `typeOperation` form options.
    Classifying them structurally is what replaced matching free text like
    "Div"/"TNC Div"."""

    def test_maps_the_real_vocabulary(self):
        from fortuneo_parser import classify_securities_operation

        self.assertEqual(classify_securities_operation("Achat Comptant"), "BUY")
        self.assertEqual(classify_securities_operation("Vente Comptant"), "SELL")
        self.assertEqual(
            classify_securities_operation("Encaissement coupons intérêt/dividende"),
            "DIVIDEND")
        self.assertEqual(
            classify_securities_operation("Encaissement coupons sur OPCVM"), "DIVIDEND")
        self.assertEqual(classify_securities_operation("TAXE TRANSAC FINAN"), "FEE")

    def test_an_unknown_operation_is_untyped_rather_than_guessed(self):
        # "Indemnisation" covers several unrelated broker events and cannot be
        # typed from its label. It is still imported -- it simply does not join a
        # typed aggregate, where a wrong guess would corrupt one silently.
        from fortuneo_parser import classify_securities_operation

        self.assertIsNone(classify_securities_operation("Indemnisation"))
        self.assertIsNone(classify_securities_operation("Something Fortuneo added"))

    def test_the_entitlement_leg_of_a_dividend_is_ignored(self):
        # A dividend has an entitlement leg and a cash leg. Importing both would
        # double every dividend.
        from fortuneo_parser import is_ignored_securities_operation

        self.assertTrue(is_ignored_securities_operation(
            "OST de création de coupons - Détachement coupon optionnel"))
        self.assertTrue(is_ignored_securities_operation(
            "OST de création de coupons - Détachement coupon sur OPCVM"))
        self.assertFalse(is_ignored_securities_operation(
            "Encaissement coupons intérêt/dividende"))

    def test_a_cancellation_keeps_the_type_it_reverses(self):
        # It carries the negated amount, so the pair nets to zero in every
        # aggregate instead of leaving a stray untyped row behind.
        from fortuneo_parser import (
            classify_securities_operation,
            is_ignored_securities_operation,
        )

        self.assertEqual(
            classify_securities_operation("ANNUL. Encaissement coupons intérêt/dividende"),
            "DIVIDEND")
        self.assertTrue(is_ignored_securities_operation(
            "ANNUL. OST de création de coupons - Détachement coupon optionnel"))


class SecuritiesHistoryFingerprintTest(unittest.TestCase):
    """The legacy page exposes no per-row id. Without one the backend falls back
    to replacing a rolling 90-day window, which for a ledger read in full would
    both truncate the history and duplicate it on the next sync."""

    def rows(self):
        from fortuneo_parser import parse_securities_history
        return parse_securities_history(SECURITIES_HISTORY_FIXTURE)

    def test_the_same_history_fingerprints_identically_twice(self):
        from fortuneo_parser import fingerprint_securities_history

        first = fingerprint_securities_history(self.rows())
        second = fingerprint_securities_history(self.rows())

        self.assertEqual(
            [row["externalId"] for row in first], [row["externalId"] for row in second])

    def test_different_rows_get_different_identifiers(self):
        from fortuneo_parser import fingerprint_securities_history

        identifiers = [row["externalId"] for row in fingerprint_securities_history(self.rows())]

        self.assertEqual(len(set(identifiers)), len(identifiers))

    def test_two_identical_rows_are_told_apart(self):
        # Two identical purchases on the same day are a real thing; collapsing
        # them onto one identifier would silently drop one of them.
        from fortuneo_parser import fingerprint_securities_history

        row = self.rows()[0]
        identifiers = [r["externalId"] for r in fingerprint_securities_history([row, dict(row)])]

        self.assertEqual(len(set(identifiers)), 2)

    def test_a_changed_amount_changes_the_identifier(self):
        from fortuneo_parser import fingerprint_securities_history

        row = self.rows()[0]
        edited = {**row, "netAmount": (row["netAmount"] or 0) + 1}

        self.assertNotEqual(
            fingerprint_securities_history([row])[0]["externalId"],
            fingerprint_securities_history([edited])[0]["externalId"],
        )

    def test_the_identifier_fits_the_stored_column(self):
        from fortuneo_parser import fingerprint_securities_history

        for row in fingerprint_securities_history(self.rows()):
            self.assertTrue(row["externalId"].startswith("ft_h_"))
            self.assertLessEqual(len(row["externalId"]), 100)


class SecuritiesHistoryRequestTest(unittest.IsolatedAsyncioTestCase):
    ROW = (
        '<tr class="l1"><td>TEST</td><td>ACHAT COMPTANT</td><td>EURONEXT</td>'
        '<td>02/03/2021</td><td>1</td><td>10,00</td><td>10,00</td>'
        '<td>0,00</td><td>10,00</td><td>EUR</td></tr>'
    )

    @classmethod
    def page(cls, total: int, rows: int) -> str:
        return f'<input name="nbResultatsTotal" value="{total}">' + cls.ROW * rows

    async def test_requests_the_whole_possible_account_lifetime(self):
        from main import _fetch_securities_history

        page_fetch = AsyncMock(return_value={
            "status": 200,
            "text": '<input name="nbResultatsTotal" value="0">',
        })
        with patch("main._page_fetch", new=page_fetch):
            self.assertEqual(
                await _fetch_securities_history(object(), "pea", "legacy-id"), [])

        request_body = page_fetch.await_args.args[4]
        self.assertIn("dateDebut=01%2F01%2F1990", request_body)

    async def test_paginates_beyond_four_thousand_operations(self):
        from main import _fetch_securities_history

        responses = [
            {"status": 200, "text": self.page(4001, 100)} for _ in range(40)
        ] + [{"status": 200, "text": self.page(4001, 1)}]
        page_fetch = AsyncMock(side_effect=responses)

        with patch("main._page_fetch", new=page_fetch):
            transactions = await _fetch_securities_history(
                object(), "pea", "legacy-id"
            )

        self.assertEqual(len(transactions), 4001)
        self.assertEqual(page_fetch.await_count, 41)
        self.assertIn("offset=4000", page_fetch.await_args.args[4])

    async def test_missing_total_fails_loudly(self):
        from main import _fetch_securities_history

        with patch(
            "main._page_fetch",
            new=AsyncMock(return_value={"status": 200, "text": self.ROW}),
        ):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_securities_history(object(), "pea", "legacy-id")

        self.assertEqual(raised.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    async def test_short_history_read_fails_loudly(self):
        from main import _fetch_securities_history

        page_fetch = AsyncMock(side_effect=[
            {"status": 200, "text": self.page(101, 100)},
            {"status": 200, "text": self.page(101, 0)},
        ])
        with patch("main._page_fetch", new=page_fetch):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_securities_history(object(), "pea", "legacy-id")

        self.assertEqual(raised.exception.detail, "PORTFOLIO_INCOMPLETE")


class PageFetchTest(unittest.IsolatedAsyncioTestCase):
    async def test_in_page_fetch_receives_an_abort_timeout(self):
        from main import DATA_REQUEST_TIMEOUT_MS, _PAGE_FETCH_SCRIPT, _page_fetch

        page = AsyncMock()
        page.evaluate.return_value = {"status": 200, "text": "ok"}

        result = await _page_fetch(page, "/data", "GET", {}, None)

        self.assertEqual(result, {"status": 200, "text": "ok"})
        self.assertEqual(page.evaluate.await_args.args[1][-1], DATA_REQUEST_TIMEOUT_MS)
        self.assertIn("AbortSignal.timeout(timeoutMs)", _PAGE_FETCH_SCRIPT)


class AuthenticationOutcomeTest(unittest.IsolatedAsyncioTestCase):
    async def test_slow_login_is_not_reported_as_invalid_credentials(self):
        from main import InitiateRequest, initiate

        field = AsyncMock()
        page = AsyncMock()
        context = AsyncMock()
        context.new_page.return_value = page
        browser = AsyncMock()
        browser.new_context.return_value = context
        playwright = AsyncMock()
        playwright.chromium.launch.return_value = browser
        manager = MagicMock()
        manager.start = AsyncMock(return_value=playwright)

        with (
            patch("main.async_playwright", return_value=manager),
            patch("main._cleanup_expired", new=AsyncMock()),
            patch("main._attach_client_id_capture", return_value={"value": None}),
            patch("main._first_visible", new=AsyncMock(side_effect=[field, field, field])),
            patch("main._dismiss_cookie_consent", new=AsyncMock()),
            patch("main._wait_for_login_outcome", new=AsyncMock(return_value="timeout")),
            patch("main._close_resources", new=AsyncMock()),
        ):
            with self.assertRaises(HTTPException) as raised:
                await initiate(InitiateRequest(login="user", password="secret"))

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")


class TransactionParsingTest(unittest.IsolatedAsyncioTestCase):
    async def test_securities_accounts_never_fall_back_to_the_cash_api(self):
        from main import _transactions_for_account

        cash_fetch = AsyncMock(return_value=[{"date": "2026-01-01"}])
        with patch("main._fetch_transactions", new=cash_fetch):
            for account_type in ("PEA", "COMPTE_TITRES"):
                with self.subTest(account_type=account_type):
                    transactions = await _transactions_for_account(
                        object(),
                        "api-key",
                        {"webId": "account-id", "type": account_type},
                        [],
                    )
                    self.assertEqual(transactions, [])

        cash_fetch.assert_not_awaited()

    async def test_cash_accounts_use_the_transaction_api(self):
        from main import _transactions_for_account

        expected = [{"date": "2026-01-01"}]
        cash_fetch = AsyncMock(return_value=expected)
        with patch("main._fetch_transactions", new=cash_fetch):
            transactions = await _transactions_for_account(
                object(),
                "api-key",
                {"webId": "account-id", "type": "CHECKING"},
                [],
            )

        self.assertEqual(transactions, expected)
        cash_fetch.assert_awaited_once_with(ANY, "api-key", "account-id", "CHECKING")

    async def test_transaction_date_is_accepted_when_booking_date_is_absent(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"transactionDate":"2026-08-24T12:34:56Z",'
                '"amount":{"value":"42.50"},'
                '"label":{"simplifiedLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(transactions[0]["date"], "2026-08-24")
        self.assertEqual(str(transactions[0]["amount"]), "42.50")

    async def test_booking_date_takes_precedence_over_transaction_date(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"bookingDate":"2026-08-23",'
                '"transactionDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(transactions[0]["date"], "2026-08-23")

    async def test_cash_account_request_matches_the_official_frontend_query(self):
        # The list behind "Voir toutes les transactions" is the one the page already
        # holds; this single request is what decides how much history is reachable.
        from main import _fetch_transactions

        page_fetch = AsyncMock(return_value={"status": 200, "text": "[]"})
        with patch("main._page_fetch", new=page_fetch):
            await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        requested_url = page_fetch.await_args.args[1]
        self.assertIn("/fto-transaction-api/v1/accounts/account-id/transactions", requested_url)
        self.assertIn("transactionType=CAV", requested_url)
        self.assertNotIn("PENDING", requested_url)
        self.assertIn("metadata=true", requested_url)

    async def test_investment_account_request_omits_the_cash_product_filter(self):
        # CAV means "compte a vue". Asking a PEA for its cash-account entries filters
        # its own ledger away, so the product filter is not sent for securities.
        from main import _fetch_transactions

        for account_type in ("PEA", "COMPTE_TITRES"):
            with self.subTest(account_type=account_type):
                page_fetch = AsyncMock(return_value={"status": 200, "text": "[]"})
                with patch("main._page_fetch", new=page_fetch):
                    await _fetch_transactions(object(), "api-key", "account-id", account_type)

                requested_url = page_fetch.await_args.args[1]
                self.assertNotIn("transactionType", requested_url)
                self.assertIn("metadata=true", requested_url)

    async def test_structured_operation_type_is_captured(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"id":"tx-1","type":"COUPON","bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(
                object(), "api-key", "account-id", "PEA")

        self.assertEqual(transactions[0]["type"], "COUPON")

    async def test_unusable_operation_type_degrades_instead_of_failing(self):
        # The type classifies an entry; it is not the key the import is keyed on.
        # An unexpected shape must never cost the user a sync.
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"id":"tx-1","type":{"code":"X"},"bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(
                object(), "api-key", "account-id", "PEA")

        self.assertIsNone(transactions[0]["type"])

    async def test_stable_transaction_id_is_exposed_as_external_id(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"id":"tx-1","bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(transactions[0]["externalId"], "tx-1")

    async def test_numeric_transaction_id_is_normalized_to_a_string(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"id":42,"bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(transactions[0]["externalId"], "42")

    async def test_missing_transaction_id_degrades_to_none(self):
        # A response shape without ids must still import: the backend falls back
        # to the rolling-window strategy rather than failing the whole snapshot.
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            transactions = await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertIsNone(transactions[0]["externalId"])

    async def test_structured_transaction_id_is_invalid_data(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"id":{"value":"tx-1"},"bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_blank_transaction_id_is_invalid_data(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": (
                '[{"id":"   ","bookingDate":"2026-08-24",'
                '"amount":{"value":"1"},'
                '"label":{"originalLabel":"Anonymized operation"},'
                '"metadata":{}}]'
            ),
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_non_object_transaction_is_invalid_data(self):
        from main import _fetch_transactions

        response = {"status": 200, "text": '["changed-shape"]'}
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")

    async def test_changed_nested_transaction_shape_is_invalid_data(self):
        from main import _fetch_transactions

        response = {
            "status": 200,
            "text": '[{"bookingDate":"2026-08-24","amount":[],"label":{}}]',
        }
        with patch("main._page_fetch", new=AsyncMock(return_value=response)):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_transactions(object(), "api-key", "account-id", "CHECKING")

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "INVALID_DATA")


class InvestorProfileGateTest(unittest.IsolatedAsyncioTestCase):
    def test_marker_detection_tolerates_fragmented_anonymized_html(self):
        from main import _investor_profile_gate_in_html

        self.assertTrue(
            _investor_profile_gate_in_html("<h1>Créer votre profil <span>investisseur</span></h1>")
        )
        self.assertFalse(_investor_profile_gate_in_html("<main>Portefeuille disponible</main>"))

    async def test_blocking_gate_returns_an_actionable_error_code(self):
        from main import _fetch_positions

        gate = "<main><h1>Créer votre profil investisseur</h1></main>"
        page = AsyncMock()
        with (
            patch("main._fetch_via_iframe", new=AsyncMock(return_value=gate)),
            patch("main._load_legacy_page", new=AsyncMock(return_value=gate)),
        ):
            with self.assertRaises(HTTPException) as raised:
                await _fetch_positions(page, "pea", "a" * 32)

        self.assertEqual(raised.exception.status_code, 409)
        self.assertEqual(raised.exception.detail, "INVESTOR_PROFILE_REQUIRED")

class AccountNameTest(unittest.TestCase):
    def test_prefers_type_label_and_account_number(self):
        from main import _account_name

        name = _account_name({
            "typeLabel": "PEA",
            "accountNumber": "XXXXXX000042",
            "label": "SYNTHETIC HOLDER",
        })
        self.assertEqual(name, "PEA XXXXXX000042")

    def test_falls_back_to_holder_label_when_type_info_is_missing(self):
        from main import _account_name

        name = _account_name({"typeLabel": None, "accountNumber": None, "label": "SYNTHETIC HOLDER"})
        self.assertEqual(name, "SYNTHETIC HOLDER")


class LegacyAccountIdsTest(unittest.TestCase):
    """The legacy site keys accounts by its own id, which the modern
    Equipment API never exposes -- passing an Equipment id as `ca` renders
    the portfolio page with no holdings instead of failing, so getting this
    mapping right is what makes positions appear at all.
    """

    HOME = (
        '<div class="pea compte">'
        '<a href="/fr/prive/mes-comptes/pea/situation/?ca=' + "a" * 32 + '" rel="x">PEA</a></div>'
        '<div class="ppe compte">'
        '<a href="/fr/prive/mes-comptes/ppe/situation/?ca=' + "b" * 32 + '">PEA PME</a></div>'
        '<div class="cto compte">'
        '<a href="/fr/prive/mes-comptes/compte-titres-pea/situation/?ca=' + "c" * 32 + '">CTO</a></div>'
        '<a href="/fr/prive/mes-comptes/compte-courant/consulter-situation/?ca=' + "d" * 32 + '">CC</a>'
    )

    def test_maps_every_segment_to_its_legacy_id(self):
        ids = parse_legacy_account_ids(self.HOME)
        self.assertEqual(ids["pea"], "a" * 32)
        self.assertEqual(ids["ppe"], "b" * 32)
        self.assertEqual(ids["compte-titres-pea"], "c" * 32)
        # `consulter-situation` (cash accounts) is matched too.
        self.assertEqual(ids["compte-courant"], "d" * 32)

    def test_first_link_per_segment_wins(self):
        html = self.HOME + '<a href="/fr/prive/mes-comptes/pea/situation/?ca=' + "e" * 32 + '">dup</a>'
        self.assertEqual(parse_legacy_account_ids(html)["pea"], "a" * 32)

    def test_an_equipment_style_id_is_never_mistaken_for_a_legacy_one(self):
        # Equipment ids are 22-char base64-ish, not 32-hex; a link carrying
        # one must not be picked up as a legacy id.
        html = '<a href="/fr/prive/mes-comptes/pea/situation/?ca=Ab1Cd2Ef3Gh4Ij5Kl6Mn7O">x</a>'
        self.assertEqual(parse_legacy_account_ids(html), {})

    def test_no_links_yields_an_empty_mapping(self):
        self.assertEqual(parse_legacy_account_ids("<html><body>nothing</body></html>"), {})


class PortfolioSummaryTest(unittest.TestCase):
    """The page's own valuation summary is what makes an empty account
    distinguishable from a table that failed to render -- both parse to
    zero rows, but only one has a zero securities total.
    """

    def _page(self, securities, cash, total):
        return (
            "<table>"
            f"<tr><td class='txt'>Évaluation Titres</td><td class='numb'>{securities}</td></tr>"
            "<tr><td class='txt'>dont +/- values latentes au comptant</td>"
            "<td class='numb'>0,00</td></tr>"
            f"<tr><td class='txt'>Solde espèces EUR</td><td class='numb'>{cash}</td></tr>"
            f"<tr><td class='txt'><strong>Valorisation totale</strong></td>"
            f"<td class='numb gras'>{total}</td></tr>"
            "</table>"
        )

    def test_reads_securities_cash_and_total(self):
        summary = parse_portfolio_summary(self._page("12 500,00", "1 234,56", "13 734,56"))
        self.assertEqual(str(summary["securitiesEur"]), "12500.00")
        self.assertEqual(str(summary["cashEur"]), "1234.56")
        self.assertEqual(str(summary["totalEur"]), "13734.56")

    def test_an_empty_account_reports_zero_securities_not_a_missing_summary(self):
        summary = parse_portfolio_summary(self._page("0,00", "250,00", "250,00"))
        self.assertEqual(str(summary["securitiesEur"]), "0.00")
        self.assertEqual(str(summary["cashEur"]), "250.00")

    def test_a_page_without_the_summary_yields_nones(self):
        # A login form / interstitial / SPA shell must not look like an
        # account worth zero -- the caller fails closed on these.
        summary = parse_portfolio_summary("<html><body>nothing here</body></html>")
        self.assertIsNone(summary["securitiesEur"])
        self.assertIsNone(summary["cashEur"])

    def test_labels_match_without_accents_and_ignore_trailing_text(self):
        html = ("<table><tr><td>EVALUATION TITRES au 26/07</td><td>1 000,00</td></tr>"
                "<tr><td>Solde especes EUR</td><td>2,50</td></tr></table>")
        summary = parse_portfolio_summary(html)
        self.assertEqual(str(summary["securitiesEur"]), "1000.00")
        self.assertEqual(str(summary["cashEur"]), "2.50")

    def test_the_wait_selector_targets_the_block_the_summary_is_parsed_from(self):
        # The fetch waits on PORTFOLIO_SUMMARY_SELECTOR to decide the page has
        # rendered, then parses the summary out of it. If the two ever drift
        # apart the fetch would wait on one element and read another -- so the
        # selector must be an id that really wraps the summary rows.
        from main import PORTFOLIO_SUMMARY_SELECTOR

        page = (
            '<div class="block" id="valorisation_compte">'
            + self._page("12 500,00", "1 234,56", "13 734,56")
            + "</div>"
        )
        self.assertEqual(PORTFOLIO_SUMMARY_SELECTOR, "#valorisation_compte")
        self.assertEqual(str(parse_portfolio_summary(page)["securitiesEur"]), "12500.00")

    def test_portfolio_snapshot_uses_one_internally_consistent_total(self):
        from main import _portfolio_snapshot

        page = PORTFOLIO_POSITIONS_FIXTURE + self._page(
            "10 500,00", "500,00", "11 000,00"
        )

        snapshot = _portfolio_snapshot(page)

        self.assertIsNotNone(snapshot)
        self.assertEqual(str(snapshot["securitiesEur"]), "10500.00")
        self.assertEqual(str(snapshot["cashEur"]), "500.00")
        self.assertEqual(str(snapshot["totalEur"]), "11000.00")

    def test_portfolio_snapshot_rejects_an_inconsistent_total(self):
        from main import _portfolio_snapshot

        page = PORTFOLIO_POSITIONS_FIXTURE + self._page(
            "10 500,00", "500,00", "12 000,00"
        )

        self.assertIsNone(_portfolio_snapshot(page))

    def test_the_latent_gains_row_is_never_mistaken_for_the_securities_total(self):
        summary = parse_portfolio_summary(self._page("12 500,00", "1 234,56", "13 734,56"))
        self.assertNotEqual(str(summary["securitiesEur"]), "0.00")


class NbspRepresentationTest(unittest.TestCase):
    """The same page arrives either as raw HTTP text (literal U+00A0) or as
    browser-serialised DOM (`&nbsp;`). Both must parse identically -- a
    pattern that spells only one of them fails silently on the other, which
    is exactly how a page full of holdings once read as an empty account.
    """

    ROW = (
        '<tr name="FTN0001princip" class="l1">'
        '<td class="txt"><b class="libelle_valeur_tableau">TEST ONE (T1)</b></td>'
        '<td class="numb"><b>12,50<span title="x"><b>V</b></span></b></td>'
        '<td class="numb"><b>1{sep}000</b></td>'
        '<td class="numb"></td>'
        '<td class="numb">10,000</td>'
        '<td class="numb" id="idValorisation_0_tab__categorie_0">12{sep}500,00</td>'
        '<td class="numb">+2{sep}500,00&nbsp;<span class="augmente ">+25,00%</span></td>'
        "</tr>"
    )
    SUMMARY = (
        "<table>"
        '<tr><td class="txt">Évaluation Titres</td><td class="numb">12{sep}500,00</td></tr>'
        '<tr><td class="txt">Solde espèces EUR</td><td class="numb">1{sep}234,56</td></tr>'
        "</table>"
    )

    def _page(self, sep):
        return (self.ROW + self.SUMMARY).replace("{sep}", sep)

    def test_entity_and_literal_nbsp_parse_identically(self):
        entity = self._page("&nbsp;")
        literal = self._page("\xa0")
        self.assertEqual(
            parse_portfolio_positions(entity), parse_portfolio_positions(literal)
        )
        self.assertEqual(
            parse_portfolio_summary(entity), parse_portfolio_summary(literal)
        )

    def test_thousands_separated_values_survive_the_entity_form(self):
        positions = parse_portfolio_positions(self._page("&nbsp;"))
        self.assertEqual(len(positions), 1)
        self.assertEqual(str(positions[0]["quantity"]), "1000")
        self.assertEqual(str(positions[0]["currentValueEur"]), "12500.00")
        summary = parse_portfolio_summary(self._page("&nbsp;"))
        self.assertEqual(str(summary["securitiesEur"]), "12500.00")
        self.assertEqual(str(summary["cashEur"]), "1234.56")


if __name__ == "__main__":
    unittest.main()
