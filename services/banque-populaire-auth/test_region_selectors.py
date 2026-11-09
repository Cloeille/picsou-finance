"""The establishment picker selectors and steps against a synthetic page, in real Chromium.

The markup mirrors what was observed live on 2026-11-05: a consent banner, then a
`neo-select-simple` whose trigger is a combobox button with a numbered id, options
`neo-select-simple-option[role=option]`, and the identifier text input
(`neo-input-1`) of a form with a carousel `Suivant` button before it. No network.
"""

import unittest
from unittest.mock import patch

from bpce import login
from playwright.async_api import async_playwright

import main

OPTIONS = "".join(
    f'<neo-select-simple-option role="option"><span> {label} </span></neo-select-simple-option>'
    for _, label in main.REGIONS
)
PAGE = f"""
<div id="banner" style="position:fixed;inset:0;background:#fff;z-index:10">
  <button id="no_consent_btn" onclick="document.getElementById('banner').remove()">Continuer sans accepter</button>
</div>
<neo-select-simple>
  <label for="neo-input-2">Sélectionnez votre établissement bancaire</label>
  <button type="button" role="combobox" id="neo-input-2" aria-expanded="false"
          onclick="document.getElementById('panel').hidden = false">
    <span id="value">Sélectionnez votre établissement bancaire</span>
  </button>
  <div id="panel" role="listbox" hidden>{OPTIONS}</div>
</neo-select-simple>
<button type="button">Suivant</button>
<form>
  <input type="text" id="neo-input-1">
  <button type="submit">Valider</button>
</form>
<script>
  for (const option of document.querySelectorAll('neo-select-simple-option')) {{
    option.addEventListener('click', () => {{
      document.getElementById('value').textContent = option.textContent.trim();
      document.getElementById('panel').hidden = true;
    }});
  }}
</script>
"""


class RegionSelectorTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True)
        self.page = await self.browser.new_page()
        for p in (patch.object(login, "STEP_TIMEOUT_SECONDS", 2), patch.object(login, "POLL_INTERVAL_SECONDS", 0.01)):
            p.start()
            self.addCleanup(p.stop)

    async def asyncTearDown(self):
        await self.browser.close()
        await self.playwright.stop()

    async def test_the_banner_is_refused_and_the_exact_option_picked(self):
        await self.page.set_content(PAGE)

        await login._select_region(main.SITE, self.page, "RIVES DE PARIS")

        self.assertEqual(await self.page.locator("#banner").count(), 0)
        self.assertEqual(await self.page.inner_text("#value"), "RIVES DE PARIS")

    async def test_a_banner_that_appears_after_the_check_is_refused_and_the_picker_clicked_again(self):
        # Hidden when the banner is checked, shown by the picker click: it then covers
        # the options, so the option click is the one refused and retried, every run.
        late = PAGE.replace(
            '<div id="banner" style="',
            '<div id="banner" style="display:none;',
        ).replace(
            "<script>",
            "<script>document.getElementById('neo-input-2').addEventListener("
            "'click', () => document.getElementById('banner').style.display = 'block');",
        )
        await self.page.set_content(late)

        await login._select_region(main.SITE, self.page, "RIVES DE PARIS")

        self.assertEqual(await self.page.locator("#banner").count(), 0)
        self.assertEqual(await self.page.inner_text("#value"), "RIVES DE PARIS")

    async def test_an_accented_label_is_matched_exactly(self):
        await self.page.set_content(PAGE)

        await login._select_region(main.SITE, self.page, "BOURGOGNE FRANCHE-COMTÉ")

        self.assertEqual(await self.page.inner_text("#value"), "BOURGOGNE FRANCHE-COMTÉ")

    async def test_an_option_the_page_does_not_offer_is_a_format_change(self):
        await self.page.set_content(PAGE.replace(" RIVES DE PARIS ", " RIVES DE PARIS OUEST "))

        with self.assertRaises(login.LoginError) as ctx:
            await login._select_region(main.SITE, self.page, "RIVES DE PARIS")

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")
        self.assertEqual(await self.page.inner_text("#value"), "Sélectionnez votre établissement bancaire")

    async def test_the_identifier_selector_matches_the_text_input_not_the_combobox(self):
        await self.page.set_content(PAGE)

        field = self.page.locator(login.IDENTIFIER_INPUT)
        self.assertEqual(await field.count(), 1)
        self.assertEqual(await field.get_attribute("id"), "neo-input-1")
        self.assertEqual(await self.page.locator(login.REGION_TRIGGER).get_attribute("id"), "neo-input-2")
        self.assertEqual(await self.page.locator(login.NEXT_BUTTON).count(), 1)



REFUSAL_PAGE = """
<h1>Entrez votre mot de passe</h1>
<button type="button">Mot de passe oublié ?</button>
<div role="dialog"><h2>Identifiant ou mot de passe erroné</h2>
  <button type="button">Réessayer</button><button type="button">Annuler</button></div>
"""
PASSWORD_PAGE = """
<h1>Entrez votre mot de passe</h1>
<button type="button">Mot de passe oublié ?</button>
<button type="submit">Valider</button>
"""


class RefusalSelectorTest(unittest.IsolatedAsyncioTestCase):
    """The bank's refusal modal (observed live 2026-11-05) reads as a refusal, the plain page does not."""

    async def asyncSetUp(self):
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True)
        self.page = await self.browser.new_page()

    async def asyncTearDown(self):
        await self.browser.close()
        await self.playwright.stop()

    async def test_the_refusal_modal_is_seen_as_an_error(self):
        await self.page.set_content(REFUSAL_PAGE)
        self.assertTrue(await self.page.locator(login.ERROR_SELECTOR).first.is_visible())

    async def test_the_password_page_with_its_forgotten_link_is_not_an_error(self):
        await self.page.set_content(PASSWORD_PAGE)
        self.assertEqual(await self.page.locator(login.ERROR_SELECTOR).count(), 0)


if __name__ == "__main__":
    unittest.main()
