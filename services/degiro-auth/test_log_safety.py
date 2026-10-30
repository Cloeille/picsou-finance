"""Log-injection guards: the safe() helper and the handler's SafeFormatter."""

import logging
import sys
import unittest

import main


def _format(msg, *args, exc_info=None):
    record = logging.LogRecord("degiro-auth", logging.INFO, __file__, 1, msg, args, exc_info)
    return main.SafeFormatter(logging.BASIC_FORMAT).format(record)


class SafeHelperTest(unittest.TestCase):
    def test_two_characters_cannot_start_a_new_line(self):
        self.assertEqual(main.safe("\nINFO:degiro-auth:forged"[:2]), "?I")

    def test_replaces_crlf_lf_and_cr(self):
        self.assertEqual(main.safe("a\r\nb\nc\rd"), "a?b?c?d")

    def test_replaces_unicode_line_breaks(self):
        self.assertEqual(main.safe("a\u0085b c d"), "a?b?c?d")

    def test_replaces_esc_and_other_controls(self):
        self.assertEqual(main.safe("\x1b[2Jx\x00\x7f"), "?[2Jx??")

    def test_keeps_ordinary_text(self):
        self.assertEqual(main.safe("jd"), "jd")

    def test_renders_none_as_text(self):
        self.assertEqual(main.safe(None), "None")

    def test_truncates_long_input(self):
        self.assertEqual(main.safe("x" * 5000), "x" * 500 + "...")


class SafeFormatterTest(unittest.TestCase):
    def test_a_forged_line_stays_on_the_same_line(self):
        out = _format("DEGIRO auth initiate for user %s***", "\nI")
        self.assertEqual(out, "INFO:degiro-auth:DEGIRO auth initiate for user \\nI***")

    def test_traceback_is_kept_but_cannot_break_the_line(self):
        try:
            raise ValueError("bad\r\nERROR:degiro-auth:forged")
        except ValueError:
            out = _format("boom", exc_info=sys.exc_info())
        self.assertNotIn("\n", out)
        self.assertNotIn("\r", out)
        self.assertIn("ValueError: bad\\r\\nERROR:degiro-auth:forged", out)

    def test_handler_installed_on_the_root_logger_uses_it(self):
        formatters = [type(h.formatter) for h in logging.getLogger().handlers]
        self.assertIn(main.SafeFormatter, formatters)


if __name__ == "__main__":
    unittest.main()
