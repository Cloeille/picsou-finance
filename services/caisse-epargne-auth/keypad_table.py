"""Virtual keypad identification: fail closed, never guess a digit.

The login page shows 10 keys, each an image shipped as a CSS `data:` URI, in a
layout that changes between connections. A key is identified by the SHA-256 of
its decoded image bytes, looked up in `DIGEST_TO_DIGIT`.

`DIGEST_TO_DIGIT` was captured live on 2026-10-07 (identifier only, no
password). If the bank changes its key images, every digest becomes unknown,
every login is refused with `KEYPAD_CHANGED` and nothing is clicked: recapture
the table then.
"""

import base64
import binascii
import hashlib
import re

# sha256 hex digest of the decoded key image -> the digit it shows.
# Captured live 2026-10-07 (test connection #1, identifier only, no password):
# 10 PNG of 922 bytes each. Digits read on the rendered images.
DIGEST_TO_DIGIT: dict[str, str] = {
    "044bdba5cd865e39d241bf3647ee6f8dc44a28c09bdd422ec9091e3df468ead1": "0",
    "8b0dd926d44e1a2aca0be91fd1953fd1c530f12d1d5ab5abc6dd9ac239daf700": "1",
    "c8a635ea18cacd7ce188398ff2d12396fe573409877badd64a21dec2441325b5": "2",
    "58bc5a9d52bd6ba3167c74895df3fd7eadc22784431214037f92cc6ab5b9d6ee": "3",
    "418640dc681e40e6b4178220365ccdf8210105f8fe4c1137a97d09e3a4c3710b": "4",
    "3d7f5f6b43937a8377c6cc12ce92e7c01dd27c04fcc83f9c46bde109adbd8db9": "5",
    "905ad6961a59688e137c3b6bc794174d50e8b426717316862f52a440219e82a1": "6",
    "767ba9ca6a35956f1abf90b5e4ba9abfe9759bb20ff2d381f4ebd131f16584ca": "7",
    "081a4deaa1b28a2f479439e8068ffa79551fa5ea6765b88315ce7edd5055519d": "8",
    "d42e951ae8047cebaad26bb36105c03bd0b400d03439369e392be8a2460467cd": "9",
}

DIGITS = "0123456789"
KEY_COUNT = 10

_DATA_URI = re.compile(
    r"""url\(\s*(?P<quote>["']?)data:image/[A-Za-z0-9.+-]+;base64,"""
    r"""(?P<data>[A-Za-z0-9+/=]+)(?P=quote)\s*\)"""
)


class KeypadChanged(Exception):
    """The pad cannot be read with certainty. Carries no digest, on purpose."""

    def __init__(self) -> None:
        super().__init__("KEYPAD_CHANGED")


def digest_from_css_background(css: object) -> str | None:
    """SHA-256 hex of the image inside a `background-image: url("data:...")`, else None."""
    if not isinstance(css, str):
        return None
    match = _DATA_URI.search(css)
    if match is None:
        return None
    try:
        raw = base64.b64decode(match.group("data"), validate=True)
    except (binascii.Error, ValueError):
        return None
    if not raw:
        return None
    return hashlib.sha256(raw).hexdigest()


def identify_keys(digests: list[str], table: dict[str, str] | None = None) -> list[str]:
    """The digit shown by each key, in pad order; raises `KeypadChanged` on any doubt.

    All 10 keys must be known and together show each of the 10 digits exactly once.
    """
    lookup = DIGEST_TO_DIGIT if table is None else table
    if len(digests) != KEY_COUNT:
        raise KeypadChanged()
    digits: list[str] = []
    for digest in digests:
        digit = lookup.get(digest) if isinstance(digest, str) else None
        if digit is None:
            raise KeypadChanged()
        digits.append(digit)
    if sorted(digits) != list(DIGITS):
        raise KeypadChanged()
    return digits
