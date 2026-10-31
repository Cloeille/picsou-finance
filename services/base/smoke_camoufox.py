"""Launch the installed Firefox in both modes using synthetic, offline HTML only."""

import asyncio
import platform
import struct

from camoufox.async_api import AsyncCamoufox
from camoufox.pkgman import get_path


async def smoke():
    # Prove the installed binary matches the target CPU even when emulation exists.
    expected_machine = {"x86_64": 62, "aarch64": 183}[platform.machine().lower()]
    with open(get_path("camoufox-bin"), "rb") as binary:
        header = binary.read(20)
    assert header[:4] == b"\x7fELF", "Camoufox is not an ELF executable"
    assert struct.unpack("<H", header[18:20])[0] == expected_machine, "Wrong Camoufox architecture"

    for headless in (True, False):
        async with AsyncCamoufox(headless=headless, geoip=False, timeout=60000) as browser:
            page = await browser.new_page()
            await page.set_content("<title>Picsou browser smoke</title><p>Offline Firefox works</p>")
            assert await page.title() == "Picsou browser smoke"
            assert await page.locator("p").inner_text() == "Offline Firefox works"
        print(f"Camoufox {platform.machine()} headless={headless}: OK", flush=True)


if __name__ == "__main__":
    asyncio.run(smoke())
