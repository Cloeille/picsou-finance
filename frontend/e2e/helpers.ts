import type { Page } from '@playwright/test'

/**
 * Seed localStorage before any page loads: French locale (read when i18next
 * initializes) and the sidebar-style prompt marked as seen, since its first-run
 * modal would otherwise cover the app in a fresh browser context.
 */
export async function seedStorage(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('picsou-locale', 'fr')
    if (!localStorage.getItem('picsou-app')) {
      localStorage.setItem(
        'picsou-app',
        JSON.stringify({ state: { hasSeenSidebarStylePrompt: true }, version: 0 }),
      )
    }
  })
}

/**
 * Navigate to the app. In demo mode, /login redirects to / automatically.
 */
export async function login(page: Page) {
  await seedStorage(page)
  await page.goto('/')
  await page.waitForURL('/')
}
