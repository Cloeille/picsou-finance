import { test, expect, type Page } from '@playwright/test'
import { setupLocale } from './helpers'

// Issue #273: at phone widths, nothing may stick out of the screen or of the card that clips
// it. French labels are the longest the app ships, so the check runs in French.
test.use({ viewport: { width: 360, height: 800 } })

/**
 * Visible elements of <main> whose right edge passes the edge of whatever clips them: the
 * viewport, or an ancestor with overflow other than visible. A strip that scrolls only
 * sideways is allowed to hold wider content, since the user can swipe to it.
 */
async function clippedElements(page: Page): Promise<string[]> {
  return page.locator('main').evaluate((main) => {
    const found: string[] = []
    for (const el of main.querySelectorAll<HTMLElement>('*')) {
      if (el.closest('svg, [aria-hidden="true"], .sr-only, .recharts-wrapper')) continue
      const style = getComputedStyle(el)
      const box = el.getBoundingClientRect()
      if (style.visibility === 'hidden' || box.width === 0 || box.height === 0) continue
      let limit = window.innerWidth
      for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
        const overflowX = getComputedStyle(a).overflowX
        const sidewaysScroller =
          (overflowX === 'auto' || overflowX === 'scroll') &&
          a.scrollWidth > a.clientWidth + 1 &&
          a.scrollHeight <= a.clientHeight + 1
        if (sidewaysScroller) { limit = Infinity; break }
        if (overflowX !== 'visible') limit = Math.min(limit, a.getBoundingClientRect().right)
      }
      if (box.right > limit + 1 && box.left < limit) {
        const label = (el.innerText || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 40)
        found.push(`${label} (right ${Math.round(box.right)} > ${Math.round(limit)})`)
      }
    }
    return found
  })
}

async function open(page: Page, path: string) {
  await setupLocale(page)
  await page.goto(path)
  await page.waitForLoadState('networkidle')
}

// Each page with the label that used to be cut, waited for since demo data arrives late.
const PAGES = [
  ['/settings', 'Système'],
  ['/settings/family', 'Sélectionner'],
  ['/analysis', '30 ans'],
  ['/accounts/2', '+ Ajouter'],
  ['/goals', 'Apport immobilier'],
  ['/goals/1/calendar', /^Calendrier de/],
] as const

test.describe('Phone layout (360px)', () => {
  for (const [path, label] of PAGES) {
    test(`nothing is clipped on ${path}`, async ({ page }) => {
      await open(page, path)
      await expect(page.getByRole('main').getByText(label).first()).toBeVisible()
      expect(await clippedElements(page)).toEqual([])
    })
  }

  test('goal names are shown in full, not cut with an ellipsis', async ({ page }) => {
    const isCut = (el: HTMLElement) => el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1
    await open(page, '/goals')
    for (const name of ['Vacances été 2025', 'Apport immobilier']) {
      const title = page.getByRole('main').getByText(name, { exact: true })
      await expect(title).toBeVisible()
      expect(await title.evaluate(isCut)).toBe(false)
    }
    await open(page, '/goals/1/calendar')
    const heading = page.getByRole('heading', { level: 1 })
    await expect(heading).toHaveText(/Vacances été 2025/)
    expect(await heading.evaluate(isCut)).toBe(false)
  })

  test('every language of the settings switch is on screen', async ({ page }) => {
    await open(page, '/settings')
    for (const code of ['FR', 'EN', 'DE', 'ES']) {
      await expect(page.getByRole('main').getByRole('button', { name: code, exact: true })).toBeInViewport({ ratio: 1 })
    }
  })

  test('sync providers sit on one swipeable row', async ({ page }) => {
    await open(page, '/sync')
    await expect(page.getByRole('tab', { name: 'Comptes' })).toBeAttached()
    const tops = await page.getByRole('tab').evaluateAll((tabs) =>
      new Set(tabs.map((tab) => Math.round(tab.getBoundingClientRect().top))).size,
    )
    expect(tops).toBe(1)
  })

  test('account filters sit on one swipeable row', async ({ page }) => {
    await open(page, '/accounts')
    const filter = page.getByRole('main').getByRole('button', { name: 'Actions & Fonds' })
    await expect(filter).toBeVisible()
    const tops = await filter.locator('xpath=..').locator('button')
      .evaluateAll((buttons) => new Set(buttons.map((b) => Math.round(b.getBoundingClientRect().top))).size)
    expect(tops).toBe(1)
  })

  test('the active budget section is scrolled into view', async ({ page }) => {
    await open(page, '/budget/review')
    await expect(page.getByRole('link', { name: /À catégoriser/ })).toBeInViewport({ ratio: 1 })
  })

  test('a deep link to the last sync tab shows that tab', async ({ page }) => {
    await open(page, '/sync?tab=visibility')
    const tab = page.getByRole('tab', { name: 'Comptes' })
    await expect(tab).toHaveAttribute('aria-selected', 'true')
    await expect(tab).toBeInViewport({ ratio: 1 })
  })
})
