"""Detect rendered hCaptcha frames, including challenges inside nested frames."""

from urllib.parse import urlsplit


_VISIBLE_RECT = """
frame => {
    const rendered = () => {
        if (!frame.checkVisibility({opacityProperty: true, visibilityProperty: true})) return false;
        let position = getComputedStyle(frame).position;
        for (let el = frame.parentElement; el; el = el.parentElement) {
            const style = getComputedStyle(el);
            const containingBlock = style.display !== 'contents' && style.display !== 'inline' && (
                ['transform', 'translate', 'rotate', 'scale', 'perspective', 'filter', 'backdropFilter']
                    .some(property => style[property] !== 'none') ||
                /(?:layout|paint|strict|content)/.test(style.contain) ||
                /(?:transform|translate|rotate|scale|perspective|filter|contain)/.test(style.willChange) ||
                style.contentVisibility === 'auto');
            if ((position === 'fixed' && !containingBlock) ||
                (position === 'absolute' && !containingBlock && style.position === 'static')) continue;
            let viewportOverflow = el === document.documentElement;
            if (el === document.body && style.contain === 'none') {
                const rootStyle = getComputedStyle(document.documentElement);
                viewportOverflow = rootStyle.overflowX === 'visible' &&
                    rootStyle.overflowY === 'visible' && rootStyle.contain === 'none';
            }
            // overflow:clip can extend beyond the padding box via overflow-clip-margin.
            const clipX = ['auto', 'scroll', 'hidden'].includes(style.overflowX);
            const clipY = ['auto', 'scroll', 'hidden'].includes(style.overflowY);
            if (!viewportOverflow && style.display !== 'inline' && (clipX || clipY)) {
                const padding = el.getBoxQuads({box: 'padding'})[0];
                // A rotated empty clip has a nonempty axis-aligned observer rectangle.
                if (padding && (
                    (clipX && padding.p1.x === padding.p2.x && padding.p1.y === padding.p2.y) ||
                    (clipY && padding.p1.x === padding.p4.x && padding.p1.y === padding.p4.y))) return false;
            }
            position = style.position;
        }
        return true;
    };
    if (!rendered()) return null;
    return new Promise(resolve => {
        const observer = new IntersectionObserver(([entry]) => {
            observer.disconnect();
            if (!rendered()) {
                resolve(null);
                return;
            }
            const content = frame.getBoxQuads({box: 'content'})[0];
            if (!content) {
                resolve(null);
                return;
            }
            const bounds = content.getBounds();
            const box = entry.boundingClientRect;
            const clip = entry.intersectionRect;
            const left = Math.max(clip.left, bounds.left);
            const top = Math.max(clip.top, bounds.top);
            const right = Math.min(clip.right, bounds.right);
            const bottom = Math.min(clip.bottom, bounds.bottom);
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
