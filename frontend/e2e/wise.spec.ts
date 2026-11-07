import { test, expect } from '@playwright/test'
import { setupLocale } from './helpers'

// Runs against the demo-mode dev server (VITE_DEMO_MODE=true): every /wise/* call is served by
// the stateful in-memory handlers in src/demo/index.ts, which reset on every full page load.

test.beforeEach(async ({ page }) => {
  await setupLocale(page)
})

test('connects then disconnects from the Sync page Wise Jars tab', async ({ page }) => {
  await page.goto('/sync')
  await page.getByRole('tab', { name: 'Wise Jars', exact: true }).click()

  await expect(page.getByText(/Ce connecteur sert uniquement à vos Jars Wise/)).toBeVisible()
  await page.locator('#wise-token').fill('demo-wise-token')
  await page.getByRole('button', { name: 'Connecter', exact: true }).click()

  await expect(page.getByText('••••demo')).toBeVisible()
  await expect(page.locator('#wise-token')).toHaveCount(0)

  await page.getByRole('button', { name: 'Déconnecter', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Supprimer' }).click()

  await expect(page.locator('#wise-token')).toBeVisible()
  await expect(page.getByText('••••demo')).toHaveCount(0)
})

test('the add-account modal opens the Wise Jars connection form', async ({ page }) => {
  await page.goto('/accounts')
  await page.getByRole('button', { name: 'Ajouter un compte' }).click()

  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: /Wise Jars/ }).click()

  await expect(dialog.locator('#wise-token')).toBeVisible()
  await expect(dialog.getByRole('link', { name: 'Créer un jeton' })).toHaveAttribute(
    'href',
    'https://wise.com/your-account/integrations-and-tools/api-tokens',
  )
})
