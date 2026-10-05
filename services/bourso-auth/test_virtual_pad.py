"""The virtual keyboard is the piece a wrong guess makes catastrophic.

BoursoBank counts failed logins and locks the account, so a pad decoded to the
wrong digits does not fail politely -- it burns attempts. These tests run against
the SVGs BoursoBank actually serves (`fixtures.SVG_BY_DIGIT`), so what is checked
is the parsing and the mapping, not the digest table restating itself.
"""

import unittest

from fixtures import SVG_BY_DIGIT
from virtual_pad import (
    VirtualPadError,
    encode_password,
    extract_challenge,
    parse_virtual_pad,
    svg_digest,
)

# Arbitrary three-letter codes standing in for the ones BoursoBank rotates per
# session; index is the digit they encode.
KEYS = ["AAA", "BBB", "CCC", "DDD", "EEE", "FFF", "GGG", "HHH", "III", "JJJ"]

# Deliberately not 0-9: the pad is shuffled, and the bug this module replaces
# assumed DOM order was digit order.
SHUFFLED = [7, 2, 9, 0, 4, 1, 8, 5, 3, 6]


def pad_html(order=SHUFFLED, keys=KEYS, svgs=None) -> str:
    images = svgs or SVG_BY_DIGIT
    return "".join(
        f'<button class="c-vpad__button" type="button" data-matrix-key="{keys[digit]}"'
        f' data-brs-vpad-key><img alt="" src="{images[str(digit)]}"></button>'
        for digit in order
    )


CHALLENGE_SCRIPT = 'jQuery("[data-matrix-random-challenge]").val("kQ3Zx9-challenge")'


class ParseVirtualPadTest(unittest.TestCase):
    def test_decodes_a_shuffled_pad_into_digit_order(self):
        self.assertEqual(parse_virtual_pad(pad_html()), KEYS)

    def test_tolerates_the_space_boursobank_puts_after_base64(self):
        # The served data URI is "data:image/svg+xml;base64, PHN2..." -- the
        # space is BoursoBank's, and it must not read as a different image.
        tightened = {digit: uri.replace("base64, ", "base64,") for digit, uri in SVG_BY_DIGIT.items()}
        self.assertEqual(parse_virtual_pad(pad_html(svgs=tightened)), KEYS)

    def test_an_unknown_image_is_refused_rather_than_guessed(self):
        unknown = dict(SVG_BY_DIGIT)
        unknown["4"] = "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="
        with self.assertRaises(VirtualPadError):
            parse_virtual_pad(pad_html(svgs=unknown))

    def test_a_partial_pad_is_refused(self):
        with self.assertRaises(VirtualPadError) as raised:
            parse_virtual_pad(pad_html(order=[0, 1, 2, 3]))
        self.assertIn("missing digits", str(raised.exception))

    def test_a_repeated_digit_is_refused(self):
        with self.assertRaises(VirtualPadError):
            parse_virtual_pad(pad_html(order=SHUFFLED + [3]))

    def test_a_reused_key_code_is_refused(self):
        collide = list(KEYS)
        collide[8] = collide[2]
        with self.assertRaises(VirtualPadError):
            parse_virtual_pad(pad_html(keys=collide))

    def test_markup_without_a_pad_is_refused(self):
        with self.assertRaises(VirtualPadError):
            parse_virtual_pad("<div>Service momentanément indisponible</div>")

    def test_digests_are_distinct_per_digit(self):
        digests = {svg_digest(uri) for uri in SVG_BY_DIGIT.values()}
        self.assertEqual(len(digests), 10)


class ChallengeTest(unittest.TestCase):
    def test_reads_the_challenge_out_of_the_inline_script(self):
        self.assertEqual(extract_challenge(CHALLENGE_SCRIPT), "kQ3Zx9-challenge")

    def test_falls_back_to_the_attribute_form(self):
        markup = '<div data-matrix-random-challenge="attr-challenge"></div>'
        self.assertEqual(extract_challenge(markup), "attr-challenge")

    def test_a_pad_without_a_challenge_is_refused(self):
        with self.assertRaises(VirtualPadError):
            extract_challenge("<div></div>")


class EncodePasswordTest(unittest.TestCase):
    def test_encodes_each_digit_through_the_pad(self):
        self.assertEqual(encode_password("1234", KEYS), "BBB|CCC|DDD|EEE")

    def test_repeated_digits_repeat_their_key(self):
        self.assertEqual(encode_password("11", KEYS), "BBB|BBB")

    def test_a_non_numeric_password_is_refused_not_silently_shortened(self):
        # Dropping the letter would submit a different, shorter password and
        # spend a login attempt on it.
        for bad in ("12a4", "12 4", "abcd", "1²34"):
            with self.assertRaises(VirtualPadError):
                encode_password(bad, KEYS)

    def test_an_empty_password_is_refused(self):
        with self.assertRaises(VirtualPadError):
            encode_password("", KEYS)


if __name__ == "__main__":
    unittest.main()
