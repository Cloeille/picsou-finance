import unittest

from portfolio_parser import (
    build_positions,
    build_product_info_map,
    is_real_product_id,
    parse_cash_eur,
    parse_raw_positions,
    sanitize_product_info,
    value_pairs_to_dict,
)


class IsRealProductIdTest(unittest.TestCase):
    def test_numeric_string_is_real(self):
        self.assertTrue(is_real_product_id("15690087"))

    def test_int_is_real(self):
        self.assertTrue(is_real_product_id(15690087))

    def test_flatex_cash_pseudo_position_is_not_real(self):
        self.assertFalse(is_real_product_id("FLATEX_EUR"))

    def test_none_is_not_real(self):
        self.assertFalse(is_real_product_id(None))

    def test_null_literal_is_not_real(self):
        self.assertFalse(is_real_product_id("NULL"))


class SanitizeProductInfoTest(unittest.TestCase):
    def test_literal_null_string_becomes_none(self):
        info = {"isin": "NULL", "symbol": "NULL", "name": "Real Name", "closePrice": 10.0}
        self.assertEqual(sanitize_product_info(info), {
            "isin": None, "symbol": None, "name": "Real Name", "closePrice": 10.0,
        })

    def test_blank_string_becomes_none(self):
        info = {"isin": "  ", "symbol": "", "name": "Real Name", "closePrice": None}
        result = sanitize_product_info(info)
        self.assertIsNone(result["isin"])
        self.assertIsNone(result["symbol"])

    def test_null_is_case_insensitive(self):
        info = {"isin": "null", "symbol": "Null", "name": None, "closePrice": None}
        result = sanitize_product_info(info)
        self.assertIsNone(result["isin"])
        self.assertIsNone(result["symbol"])

    def test_real_values_pass_through_unchanged(self):
        info = {"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares Core MSCI World", "closePrice": 38.0}
        self.assertEqual(sanitize_product_info(info), info)

    def test_missing_keys_default_to_none(self):
        result = sanitize_product_info({})
        self.assertIsNone(result["isin"])
        self.assertIsNone(result["symbol"])
        self.assertIsNone(result["name"])
        self.assertIsNone(result["closePrice"])

    def test_literal_null_close_price_becomes_none(self):
        # "NULL" is truthy, so an unsanitized closePrice would win the
        # `closePrice or price` fallback in build_positions and reach Java as a
        # non-numeric node — decoded as 0, pricing the holding at zero.
        info = {"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": "NULL"}
        self.assertIsNone(sanitize_product_info(info)["closePrice"])


class ValuePairsToDictTest(unittest.TestCase):
    def test_flattens_name_value_pairs(self):
        item = {"id": "1", "value": [{"name": "size", "value": 10}, {"name": "price", "value": 42.5}]}
        self.assertEqual(value_pairs_to_dict(item), {"size": 10, "price": 42.5})

    def test_falls_back_to_flat_dict(self):
        item = {"size": 10, "price": 42.5}
        self.assertEqual(value_pairs_to_dict(item), item)

    def test_ignores_non_dict_pairs(self):
        item = {"value": [{"name": "size", "value": 10}, "garbage"]}
        self.assertEqual(value_pairs_to_dict(item), {"size": 10})


class ParseCashEurTest(unittest.TestCase):
    def test_picks_eur_row(self):
        rows = [
            {"value": [{"name": "currencyCode", "value": "USD"}, {"name": "value", "value": 100.0}]},
            {"value": [{"name": "currencyCode", "value": "EUR"}, {"name": "value", "value": 250.5}]},
        ]
        self.assertEqual(parse_cash_eur(rows), 250.5)

    def test_no_eur_row_returns_zero(self):
        rows = [{"value": [{"name": "currencyCode", "value": "USD"}, {"name": "value", "value": 100.0}]}]
        self.assertEqual(parse_cash_eur(rows), 0.0)

    def test_empty_input_returns_zero(self):
        self.assertEqual(parse_cash_eur([]), 0.0)
        self.assertEqual(parse_cash_eur(None), 0.0)


class ParseRawPositionsTest(unittest.TestCase):
    def test_filters_out_zero_size_positions(self):
        rows = [
            {"id": "1", "value": [{"name": "size", "value": 0}, {"name": "price", "value": 10}]},
            {"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]},
        ]
        result = parse_raw_positions(rows)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["productId"], "2")
        self.assertEqual(result[0]["size"], 5.0)
        self.assertEqual(result[0]["price"], 20.0)

    def test_id_falls_back_to_row_level_id(self):
        rows = [{"id": "12345", "value": [{"name": "size", "value": 3}]}]
        result = parse_raw_positions(rows)
        self.assertEqual(result[0]["productId"], "12345")

    def test_empty_input_returns_empty_list(self):
        self.assertEqual(parse_raw_positions([]), [])
        self.assertEqual(parse_raw_positions(None), [])

    def test_flatex_cash_pseudo_position_is_excluded(self):
        rows = [
            {"id": "FLATEX_EUR", "value": [{"name": "size", "value": 250.0}, {"name": "price", "value": 1}]},
            {"id": "2", "value": [{"name": "size", "value": 5}, {"name": "price", "value": 20}]},
        ]
        result = parse_raw_positions(rows)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["productId"], "2")


class BuildPositionsTest(unittest.TestCase):
    def test_merges_resolved_product_info_and_prefers_close_price(self):
        raw = [{"productId": "123", "size": 10.0, "price": 42.0, "breakEvenPrice": 38.0}]
        products = {"123": {
            "isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares Core MSCI World", "closePrice": 45.0,
        }}

        result = build_positions(raw, products)

        self.assertEqual(result, [{
            "isin": "IE00B4L5Y983",
            "symbol": "IWDA",
            "name": "iShares Core MSCI World",
            "quantity": 10.0,
            "buyingPrice": 38.0,
            "currentPrice": 45.0,
        }])

    def test_current_price_falls_back_to_portfolio_row_price_without_close_price(self):
        raw = [{"productId": "123", "size": 10.0, "price": 42.0, "breakEvenPrice": 38.0}]
        products = {"123": {"isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": None}}

        result = build_positions(raw, products)

        self.assertEqual(result[0]["currentPrice"], 42.0)
        self.assertEqual(result[0]["buyingPrice"], 38.0)

    def test_literal_null_close_price_falls_back_to_portfolio_row_price(self):
        # End-to-end over the sanitize step: a "NULL" closePrice must not be treated
        # as a real reference price, otherwise the holding is priced at zero and the
        # account balance is silently understated.
        raw = [{"productId": "123", "size": 10.0, "price": 42.0, "breakEvenPrice": 38.0}]
        products = build_product_info_map({"123": {
            "isin": "IE00B4L5Y983", "symbol": "IWDA", "name": "iShares", "closePrice": "NULL",
        }})

        result = build_positions(raw, products)

        self.assertEqual(result[0]["currentPrice"], 42.0)

    def test_survives_missing_product_info_with_raw_id_labels(self):
        raw = [{"productId": "999", "size": 1.0, "price": 5.0, "breakEvenPrice": 4.5}]

        result = build_positions(raw, {})

        self.assertEqual(result[0]["isin"], None)
        self.assertEqual(result[0]["symbol"], "999")
        self.assertEqual(result[0]["name"], "999")
        self.assertEqual(result[0]["buyingPrice"], 4.5)
        self.assertEqual(result[0]["currentPrice"], 5.0)

    def test_none_product_id_does_not_crash(self):
        raw = [{"productId": None, "size": 1.0, "price": 5.0, "breakEvenPrice": 5.0}]

        result = build_positions(raw, {})

        self.assertEqual(result[0]["symbol"], "None")


class BuildProductInfoMapTest(unittest.TestCase):
    def test_keys_stay_strings_matching_json_object_keys(self):
        data = {"15690087": {"isin": "IE00BGSF1X88", "symbol": "IB01", "name": "iShares Treasury", "closePrice": 121.36}}

        result = build_product_info_map(data)

        self.assertIn("15690087", result)
        self.assertNotIn(15690087, result)

    def test_skips_non_numeric_pseudo_position_ids(self):
        data = {
            "FLATEX_EUR": {"isin": None, "symbol": None, "name": "Cash"},
            "65147": {"isin": "FR0000131104", "symbol": "BNP", "name": "BNP Paribas SA", "closePrice": 111.88},
        }

        result = build_product_info_map(data)

        self.assertNotIn("FLATEX_EUR", result)
        self.assertIn("65147", result)

    def test_empty_or_none_data_returns_empty_map(self):
        self.assertEqual(build_product_info_map({}), {})
        self.assertEqual(build_product_info_map(None), {})

    def test_end_to_end_enrichment_actually_matches(self):
        """Regression test for the real bug found live: _fetch_product_info used to key
        `products` by int(pid) while build_positions looks positions up by the string
        productId parse_raw_positions produces — enrichment silently never matched,
        even though the raw DEGIRO response (reproduced here) was perfectly clean."""
        portfolio_rows = [{
            "id": "15690087",
            "value": [{"name": "size", "value": 290.67}, {"name": "price", "value": 121.36},
                      {"name": "breakEvenPrice", "value": 69.48}],
        }]
        product_info_response = {"15690087": {
            "isin": "IE00BGSF1X88", "symbol": "IB01",
            "name": "iShares $ Treasury Bond 0-1yr UCITS ETF USD A", "closePrice": 121.36,
        }}

        raw_positions = parse_raw_positions(portfolio_rows)
        products = build_product_info_map(product_info_response)
        result = build_positions(raw_positions, products)

        self.assertEqual(result, [{
            "isin": "IE00BGSF1X88",
            "symbol": "IB01",
            "name": "iShares $ Treasury Bond 0-1yr UCITS ETF USD A",
            "quantity": 290.67,
            "buyingPrice": 69.48,
            "currentPrice": 121.36,
        }])


if __name__ == "__main__":
    unittest.main()
