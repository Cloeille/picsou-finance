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
            // Clip the content quad to the observer's rectangle; their axis-aligned bounds can overlap
            // where the rotated quad itself does not.
            const clip = entry.intersectionRect;
            let polygon = [content.p1, content.p2, content.p3, content.p4].map(point => [point.x, point.y]);
            for (const [axis, bound, sign] of [
                    [0, clip.left, 1], [0, clip.right, -1], [1, clip.top, 1], [1, clip.bottom, -1]]) {
                const kept = [];
                polygon.forEach((from, index) => {
                    const to = polygon[(index + 1) % polygon.length];
                    const fromInside = sign * (from[axis] - bound);
                    const toInside = sign * (to[axis] - bound);
                    if (fromInside >= 0) kept.push(from);
                    if ((fromInside >= 0) !== (toInside >= 0)) {
                        const t = fromInside / (fromInside - toInside);
                        kept.push([from[0] + t * (to[0] - from[0]), from[1] + t * (to[1] - from[1])]);
                    }
                });
                polygon = kept;
            }
            // An empty polygon gives left = Infinity and right = -Infinity, so the size check rejects it.
            const left = Math.min(...polygon.map(point => point[0]));
            const top = Math.min(...polygon.map(point => point[1]));
            const right = Math.max(...polygon.map(point => point[0]));
            const bottom = Math.max(...polygon.map(point => point[1]));
            const box = entry.boundingClientRect;
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
