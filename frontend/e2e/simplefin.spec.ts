import { test, expect, type Page } from '@playwright/test'
import { setupLocale } from './helpers'

// Runs against the demo-mode dev server (VITE_DEMO_MODE=true): every /simplefin/* call is served by
// the in-memory handlers in src/demo/index.ts, so no backend and no SimpleFIN network access.
// The demo SimpleFIN state is module-level and resets on every full page load.

const CREATE_TOKEN_URL = 'https://bridge.simplefin.org/simplefin/create'

async function expectTokenLink(page: Page) {
  const link = page.getByRole('link', { name: 'Créer un jeton' })
  await expect(link).toBeVisible()
  await expect(link).toHaveAttribute('href', CREATE_TOKEN_URL)
  await expect(link).toHaveAttribute('target', '_blank')
  await expect(link).toHaveAttribute('rel', /noopener|noreferrer/)
}

test.describe('SimpleFIN — Sync page tab', () => {
  test.beforeEach(async ({ page }) => {
    await setupLocale(page)
    await page.goto('/sync')
    await page.waitForURL('**/sync')
  })

  test('shows the token form and the Bridge link', async ({ page }) => {
    await page.getByRole('tab', { name: 'SimpleFIN', exact: true }).click()

    const token = page.locator('#simplefin-token')
    await expect(token).toBeVisible()
    await expect(token).toHaveAttribute('type', 'password')
    await expectTokenLink(page)

    // Nothing to submit yet.
    await expect(page.getByRole('button', { name: 'Connecter', exact: true })).toBeDisabled()
  })

  test('enables Connect once a token is typed and ignores whitespace-only input', async ({ page }) => {
    await page.getByRole('tab', { name: 'SimpleFIN', exact: true }).click()

    const token = page.locator('#simplefin-token')
    const connect = page.getByRole('button', { name: 'Connecter', exact: true })

    await token.fill('   ')
    await expect(connect).toBeDisabled()

    await token.fill('demo-setup-token')
    await expect(connect).toBeEnabled()
  })

  test('connects in demo mode, then disconnects after confirmation', async ({ page }) => {
    await page.getByRole('tab', { name: 'SimpleFIN', exact: true }).click()

    await page.locator('#simplefin-token').fill('demo-setup-token')
    await page.getByRole('button', { name: 'Connecter', exact: true }).click()

    // Connected card: masked token from the demo handler, form gone, actions available.
    await expect(page.getByText('••••demo')).toBeVisible()
    await expect(page.locator('#simplefin-token')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Synchroniser', exact: true })).toBeVisible()

    // Disconnect asks for confirmation first.
    await page.getByRole('button', { name: 'Déconnecter', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('Supprimer la connexion SimpleFIN enregistrée')

    // Cancelling keeps the connection.
    await dialog.getByRole('button', { name: 'Annuler' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByText('••••demo')).toBeVisible()

    // Confirming brings the token form back.
    await page.getByRole('button', { name: 'Déconnecter', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click()
    await expect(page.locator('#simplefin-token')).toBeVisible()
    await expect(page.getByText('••••demo')).toHaveCount(0)
  })
})

test.describe('SimpleFIN — Add account entry', () => {
  test('SimpleFIN entry is listed and opens the connection form', async ({ page }) => {
    await setupLocale(page)
    await page.goto('/accounts')
    await page.waitForLoadState('networkidle')

    // Dismiss the sidebar-style onboarding dialog if present
    const closeOnboarding = page.getByRole('button', { name: 'Close' })
    if (await closeOnboarding.isVisible()) {
      await closeOnboarding.click()
    }

    await page.getByRole('button', { name: 'Ajouter un compte' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const entry = dialog.getByRole('button', { name: /SimpleFIN/ })
    await expect(entry).toBeVisible()
    await expect(entry).toContainText('Importez vos comptes bancaires avec un jeton SimpleFIN')

    await entry.click()

    // Wizard step: back button, token input and the Bridge link.
    await expect(dialog.getByRole('button', { name: /Retour|Back/i })).toBeVisible()
    await expect(dialog.locator('#simplefin-token')).toBeVisible()
    await expectTokenLink(page)

    // Back returns to the source list.
    await dialog.getByRole('button', { name: /Retour|Back/i }).click()
    await expect(dialog.getByRole('button', { name: /SimpleFIN/ })).toBeVisible()
  })
})
