"""
Per-member state kept NEXT TO the Firefox profile directory, never inside it:

  <PROFILES_ROOT>/<key>/                    the Firefox profile (the live session)
  <PROFILES_ROOT>/<key>.fingerprint.json    the pinned browser identity
  <PROFILES_ROOT>/<key>.login-failed        date of the last failed automated login

Why beside the profile rather than in it: `_has_profile` (profile dir not empty) must keep
meaning "there is a browser session to reuse", and a wedged profile is quarantined by moving
the whole directory -- the pacing guard must survive that, and the replacement profile should
keep the same device identity instead of showing up as yet another browser.

Pure file logic, no browser. Callers hold the member's lock (main.py `_member_lock`).
"""

import json
import logging
import os
import random
from dataclasses import asdict
from datetime import date
from typing import Any, Dict

from browserforge.fingerprints import (
    Fingerprint,
    NavigatorFingerprint,
    Screen,
    ScreenFingerprint,
    VideoCard,
)
from camoufox.fingerprints import generate_fingerprint
from camoufox.webgl import sample_webgl

from logsafe import safe

log = logging.getLogger("revolut-auth")

OS = "linux"
LOCALE = "fr-FR"
# Camoufox's geoip option (always on in the sidecar) overrides this with the egress IP's
# timezone -- Europe/Paris on the home connection. It is the declared default for a launch
# without geoip, so the identity never silently falls back to the host's timezone.
TIMEZONE = "Europe/Paris"
# One fixed screen. entrypoint.sh starts Xvfb with the same geometry, so what the page is told
# about the screen is also what the display really is.
SCREEN = (1600, 900)

_GENERATION_ATTEMPTS = 20


def _write_atomically(path: str, text: str) -> None:
    """Replace, never truncate-then-write: a crash mid-write cannot leave a corrupt file."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


# ─── Pinned browser identity ──────────────────────────────────────────────────

def _generate_identity() -> Dict[str, Any]:
    """Everything Camoufox would otherwise redraw on each launch, drawn once.

    Handing Camoufox a `fingerprint=` pins only the BrowserForge part (navigator, screen,
    headers). Camoufox still draws the WebGL vendor/renderer, the canvas anti-aliasing offset,
    the font-spacing seed and `history.length` on every launch, so those are saved too (see
    `_to_launch_kwargs`).
    """
    constraint = Screen(min_width=SCREEN[0], max_width=SCREEN[0], min_height=SCREEN[1], max_height=SCREEN[1])
    for _ in range(_GENERATION_ATTEMPTS):
        try:
            fingerprint = generate_fingerprint(screen=constraint, strict=True, os=OS)
        except ValueError:  # BrowserForge could not satisfy the constraint for this sample
            continue
        if (fingerprint.screen.width, fingerprint.screen.height) == SCREEN:
            break
    else:
        raise RuntimeError(f"could not generate a {SCREEN[0]}x{SCREEN[1]} {OS} fingerprint")
    webgl = sample_webgl("lin")
    return {
        "fingerprint": asdict(fingerprint),
        "webgl": [webgl["webGl:vendor"], webgl["webGl:renderer"]],
        "canvasAaOffset": random.randint(-50, 50),  # nosec - a device trait, not a secret
        "fontSpacingSeed": random.randint(0, 1_073_741_823),  # nosec
        "historyLength": random.randint(1, 5),  # nosec
    }


def _to_launch_kwargs(identity: Dict[str, Any]) -> Dict[str, Any]:
    """AsyncCamoufox keyword arguments for a stored identity.

    Raises KeyError/TypeError/ValueError/AttributeError on anything that is not a valid identity
    for the pinned OS and screen; `launch_kwargs` then regenerates.
    """
    raw = identity["fingerprint"]
    fingerprint = Fingerprint(**{
        **raw,
        "screen": ScreenFingerprint(**raw["screen"]),
        "navigator": NavigatorFingerprint(**raw["navigator"]),
        "videoCard": VideoCard(**raw["videoCard"]) if raw.get("videoCard") else None,
    })
    if (fingerprint.screen.width, fingerprint.screen.height) != SCREEN:
        raise ValueError("stored fingerprint has another screen size")
    if "Firefox/" not in fingerprint.navigator.userAgent or not fingerprint.navigator.platform.startswith("Linux"):
        raise ValueError("stored fingerprint is not a Linux Firefox one")
    vendor, renderer = identity["webgl"]
    sample_webgl("lin", vendor, renderer)  # ValueError when this pair no longer exists for Linux
    return {
        "os": OS,
        "locale": LOCALE,
        "fingerprint": fingerprint,
        "webgl_config": (vendor, renderer),
        # Camoufox applies these with set_into, which never overrides a key already present.
        "config": {
            "timezone": TIMEZONE,
            "canvas:aaOffset": int(identity["canvasAaOffset"]),
            "fonts:spacing_seed": int(identity["fontSpacingSeed"]),
            "window.history.length": int(identity["historyLength"]),
        },
        # We hand Camoufox a fingerprint and pinned config on purpose: it warns about both.
        "i_know_what_im_doing": True,
    }


def launch_kwargs(path: str) -> Dict[str, Any]:
    """AsyncCamoufox keyword arguments pinning the member's browser identity.

    The identity is generated on first use and saved at `path`. A missing, unreadable or invalid
    file is not an error: a new identity replaces it. A fresh dict is returned on every call
    (Camoufox mutates the `config` it is given).
    """
    try:
        with open(path, encoding="utf-8") as f:
            return _to_launch_kwargs(json.load(f))
    except FileNotFoundError:
        pass
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        log.warning("browser identity %s is unusable (%s) -- generating a new one", safe(path), exc)
    identity = _generate_identity()
    try:
        _write_atomically(path, json.dumps(identity))
    except OSError as exc:
        log.warning("could not save the browser identity to %s (%s) -- it lasts for this launch only", safe(path), exc)
    return _to_launch_kwargs(identity)


# ─── Login pacing ─────────────────────────────────────────────────────────────

def login_failed_on(path: str, day: date) -> bool:
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip() == day.isoformat()
    except (OSError, ValueError):  # missing, unreadable or garbage: no known failure
        return False


def record_login_failure(path: str, day: date) -> None:
    try:
        _write_atomically(path, day.isoformat())
    except OSError as exc:
        log.warning("could not record the failed login in %s: %s", safe(path), exc)


def clear_login_failure(path: str) -> None:
    try:
        os.remove(path)
    except FileNotFoundError:
        pass
    except OSError as exc:
        log.warning("could not clear the failed-login marker %s: %s", safe(path), exc)
