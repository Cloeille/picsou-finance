import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { SyncProgress } from '@/types/api'

// Key-echo translator: assertions target i18n keys, locale content is covered by locales-parity.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { RevolutPhaseLine } = await import('./RevolutPhaseLine')

const progress: SyncProgress = {
  running: true, phase: 'AWAITING_APPROVAL', elapsedSeconds: 5, remainingSeconds: 290, accountsFound: null,
  done: false, error: null, errorCode: null, viewerPort: null, viewerPassword: null, discovered: [],
}

describe('RevolutPhaseLine', () => {
  it('shows only the phase when no captcha is being handed over', () => {
    render(<RevolutPhaseLine progress={progress} />)

    expect(screen.getByText('sync.revolut.phase.awaitingApproval')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('offers the captcha window in a new tab, without leaking the opener, during a hand-off', () => {
    render(<RevolutPhaseLine progress={{
      ...progress, phase: 'AWAITING_HUMAN', viewerPort: 6080, viewerPassword: 'Ab3dEf9Z',
    }} />)

    const link = screen.getByRole('link', { name: 'sync.revolut.openViewer' })
    expect(screen.getByText('sync.revolut.phase.awaitingHuman')).toBeInTheDocument()
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(link.getAttribute('href')).toBe(
      `http://${window.location.hostname}:6080/vnc.html#autoconnect=true&resize=scale&password=Ab3dEf9Z`)
  })
})
