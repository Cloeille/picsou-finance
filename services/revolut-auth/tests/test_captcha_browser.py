"""Offline browser fixtures exercise CSS visibility without contacting Revolut or hCaptcha."""

import sys
from pathlib import Path

import pytest
import pytest_asyncio
from camoufox.async_api import AsyncCamoufox

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import captcha
import main


pytestmark = pytest.mark.asyncio(loop_scope="module")


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def browser():
    async with AsyncCamoufox(
            headless=True, firefox_user_prefs={"layout.css.getBoxQuads.enabled": True}) as instance:
        yield instance


@pytest_asyncio.fixture(loop_scope="module")
async def page(browser):
    context = await browser.new_context(viewport={"width": 800, "height": 600})

    async def serve(route):
        await route.fulfill(content_type="text/html", body="<html><body>Fixture</body></html>")

    await context.route("**/*", serve)
    try:
        yield await context.new_page()
    finally:
        await context.close()


@pytest.mark.parametrize(("style", "expected"), [
    ("", True),
    ("display:none", False),
    ("visibility:hidden", False),
    ("opacity:0", False),
    ("opacity:0.1", True),
    ("position:absolute;top:-9999px", False),
    ("position:absolute;left:900px", False),
    ("width:1px;height:1px", False),
])
async def test_challenge_visibility(page, style, expected):
    await page.set_content(
        '<iframe src="https://newassets.hcaptcha.com/challenge" '
        f'style="border:0;width:400px;height:300px;{style}"></iframe>')
    assert await captcha.visible(page) is expected


@pytest.mark.parametrize("style", ["opacity:0", "visibility:hidden", "display:none",
                                  "width:0;height:0;overflow:hidden"])
async def test_hidden_ancestor(page, style):
    await page.set_content(
        f'<div style="{style}"><iframe src="https://hcaptcha.com/challenge" '
        'style="width:400px;height:300px"></iframe></div>')
    assert not await captcha.visible(page)


@pytest.mark.parametrize(("ancestor_style", "expected"), [
    ("visibility:hidden", True),
    ("visibility:collapse", True),
    ("display:none", False),
    ("opacity:0", False),
])
async def test_frame_visibility_overrides_ancestor_visibility(page, ancestor_style, expected):
    await page.set_content(
        f'<div style="{ancestor_style}"><iframe src="https://hcaptcha.com/challenge" '
        'style="border:0;width:400px;height:300px;visibility:visible"></iframe></div>')
    assert await captcha.visible(page) is expected


@pytest.mark.parametrize("transform", ["none", "scale(2)", "scale(0.5)", "rotate(90deg)"])
@pytest.mark.parametrize("dimension", ["height", "width"])
@pytest.mark.parametrize(("extent", "padding", "expected"), [
    (2, 0, False),
    (6, 0, True),
    (12, 10, False),
    (16, 10, True),
])
async def test_clipped_frame_requires_visible_content(
        page, transform, dimension, extent, padding, expected):
    await page.set_content(
        f'<div style="width:404px;height:304px;{dimension}:{extent}px;overflow:hidden;'
        f'margin:100px;transform-origin:top left;transform:{transform}">'
        '<iframe src="https://hcaptcha.com/challenge" '
        f'style="display:block;width:400px;height:300px;border:2px solid transparent;padding:{padding}px">'
        '</iframe></div>')
    assert await captcha.visible(page) is expected


@pytest.mark.parametrize(("position", "wrapper_style", "expected"), [
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
])
async def test_positioned_challenge_uses_containing_block_clips(
        page, position, wrapper_style, expected):
    await page.set_content(
        f'<div style="width:0;height:0;overflow:hidden;{wrapper_style}">'
        '<iframe src="https://hcaptcha.com/challenge" '
        f'style="border:0;width:400px;height:300px;position:{position};top:20px;left:20px">'
        '</iframe></div>')
    assert await captcha.visible(page) is expected


@pytest.mark.parametrize("transform", ["none", "scale(2)", "rotate(15deg)"])
@pytest.mark.parametrize(("dimension", "padding", "expected"), [
    ("height", "0", False),
    ("width", "0", False),
    ("height", "0 0 10px", True),
    ("width", "0 10px 0 0", True),
])
async def test_overflow_clips_at_transformed_padding_box(
        page, transform, dimension, padding, expected):
    await page.set_content(
        f'<div style="width:400px;height:300px;{dimension}:0;padding:{padding};'
        'overflow:hidden;border:2px solid transparent;margin:100px;'
        f'transform-origin:top left;transform:{transform}">'
        '<iframe src="https://hcaptcha.com/challenge" '
        'style="border:0;width:400px;height:300px"></iframe></div>')
    assert await captcha.visible(page) is expected


@pytest.mark.parametrize("dimension", ["height", "width"])
async def test_scaled_subpixel_padding_box_exposes_content(page, dimension):
    await page.set_content(
        f'<div style="width:400px;height:300px;{dimension}:0.25px;overflow:hidden;'
        'transform-origin:top left;transform:scale(10)">'
        '<iframe src="https://hcaptcha.com/challenge" '
        'style="display:block;border:0;width:400px;height:300px"></iframe></div>')
    assert await captcha.visible(page)


@pytest.mark.parametrize("element", ["html", "body"])
async def test_viewport_overflow_does_not_clip_at_empty_element_box(page, element):
    await page.set_content(
        f'<style>{element} {{height:0;overflow:hidden}}</style>'
        '<iframe src="https://hcaptcha.com/challenge" '
        'style="display:block;border:0;width:400px;height:300px"></iframe>')
    assert await captcha.visible(page)


async def test_challenge_visibility_tracks_opacity_changes(page):
    await page.set_content('<iframe src="https://hcaptcha.com/challenge" '
                           'style="border:0;width:400px;height:300px;opacity:0"></iframe>')
    assert not await captcha.visible(page)
    await page.locator('iframe').evaluate("frame => frame.style.opacity = '0.1'")
    assert await captcha.visible(page)
    await page.locator('iframe').evaluate("frame => frame.style.opacity = '0'")
    assert not await captcha.visible(page)


@pytest.mark.parametrize("target", ["frame", "frame.parentElement"])
async def test_opacity_change_during_geometry_observation(page, target):
    await page.set_content('<div><iframe src="https://hcaptcha.com/challenge" '
                           'style="border:0;width:400px;height:300px"></iframe></div>')
    portion = await page.locator('iframe').evaluate(f"""frame => {{
        const portion = ({captcha._VISIBLE_RECT})(frame);
        {target}.style.opacity = '0';
        return portion;
    }}""")
    assert portion is None


async def test_nested_challenge(page):
    await page.set_content("""<iframe style="width:600px;height:400px" srcdoc='
        <iframe src="https://hcaptcha.com/challenge" style="width:400px;height:300px"></iframe>
        '></iframe>""")
    assert await captcha.visible(page)


async def test_nested_challenge_under_transparent_parent_frame(page):
    await page.set_content("""<iframe style="width:600px;height:400px;opacity:0" srcdoc='
        <iframe src="https://hcaptcha.com/challenge" style="width:400px;height:300px"></iframe>
        '></iframe>""")
    assert not await captcha.visible(page)


async def test_challenge_outside_visible_part_of_parent_frame(page):
    await page.set_content("""<div style="width:100px;height:400px;overflow:hidden">
        <iframe style="width:600px;height:400px" srcdoc='
            <iframe src="https://hcaptcha.com/challenge"
                style="margin-left:200px;width:200px;height:200px"></iframe>
        '></iframe></div>""")
    assert not await captcha.visible(page)


async def test_unrelated_frame_url_containing_hcaptcha(page):
    await page.set_content('<iframe src="https://hcaptcha.com.example.test/challenge" '
                          'style="width:400px;height:300px"></iframe>')
    assert not await captcha.visible(page)


async def test_challenge_inserted_after_first_poll(page):
    await page.set_content('<main>Waiting</main>')
    assert not await captcha.visible(page)
    async with page.expect_event("framenavigated", predicate=lambda frame: captcha._is_hcaptcha_url(frame.url)):
        await page.evaluate("""() => {
            const frame = document.createElement('iframe');
            frame.src = 'https://hcaptcha.com/challenge';
            frame.style = 'width:400px;height:300px';
            document.body.append(frame);
        }""")
    assert await captcha.visible(page)


async def test_login_screen_detects_nested_challenge_before_passcode(page):
    await page.set_content("""<input><input><input><input><input><input>
        <iframe style="width:600px;height:400px" srcdoc='
            <iframe src="https://hcaptcha.com/challenge" style="width:400px;height:300px"></iframe>
        '></iframe>""")
    assert await main._wait_for_login_screen(page) == "captcha"


async def test_login_screen_ignores_parked_challenge_and_accepts_passcode(page):
    await page.set_content("""<input><input><input><input><input><input>
        <iframe src="https://hcaptcha.com/challenge"
            style="width:400px;height:300px;opacity:0"></iframe>""")
    assert await main._wait_for_login_screen(page) == "passcode"
