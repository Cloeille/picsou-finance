import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (key) => m.get(key) ?? null,
    setItem: (key, value) => void m.set(key, String(value)),
    removeItem: (key) => void m.delete(key),
    clear: () => m.clear(),
    key: (index) => [...m.keys()][index] ?? null,
    get length() { return m.size },
  } as Storage
}

vi.stubGlobal('localStorage', memoryStorage())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', changeLanguage: () => {} },
  }),
}))

const { SidebarStylePromptModal } = await import('./SidebarStylePromptModal')
const { NAV_ITEMS } = await import('./sidebar-nav-items')

function sketch(optionLabel: string) {
  render(<SidebarStylePromptModal open onOpenChange={() => {}} />)
  const column = screen.getByRole('radio', { name: optionLabel }).querySelector<HTMLElement>('[data-slot="preview-sidebar"]')
  if (!column) throw new Error(`no sidebar sketch for ${optionLabel}`)
  const [header, nav, member] = Array.from(column.children)
  return { column, header, nav, member }
}

describe('SidebarStylePromptModal miniatures', () => {
  it('sketches the classic sidebar as a fit-height card that ends on the member row, right under the nav list', () => {
    const { column, nav, member } = sketch('settings.sidebarStyleClassic')

    expect(column).toHaveClass('h-fit')
    expect(column).not.toHaveClass('h-full')
    expect(column.children).toHaveLength(3)
    expect(nav.nextElementSibling).toBe(member)
    expect(member).not.toHaveClass('mt-auto')
    expect(member.querySelector('.lucide-settings')).toBeNull()
    expect(member.querySelector('.lucide-chevron-down')).not.toBeNull()
  })

  it('lists Settings between the routes and Family in the classic sketch, as the classic sidebar does', () => {
    const { nav } = sketch('settings.sidebarStyleClassic')
    const rows = Array.from(nav.children)

    expect(rows).toHaveLength(NAV_ITEMS.length + 2)
    expect(rows.at(-2)?.querySelector('.lucide-settings')).not.toBeNull()
    expect(rows.at(-1)?.querySelector('.lucide-users')).not.toBeNull()
  })

  it('sketches the current sidebar full height, with the member row and the settings gear pinned to the bottom', () => {
    const { column, nav, member } = sketch('settings.sidebarStyleCurrent')

    expect(column).toHaveClass('h-full')
    expect(member).toHaveClass('mt-auto')
    expect(member.querySelector('.lucide-settings')).not.toBeNull()
    expect(nav.children).toHaveLength(NAV_ITEMS.length + 1)
  })

  it.each(['settings.sidebarStyleClassic', 'settings.sidebarStyleCurrent'])(
    'draws %s with the logo left of the privacy toggle and two-line nav rows',
    (label) => {
      const { column, header, nav } = sketch(label)

      expect(column).not.toHaveClass('items-center')
      expect(header).toHaveClass('justify-between')
      expect(header.firstElementChild?.tagName).toBe('IMG')
      expect(header.lastElementChild).toHaveClass('lucide-eye')
      for (const row of Array.from(nav.children)) {
        expect(row.lastElementChild?.children).toHaveLength(2)
      }
    },
  )
})
