import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Sidebar navigation', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test('should navigate to Comptes', async ({ page }) => {
    await page.getByRole('link', { name: 'Comptes' }).click()
    await page.waitForURL('**/accounts')
    await expect(page.getByRole('heading', { level: 1, name: 'Comptes' })).toBeVisible()
  })

  test('should navigate to Objectifs', async ({ page }) => {
    await page.getByRole('link', { name: 'Objectifs' }).click()
    await page.waitForURL('**/goals')
    await expect(page.getByRole('heading', { level: 1, name: 'Objectifs' })).toBeVisible()
  })

  // Sync is not a sidebar nav item (it lives in the profile menu, covered by
  // AppSidebar.test.tsx) — check the direct route instead.
  test('should reach Synchronisation via its route', async ({ page }) => {
    await page.goto('/sync')
    await page.waitForURL('**/sync')
    await expect(page.getByRole('heading', { name: 'Synchronisation' })).toBeVisible()
  })

  test('should navigate back to Tableau de bord', async ({ page }) => {
    // Go to accounts first
    await page.getByRole('link', { name: 'Comptes' }).click()
    await page.waitForURL('**/accounts')

    // Go back to dashboard
    await page.getByRole('link', { name: 'Tableau de bord' }).click()
    await page.waitForURL('/')
    await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()
  })
})

for (const sidebarStyle of ['classic', 'current']) {
  for (const locale of ['fr', 'en', 'de', 'es']) {
    test(`${sidebarStyle} sidebar shows every nav subtitle in full (${locale})`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height: 900 })
      await page.addInitScript(({ sidebarStyle, locale }) => {
        localStorage.setItem('picsou-locale', locale)
        localStorage.setItem('picsou-app', JSON.stringify({
          state: { sidebarStyle, hasSeenSidebarStylePrompt: true },
          version: 0,
        }))
      }, { sidebarStyle, locale })
      await page.goto('/')

      const subtitles = page.locator('nav a [data-slot="item-description"]')
      await expect(subtitles.first()).toBeVisible()
      const clipped = await subtitles.evaluateAll((els) => els
        .filter((el) => el.scrollHeight > el.clientHeight || el.scrollWidth > el.clientWidth)
        .map((el) => el.textContent))
      expect(clipped).toEqual([])
    })
  }
}
