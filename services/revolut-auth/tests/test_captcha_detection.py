"""hCaptcha detection (captcha.py) on real pages in a real Camoufox, no network.

The pages are local HTML. The captcha frame is a same-origin `srcdoc` document whose URL is made to
look like hCaptcha's (the page's requests to hcaptcha.com are answered from a fixture), so the
detection runs against the same DOM shapes Revolut's sign-in page produces without ever
contacting hCaptcha: frames inserted late, parked off-screen, hidden by an overlay, nested.

Skipped when the Camoufox browser binary is not installed (a bare checkout); the sidecar image
and the CI job that builds it have it.
"""

import pytest
from camoufox.async_api import AsyncCamoufox

import captcha

try:
    from camoufox.pkgman import camoufox_path
    camoufox_path(download_if_missing=False)
    _BROWSER = True
except Exception:  # noqa: BLE001 - FileNotFoundError / UnsupportedVersion: no usable browser
    _BROWSER = False

pytestmark = pytest.mark.skipif(not _BROWSER, reason="Camoufox browser binary not installed")

CHALLENGE_URL = "https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=challenge&id=1"
CHECKBOX_URL = "https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=checkbox&id=1"
FRAME_DOCUMENT = "<!doctype html><body style='margin:0;background:#ddd'>I am human</body>"

# Revolut's resting state: the invisible-checkbox frame is display:none, the challenge frame is
# parked at top:-9999px inside a hidden, transparent wrapper (measured on the real sign-in page).
PARKED = f"""
<iframe src="{CHECKBOX_URL}" style="display:none"></iframe>
<div style="visibility:hidden;opacity:0;position:absolute;top:-10000px">
  <div style="visibility:hidden;position:relative;top:-9999px">
    <iframe src="{CHALLENGE_URL}" title="hCaptcha challenge" style="visibility:hidden;width:300px;height:150px"></iframe>
  </div>
</div>"""

SHOWN = f'<iframe id="hc" src="{CHALLENGE_URL}" title="hCaptcha challenge" style="width:400px;height:520px;border:0"></iframe>'


@pytest.fixture
async def page():
    async with AsyncCamoufox(headless=True, os="linux", humanize=False) as browser:
        page = await browser.new_page(viewport={"width": 1280, "height": 800})

        async def serve(route):
            url = route.request.url
            if "hcaptcha.com" in url:
                await route.fulfill(body=FRAME_DOCUMENT, content_type="text/html")
            else:
                await route.fulfill(body=PAGES[url], content_type="text/html")

        PAGES = {}
        page.pages = PAGES
        await page.route("**/*", serve)
        yield page


async def _settled(page):
    """Every iframe element has its frame, with the URL it was given: a negative result is then real."""
    await page.wait_for_function(
        "() => [...document.querySelectorAll('iframe')].every(f => f.contentWindow !== null)")
    expected = await page.evaluate("() => document.querySelectorAll('iframe').length")
    for _ in range(50):
        if len(page.frames) - 1 >= expected and all(f.url not in ("", "about:blank") for f in page.frames[1:]):
            return
        await page.wait_for_timeout(100)


async def _open(page, body):
    page.pages["http://sso.test/signin"] = (
        "<!doctype html><body><input name='phoneNumber' inputmode='tel'><button>Continuer</button>"
        f"{body}</body>")
    await page.goto("http://sso.test/signin")
    await page.wait_for_load_state("load")
    await _settled(page)


async def _seen_within(page, seconds):
    """What the sidecar's loop does: poll. The element exists before its frame has loaded its URL."""
    for _ in range(seconds * 5):
        if await captcha.visible(page):
            return True
        await page.wait_for_timeout(200)
    return False


async def test_no_captcha_on_a_clean_page(page):
    await _open(page, "")

    assert not await captcha.visible(page)


async def test_revolut_resting_state_with_its_two_parked_frames_is_not_a_captcha(page):
    await _open(page, PARKED)
    assert len([f for f in page.frames if "hcaptcha.com" in f.url]) == 2, "the fixture must really load both frames"

    assert not await captcha.visible(page)


async def test_a_challenge_in_the_page_is_seen(page):
    await _open(page, SHOWN)

    assert await _seen_within(page, seconds=10)


async def test_a_frame_added_late_is_seen_by_a_later_poll_but_not_before(page):
    await _open(page, PARKED)
    await page.evaluate(
        "(src) => setTimeout(() => { const f = document.createElement('iframe'); f.id = 'late'; f.src = src;"
        " f.style.cssText = 'width:400px;height:520px'; document.body.appendChild(f); }, 600)", CHALLENGE_URL)

    assert not await captcha.visible(page)  # the frame does not exist yet
    await page.wait_for_selector("#late", timeout=5000)
    assert await _seen_within(page, seconds=10), "a poll after the frame loaded must see it"


async def test_a_challenge_that_fades_in_is_not_seen_while_transparent(page):
    await _open(page, f'<div id="fade" style="opacity:0">{SHOWN}</div>')
    assert not await captcha.visible(page)

    await page.evaluate("() => { document.getElementById('fade').style.opacity = '1'; }")

    assert await _seen_within(page, seconds=10)


@pytest.mark.parametrize("hide", [
    "display:none",
    "visibility:hidden",
    "opacity:0",
    "position:absolute;top:-9999px",
    "position:absolute;left:-9999px",
    "overflow:hidden;height:0",
], ids=lambda s: s.replace(";", " "))
async def test_a_hidden_overlay_around_the_challenge_hides_it(page, hide):
    await _open(page, f'<div id="overlay" style="{hide}">{SHOWN}</div>')

    assert not await captcha.visible(page)


async def test_the_overlay_being_shown_reveals_it(page):
    await _open(page, f'<div id="overlay" style="visibility:hidden;opacity:0">{SHOWN}</div>')
    assert not await captcha.visible(page)

    await page.evaluate("() => { const o = document.getElementById('overlay'); o.style.visibility = 'visible'; o.style.opacity = '1'; }")

    assert await _seen_within(page, seconds=10)


async def test_a_challenge_nested_in_another_frame_is_found_only_while_every_level_is_shown(page):
    inner = f"<!doctype html><body style='margin:0'>{SHOWN}</body>"
    await _open(page, "<iframe id='outer' style='width:600px;height:600px;border:0' srcdoc=\"" +
                inner.replace('"', "&quot;") + "\"></iframe>")
    await page.wait_for_function("() => document.getElementById('outer').contentDocument.getElementById('hc')")
    await page.wait_for_timeout(500)
    assert await _seen_within(page, seconds=10)

    await page.evaluate("() => { document.getElementById('outer').style.visibility = 'hidden'; }")
    assert not await captcha.visible(page), "the outer frame is hidden, so the challenge inside it is too"


async def test_a_frame_that_only_mentions_hcaptcha_in_its_address_is_not_a_captcha(page):
    for lookalike in ("http://sso.test/page?next=hcaptcha.com", "http://nothcaptcha.com/x", "http://hcaptcha.com.evil.test/x"):
        page.pages[lookalike] = "<!doctype html><body>nothing</body>"
    await _open(page, "".join(
        f'<iframe src="{u}" style="width:300px;height:200px"></iframe>'
        for u in ("http://sso.test/page?next=hcaptcha.com", "http://nothcaptcha.com/x", "http://hcaptcha.com.evil.test/x")))
    await page.wait_for_timeout(500)

    assert not await captcha.visible(page)


async def test_a_tiny_tracking_frame_is_not_a_challenge(page):
    await _open(page, f'<iframe src="{CHALLENGE_URL}" style="width:1px;height:1px"></iframe>')

    assert not await captcha.visible(page)


async def test_a_frame_scrolled_far_below_the_viewport_is_not_shown(page):
    await _open(page, f'<div style="height:5000px"></div>{SHOWN}')

    assert not await captcha.visible(page)


async def test_describe_reports_each_frame_and_whether_it_is_shown(page):
    await _open(page, PARKED)
    parked = await captcha.describe(page)
    await _open(page, SHOWN)
    shown = await captcha.describe(page)

    assert parked.count("hidden") == 2 and "shown" not in parked
    assert "challenge:" in shown and shown.endswith("shown")
    assert await captcha.describe(await _blank(page)) == "none"


async def _blank(page):
    await _open(page, "")
    return page
