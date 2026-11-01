"""Offline browser fixtures exercise CSS visibility without contacting Revolut or hCaptcha.

One Camoufox browser is launched per class and shared by every test through a single event
loop (`asyncio.Runner`), because Playwright objects belong to the loop that created them.
Each test gets its own context and page. Parametrized checks become one test method each.
"""

import asyncio
import itertools
import re
import unittest

from camoufox import DefaultAddons
from camoufox.async_api import AsyncCamoufox

import captcha
import main


def _case_id(parts):
    return re.sub(r"[^0-9A-Za-z]+", "_", "-".join(str(part) for part in parts)).strip("_") or "empty"


def _stack(*axes):
    """Cartesian product of parameter axes, in the order given.

    Each row is the concatenated arguments of one case, also used to name its test method.
    """
    return [tuple(itertools.chain.from_iterable(row)) for row in itertools.product(*axes)]


def browser_cases(*rows):
    """Mark a check coroutine as a family of tests: one `test_<name>_<case>` method per row."""
    def mark(check):
        check.rows = rows or ((),)
        return check
    return mark


class CaptchaBrowserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = asyncio.Runner()
        cls.addClassCleanup(cls.runner.close)
        # Camoufox bundles uBlock Origin, whose blocking webRequest listener intermittently stalls
        # http(s) requests, sometimes for the rest of the browser's life. A stalled iframe request
        # never reaches Playwright's route handler, so set_content never sees the load event. These
        # offline fixtures serve every request themselves and need no content blocker.
        launcher = AsyncCamoufox(
            headless=True, exclude_addons=[DefaultAddons.UBO],
            firefox_user_prefs={"layout.css.getBoxQuads.enabled": True})
        cls.browser = cls.runner.run(launcher.__aenter__())
        cls.addClassCleanup(lambda: cls.runner.run(launcher.__aexit__(None, None, None)))

    async def _with_page(self, check, args):
        context = await self.browser.new_context(viewport={"width": 800, "height": 600})

        async def serve(route):
            await route.fulfill(content_type="text/html", body="<html><body>Fixture</body></html>")

        await context.route("**/*", serve)
        try:
            await check(self, await context.new_page(), *args)
        finally:
            await context.close()

    @browser_cases(
        ("", True),
        ("display:none", False),
        ("visibility:hidden", False),
        ("opacity:0", False),
        ("opacity:0.1", True),
        ("position:absolute;top:-9999px", False),
        ("position:absolute;left:900px", False),
        ("width:1px;height:1px", False),
    )
    async def challenge_visibility(self, page, style, expected):
        await page.set_content(
            '<iframe src="https://newassets.hcaptcha.com/challenge" '
            f'style="border:0;width:400px;height:300px;{style}"></iframe>')
        self.assertIs(await captcha.visible(page), expected)

    @browser_cases(("opacity:0",), ("visibility:hidden",), ("display:none",),
                   ("width:0;height:0;overflow:hidden",))
    async def hidden_ancestor(self, page, style):
        await page.set_content(
            f'<div style="{style}"><iframe src="https://hcaptcha.com/challenge" '
            'style="width:400px;height:300px"></iframe></div>')
        self.assertFalse(await captcha.visible(page))

    @browser_cases(
        ("visibility:hidden", True),
        ("visibility:collapse", True),
        ("display:none", False),
        ("opacity:0", False),
    )
    async def frame_visibility_overrides_ancestor_visibility(self, page, ancestor_style, expected):
        await page.set_content(
            f'<div style="{ancestor_style}"><iframe src="https://hcaptcha.com/challenge" '
            'style="border:0;width:400px;height:300px;visibility:visible"></iframe></div>')
        self.assertIs(await captcha.visible(page), expected)

    @browser_cases(*_stack(
        [(2, 0, False), (6, 0, True), (12, 10, False), (16, 10, True)],
        [("height",), ("width",)],
        [("none",), ("scale(2)",), ("scale(0.5)",), ("rotate(90deg)",)],
    ))
    async def clipped_frame_requires_visible_content(
            self, page, extent, padding, expected, dimension, transform):
        await page.set_content(
            f'<div style="width:404px;height:304px;{dimension}:{extent}px;overflow:hidden;'
            f'margin:100px;transform-origin:top left;transform:{transform}">'
            '<iframe src="https://hcaptcha.com/challenge" '
            f'style="display:block;width:400px;height:300px;border:2px solid transparent;padding:{padding}px">'
            '</iframe></div>')
        self.assertIs(await captcha.visible(page), expected)

    @browser_cases(
        ("fixed", "", True),
        ("absolute", "", True),
        ("fixed", "position:relative", True),
        ("absolute", "position:relative", False),
        ("fixed", "transform:translate(20px, 20px)", False),
        ("absolute", "transform:translate(20px, 20px)", False),
        ("fixed", "rotate:15deg", False),
        ("absolute", "rotate:15deg", False),
        ("fixed", "contain:layout", False),
        ("absolute", "contain:layout", False),
    )
    async def positioned_challenge_uses_containing_block_clips(
            self, page, position, wrapper_style, expected):
        await page.set_content(
            f'<div style="width:0;height:0;overflow:hidden;{wrapper_style}">'
            '<iframe src="https://hcaptcha.com/challenge" '
            f'style="border:0;width:400px;height:300px;position:{position};top:20px;left:20px">'
            '</iframe></div>')
        self.assertIs(await captcha.visible(page), expected)

    @browser_cases(*_stack(
        [("height", "0", False), ("width", "0", False),
         ("height", "0 0 10px", True), ("width", "0 10px 0 0", True)],
        [("none",), ("scale(2)",), ("rotate(15deg)",)],
    ))
    async def overflow_clips_at_transformed_padding_box(
            self, page, dimension, padding, expected, transform):
        await page.set_content(
            f'<div style="width:400px;height:300px;{dimension}:0;padding:{padding};'
            'overflow:hidden;border:2px solid transparent;margin:100px;'
            f'transform-origin:top left;transform:{transform}">'
            '<iframe src="https://hcaptcha.com/challenge" '
            'style="border:0;width:400px;height:300px"></iframe></div>')
        self.assertIs(await captcha.visible(page), expected)

    @browser_cases(("height",), ("width",))
    async def scaled_subpixel_padding_box_exposes_content(self, page, dimension):
        await page.set_content(
            f'<div style="width:400px;height:300px;{dimension}:0.25px;overflow:hidden;'
            'transform-origin:top left;transform:scale(10)">'
            '<iframe src="https://hcaptcha.com/challenge" '
            'style="display:block;border:0;width:400px;height:300px"></iframe></div>')
        self.assertTrue(await captcha.visible(page))

    @browser_cases(("height", "scale(1, 400)"), ("width", "scale(400, 1)"))
    async def scaled_subpixel_frame_is_visible(self, page, dimension, transform):
        await page.set_content(
            '<iframe src="https://hcaptcha.com/challenge" style="display:block;border:0;'
            f'width:400px;height:300px;{dimension}:0.25px;'
            f'transform-origin:top left;transform:{transform}"></iframe>')
        self.assertTrue(await captcha.visible(page))

    @browser_cases((40, False), (100, True))
    async def rotated_frame_clip_must_overlap_content_quad(self, page, clip, expected):
        # A 200px square rotated by 45 degrees has a 283px axis-aligned box. The wrapper starts at that
        # box's top-left corner, where the quad only begins past x + y = 141px.
        await page.set_content(
            f'<div style="width:{clip}px;height:{clip}px;overflow:hidden;margin:100px">'
            '<iframe src="https://hcaptcha.com/challenge" style="display:block;border:0;'
            'width:200px;height:200px;margin:41.4px 0 0 41.4px;transform:rotate(45deg)"></iframe></div>')
        self.assertIs(await captcha.visible(page), expected)

    @browser_cases(("html",), ("body",))
    async def viewport_overflow_does_not_clip_at_empty_element_box(self, page, element):
        await page.set_content(
            f'<style>{element} {{height:0;overflow:hidden}}</style>'
            '<iframe src="https://hcaptcha.com/challenge" '
            'style="display:block;border:0;width:400px;height:300px"></iframe>')
        self.assertTrue(await captcha.visible(page))

    @browser_cases()
    async def challenge_visibility_tracks_opacity_changes(self, page):
        await page.set_content('<iframe src="https://hcaptcha.com/challenge" '
                               'style="border:0;width:400px;height:300px;opacity:0"></iframe>')
        self.assertFalse(await captcha.visible(page))
        await page.locator('iframe').evaluate("frame => frame.style.opacity = '0.1'")
        self.assertTrue(await captcha.visible(page))
        await page.locator('iframe').evaluate("frame => frame.style.opacity = '0'")
        self.assertFalse(await captcha.visible(page))

    @browser_cases(("frame",), ("frame.parentElement",))
    async def opacity_change_during_geometry_observation(self, page, target):
        await page.set_content('<div><iframe src="https://hcaptcha.com/challenge" '
                               'style="border:0;width:400px;height:300px"></iframe></div>')
        portion = await page.locator('iframe').evaluate(f"""frame => {{
            const portion = ({captcha._VISIBLE_RECT})(frame);
            {target}.style.opacity = '0';
            return portion;
        }}""")
        self.assertIsNone(portion)

    @browser_cases()
    async def nested_challenge(self, page):
        await page.set_content("""<iframe style="width:600px;height:400px" srcdoc='
            <iframe src="https://hcaptcha.com/challenge" style="width:400px;height:300px"></iframe>
            '></iframe>""")
        self.assertTrue(await captcha.visible(page))

    @browser_cases()
    async def nested_challenge_under_transparent_parent_frame(self, page):
        await page.set_content("""<iframe style="width:600px;height:400px;opacity:0" srcdoc='
            <iframe src="https://hcaptcha.com/challenge" style="width:400px;height:300px"></iframe>
            '></iframe>""")
        self.assertFalse(await captcha.visible(page))

    @browser_cases()
    async def challenge_outside_visible_part_of_parent_frame(self, page):
        await page.set_content("""<div style="width:100px;height:400px;overflow:hidden">
            <iframe style="width:600px;height:400px" srcdoc='
                <iframe src="https://hcaptcha.com/challenge"
                    style="margin-left:200px;width:200px;height:200px"></iframe>
            '></iframe></div>""")
        self.assertFalse(await captcha.visible(page))

    @browser_cases()
    async def unrelated_frame_url_containing_hcaptcha(self, page):
        await page.set_content('<iframe src="https://hcaptcha.com.example.test/challenge" '
                              'style="width:400px;height:300px"></iframe>')
        self.assertFalse(await captcha.visible(page))

    @browser_cases()
    async def challenge_inserted_after_first_poll(self, page):
        await page.set_content('<main>Waiting</main>')
        self.assertFalse(await captcha.visible(page))
        async with page.expect_event("framenavigated", predicate=lambda frame: captcha._is_hcaptcha_url(frame.url)):
            await page.evaluate("""() => {
                const frame = document.createElement('iframe');
                frame.src = 'https://hcaptcha.com/challenge';
                frame.style = 'width:400px;height:300px';
                document.body.append(frame);
            }""")
        self.assertTrue(await captcha.visible(page))

    @browser_cases()
    async def login_screen_detects_nested_challenge_before_passcode(self, page):
        await page.set_content("""<input><input><input><input><input><input>
            <iframe style="width:600px;height:400px" srcdoc='
                <iframe src="https://hcaptcha.com/challenge" style="width:400px;height:300px"></iframe>
            '></iframe>""")
        self.assertEqual(await main._wait_for_login_screen(page), "captcha")

    @browser_cases()
    async def login_screen_ignores_parked_challenge_and_accepts_passcode(self, page):
        await page.set_content("""<input><input><input><input><input><input>
            <iframe src="https://hcaptcha.com/challenge"
                style="width:400px;height:300px;opacity:0"></iframe>""")
        self.assertEqual(await main._wait_for_login_screen(page), "passcode")


def _register_tests():
    for name, check in list(vars(CaptchaBrowserTest).items()):
        rows = getattr(check, "rows", None)
        if rows is None:
            continue
        delattr(CaptchaBrowserTest, name)
        seen = set()
        for row in rows:
            test_name = f"test_{name}_{_case_id(row)}" if row else f"test_{name}"
            if test_name in seen:
                raise RuntimeError(f"duplicate test name {test_name}")
            seen.add(test_name)

            def test(self, _check=check, _row=row):
                self.runner.run(self._with_page(_check, _row))

            test.__name__ = test_name
            setattr(CaptchaBrowserTest, test_name, test)


_register_tests()


if __name__ == "__main__":
    unittest.main()
