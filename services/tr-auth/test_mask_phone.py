"""mask_phone keeps only the last two digits of the number, whatever its format."""

import unittest

import main


class MaskPhoneTest(unittest.TestCase):
    def test_normalised_international_number(self):
        self.assertEqual(main.mask_phone(main.normalise_phone("0612345678")), "****78")

    def test_number_without_leading_zero_or_plus(self):
        self.assertEqual(main.mask_phone(main.normalise_phone("612345678")), "****78")

    def test_spaces_and_country_code(self):
        self.assertEqual(main.mask_phone("+33 6 12 34 56 07"), "****07")

    def test_other_country(self):
        self.assertEqual(main.mask_phone("+49 151 23456789"), "****89")

    def test_nothing_but_the_last_two_digits_leaks(self):
        masked = main.mask_phone("+33612345678")
        for fragment in ("+33", "612", "3456"):
            self.assertNotIn(fragment, masked)

    def test_separators_and_controls_are_ignored(self):
        self.assertEqual(main.mask_phone("+33 6 12 34 56 7\n8"), "****78")

    def test_fewer_than_two_digits(self):
        for value in ("", "+", "7", "abc"):
            with self.subTest(value=value):
                self.assertEqual(main.mask_phone(value), "****")


if __name__ == "__main__":
    unittest.main()
