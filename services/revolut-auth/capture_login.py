"""
Revolut ASSISTED login capture — using Camoufox (stealth Firefox).

Vanilla Playwright Chromium gets fingerprinted as automation and challenged by
Revolut's anti-bot (captcha loops, "Mauvais code d'accès"). The user's real
Firefox-based browser (Zen) logs in with zero friction, so we drive a stealth
*Firefox* instead: Camoufox spoofs the fingerprint at the C++ level (0% bot-
detection) and yields a standard Playwright browser object.

You perform the whole login by hand in the window that appears (phone number,
passcode, mobile approval). On success the session is written to
revolut-storage.json; the sidecar reuses it (same engine → consistent
fingerprint). No credentials are read by this script.
"""

import asyncio
import sys

from camoufox.async_api import AsyncCamoufox

APP = "https://app.revolut.com/"
STORAGE = "revolut-storage.json"
# A PERSISTENT Camoufox profile: reused (same user_data_dir) by every sync so the
# browser fingerprint AND cookies stay consistent across launches -- Revolut kills a
# session whose device fingerprint keeps changing between replays.
PROFILE_DIR = "revolut-profile"
WAIT_S = 420  # generous — take your time with the mobile approval / device enrolment


def log(msg: str) -> None:
    line = f"[capture] {msg}"
    print(line, flush=True)
    try:
        with open("capture.log", "a") as f:
            f.write(line + "\n")
    except Exception:  # noqa: BLE001
        pass


async def logged_in(page) -> bool:
    """200 from token/info (cookie + x-device-id) means the session is live."""
    try:
        return await page.evaluate(
            """async () => {
                const c = document.cookie.split(';').map(s=>s.trim())
                          .find(s=>s.startsWith('revo_device_id='));
                const dev = c ? c.split('=').slice(1).join('=') : '';
                const r = await fetch('/api/retail/token/info', {
                  credentials: 'include',
                  headers: {'x-device-id': dev, 'x-browser-application': 'WEB_CLIENT',
                            'x-client-version': '100.0'}
                });
                return r.status === 200;
            }"""
        )
    except Exception:  # noqa: BLE001
        return False


async def main() -> int:
    try:
        open("capture.log", "w").close()
    except Exception:  # noqa: BLE001
        pass
    log("launching Camoufox (stealth Firefox), headful — a window should appear on your screen")
    # os="linux" + geoip keeps the fingerprint consistent with the host; humanize adds
    # human-like cursor motion. Camoufox manages UA / navigator / canvas / WebGL itself,
    # so we set no user_agent and inject no webdriver patch.
    async with AsyncCamoufox(headless=False, humanize=True, os="linux", geoip=True,
                             persistent_context=True, user_data_dir=PROFILE_DIR) as ctx:
        page = await ctx.new_page()
        await page.goto(APP, wait_until="domcontentloaded", timeout=45000)
        log("=> LOG IN BY HAND in the window: phone number, passcode, approve on your phone.")
        log(f"waiting up to {WAIT_S}s for you to reach your dashboard...")
        for _ in range(WAIT_S // 3):
            if await logged_in(page):
                break
            await page.wait_for_timeout(3000)
        if await logged_in(page):
            await page.context.storage_state(path=STORAGE)
            log(f"GO: logged in. Session saved to {STORAGE} (secret — gitignored).")
            return 0
        log("NO-GO: not logged in within the window. Re-run and take your time.")
        return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
