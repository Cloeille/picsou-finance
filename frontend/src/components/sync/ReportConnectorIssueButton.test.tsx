import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { useAppStore } from '@/stores/app-store'
import {
  buildConnectorReport,
  extractTypedErrorCode,
  PICSOU_NEW_ISSUE_URL,
  sanitizeConnectorErrorCode,
} from '@/lib/connector-report'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({ api: { get: apiGet, post: apiPost, delete: apiDelete } }))
vi.mock('@/lib/app-version', () => ({ APP_VERSION: '1.4.0' }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))

const { ReportConnectorIssueButton } = await import('./ReportConnectorIssueButton')
const { AmundiTab } = await import('@/pages/sync/AmundiTab')
const { BoursoPanel } = await import('./BoursoPanel')
const { BankSyncTab } = await import('@/pages/sync/BankSyncTab')

const FORBIDDEN_PII = [
  'FR7630006000011234567890189',
  'Jane Doe',
  'jane.doe@example.com',
  'Compte Courant Joint',
  '42 150,75',
  'raw upstream bank message',
]

function renderWithQuery(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={['/sync?tab=banks']}>
      <QueryClientProvider client={client}>{ui}</QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('connector-report helpers', () => {
  it('builds a GitHub new-issue URL containing only the allowed technical fields', () => {
    const report = buildConnectorReport({
      connectorId: 'bourso',
      errorCode: 'UPSTREAM_FORMAT_CHANGED',
      appVersion: '1.4.0-rc.1',
      runtime: 'desktop',
    })
    const parsed = new URL(report.url)
    expect(`${parsed.origin}${parsed.pathname}`).toBe(PICSOU_NEW_ISSUE_URL)
    expect(Array.from(parsed.searchParams.keys()).sort()).toEqual(['body', 'title'])
    expect(parsed.searchParams.get('title')).toBe('Broken bank connector: bourso (UPSTREAM_FORMAT_CHANGED)')
    expect(parsed.searchParams.get('body')).toBe(
      '- Connector: bourso\n- Error code: UPSTREAM_FORMAT_CHANGED\n- App version: 1.4.0-rc.1\n- Runtime: desktop',
    )
  })

  it('rejects arbitrary non-version strings as appVersion', () => {
    for (const invalid of ['test-version', 'JaneDoe', 'FR7630006000011234567890189', '1234567890.0.0', '1.4']) {
      const report = buildConnectorReport({
        connectorId: 'amundi',
        errorCode: 'INTERNAL_ERROR',
        appVersion: invalid,
        runtime: 'web',
      })
      expect(report.appVersion).toBe('unknown')
      expect(report.body).toContain('- App version: unknown')
      expect(report.url).not.toContain(invalid)
    }
  })

  it('drops any non-allowlisted error code or free-text bank message', () => {
    const rawBankError =
      'Bank rejected FR7630006000011234567890189 for Jane Doe (42 150,75 EUR) on Compte Courant Joint'
    expect(sanitizeConnectorErrorCode(rawBankError)).toBe('UNKNOWN')
    expect(extractTypedErrorCode({ response: { status: 422, data: { detail: rawBankError } } })).toBe('UNKNOWN')

    const report = buildConnectorReport({
      connectorId: 'revolut',
      errorCode: rawBankError,
      appVersion: '1.4.0',
      runtime: 'web',
    })
    for (const forbidden of FORBIDDEN_PII) {
      expect(report.url).not.toContain(forbidden)
      expect(report.title).not.toContain(forbidden)
      expect(report.body).not.toContain(forbidden)
    }
    expect(report.errorCode).toBe('UNKNOWN')
  })

  it('extracts known typed codes and HTTP 429 rate-limit codes without reading free text', () => {
    expect(
      extractTypedErrorCode({ response: { status: 422, data: { code: 'PORTFOLIO_INCOMPLETE', detail: 'Jane Doe' } } }),
    ).toBe('PORTFOLIO_INCOMPLETE')
    expect(extractTypedErrorCode({ response: { status: 429, data: {} } })).toBe('RATE_LIMITED')
  })
})

describe('ReportConnectorIssueButton', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    useAppStore.setState({ demoMode: false, desktopMode: false })
  })

  it('previews the exact prefilled text, uses foreground ink, and keeps the dialog open on link click', () => {
    useAppStore.setState({ desktopMode: true })

    render(<ReportConnectorIssueButton connectorId="amundi" errorCode="UPSTREAM_FORMAT_CHANGED" />)

    const trigger = screen.getByRole('button', { name: 'sync.reportProblem.action' })
    expect(trigger).toHaveClass('text-foreground', 'bg-background')
    fireEvent.click(trigger)

    const titlePreview = screen.getByTestId('connector-report-title').textContent ?? ''
    const bodyPreview = screen.getByTestId('connector-report-body').textContent ?? ''
    const link = screen.getByRole('link', { name: 'sync.reportProblem.openGithub' })

    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')

    const parsed = new URL(link.getAttribute('href') ?? '')
    expect(`${parsed.origin}${parsed.pathname}`).toBe(PICSOU_NEW_ISSUE_URL)
    expect(parsed.searchParams.get('title')).toBe(titlePreview)
    expect(parsed.searchParams.get('body')).toBe(bodyPreview)
    expect(bodyPreview).toBe(
      '- Connector: amundi\n- Error code: UPSTREAM_FORMAT_CHANGED\n- App version: 1.4.0\n- Runtime: desktop',
    )

    // Leaving the dialog open avoids unmounting the anchor before the browser or WebView navigates.
    link.addEventListener('click', (e) => e.preventDefault())
    fireEvent.click(link)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(apiGet).not.toHaveBeenCalled()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('hides the report action in demo mode', () => {
    useAppStore.setState({ demoMode: true })
    render(<ReportConnectorIssueButton connectorId="amundi" errorCode="INTERNAL_ERROR" />)
    expect(screen.queryByRole('button', { name: 'sync.reportProblem.action' })).not.toBeInTheDocument()
  })

  it('surfaces the typed code from a failed SidecarSessionPanel sync without leaking bank details', async () => {
    apiGet.mockResolvedValue({
      data: {
        isActive: true,
        syncStatus: 'FAILED',
        lastSyncStartedAt: '2026-11-20T08:00:00Z',
        lastSyncCompletedAt: null,
        lastSyncError: 'PORTFOLIO_INCOMPLETE',
      },
    })
    apiPost.mockRejectedValueOnce({
      response: {
        status: 422,
        data: {
          code: 'UPSTREAM_FORMAT_CHANGED',
          detail: 'raw upstream bank message for Jane Doe FR7630006000011234567890189 Compte Courant Joint 42 150,75',
        },
      },
    })

    renderWithQuery(<AmundiTab />)
    await screen.findByText('sync.amundi.errors.portfolioIncomplete')

    fireEvent.click(screen.getByRole('button', { name: 'sync.amundi.sync' }))
    await screen.findByText('sync.amundi.errors.formatChanged')

    fireEvent.click(screen.getByRole('button', { name: 'sync.reportProblem.action' }))
    const href = screen.getByRole('link', { name: 'sync.reportProblem.openGithub' }).getAttribute('href') ?? ''
    const parsed = new URL(href)

    expect(parsed.searchParams.get('title')).toBe('Broken bank connector: amundi (UPSTREAM_FORMAT_CHANGED)')
    expect(parsed.searchParams.get('body')).toBe(
      '- Connector: amundi\n- Error code: UPSTREAM_FORMAT_CHANGED\n- App version: 1.4.0\n- Runtime: web',
    )
    for (const forbidden of FORBIDDEN_PII) {
      expect(href).not.toContain(forbidden)
      expect(decodeURIComponent(href)).not.toContain(forbidden)
    }
  })

  it('surfaces the typed code from a failed BoursoPanel sync without leaking bank details', async () => {
    apiGet.mockResolvedValue({
      data: {
        isActive: true,
        syncStatus: 'FAILED',
        lastSyncStartedAt: '2026-11-20T08:00:00Z',
        lastSyncCompletedAt: null,
        lastSyncError: 'PORTFOLIO_INCOMPLETE',
      },
    })
    apiPost.mockRejectedValueOnce({
      response: {
        status: 422,
        data: { code: 'UPSTREAM_FORMAT_CHANGED', detail: 'raw upstream bank message for Jane Doe' },
      },
    })

    renderWithQuery(<BoursoPanel />)
    await screen.findByText('sync.bourso.errors.portfolioIncomplete')

    fireEvent.click(screen.getByRole('button', { name: 'sync.bourso.sync' }))
    await screen.findByText('sync.bourso.errors.formatChanged')

    fireEvent.click(screen.getByRole('button', { name: 'sync.reportProblem.action' }))
    const href = screen.getByRole('link', { name: 'sync.reportProblem.openGithub' }).getAttribute('href') ?? ''
    const parsed = new URL(href)
    expect(parsed.searchParams.get('title')).toBe('Broken bank connector: bourso (UPSTREAM_FORMAT_CHANGED)')
    expect(decodeURIComponent(href)).not.toContain('Jane Doe')
  })

  it('offers report on a failed Enable Banking row without leaking the institution name', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/sync/status') {
        return Promise.resolve({
          data: [
            {
              id: 1,
              institutionId: 'Compte Courant Joint::FR::personal',
              institutionName: 'Compte Courant Joint',
              status: 'FAILED',
              lastSyncedAt: null,
            },
          ],
        })
      }
      if (url === '/sync/countries') return Promise.resolve({ data: ['FR'] })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })

    renderWithQuery(<BankSyncTab />)
    const bankLabel = await screen.findByText('Compte Courant Joint')
    const row = bankLabel.closest('[data-slot="card"]') as HTMLElement

    fireEvent.click(within(row).getByRole('button', { name: 'sync.reportProblem.action' }))
    const href = screen.getByRole('link', { name: 'sync.reportProblem.openGithub' }).getAttribute('href') ?? ''
    const parsed = new URL(href)
    expect(parsed.searchParams.get('title')).toBe('Broken bank connector: enable-banking (SYNC_FAILED)')
    expect(parsed.searchParams.get('body')).toBe(
      '- Connector: enable-banking\n- Error code: SYNC_FAILED\n- App version: 1.4.0\n- Runtime: web',
    )
    expect(decodeURIComponent(href)).not.toContain('Compte Courant Joint')
  })
})
