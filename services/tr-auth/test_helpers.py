"""Pure helpers of the Trade Republic sidecar: phone, cookies, claims, URLs."""

import base64
import json
import unittest

import httpx

import main
from tr_fakes import CLAIMS, PROCESS_ID, encode_claims


class PhoneTest(unittest.TestCase):
    def test_french_local_number_gets_the_country_code(self):
        self.assertEqual(main.normalise_phone(" 0612345678 "), "+33612345678")

    def test_international_number_is_kept(self):
        self.assertEqual(main.normalise_phone("+4915123456789"), "+4915123456789")

    def test_separators_are_dropped_so_the_stored_phone_is_plus_and_digits(self):
        self.assertEqual(main.normalise_phone("+33 6 12 34 56 78"), "+33612345678")
        self.assertEqual(main.normalise_phone("06.12.34.56.78"), "+33612345678")
        self.assertEqual(main.normalise_phone("06-12-34-56-78"), "+33612345678")

    def test_mask_keeps_only_the_last_two_digits(self):
        self.assertEqual(main.mask_phone(main.normalise_phone("0612345678")), "****78")
        self.assertEqual(main.mask_phone("+33 6 12 34 56 07"), "****07")
        self.assertEqual(main.mask_phone("+49 151 23456789"), "****89")

    def test_mask_hides_short_values_entirely(self):
        self.assertEqual(main.mask_phone("+3"), "****")


class CookieTest(unittest.TestCase):
    def headers(self):
        return httpx.Headers([
            ("set-cookie", "tr_session=s1; Path=/api; HttpOnly"),
            ("set-cookie", "tr_refresh=r1; Path=/api; Max-Age=3600"),
            ("set-cookie", "tr_device=d=1; Path=/api"),
            ("set-cookie", "gone=; Max-Age=0"),
        ])

    def test_set_cookies_are_parsed_by_hand(self):
        self.assertEqual(main.parse_set_cookies(self.headers()),
                         {"tr_session": "s1", "tr_refresh": "r1", "tr_device": "d=1"})

    def test_cookie_names_never_include_values(self):
        self.assertEqual(main.cookie_names(self.headers()), ["tr_session", "tr_refresh", "tr_device"])


class ClaimsTest(unittest.TestCase):
    def test_base64url_json_with_or_without_padding(self):
        padded = base64.urlsafe_b64encode(json.dumps(CLAIMS).encode()).decode()
        self.assertEqual(main.decode_claims(encode_claims(CLAIMS)), CLAIMS)
        self.assertEqual(main.decode_claims(padded), CLAIMS)

    def test_jwt_shaped_value_uses_the_middle_part(self):
        self.assertEqual(main.decode_claims(encode_claims(CLAIMS, jwt_shaped=True)), CLAIMS)

    def test_garbage_is_refused(self):
        for value in (None, "", "!!!", "a.b.c", encode_claims({})[:-3] + "~", base64.urlsafe_b64encode(b"[1]").decode()):
            with self.subTest(value=value), self.assertRaises(ValueError):
                main.decode_claims(value)


class UrlTest(unittest.TestCase):
    def test_well_formed_values_build_the_tr_paths(self):
        self.assertEqual(main.login_complete_url(PROCESS_ID, "1234"),
                         f"https://api.traderepublic.com/api/v1/auth/web/login/{PROCESS_ID}/1234")
        self.assertEqual(main.poll_url(PROCESS_ID),
                         f"https://api.traderepublic.com/api/v2/auth/web/login/processes/{PROCESS_ID}")

    def test_malicious_values_are_refused(self):
        for value in ("../../api/v1/account", "abc/def", "abc?x=1", "abc#f", "abc%2Fdef", "user@evil", "..", "", "a" * 65):
            with self.subTest(processId=value):
                with self.assertRaises(ValueError):
                    main.login_complete_url(value, "1234")
                with self.assertRaises(ValueError):
                    main.poll_url(value)
        for tan in ("12/4", "1234?x", "%31%32", "123", "abcd", "١٢٣٤", "1234\n", "12345"):
            with self.subTest(tan=tan), self.assertRaises(ValueError):
                main.login_complete_url(PROCESS_ID, tan)


class DeviceInfoTest(unittest.TestCase):
    def test_each_attempt_gets_its_own_device_id(self):
        first, second = main.build_device_info(), main.build_device_info()
        self.assertNotEqual(first, second)
        self.assertRegex(json.loads(base64.b64decode(first))["stableDeviceId"], r"^[0-9a-f]{128}$")

    def test_headers_reuse_the_given_device_info(self):
        info = main.build_device_info()
        self.assertEqual(main.tr_headers("waf", info)["x-tr-device-info"], info)
        self.assertEqual(main.tr_headers("waf", info)["x-aws-waf-token"], "waf")
        self.assertNotIn("x-aws-waf-token", main.tr_headers(None, info))


if __name__ == "__main__":
    unittest.main()
