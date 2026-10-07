"""Keypad identification: fail closed, never guess a digit.

The shipped table was captured live on 2026-10-07; the other tests use a synthetic one.
"""

import base64
import hashlib
import unittest

import keypad_table
from keypad_table import KeypadChanged, identify_keys

DIGITS = "0123456789"


def synthetic_table() -> dict[str, str]:
    return {hashlib.sha256(f"synthetic-key-{d}".encode()).hexdigest(): d for d in DIGITS}


def digest_of(digit: str) -> str:
    return hashlib.sha256(f"synthetic-key-{digit}".encode()).hexdigest()


class IdentifyKeysTest(unittest.TestCase):
    def test_the_shipped_table_maps_ten_sha256_digests_to_the_ten_digits(self):
        # Captured live 2026-10-07 (test connection #1).
        table = keypad_table.DIGEST_TO_DIGIT
        self.assertEqual(len(table), 10)
        self.assertEqual(sorted(table.values()), list(keypad_table.DIGITS))
        for digest in table:
            self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_empty_default_table_refuses_any_pad(self):
        with self.assertRaises(KeypadChanged):
            identify_keys([digest_of(d) for d in DIGITS])

    def test_returns_the_digit_of_each_key_in_pad_order(self):
        layout = list("7203916845")
        self.assertEqual(
            identify_keys([digest_of(d) for d in layout], synthetic_table()), layout
        )

    def test_unknown_digest_is_refused(self):
        digests = [digest_of(d) for d in DIGITS]
        digests[4] = hashlib.sha256(b"not-in-table").hexdigest()
        with self.assertRaises(KeypadChanged):
            identify_keys(digests, synthetic_table())

    def test_wrong_key_count_is_refused(self):
        table = synthetic_table()
        for count in (0, 9, 11):
            with self.subTest(count=count):
                digests = [digest_of(DIGITS[i % 10]) for i in range(count)]
                with self.assertRaises(KeypadChanged):
                    identify_keys(digests, table)

    def test_a_digit_appearing_twice_is_refused(self):
        digests = [digest_of(d) for d in DIGITS]
        digests[9] = digests[0]
        with self.assertRaises(KeypadChanged):
            identify_keys(digests, synthetic_table())

    def test_table_mapping_two_digests_to_one_digit_is_refused(self):
        table = synthetic_table()
        table[hashlib.sha256(b"extra").hexdigest()] = "0"
        digests = [digest_of(d) for d in DIGITS[1:]] + [hashlib.sha256(b"extra").hexdigest()]
        # 9 distinct digits + a second image for "0" is fine: still exactly 0..9.
        self.assertEqual(sorted(identify_keys(digests, table)), sorted(DIGITS))
        digests[0] = hashlib.sha256(b"extra").hexdigest()
        with self.assertRaises(KeypadChanged):
            identify_keys(digests, table)

    def test_table_with_a_non_digit_value_is_refused(self):
        table = synthetic_table()
        first = next(iter(table))
        table[first] = "x"
        with self.assertRaises(KeypadChanged):
            identify_keys([digest_of(d) for d in DIGITS], table)

    def test_the_error_does_not_carry_digests(self):
        digests = [digest_of(d) for d in DIGITS]
        with self.assertRaises(KeypadChanged) as ctx:
            identify_keys(digests, {})
        for digest in digests:
            self.assertNotIn(digest, str(ctx.exception))
            self.assertNotIn(digest, repr(ctx.exception))


class DigestFromCssTest(unittest.TestCase):
    def png(self) -> bytes:
        return b"\x89PNG\r\n\x1a\nsynthetic"

    def test_hashes_the_decoded_bytes_of_a_data_uri(self):
        raw = self.png()
        css = f'url("data:image/png;base64,{base64.b64encode(raw).decode()}")'
        self.assertEqual(
            keypad_table.digest_from_css_background(css), hashlib.sha256(raw).hexdigest()
        )

    def test_accepts_unquoted_and_single_quoted_urls(self):
        raw = self.png()
        b64 = base64.b64encode(raw).decode()
        for css in (f"url(data:image/png;base64,{b64})", f"url('data:image/png;base64,{b64}')"):
            with self.subTest(css=css[:20]):
                self.assertEqual(
                    keypad_table.digest_from_css_background(css),
                    hashlib.sha256(raw).hexdigest(),
                )

    def test_anything_else_is_none(self):
        for css in (
            "none",
            "",
            None,
            'url("https://www.caisse-epargne.fr/key.png")',
            'url("data:image/png;base64,@@@not-base64@@@")',
            'url("data:image/png;base64,")',
            'linear-gradient(red, blue)',
        ):
            with self.subTest(css=css):
                self.assertIsNone(keypad_table.digest_from_css_background(css))


if __name__ == "__main__":
    unittest.main()
