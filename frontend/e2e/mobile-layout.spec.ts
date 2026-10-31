import { test, expect, type Page } from '@playwright/test'
import { login } from './helpers'

test.use({ viewport: { width: 390, height: 844 } })

const NAV_INSET = 16

function contentBox(page: Page) {
  return page.locator('#main-content .overflow-auto').first().evaluate((el) => {
    const style = getComputedStyle(el)
    const box = el.getBoundingClientRect()
    return {
      left: box.left + parseFloat(style.paddingLeft),
      right: box.right - parseFloat(style.paddingRight),
      top: box.top + parseFloat(style.paddingTop),
      bottom: box.bottom - parseFloat(style.paddingBottom),
    }
  })
}

test.describe('Mobile layout', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  for (const route of ['/accounts', '/goals', '/settings']) {
    test(`the end of ${route} stays clear of the bottom nav`, async ({ page }) => {
      await page.goto(route)

      const nav = (await page.locator('nav.fixed').boundingBox())!
      const content = await contentBox(page)
      expect(content.bottom).toBeLessThanOrEqual(nav.y - NAV_INSET)
    })
  }

  test('the end of a page also clears a home-indicator safe area', async ({ page }) => {
    // Playwright's viewport has no safe area, so env(safe-area-inset-bottom) is 0 above;
    // a CDP override gives it the height of an iPhone home indicator.
    const safeArea = 34
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { bottom: safeArea } })
    await page.goto('/accounts')

    const nav = (await page.locator('nav.fixed').boundingBox())!
    const content = await contentBox(page)
    expect(content.bottom).toBeLessThanOrEqual(nav.y - NAV_INSET)

    // The visible bar sits above the nav's safe-area spacer: it must clear the inset by its
    // own bottom-4, which also fails this test if the override had no effect.
    const bar = (await page.locator('nav.fixed > div').first().boundingBox())!
    expect(bar.y + bar.height).toBeLessThanOrEqual(page.viewportSize()!.height - safeArea - NAV_INSET)
  })

  test('page content lines up with the bottom nav gutter', async ({ page }) => {
    await page.goto('/accounts')

    const nav = (await page.locator('nav.fixed').boundingBox())!
    const content = await contentBox(page)
    expect(content.left).toBe(nav.x)
    expect(content.right).toBe(nav.x + nav.width)
    expect(content.top).toBeGreaterThanOrEqual(NAV_INSET)
  })

  test('Famille is reachable from the bottom nav', async ({ page }) => {
    const famille = page.locator('nav.fixed').getByRole('link', { name: 'Famille' })
    await famille.click()
    await page.waitForURL('**/family')
    await expect(famille).toHaveAttribute('aria-current', 'page')
  })
})
