import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const appLocale = vi.hoisted(() => ({ language: 'de' }))
const adminHooks = vi.hoisted(() => ({
  useAiCalls: vi.fn(),
  useEbCallLog: vi.fn(),
  useClearEbCallLog: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: {
      resolvedLanguage: appLocale.language,
      language: appLocale.language,
    },
  }),
}))

vi.mock('@/features/admin/hooks', () => adminHooks)

const { AiActivitySection } = await import('./AiActivitySection')
const { EnableBankingDebugSection } = await import('./EnableBankingDebugSection')

function localeName(locales?: Intl.LocalesArgument): string {
  if (locales == null) return 'browser-default'
  if (typeof locales === 'string') return locales
  if (Array.isArray(locales)) return String(locales[0])
  return locales.toString()
}

describe('admin timestamps', () => {
  beforeEach(() => {
    appLocale.language = 'de'
    document.documentElement.lang = 'en'
    adminHooks.useAiCalls.mockReturnValue({
      data: {
        items: [{
          id: 1,
          createdAt: '2026-06-26T10:00:00Z',
          merchantLabel: 'Synthetic merchant',
          provider: 'synthetic',
          model: null,
          promptTokens: 1,
          completionTokens: 1,
          totalTokens: 2,
          status: 'OK',
          error: null,
          prompt: null,
          response: null,
        }],
        total: 1,
        totalTokens: 2,
      },
      isLoading: false,
      isError: false,
    })
    adminHooks.useEbCallLog.mockReturnValue({
      data: [{
        timestamp: '2026-06-26T10:00:00Z',
        method: 'GET',
        url: 'https://api.enablebanking.com/synthetic',
        responseStatus: 200,
        requestBody: null,
        responseBody: '{}',
      }],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    })
    adminHooks.useClearEbCallLog.mockReturnValue({ isPending: false, mutate: vi.fn() })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('formats AI activity with the app locale and follows a mounted language switch', () => {
    vi.spyOn(Date.prototype, 'toLocaleString').mockImplementation((locales) => (
      `date:${localeName(locales)}`
    ))
    const view = render(<AiActivitySection />)
    fireEvent.click(screen.getByText('admin.aiActivity.view'))

    expect(screen.getByText('date:de-DE')).toBeInTheDocument()

    appLocale.language = 'es'
    view.rerender(<AiActivitySection />)
    expect(screen.getByText('date:es-ES')).toBeInTheDocument()
  })

  it('formats Enable Banking call times with the app locale and follows a mounted language switch', () => {
    vi.spyOn(Date.prototype, 'toLocaleTimeString').mockImplementation((locales) => (
      `time:${localeName(locales)}`
    ))
    const view = render(<EnableBankingDebugSection />)
    fireEvent.click(screen.getByText('Voir les appels'))

    expect(screen.getByText('time:de-DE')).toBeInTheDocument()

    appLocale.language = 'es'
    view.rerender(<EnableBankingDebugSection />)
    expect(screen.getByText('time:es-ES')).toBeInTheDocument()
  })
})
