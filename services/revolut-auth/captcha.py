"""Detect rendered hCaptcha frames, including challenges inside nested frames."""

from urllib.parse import urlsplit


_VISIBLE_RECT = """
frame => {
    if (frame.clientWidth <= 1 || frame.clientHeight <= 1) return null;
    for (let el = frame; el; el = el.parentElement) {
        const style = getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden' ||
            style.visibility === 'collapse' || Number(style.opacity) === 0) return null;
    }
    // The browser clips transformed padding boxes along the containing-block chain.
    return new Promise(resolve => {
        const observer = new IntersectionObserver(([entry]) => {
            observer.disconnect();
            const box = entry.boundingClientRect;
            const clip = entry.intersectionRect;
            if (box.width <= 1 || box.height <= 1 ||
                clip.width <= 1 || clip.height <= 1) {
                resolve(null);
                return;
            }
            resolve([(clip.left - box.left) / box.width, (clip.top - box.top) / box.height,
                     (clip.right - box.left) / box.width, (clip.bottom - box.top) / box.height]);
        });
        observer.observe(frame);
    });
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
