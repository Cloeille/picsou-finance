"""The browser a member drives during a captcha hand-off cannot reach another member's profile.

Every member's profile lives under one directory owned by the one user all browsers run as, so the
browser itself must offer the member no way to a local file (lock_browser.py, applied to the image's
Camoufox at build). Member 1's headful browser is opened on a throwaway X display next to member 2's
saved profile, and tried the two ways a person at the remote view would: typing a file: URL, and
picking the file in an upload form.

Skipped where the Camoufox browser or Xvfb is missing (a bare checkout); the sidecar image has both,
and the CI job runs the suite there.
"""

import os
import shutil
import subprocess

import pytest
from camoufox.async_api import AsyncCamoufox
from playwright.async_api import Error as PlaywrightError
from Xlib import X, display as xdisplay

import main

try:
    from camoufox.pkgman import camoufox_path
    camoufox_path(download_if_missing=False)
    _BROWSER = True
except Exception:  # noqa: BLE001 - FileNotFoundError / UnsupportedVersion: no usable browser
    _BROWSER = False

pytestmark = pytest.mark.skipif(not _BROWSER or shutil.which("Xvfb") is None,
                                reason="needs the Camoufox browser and Xvfb")

MEMBER_2_MARKER = "member-2-session-cookie"


@pytest.fixture
def screen(monkeypatch):
    number = 200 + (os.getpid() + 350) % 700
    xvfb = subprocess.Popen(["Xvfb", f":{number}", "-screen", "0", "1600x900x24", "-nolisten", "tcp"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(50):
        if os.path.exists(f"/tmp/.X11-unix/X{number}"):
            break
        subprocess.run(["sleep", "0.1"])
    monkeypatch.setenv("DISPLAY", f":{number}")
    connection = xdisplay.Display(f":{number}")
    yield connection
    connection.close()
    xvfb.terminate()
    xvfb.wait()


def _windows_on(screen):
    """The top-level windows a person looking at the display sees."""
    children = screen.screen().root.query_tree().children
    return {w.id for w in children if w.get_attributes().map_state == X.IsViewable}


@pytest.fixture
async def member_1_browser(screen):
    with open(os.path.join(main._profile_dir("2"), "cookies.sqlite"), "w") as f:
        f.write(MEMBER_2_MARKER)
    async with AsyncCamoufox(headless=False, os="linux", humanize=False,
                             persistent_context=True, user_data_dir=main._profile_dir("1")) as ctx:
        yield ctx


@pytest.mark.parametrize("url", [
    "file://{root}/2/cookies.sqlite",
    "FILE://{root}/2/cookies.sqlite",
    "file://localhost{root}/2/cookies.sqlite",
    "view-source:file://{root}/2/cookies.sqlite",
    "file://{root}/",
])
async def test_another_members_profile_cannot_be_opened_by_url(member_1_browser, url):
    page = await member_1_browser.new_page()
    with pytest.raises(PlaywrightError, match="NS_ERROR_BLOCKED_BY_POLICY"):
        await page.goto(url.format(root=main.PROFILES_ROOT))
    await page.wait_for_timeout(500)
    assert MEMBER_2_MARKER not in await page.evaluate("() => document.documentElement.outerHTML")


async def test_an_upload_form_opens_no_file_picker(member_1_browser, screen):
    page = await member_1_browser.new_page()
    await page.set_content("<form><input type=file style='width:400px;height:80px'></form>")
    await page.wait_for_timeout(500)
    shown = _windows_on(screen)

    await page.click("input")
    await page.wait_for_timeout(3000)

    assert _windows_on(screen) == shown, "a file picker window opened on the hand-off display"
