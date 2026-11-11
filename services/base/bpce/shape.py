"""Masking of bank-supplied values for diagnostic logs (no number ever shows)."""

from __future__ import annotations

import os
from typing import Any


def mask(value: Any, *, cap: int, letters: bool) -> str:
    """One masking rule for every diagnostic log field.

    Every digit, lowercase letter, accent and symbol becomes `9`, and the value is cut
    at `cap` characters first. With `letters`, ASCII capitals are kept (a product code
    such as `PERIC`, a catalogue name such as `LIVRET A`); without, they are masked
    too and only the blanks and the length show. No number ever shows.
    """
    if not isinstance(value, str):
        return "None"
    keep = (lambda c: "A" <= c <= "Z" or c == " ") if letters else (lambda c: c == " ")
    kept = "".join(c if keep(c) else "9" for c in value[:cap])
    return kept.strip() or "None"


def log_details() -> bool:
    """`CE_LOG_PRODUCT_DETAILS=1` adds the letters of a contract id and the bank's
    catalogue name to the diagnostic log of a contract Picsou does not import.

    Off by default: both are free text on a product not seen yet, so a normal run
    never writes them (it logs the codes, whether a pfm id was sent and the length
    shape of the id).
    """
    return os.environ.get("CE_LOG_PRODUCT_DETAILS") == "1"
