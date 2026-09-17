import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Analysis', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test('should navigate to Analyse from the sidebar', async ({ page }) => {
    await page.getByRole('link', { name: 'Analyse' }).click()
    await page.waitForURL('**/analysis')
    await expect(page.getByRole('heading', { name: 'Analyse' })).toBeVisible()
  })

  test('should show the composition score and every tier', async ({ page }) => {
    await page.goto('/analysis')
    await expect(page.getByText('Score de composition')).toBeVisible()
    for (const tier of ['Matelas de sécurité', 'Immobilier', 'Actions & ETF', 'Crypto', 'Actifs alternatifs']) {
      await expect(page.getByText(tier, { exact: true })).toBeVisible()
    }
  })

  test('should open the targets dialog and offer the estimate', async ({ page }) => {
    await page.goto('/analysis')
    await page.getByRole('button', { name: 'Mes cibles' }).click()
    await expect(page.getByText("Mes cibles d'allocation")).toBeVisible()
    await expect(page.getByLabel('Dépenses mensuelles obligatoires')).toBeVisible()
    // The suggestion derived from transactions is the reason the field is fillable at all.
    await expect(page.getByRole('button', { name: 'Utiliser' })).toBeVisible()
  })

  test('should refuse targets that do not add up to 100%', async ({ page }) => {
    await page.goto('/analysis')
    await page.getByRole('button', { name: 'Mes cibles' }).click()
    await page.getByLabel('Crypto').fill('40')
    await expect(page.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('should show the sector and geographic breakdowns', async ({ page }) => {
    await page.goto('/analysis')
    await expect(page.getByText('Diversification')).toBeVisible()
    await expect(page.getByText('Secteurs')).toBeVisible()
    await expect(page.getByText('Zones géographiques')).toBeVisible()
    // Keys resolved through the labels the holding modal already ships.
    await expect(page.getByText('Technologie')).toBeVisible()
    await expect(page.getByText('États-Unis')).toBeVisible()
  })

  test('should state what the breakdown could not classify', async ({ page }) => {
    await page.goto('/analysis')
    // Coverage is stated rather than renormalised away — a bar over part of the portfolio must
    // not read as one over all of it.
    await expect(page.getByText(/Calculé sur \d+ % du portefeuille/)).toBeVisible()
    await expect(page.getByText(/non classé/)).toBeVisible()
  })
})
