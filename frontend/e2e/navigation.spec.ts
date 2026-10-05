import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Sidebar navigation', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test('should navigate to Comptes', async ({ page }) => {
    await page.getByRole('link', { name: 'Comptes' }).click()
    await page.waitForURL('**/accounts')
    await expect(page.getByText('Comptes', { exact: true })).toBeVisible()
  })

  test('should navigate to Objectifs', async ({ page }) => {
    await page.getByRole('link', { name: 'Objectifs' }).click()
    await page.waitForURL('**/goals')
    await expect(page.getByText('Objectifs', { exact: true })).toBeVisible()
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
    await expect(page.getByText('Tableau de bord')).toBeVisible()
  })
})
