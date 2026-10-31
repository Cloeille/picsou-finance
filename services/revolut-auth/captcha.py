"""Detect rendered hCaptcha frames, including challenges inside nested frames."""

from urllib.parse import urlsplit


_VISIBLE_RECT = """
frame => {
    if (frame.clientWidth <= 1 || frame.clientHeight <= 1) return null;
    const visibility = getComputedStyle(frame).visibility;
    if (visibility === 'hidden' || visibility === 'collapse') return null;
    for (let el = frame; el; el = el.parentElement) {
        const style = getComputedStyle(el);
        if (style.display === 'none' || Number(style.opacity) === 0) return null;
    }
    // Observer bounds include borders; content bounds must use the same transformed coordinates.
    const content = frame.getBoxQuads({box: 'content'})[0].getBounds();
    // The browser clips transformed padding boxes along the containing-block chain.
    return new Promise(resolve => {
        const observer = new IntersectionObserver(([entry]) => {
            observer.disconnect();
            const box = entry.boundingClientRect;
            const clip = entry.intersectionRect;
            const left = Math.max(clip.left, content.left);
            const top = Math.max(clip.top, content.top);
            const right = Math.min(clip.right, content.right);
            const bottom = Math.min(clip.bottom, content.bottom);
            if (box.width <= 1 || box.height <= 1 ||
                right - left <= 1 || bottom - top <= 1) {
                resolve(null);
                return;
            }
            resolve([(left - box.left) / box.width, (top - box.top) / box.height,
                     (right - box.left) / box.width, (bottom - box.top) / box.height]);
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
