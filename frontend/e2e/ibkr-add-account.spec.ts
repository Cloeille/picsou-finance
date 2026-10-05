import { test, expect } from '@playwright/test'
import { setupLocale } from './helpers'

test.describe('Add account — IBKR entry', () => {
  test('IBKR entry is visible in the selector and shows the connection form', async ({ page }) => {
    await setupLocale(page)
    await page.goto('/accounts')
    await page.waitForLoadState('networkidle')

    // Dismiss the sidebar-style onboarding dialog if present
    const closeOnboarding = page.getByRole('button', { name: 'Close' })
    if (await closeOnboarding.isVisible()) {
      await closeOnboarding.click()
    }

    await page.getByRole('button', { name: 'Ajouter un compte' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // IBKR entry must be present between DEGIRO and Amundi in the source list
    const ibkrButton = page.getByRole('button', { name: /Interactive Brokers/ })
    await expect(ibkrButton).toBeVisible()

    // Click the IBKR entry
    await ibkrButton.click()

    // Back button must appear
    await expect(page.getByRole('button', { name: /Retour|Back/i })).toBeVisible()

    // Token input and Query ID input must appear
    await expect(page.locator('#ibkr-token')).toBeVisible()
    await expect(page.locator('#ibkr-query')).toBeVisible()
  })
})
