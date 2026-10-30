"""Virtual keypad reading: fail closed, never guess a digit.

The login page shows a 10-key pad made of images shipped as CSS `data:` URIs, in
a layout that changes between connections (measured 2026-10-07: stable inside one
transaction, different at the next one). This module only extracts and pins a
key's image: it never maps an image to a digit. The user reads the pad in the
pop-up and clicks his own digits, so nothing here has to recognise anything.
"""

import base64
import binascii
import hashlib
import re

DIGITS = "0123456789"
KEY_COUNT = 10
KEY_COLUMNS = 5
# A key image is a small PNG (22x32, ~920 bytes live on 2026-10-07). Anything
# bigger is not a key image: it is refused instead of being forwarded to the UI.
MAX_KEY_IMAGE_BYTES = 16 * 1024

_DATA_URI = re.compile(
    r"""url\(\s*(?P<quote>["']?)data:image/png;base64,"""
    r"""(?P<data>[A-Za-z0-9+/=]+)(?P=quote)\s*\)"""
)


class KeypadChanged(Exception):
    """The pad cannot be pinned with certainty. Carries no digest, on purpose."""

    def __init__(self) -> None:
        super().__init__("KEYPAD_CHANGED")


def parse_key_css(css: object) -> tuple[str, str] | None:
    """`(sha256 of the decoded bytes, the data URI)` for a key background, else None.

    None means "not a PNG data URI, undecodable, empty, or over the size cap":
    the caller then refuses the whole pad rather than clicking an unknown key.
    """
    if not isinstance(css, str):
        return None
    match = _DATA_URI.search(css)
    if match is None:
        return None
    data = match.group("data")
    try:
        raw = base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError):
        return None
    if not raw or len(raw) > MAX_KEY_IMAGE_BYTES:
        return None
    return hashlib.sha256(raw).hexdigest(), f"data:image/png;base64,{data}"