"""Log hygiene shared by every sidecar: no logged value can forge a log line."""

import logging
import re

# C0/C1 controls (CR, LF, ESC, NEL...) and the Unicode line/paragraph separators.
_LOG_UNSAFE = re.compile(r"[\x00-\x1f\x7f-\x9f\u2028\u2029]")


class SafeFormatter(logging.Formatter):
    """Escapes line breaks and control characters in the whole formatted record,
    traceback included, so a logged value cannot forge a log line."""

    def format(self, record: logging.LogRecord) -> str:
        return _LOG_UNSAFE.sub(
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
    return _LOG_UNSAFE.sub("?", text)


def configure_logging(name: str, *, quiet: tuple[str, ...] = ()) -> logging.Logger:
    """INFO logging through SafeFormatter, and the sidecar's own logger.

    `quiet` loggers drop to WARNING: httpx logs every request URL at INFO, and
    some of those URLs carry login parameters in their query string.
    """
    handler = logging.StreamHandler()
    handler.setFormatter(SafeFormatter(logging.BASIC_FORMAT))
    logging.basicConfig(level=logging.INFO, handlers=[handler])
    for noisy in quiet:
        logging.getLogger(noisy).setLevel(logging.WARNING)
    return logging.getLogger(name)
