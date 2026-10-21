"""
Is a human-verification challenge (hCaptcha) on screen right now?

Revolut's sign-in page ALWAYS carries two hCaptcha iframes, even when nothing is asked of the
user: the invisible-checkbox frame (`display: none`, 0x0) and the challenge frame parked at
`top: -9999px` under `visibility: hidden`. Their presence says nothing. A challenge counts only
once one of those frames is actually shown to the user: sized, inside its viewport, and neither
the frame nor any ancestor hidden, `display: none` or fully transparent (a challenge fades in).

Why the previous `iframe[src*='hcaptcha.com']:visible` locator missed it:
  * it was evaluated once, before the passcode was typed, and never again -- a challenge that
    appears after the passcode is submitted was never looked for;
  * it saw only the main document's own iframes, matched on a substring of the URL, and counted
    `opacity: 0` as visible, so it could not tell a fading-in challenge from a parked one.

This check walks the browser's frame list (so late-inserted and nested frames are found), matches
the frame's *host* (`hcaptcha.com` and subdomains, not a substring of the URL), and for each match
climbs the frame chain: a frame is shown only if its owner element is shown in every document up
to the page. It is cheap enough to poll every few seconds for the whole login.
"""

# Evaluated on an <iframe> element, in the document that owns it.
_IS_SHOWN = """
(frame, minSide) => {
    // The part of the frame that can reach the screen: its own box, cut down by every ancestor
    // that clips (overflow other than visible) and by the viewport.
    let r = frame.getBoundingClientRect();
    let left = r.left, top = r.top, right = r.right, bottom = r.bottom;
    for (let el = frame; el; el = el.parentElement) {
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) === 0) return false;
        if (el !== frame && (s.overflowX !== 'visible' || s.overflowY !== 'visible')) {
            const c = el.getBoundingClientRect();
            if (s.overflowX !== 'visible') { left = Math.max(left, c.left); right = Math.min(right, c.right); }
            if (s.overflowY !== 'visible') { top = Math.max(top, c.top); bottom = Math.min(bottom, c.bottom); }
        }
    }
    left = Math.max(left, 0); top = Math.max(top, 0);
    right = Math.min(right, window.innerWidth); bottom = Math.min(bottom, window.innerHeight);
    return right - left >= minSide && bottom - top >= minSide;
}
"""

# hCaptcha's smallest visible widget (the compact checkbox) is far larger than this; the bound
# only rules out 1x1 tracking frames.
_MIN_SIDE_PX = 50


def hostname(url: str) -> str:
    return url.split("://", 1)[-1].split("/", 1)[0].split("?", 1)[0].split(":", 1)[0].lower()


def _is_hcaptcha_url(url: str) -> bool:
    host = hostname(url)
    return host == "hcaptcha.com" or host.endswith(".hcaptcha.com")


async def _shown_through_the_page(frame) -> bool:
    while frame.parent_frame is not None:
        try:
            owner = await frame.frame_element()
            if not await owner.evaluate(_IS_SHOWN, _MIN_SIDE_PX):
                return False
        except Exception:  # noqa: BLE001 - the frame navigated or detached while we looked
            return False
        frame = frame.parent_frame
    return True


async def visible(page) -> bool:
    """True while an hCaptcha frame is shown to the user, wherever it sits in the page."""
    for frame in page.frames:
        if _is_hcaptcha_url(frame.url) and await _shown_through_the_page(frame):
            return True
    return False


async def describe(page) -> str:
    """One log-safe line about the page's hCaptcha frames, for a login that went nowhere: a missed
    detection then shows the frames' geometry instead of leaving the cause to a live probe."""
    parts = []
    for frame in page.frames:
        if not _is_hcaptcha_url(frame.url):
            continue
        kind = frame.url.split("#frame=", 1)[-1].split("&", 1)[0][:20] if "#frame=" in frame.url else "?"
        try:
            box = await (await frame.frame_element()).bounding_box()
        except Exception:  # noqa: BLE001
            box = None
        size = f"{box['width']:.0f}x{box['height']:.0f}@{box['x']:.0f},{box['y']:.0f}" if box else "no-box"
        parts.append(f"{kind}:{size}:{'shown' if await _shown_through_the_page(frame) else 'hidden'}")
    return ", ".join(parts) or "none"
