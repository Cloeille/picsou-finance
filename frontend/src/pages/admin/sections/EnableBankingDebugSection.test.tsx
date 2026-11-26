import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { EnableBankingDebugSection } from './EnableBankingDebugSection'
import { buildEbCallLogExport, RAW_DATA_WARNING } from './ebCallLogExport'
import type { EbCallEntry } from '@/features/admin/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const ENTRY: EbCallEntry = {
  timestamp: '2026-11-21T09:00:00Z',
  method: 'GET',
  url: 'https://api.enablebanking.com/accounts/uid-synthetic/details',
  requestBody: '',
  responseStatus: 200,
  responseBody: '{"account_id":{"iban":"FR00SYNTHETIC"}}',
}

vi.mock('@/features/admin/hooks', () => ({
  useEbCallLog: () => ({ data: [ENTRY], isLoading: false, isError: false, refetch: vi.fn() }),
  useClearEbCallLog: () => ({ mutate: vi.fn(), isPending: false }),
}))

describe('EnableBankingDebugSection', () => {
  it('warns that the calls hold raw bank data before they can be exported', () => {
    render(<EnableBankingDebugSection />)
    fireEvent.click(screen.getByRole('button', { name: 'Voir les appels' }))

    expect(screen.getByRole('note')).toHaveTextContent(/IBAN/)
    expect(screen.getByRole('note')).toHaveTextContent(/identifiants de session/)
    expect(screen.getByRole('note')).toHaveTextContent(/avant de partager/)
  })
})

describe('buildEbCallLogExport', () => {
  it('opens the file with the raw-data warning, then the calls', () => {
    const text = buildEbCallLogExport([ENTRY])

    expect(text.startsWith(RAW_DATA_WARNING + '\n\n')).toBe(true)
    expect(RAW_DATA_WARNING).toContain('session ids')
    expect(text).toContain('GET https://api.enablebanking.com/accounts/uid-synthetic/details')
    expect(text).toContain('HTTP 200')
    expect(text).toContain('"iban": "FR00SYNTHETIC"')
    expect(text).not.toContain('>>> REQUEST BODY')
  })
})
