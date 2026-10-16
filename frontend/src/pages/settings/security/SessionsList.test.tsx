import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { SessionItem } from '@/features/mfa/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { sessions, revoke } = vi.hoisted(() => ({
  sessions: [] as SessionItem[],
  revoke: vi.fn(),
}))

vi.mock('@/features/mfa/hooks', () => ({
  useSessions: () => ({ data: sessions, isLoading: false }),
  useRevokeSession: () => ({ mutate: revoke, isPending: false, variables: undefined }),
  useRevokeAllSessionsExceptCurrent: () => ({ mutate: vi.fn(), isPending: false }),
}))

import { SessionsList } from './SessionsList'

describe('SessionsList', () => {
  it('labels an iOS app sign-in and revokes it by its opaque id', () => {
    sessions.splice(0, sessions.length,
      {
        id: '3',
        kind: 'REMEMBER_ME',
        userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15',
        ipPrefix: '192.168.1',
        createdAt: '2026-10-01T08:00:00Z',
        lastUsedAt: '2026-10-05T08:00:00Z',
        expiresAt: '2026-12-30T08:00:00Z',
        trustedFor2fa: false,
        current: true,
      },
      {
        id: '0b6f3c1e-4c1d-4a8e-9f0a-2d7e5b1c9a11',
        kind: 'IOS_APP',
        createdAt: '2026-10-02T08:00:00Z',
        lastUsedAt: '2026-10-05T07:45:00Z',
        expiresAt: '2026-11-01T08:00:00Z',
        trustedFor2fa: false,
        current: false,
      },
    )

    render(<SessionsList />)

    expect(screen.getByText('Safari · macOS')).toBeInTheDocument()
    expect(screen.getByText('settings.sessionsIosApp')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /settings.sessionsRevoke$/ }))
    expect(revoke).toHaveBeenCalledWith('0b6f3c1e-4c1d-4a8e-9f0a-2d7e5b1c9a11')
  })
})
