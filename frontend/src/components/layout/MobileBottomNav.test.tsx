import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', changeLanguage: () => {} },
  }),
}))

const { MobileBottomNav } = await import('./MobileBottomNav')

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <MobileBottomNav />
    </MemoryRouter>,
  )
}

describe('MobileBottomNav', () => {
  it('reaches the family view, like the desktop sidebar', () => {
    renderAt('/')

    expect(screen.getByRole('link', { name: 'nav.family' })).toHaveAttribute('href', '/family')
  })

  it('marks Famille as the current page on /family and nothing else', () => {
    renderAt('/family')

    const current = screen.getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
    expect(current.map((link) => link.getAttribute('href'))).toEqual(['/family'])
  })
})
