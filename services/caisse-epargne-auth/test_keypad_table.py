"""Keypad reading: fail closed, never guess a digit.

No digit table any more (see SPEC_KEYPAD_POPUP.md): the module only has to pin a
key's image exactly, or refuse the whole pad.
"""

import base64
import hashlib
import unittest

import keypad_table

DIGITS = "0123456789"


class ParseKeyCssTest(unittest.TestCase):
    def png(self) -> bytes:
        return b"\x89PNG\r\n\x1a\nsynthetic"

    def css(self, raw: bytes, quote: str = '"', mime: str = "image/png") -> str:
        return f"url({quote}data:{mime};base64,{base64.b64encode(raw).decode()}{quote})"

    def test_returns_the_digest_and_the_uri_of_a_png_data_uri(self):
        raw = self.png()
        digest, uri = keypad_table.parse_key_css(self.css(raw))
        self.assertEqual(digest, hashlib.sha256(raw).hexdigest())
        self.assertEqual(uri, f"data:image/png;base64,{base64.b64encode(raw).decode()}")

    def test_accepts_unquoted_and_single_quoted_urls(self):
        raw = self.png()
        for quote in ("", "'", '"'):
            with self.subTest(quote=quote):
                parsed = keypad_table.parse_key_css(self.css(raw, quote))
                self.assertIsNotNone(parsed)
                self.assertEqual(parsed[0], hashlib.sha256(raw).hexdigest())

    def test_a_non_png_data_uri_is_refused(self):
        self.assertIsNone(keypad_table.parse_key_css(self.css(self.png(), mime="image/svg+xml")))

    def test_an_image_over_the_size_cap_is_refused(self):
        over = b"\x89PNG" + b"x" * keypad_table.MAX_KEY_IMAGE_BYTES
        self.assertIsNone(keypad_table.parse_key_css(self.css(over)))
        at_cap = b"\x89PNG" + b"x" * (keypad_table.MAX_KEY_IMAGE_BYTES - 4)
        self.assertIsNotNone(keypad_table.parse_key_css(self.css(at_cap)))

    def test_anything_else_is_none(self):
        for css in (
            "none",
            "",
            None,
            42,
            'url("https://www.caisse-epargne.fr/key.png")',
            'url("data:image/png;base64,@@@not-base64@@@")',
            'url("data:image/png;base64,")',
            "linear-gradient(red, blue)",
        ):
            with self.subTest(css=css):
                self.assertIsNone(keypad_table.parse_key_css(css))

    def test_the_contract_constants(self):
        self.assertEqual(keypad_table.KEY_COUNT, 10)
        self.assertEqual(keypad_table.KEY_COLUMNS, 5)
        self.assertEqual(keypad_table.DIGITS, DIGITS)

    def test_the_error_does_not_carry_any_image(self):
        raw = self.png()
        with self.assertRaises(keypad_table.KeypadChanged) as ctx:
            raise keypad_table.KeypadChanged()
        self.assertEqual(str(ctx.exception), "KEYPAD_CHANGED")
        self.assertNotIn(base64.b64encode(raw).decode(), str(ctx.exception))


if __name__ == "__main__":
    unittest.main()