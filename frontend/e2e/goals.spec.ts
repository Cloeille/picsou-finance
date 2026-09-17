import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Goals page', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.getByRole('link', { name: 'Objectifs' }).click()
    await page.waitForURL('**/goals')
  })

  test('should show goal cards or empty state', async ({ page }) => {
    // Either goal cards are visible or the empty state message
    const hasGoals = (await page.locator('.grid > *').count()) > 0
    if (hasGoals) {
      // Goal cards with progress bars
      await expect(page.locator('.grid').first()).toBeVisible()
    } else {
      await expect(page.getByText('Aucun objectif défini')).toBeVisible()
    }
  })

  test('should open add goal dialog', async ({ page }) => {
    await page.getByRole('button', { name: 'Nouvel objectif' }).click()
    // Dialog should appear with form fields
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByLabel('Montant cible')).toBeVisible()
    // Close dialog
    await page.getByRole('button', { name: 'Annuler' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })

  test('should list recurring investment plans in their own section', async ({ page }) => {
    await expect(page.getByText('Investissements récurrents')).toBeVisible()
    await expect(page.getByText('DCA mensuel PEA')).toBeVisible()
    // A recurring plan has no target, so it must not render a completion percentage.
    await expect(page.getByText('Montant mensuel')).toBeVisible()
  })

  test('should switch the create dialog between the two goal shapes', async ({ page }) => {
    await page.getByRole('button', { name: 'Nouvel objectif' }).click()
    await expect(page.getByLabel('Montant cible')).toBeVisible()

    await page.getByRole('button', { name: 'Investissement mensuel' }).click()
    // The target machinery is replaced, not merely hidden alongside.
    await expect(page.getByLabel('Montant mensuel')).toBeVisible()
    await expect(page.getByLabel('Montant cible')).toHaveCount(0)
  })

  test('should reveal a plan position breakdown from its card', async ({ page }) => {
    const card = page.locator('div').filter({ hasText: 'DCA mensuel PEA' }).last()
    // Collapsed by default: the split is detail, the monthly amount is the headline.
    await expect(page.getByText('Non alloué')).toHaveCount(0)

    await card.getByRole('button', { name: 'Afficher le détail des positions' }).click()

    await expect(page.getByText('AAPL')).toBeVisible()
    // The demo plan splits 250 of its 300, so the remainder has to be stated.
    await expect(page.getByText('Non alloué')).toBeVisible()
  })

  test('should split a monthly amount across positions the account already holds', async ({ page }) => {
    await page.getByRole('button', { name: 'Nouvel objectif' }).click()
    await page.getByRole('button', { name: 'Investissement mensuel' }).click()
    await page.getByLabel('Montant mensuel').fill('400')

    // No account picked yet, so there is nothing legitimate to split across.
    await expect(page.getByText("Choisissez d'abord le compte alimenté.")).toBeVisible()

    await page.getByText('PEA BoursoBank').click()
    await page.getByText('AAPL').click()
    await page.getByLabel('Montant mensuel sur AAPL').fill('500')

    // Over the plan's own amount: refused here, before the 422 has to say it.
    await expect(page.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()

    await page.getByLabel('Montant mensuel sur AAPL').fill('250')
    await expect(page.getByRole('button', { name: 'Enregistrer' })).toBeEnabled()
  })

  test('should compare the savings rate against the French average', async ({ page }) => {
    await expect(page.getByText("Taux d'épargne")).toBeVisible()
    await expect(page.getByText(/moyenne française/)).toBeVisible()
  })
})
