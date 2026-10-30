import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Sync page tabs', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    // Sync is no longer a sidebar entry — navigate to the route directly.
    await page.goto('/sync')
    await page.waitForURL('**/sync')
  })

  test('should show every sync source as a tab', async ({ page }) => {
    const tabs = page.locator('[role="tablist"] [role="tab"]')
    await expect(tabs).toHaveText([
      'Banques',
      'Exchanges',
      'Wallets',
      'Trade Republic',
      'BoursoBank',
      'Bourse Direct',
      'DEGIRO',
      'Interactive Brokers',
      'Amundi',
      'Finary',
    ])
  })

  // Switching tabs no longer rewrites the URL (?tab= is only read as the
  // initial tab), so assert on the revealed content instead of waitForURL.
  test('should switch to Exchanges tab', async ({ page }) => {
    await page.getByRole('tab', { name: 'Exchanges' }).click()
    // Exchanges content should be visible
    await expect(page.getByText('Ajouter un exchange')).toBeVisible()
  })

  test('should switch to Wallets tab', async ({ page }) => {
    await page.getByRole('tab', { name: 'Wallets' }).click()
    // Wallets content should be visible
    await expect(page.getByText('Ajouter un wallet')).toBeVisible()
  })

  test('should switch to Finary tab and show login + file import', async ({ page }) => {
    await page.getByRole('tab', { name: 'Finary' }).click()
    // API-login form should be visible
    await expect(page.getByText('Se connecter à Finary')).toBeVisible()
    // Upload area should be visible
    await expect(page.getByText('Importer un fichier Finary (.xlsx)').first()).toBeVisible()
  })

  test('should open a tab directly via the ?tab= query param', async ({ page }) => {
    await page.goto('/sync?tab=exchanges')
    await expect(page.getByText('Ajouter un exchange')).toBeVisible()
  })
})
