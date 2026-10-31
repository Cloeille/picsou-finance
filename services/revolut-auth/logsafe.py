"""Log-injection guards shared by main.py and member_state.py: a logged value that comes from
outside (a member id, a path built from it, a browser error) must not forge a log line."""

import logging
import re

# C0/C1 controls (CR, LF, ESC, NEL...) and the Unicode line/paragraph separators.
_UNSAFE = re.compile(r"[\x00-\x1f\x7f-\x9f  ]")


class SafeFormatter(logging.Formatter):
    """Escapes line breaks and control characters in the whole formatted record,
    traceback included, so a logged value cannot forge a log line."""

    def format(self, record: logging.LogRecord) -> str:
        return _UNSAFE.sub(
            lambda m: m.group().encode("unicode_escape").decode("ascii"),
            super().format(record),
        )


def safe(value: object, limit: int = 500) -> str:
    """An outside-controlled value made safe for a log line: truncated, line
    breaks and control characters replaced with '?'. SafeFormatter already
    covers the handler; this keeps the call site safe on its own."""
    text = str(value)
    if len(text) > limit:
        text = text[:limit] + "..."
    text = text.replace("\r\n", "?").replace("\n", "?")
    return _UNSAFE.sub("?", text)
