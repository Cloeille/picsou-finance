import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Accounts page', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.getByRole('link', { name: 'Comptes' }).click()
    await page.waitForURL('**/accounts')
  })

  test('should show account cards grid', async ({ page }) => {
    // Account grid should be visible
    await expect(page.locator('.grid').first()).toBeVisible()
  })

  test('should filter accounts by type', async ({ page }) => {
    // Type filters are plain buttons now (no tablist)
    await page.getByRole('button', { name: 'Actions & Fonds' }).click()
    // The total row should still be visible
    await expect(page.getByText('Total').first()).toBeVisible()

    // Reset with "Tout" — .first() because the chart's time-range selector
    // also has a "Tout" button once it loads; the type filter row comes first in the DOM.
    await page.getByRole('button', { name: 'Tout', exact: true }).first().click()
    await expect(page.locator('.grid').first()).toBeVisible()
  })

  test('should open add account dialog', async ({ page }) => {
    await page.getByRole('button', { name: 'Ajouter un compte' }).click()
    // Dialog should appear with the source choices
    await expect(page.getByRole('dialog')).toBeVisible()
    // Close via the dialog's close (X) button
    await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })
})
