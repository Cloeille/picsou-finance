"""Decodes BoursoBank's virtual keyboard into a digit -> key-code mapping.

The password is never sent as digits. BoursoBank renders ten buttons, each
carrying a three-letter `data-matrix-key` that rotates per session, and the
password is submitted as those codes joined by "|". Which button is which digit
is *only* recoverable from the button's image: the label is a base64 SVG, not
text, precisely so that a naive scraper cannot read it.

The ten SVGs are fixed, so the mapping is a lookup. This module stores the
SHA-256 of each SVG's base64 payload rather than the payload itself -- the raw
data URIs live in `virtual_pad_fixtures.py`, which the tests use to check this
table against the real bytes instead of against itself.

Kept free of FastAPI and httpx: this is the piece that silently breaks when
BoursoBank reskins its login page, so it has to be testable on its own. It never
guesses -- a pad that does not yield all ten digits raises rather than falling
back to DOM order, which is shuffled and would encode a wrong password.
"""

import hashlib
import re

# SHA-256 of each button SVG's base64 payload, whitespace stripped. Digits are
# strings because that is what indexes a password character.
SVG_DIGEST_TO_DIGIT = {
    "8560081e18568aba02ef3b1f7ac0e8b238cbbd21b70a5e919360ac456d45d506": "0",
    "eadac6d6288cbd61524fd1a3078a19bf555735c7af13a2890e307263c4c7259b": "1",
    "c54018639480788c02708b2d89651627dadf74048e029844f92006e19eadc094": "2",
    "f3022aeced3b8f45f69c1ec001909636984c81b7e5fcdc2bc481668b1e84ae05": "3",
    "3e3d48446781f9f337858e56d01dd9a66c6be697ba34d8f63f48e694f755a480": "4",
    "4b16fb3592febdd9fb794dc52e4d49f5713e9af05486388f3ca259226dcd5cce": "5",
    "9b3afcc0ceb68c70cc697330d8a609900cf330b6aef1fb102f7a1c34cd8bc3d4": "6",
    "9e760193de1b6c5135ebe1bcad7ff65a2aacfc318973ce29ecb23ed2f86d6012": "7",
    "64d87d9a7023788e21591679c1783390011575a189ea82bb36776a096c7ca02c": "8",
    "1b358233ad4eb6b10bf0dadc3404261317a1b78b62f8501b70c646d654ae88f1": "9",
}

# Ordered `.*?` between the two attributes: the button carries a pile of other
# attributes and BoursoBank has moved them around before. `[A-Z]{3}` is the
# observed key shape; anything else means the scheme changed and we should say
# so rather than half-decode.
_BUTTON_RE = re.compile(
    r'<button[^>]*?data-matrix-key="(?P<key>[A-Z]{3})"[^>]*?>'
    r'.*?src="(?P<svg>data:image[^"]*)"',
    re.DOTALL,
)

# The challenge is written by a jQuery `.val(...)` call in an inline script, not
# carried as an attribute. The attribute form is kept as a fallback because it is
# cheap and BoursoBank has shipped both shapes.
_CHALLENGE_SCRIPT_RE = re.compile(
    r'data-matrix-random-challenge\]"\)\.val\("(?P<challenge>.*?)"\)'
)
_CHALLENGE_ATTR_RE = re.compile(r'data-matrix-random-challenge="(?P<challenge>[^"]+)"')

_DATA_URI_PREFIX_RE = re.compile(r"^data:image/[^;]+;base64,", re.IGNORECASE)


class VirtualPadError(Exception):
    """The keyboard could not be decoded; the caller maps this to a stable code."""


def svg_digest(data_uri: str) -> str:
    """SHA-256 of a button image's base64 payload, whitespace-insensitive.

    BoursoBank serves the data URI with a space after `base64,`; strip every
    whitespace character so a formatting change does not read as a new image.
    """
    payload = _DATA_URI_PREFIX_RE.sub("", data_uri.strip())
    return hashlib.sha256("".join(payload.split()).encode()).hexdigest()


def parse_virtual_pad(html: str) -> list[str]:
    """Return the ten key codes indexed by the digit they stand for.

    Raises `VirtualPadError` unless all ten digits resolve to distinct keys --
    a partially decoded pad would silently encode the wrong password, and
    BoursoBank answers a wrong password with a lockout counter.
    """
    by_digit: dict[str, str] = {}
    for match in _BUTTON_RE.finditer(html):
        digit = SVG_DIGEST_TO_DIGIT.get(svg_digest(match.group("svg")))
        if digit is None:
            raise VirtualPadError(
                "Unrecognised virtual-keyboard image -- BoursoBank changed its login page"
            )
        if digit in by_digit:
            raise VirtualPadError(f"Digit {digit} appears twice on the virtual keyboard")
        by_digit[digit] = match.group("key")

    missing = [str(digit) for digit in range(10) if str(digit) not in by_digit]
    if missing:
        raise VirtualPadError(
            f"Virtual keyboard is missing digits {','.join(missing)} "
            f"({len(by_digit)} of 10 buttons decoded)"
        )
    if len(set(by_digit.values())) != 10:
        raise VirtualPadError("Virtual keyboard reused a key code across digits")
    return [by_digit[str(digit)] for digit in range(10)]


def extract_challenge(html: str) -> str:
    """The per-session `matrixRandomChallenge` that must accompany the password."""
    for pattern in (_CHALLENGE_SCRIPT_RE, _CHALLENGE_ATTR_RE):
        match = pattern.search(html)
        if match:
            challenge = match.group("challenge").strip()
            if challenge:
                return challenge
    raise VirtualPadError("Virtual keyboard carried no matrixRandomChallenge")


def encode_password(password: str, keys: list[str]) -> str:
    """Translate a numeric password into the pipe-joined key codes to submit."""
    if not password:
        raise VirtualPadError("Password is empty")
    encoded = []
    for character in password:
        if not character.isdigit() or not character.isascii():
            # BoursoBank passwords are numeric. Anything else cannot be typed on
            # the pad, and dropping it would submit a different, shorter password.
            raise VirtualPadError("BoursoBank passwords are digits only")
        encoded.append(keys[int(character)])
    return "|".join(encoded)
