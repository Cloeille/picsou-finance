import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SidebarWhatsNewCard } from './SidebarWhatsNewCard'
import { useAppStore } from '@/stores/app-store'
import { getLatestWhatsNew, PICSOU_GITHUB_URL } from '@/lib/whats-new'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

function renderCard() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <SidebarWhatsNewCard />
    </QueryClientProvider>,
  )
}

describe('SidebarWhatsNewCard', () => {
  beforeEach(() => {
    useAppStore.setState({ lastSeenWhatsNewVersion: null, demoMode: true })
  })

  it('shows latest release preview and GitHub star link', () => {
    const latest = getLatestWhatsNew()
    renderCard()

    expect(screen.getByText(`v${latest.version}`)).toBeInTheDocument()
    expect(screen.getByText(latest.summary.fr)).toBeInTheDocument()

    const starLink = screen.getByRole('link', { name: 'whatsNew.starGithub' })
    expect(starLink).toHaveAttribute('href', PICSOU_GITHUB_URL)
  })

  it('opens the changelog modal and marks the latest version as seen', () => {
    const latest = getLatestWhatsNew()
    renderCard()

    fireEvent.click(screen.getByRole('button', { name: /whatsNew\.title/ }))

    expect(useAppStore.getState().lastSeenWhatsNewVersion).toBe(latest.version)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText(latest.highlights.fr[0])).toBeInTheDocument()
  })
})
