import type { Page } from '@playwright/test'

/**
 * Set French locale before any page loads.
 * Uses addInitScript to ensure localStorage is set before i18next initializes.
 */
async function setupLocale(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('picsou-locale', 'fr')
    localStorage.setItem('picsou-app', JSON.stringify({
      state: {
        sidebarCollapsed: false,
        dateFormat: 'locale',
        sidebarStyle: 'current',
        hasSeenSidebarStylePrompt: true,
      },
      version: 0,
    }))
  })
}

/**
 * Navigate to the app. In demo mode, /login redirects to / automatically.
 */
export async function login(page: Page) {
  await setupLocale(page)
  await page.goto('/')
  await page.waitForURL('/')
}

export { setupLocale }
