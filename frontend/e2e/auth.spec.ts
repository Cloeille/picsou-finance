import { test, expect } from '@playwright/test'
import { setupLocale } from './helpers'

test.describe('Demo mode', () => {
  test('should show dashboard directly in demo mode', async ({ page }) => {
    await setupLocale(page)

    // In demo mode, /login redirects to / immediately
    await page.goto('/login')
    await page.waitForURL('/')

    // The sidebar is immediate; this assertion waits for the dashboard query
    // and avoids mistaking the navigation label for loaded page content.
    await expect(page.getByText('Patrimoine total').first()).toBeVisible({ timeout: 15_000 })
    // Charts are rendered as SVGs inside a grid
    await expect(page.locator('.grid').first()).toBeVisible()
  })
})
