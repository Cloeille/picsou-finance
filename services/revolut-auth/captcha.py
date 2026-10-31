"""Detect rendered hCaptcha frames, including challenges inside nested frames."""

from urllib.parse import urlsplit


_VISIBLE_RECT = """
frame => {
    const box = frame.getBoundingClientRect();
    if (frame.clientWidth <= 1 || frame.clientHeight <= 1 ||
        box.width <= 1 || box.height <= 1) return null;
    let left = box.left, top = box.top, right = box.right, bottom = box.bottom;
    for (let el = frame; el; el = el.parentElement) {
        const style = getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden' ||
            style.visibility === 'collapse' || Number(style.opacity) === 0) return null;
        if (el !== frame) {
            const clip = el.getBoundingClientRect();
            if (style.overflowX !== 'visible') {
                left = Math.max(left, clip.left);
                right = Math.min(right, clip.right);
            }
            if (style.overflowY !== 'visible') {
                top = Math.max(top, clip.top);
                bottom = Math.min(bottom, clip.bottom);
            }
        }
    }
    left = Math.max(left, 0);
    top = Math.max(top, 0);
    right = Math.min(right, window.innerWidth);
    bottom = Math.min(bottom, window.innerHeight);
    if (right - left <= 1 || bottom - top <= 1) return null;
    return [(left - box.left) / box.width, (top - box.top) / box.height,
            (right - box.left) / box.width, (bottom - box.top) / box.height];
}
"""


def _is_hcaptcha_url(url: str) -> bool:
    try:
        parsed = urlsplit(url)
        host = (parsed.hostname or "").rstrip(".")
        return parsed.scheme in {"http", "https"} and (
            host == "hcaptcha.com" or host.endswith(".hcaptcha.com"))
    except ValueError:
        return False


async def _shown_through_page(frame) -> bool:
    visible_rect = None
    while (parent := frame.parent_frame) is not None:
        owner = None
        try:
            owner = await frame.frame_element()
            portion = await owner.evaluate(_VISIBLE_RECT)
            if portion is None:
                return False
            box = await owner.bounding_box()
            if box is None:
                return False
            rect = (box["x"] + portion[0] * box["width"],
                    box["y"] + portion[1] * box["height"],
                    box["x"] + portion[2] * box["width"],
                    box["y"] + portion[3] * box["height"])
            if visible_rect is None:
                visible_rect = rect
            else:
                visible_rect = (max(visible_rect[0], rect[0]), max(visible_rect[1], rect[1]),
                                min(visible_rect[2], rect[2]), min(visible_rect[3], rect[3]))
            if visible_rect[2] - visible_rect[0] <= 1 or visible_rect[3] - visible_rect[1] <= 1:
                return False
        except Exception:
            if frame.is_detached():
                return False
            raise
        finally:
            if owner is not None:
                await owner.dispose()
        frame = parent
    return visible_rect is not None


async def visible(page) -> bool:
    for frame in page.frames:
        if _is_hcaptcha_url(frame.url) and await _shown_through_page(frame):
            return True
    return False
