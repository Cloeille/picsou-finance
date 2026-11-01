"""The identifier selectors against both live variants of the login page.

The bank serves the identifier field as `#neo-input-1` on some instances and
`#neo-input-2` on others. Pages are synthetic HTML with the observed structure
(a carousel `Suivant` button before the form), loaded in real Chromium.
"""

import unittest

from playwright.async_api import async_playwright

import login

PAGE = """
<button type="button">Suivant</button>
<form>
  <input type="text" id="{field_id}">
  <button type="button">Annuler</button>
  <button type="submit">Valider</button>
</form>
"""


class IdentifierSelectorTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True)
        self.page = await self.browser.new_page()

    async def asyncTearDown(self):
        await self.browser.close()
        await self.playwright.stop()

    async def check_variant(self, field_id: str) -> None:
        await self.page.set_content(PAGE.format(field_id=field_id))

        await self.page.fill(login.IDENTIFIER_INPUT, "12345678", timeout=2000)

        self.assertEqual(await self.page.input_value(f"#{field_id}"), "12345678")
        button = self.page.locator(login.NEXT_BUTTON)
        self.assertEqual(await button.count(), 1)
        self.assertEqual(await button.get_attribute("type"), "submit")
        self.assertEqual((await button.inner_text()).strip(), "Valider")

    async def test_first_variant_neo_input_1(self):
        await self.check_variant("neo-input-1")

    async def test_second_variant_neo_input_2(self):
        await self.check_variant("neo-input-2")


if __name__ == "__main__":
    unittest.main()
