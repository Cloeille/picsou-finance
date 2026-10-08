import { test, expect } from '@playwright/test'
import { setupLocale } from './helpers'

// Runs against the demo-mode dev server (VITE_DEMO_MODE=true): every /simplefin/* call is served by
// the stateful in-memory handlers in src/demo/index.ts, which reset on every full page load.

test.beforeEach(async ({ page }) => {
  await setupLocale(page)
})

test('connects then disconnects from the Sync page SimpleFIN tab', async ({ page }) => {
  await page.goto('/sync')
  await page.getByRole('tab', { name: 'SimpleFIN', exact: true }).click()

  await page.locator('#simplefin-token').fill('demo-setup-token')
  await page.getByRole('button', { name: 'Connecter', exact: true }).click()

  await expect(page.getByText('••••demo')).toBeVisible()
  await expect(page.locator('#simplefin-token')).toHaveCount(0)

  await page.getByRole('button', { name: 'Déconnecter', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click()

  await expect(page.locator('#simplefin-token')).toBeVisible()
  await expect(page.getByText('••••demo')).toHaveCount(0)
})

test('the add-account modal opens the SimpleFIN connection form', async ({ page }) => {
  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Ajouter un compte' }).click()

  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: /SimpleFIN/ }).click()

  await expect(dialog.locator('#simplefin-token')).toBeVisible()
  await expect(dialog.getByRole('link', { name: 'Créer un jeton' })).toHaveAttribute(
    'href',
    'https://bridge.simplefin.org/simplefin/create',
  )
})
