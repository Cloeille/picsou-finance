import { test, expect } from '@playwright/test'

// The source list of the "Add account" dialog: every row is icon, title, description, arrow.
// A long description (Caisse d'Epargne, Banque Populaire, German or Spanish copy) must wrap
// inside the row instead of pushing the arrow out of the dialog.

const LOCALES = ['fr', 'en', 'de', 'es'] as const
const WIDTHS = [375, 768, 1280] as const
const ADD_BUTTON = /^(Ajouter un compte|Add account|Konto hinzufügen|Añadir cuenta)/i

for (const locale of LOCALES) {
  for (const width of WIDTHS) {
    test(`source rows keep their arrow aligned and inside the dialog (${locale}, ${width}px)`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.addInitScript((lng) => {
        localStorage.setItem('picsou-locale', lng)
        localStorage.setItem('picsou-app', JSON.stringify({
          state: { sidebarCollapsed: false, dateFormat: 'locale', sidebarStyle: 'current', hasSeenSidebarStylePrompt: true },
          version: 0,
        }))
      }, locale)
      await page.goto('/accounts')
      await page.waitForLoadState('networkidle')
      await page.getByRole('button', { name: ADD_BUTTON }).first().click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()

      const rows = await dialog.evaluate((dlg) => {
        const dialogBox = dlg.getBoundingClientRect()
        return [...dlg.querySelectorAll<HTMLElement>('button')]
          .filter((row) => row.querySelector('svg.lucide-arrow-right'))
          .map((row) => {
            const box = row.getBoundingClientRect()
            const arrow = row.querySelector('svg.lucide-arrow-right')!.getBoundingClientRect()
            const description = [...row.querySelectorAll('p')].at(-1)!
            const text = description.getBoundingClientRect()
            return {
              label: row.innerText.replace(/\s+/g, ' ').trim().slice(0, 40),
              rowInsideDialog: box.right <= dialogBox.right + 1,
              arrowGap: Math.round(box.right - arrow.right),
              arrowCentreOffset: Math.round(arrow.top + arrow.height / 2 - (box.top + box.height / 2)),
              arrowWidth: Math.round(arrow.width),
              textClear: text.right <= arrow.left,
              descriptionLines: Math.round(text.height / parseFloat(getComputedStyle(description).lineHeight)),
              dialogScrollsSideways: dlg.scrollWidth > dlg.clientWidth + 1,
            }
          })
      })

      expect(rows.length).toBeGreaterThan(10)
      expect(rows.filter((r) => !r.rowInsideDialog || r.dialogScrollsSideways || !r.textClear)).toEqual([])
      // Same arrow position, size and vertical centring on every row, whatever the copy length.
      expect(new Set(rows.map((r) => r.arrowGap)).size).toBe(1)
      expect(new Set(rows.map((r) => r.arrowWidth)).size).toBe(1)
      expect(rows.every((r) => Math.abs(r.arrowCentreOffset) <= 1)).toBe(true)
      // A description is clamped to two lines, never more.
      expect(Math.max(...rows.map((r) => r.descriptionLines))).toBeLessThanOrEqual(2)
    })
  }
}
