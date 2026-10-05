"""Log-injection guards: the safe() helper and the handler's SafeFormatter."""

import logging
import sys
import unittest

import main


def _format(msg, *args, exc_info=None):
    record = logging.LogRecord("tr-auth", logging.WARNING, __file__, 1, msg, args, exc_info)
    return main.SafeFormatter(logging.BASIC_FORMAT).format(record)


class SafeHelperTest(unittest.TestCase):
    def test_replaces_crlf_lf_and_cr(self):
        self.assertEqual(main.safe("a\r\nINFO:tr-auth:forged"), "a?INFO:tr-auth:forged")
        self.assertEqual(main.safe("a\nb\rc"), "a?b?c")

    def test_replaces_unicode_line_breaks(self):
        self.assertEqual(main.safe("a\u0085b c d"), "a?b?c?d")

    def test_replaces_esc_and_other_controls(self):
        self.assertEqual(main.safe("\x1b[2Jx\x00\x7f"), "?[2Jx??")

    def test_keeps_ordinary_text(self):
        self.assertEqual(main.safe("+33 6 •• é"), "+33 6 •• é")

    def test_renders_none_as_text(self):
        self.assertEqual(main.safe(None), "None")

    def test_truncates_long_input(self):
        self.assertEqual(main.safe("x" * 5000), "x" * 500 + "...")


class SafeFormatterTest(unittest.TestCase):
    def test_a_forged_line_stays_on_the_same_line(self):
        out = _format("Initiating TR auth for %s", "**78\nINFO:tr-auth:forged")
        self.assertEqual(out, "WARNING:tr-auth:Initiating TR auth for **78\\nINFO:tr-auth:forged")

    def test_escape_sequences_are_escaped(self):
        self.assertEqual(_format("x %s", "\x1b[31m "), "WARNING:tr-auth:x \\x1b[31m\\u2028")

    def test_traceback_is_kept_but_cannot_break_the_line(self):
        try:
            raise ValueError("bad\nERROR:tr-auth:forged")
        except ValueError:
            out = _format("boom", exc_info=sys.exc_info())
        self.assertNotIn("\n", out)
        self.assertIn("Traceback (most recent call last):", out)
        self.assertIn("ValueError: bad\\nERROR:tr-auth:forged", out)

    def test_handler_installed_on_the_root_logger_uses_it(self):
        formatters = [type(h.formatter) for h in logging.getLogger().handlers]
        self.assertIn(main.SafeFormatter, formatters)


if __name__ == "__main__":
    unittest.main()
