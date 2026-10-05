import { test, expect } from '@playwright/test'
import { login } from './helpers'
import { Buffer } from 'node:buffer'

test.describe('Sync page tabs', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    // Sync is no longer a sidebar entry — navigate to the route directly.
    await page.goto('/sync')
    await page.waitForURL('**/sync')
  })

  test('should expose every supported provider tab', async ({ page }) => {
    const providerNames = [
      'Banques',
      'Exchanges',
      'Wallets',
      'Trade Republic',
      'Revolut',
      'BoursoBank',
      'Bourse Direct',
      'DEGIRO',
      'Interactive Brokers',
      'Amundi',
      'Fortuneo',
      'American Express',
      'CORUM',
      'Sofidy',
      'Actual Budget',
      'Finary',
      'Import HomeBank',
      'Comptes',
    ]

    for (const name of providerNames) {
      await expect(page.getByRole('tab', { name, exact: true })).toBeVisible()
    }
    await expect(page.getByRole('tab')).toHaveCount(providerNames.length)
  })

  test('should require a currency for QIF files and reset it for HomeBank backups', async ({ page }) => {
    await page.getByRole('tab', { name: 'Import HomeBank', exact: true }).click()
    const fileInput = page.getByLabel('Fichier HomeBank')
    const qif = Buffer.from('!Account\nNSynthetic Checking\nTBank\n^\n!Type:Bank\nD2024/01/01\nT-12.34\nPTest transaction\n^\n')
    await fileInput.setInputFiles({
      name: 'sample.qif',
      mimeType: 'text/plain',
      buffer: qif,
    })

    const previewButton = page.getByRole('button', { name: "Prévisualiser l'import" })
    const currencyInput = page.getByLabel('Devise du QIF (ISO 4217)')
    await expect(currencyInput).toBeVisible()
    await expect(page.getByLabel('Mot de passe du fichier (si nécessaire)')).toHaveCount(0)
    await expect(previewButton).toBeDisabled()

    await currencyInput.fill('ABC')
    await expect(previewButton).toBeDisabled()
    await currencyInput.fill('EUR')
    await expect(previewButton).toBeEnabled()

    await fileInput.setInputFiles({
      name: 'sample.hbexport',
      mimeType: 'application/octet-stream',
      buffer: Buffer.from('synthetic HomeBank backup fixture'),
    })
    await expect(page.getByLabel('Mot de passe du fichier (si nécessaire)')).toBeVisible()
    await expect(page.getByLabel('Devise du QIF (ISO 4217)')).toHaveCount(0)

    await fileInput.setInputFiles({
      name: 'sample.qif',
      mimeType: 'text/plain',
      buffer: qif,
    })
    await expect(page.getByLabel('Devise du QIF (ISO 4217)')).toHaveValue('')
    await expect(page.getByLabel('Mot de passe du fichier (si nécessaire)')).toHaveCount(0)
  })

  test('should show the disconnected Fortuneo panel without a contract error', async ({ page }) => {
    await page.getByRole('tab', { name: 'Fortuneo', exact: true }).click()

    await expect(page.getByText('Aucune session active')).toBeVisible()
    await expect(page.getByLabel('Identifiant')).toBeVisible()
    await expect(page.getByText(/Invalid input|syncStatus|ZodError/)).toHaveCount(0)
  })

  // Switching tabs no longer rewrites the URL (?tab= is only read as the
  // initial tab), so assert on the revealed content instead of waitForURL.
  test('should switch to Exchanges tab', async ({ page }) => {
    await page.getByRole('tab', { name: 'Exchanges' }).click()
    // Exchanges content should be visible
    await expect(page.getByText('Ajouter un exchange')).toBeVisible()
  })

  test('should switch to Wallets tab', async ({ page }) => {
    await page.getByRole('tab', { name: 'Wallets' }).click()
    // Wallets content should be visible
    await expect(page.getByText('Ajouter un wallet')).toBeVisible()
  })

  test('should switch to Finary tab and show login + file import', async ({ page }) => {
    await page.getByRole('tab', { name: 'Finary' }).click()
    // API-login form should be visible
    await expect(page.getByText('Se connecter à Finary')).toBeVisible()
    // Upload area should be visible
    await expect(page.getByText('Importer un fichier Finary (.xlsx)').first()).toBeVisible()
  })

  test('should switch to Actual Budget tab and show the file import', async ({ page }) => {
    await page.getByRole('tab', { name: 'Actual Budget', exact: true }).click()
    await expect(page.getByText('Importer un export Actual Budget')).toBeVisible()
    await expect(page.getByRole('button', { name: "Prévisualiser l'import" })).toBeDisabled()
  })

  test('should open a tab directly via the ?tab= query param', async ({ page }) => {
    await page.goto('/sync?tab=exchanges')
    await expect(page.getByText('Ajouter un exchange')).toBeVisible()
  })
})
