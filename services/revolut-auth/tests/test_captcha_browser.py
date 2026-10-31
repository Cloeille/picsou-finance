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
    async with AsyncCamoufox(headless=True) as instance:
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
    async with page.expect_event("framenavigated", predicate=lambda frame: "hcaptcha.com" in frame.url):
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
