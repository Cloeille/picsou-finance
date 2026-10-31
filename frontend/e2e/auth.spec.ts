import { test, expect } from '@playwright/test'
import { seedStorage } from './helpers'

test.describe('Demo mode', () => {
  test('should show dashboard directly in demo mode', async ({ page }) => {
    await seedStorage(page)

    // In demo mode, /login redirects to / immediately
    await page.goto('/login')
    await page.waitForURL('/')

    // Dashboard should be visible
    await expect(page.getByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeVisible()

    // Dashboard should show net worth and chart area
    await expect(page.getByText('Patrimoine total').first()).toBeVisible()
    // Charts are rendered as SVGs inside a grid
    await expect(page.locator('.grid').first()).toBeVisible()
  })
})
