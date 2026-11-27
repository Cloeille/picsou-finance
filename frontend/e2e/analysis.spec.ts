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
    // The cushion is measured in euros above the allocation bars, not as one of them. Scoped to
    // its card because the sector breakdown further down has its own "Immobilier".
    const allocation = page
      .locator('[data-slot="card"]')
      .filter({ hasText: "Répartition par classe d'actifs" })
    await expect(allocation).toBeVisible()
    for (const tier of ['Immobilier', 'Actions et ETF', 'Crypto', 'Actifs alternatifs']) {
      await expect(allocation.getByText(tier, { exact: true })).toBeVisible()
    }
  })

  test('should open the targets dialog and offer the estimate', async ({ page }) => {
    await page.goto('/analysis')
    await page.getByRole('button', { name: 'Mes cibles' }).click()
    await expect(page.getByText("Mes cibles d'allocation")).toBeVisible()
    await expect(page.getByLabel('Dépenses mensuelles obligatoires')).toBeVisible()
    // Only one suggestion is offered: the transaction-derived estimate wins over recurring series.
    await expect(page.getByText(/Estimé à/)).toBeVisible()
    await expect(page.getByText(/D'après vos récurrents/)).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Utiliser' })).toHaveCount(1)
  })

  test('should refuse targets that do not add up to 100%', async ({ page }) => {
    await page.goto('/analysis')
    await page.getByRole('button', { name: 'Mes cibles' }).click()
    await page.getByLabel('Crypto').fill('40')
    await expect(page.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('should show the sector and geographic breakdowns', async ({ page }) => {
    await page.goto('/analysis')
    await page.getByRole('tab', { name: 'Diversification' }).click()
    await expect(page.getByText('Secteurs')).toBeVisible()
    await expect(page.getByText('Zones géographiques')).toBeVisible()
    // Keys resolved through the labels the holding modal already ships.
    await expect(page.getByText('Technologie')).toBeVisible()
    await expect(page.getByText('États-Unis')).toBeVisible()
  })

  test('should state what the breakdown could not classify', async ({ page }) => {
    await page.goto('/analysis')
    await page.getByRole('tab', { name: 'Diversification' }).click()
    // Coverage is stated rather than renormalised away — a bar over part of the portfolio must
    // not read as one over all of it.
    await expect(page.getByText(/Cette répartition couvre \d+\s% de votre portefeuille/)).toBeVisible()
    const overallBadge = page.getByRole('button', {
      name: 'Voir le détail du montant non classé',
      exact: true,
    })
    await expect(overallBadge).toBeVisible()
    await overallBadge.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByText('Positions non classées')).toBeVisible()
    await expect(dialog.getByText('Actions Monde (FCPE)')).toBeVisible()
    await expect(dialog.getByText('Pas de profil (non encore recherché)')).toBeVisible()
    await expect(dialog.getByRole('button', { name: /Classer/ }).first()).toBeVisible()
    await dialog.getByRole('tab', { name: /Secteurs/ }).click()
    await expect(dialog.getByText('Amundi Private Assets ETF')).toBeVisible()
    await expect(dialog.getByText('Composition incomplète')).toBeVisible()
    await expect(
      dialog.getByText("Composition publiée partielle : le reste n'est pas connu"),
    ).toBeVisible()
    // Only FCPE-DEMO (NO_PROFILE) gets a Classify button; INCOMPLETE_COMPOSITION never does.
    await expect(dialog.getByRole('button', { name: /Classer/ })).toHaveCount(1)
  })
})
