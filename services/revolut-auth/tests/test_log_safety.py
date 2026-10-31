"""Log-injection guards: the safe() helper and the handler's SafeFormatter.

Run: python -m unittest discover -s tests   (from services/revolut-auth)
"""

import logging
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


def _format(msg, *args, exc_info=None):
    record = logging.LogRecord("revolut-auth", logging.WARNING, __file__, 1, msg, args, exc_info)
    return main.SafeFormatter(logging.BASIC_FORMAT).format(record)


class SafeHelperTest(unittest.TestCase):
    def test_a_multi_line_browser_error_becomes_one_line(self):
        err = RuntimeError("Page.goto: net::ERR\nCall log:\n  - navigating to \"https://app.revolut.com/\"")
        self.assertEqual(main.safe(err),
                         "Page.goto: net::ERR?Call log:?  - navigating to \"https://app.revolut.com/\"")

    def test_replaces_crlf_and_cr(self):
        self.assertEqual(main.safe("a\r\nb\rc"), "a?b?c")

    def test_replaces_unicode_line_breaks(self):
        self.assertEqual(main.safe("a\u0085b c d"), "a?b?c?d")

    def test_replaces_esc_and_other_controls(self):
        self.assertEqual(main.safe("\x1b[2Jx\x00\x7f"), "?[2Jx??")

    def test_keeps_a_member_id(self):
        self.assertEqual(main.safe("42"), "42")

    def test_renders_none_as_text(self):
        self.assertEqual(main.safe(None), "None")

    def test_truncates_long_input(self):
        self.assertEqual(main.safe("x" * 5000), "x" * 500 + "...")


class SafeFormatterTest(unittest.TestCase):
    def test_a_forged_line_stays_on_the_same_line(self):
        out = _format("member %s: launch failed: %s", "42", "boom\nINFO:revolut-auth:forged")
        self.assertEqual(out, "WARNING:revolut-auth:member 42: launch failed: boom\\nINFO:revolut-auth:forged")

    def test_traceback_is_kept_but_cannot_break_the_line(self):
        try:
            raise ValueError("bad forged")
        except ValueError:
            out = _format("boom", exc_info=sys.exc_info())
        self.assertNotIn("\n", out)
        self.assertNotIn(" ", out)
        self.assertIn("ValueError: bad\\u2028forged", out)

    def test_handler_installed_on_the_root_logger_uses_it(self):
        formatters = [type(h.formatter) for h in logging.getLogger().handlers]
        self.assertIn(main.SafeFormatter, formatters)


if __name__ == "__main__":
    unittest.main()
