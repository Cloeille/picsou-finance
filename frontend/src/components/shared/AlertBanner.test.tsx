import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AlertBanner } from './AlertBanner'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

describe('AlertBanner', () => {
  it('renders the message with the default inline classes', () => {
    render(<AlertBanner>Something broke</AlertBanner>)
    const message = screen.getByText('Something broke')
    expect(message).toHaveClass('min-w-0', 'flex-1')
    expect(message.parentElement).toHaveClass(
      'flex flex-wrap items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive',
      { exact: true },
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('calls onDismiss from the x button', () => {
    const onDismiss = vi.fn()
    render(<AlertBanner onDismiss={onDismiss}>Oops</AlertBanner>)
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('merges className and exposes role=alert on paragraph variants', () => {
    render(
      <AlertBanner variant="bordered" role="alert" className="mt-2">
        Denied
      </AlertBanner>,
    )
    const alert = screen.getByRole('alert')
    expect(alert.tagName).toBe('P')
    expect(alert).toHaveClass(
      'rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive mt-2',
      { exact: true },
    )
  })

  it('renders icon before the message, action after it, and dismiss button last', () => {
    render(
      <AlertBanner
        variant="roomy"
        role="alert"
        icon={<i data-testid="icon" />}
        action={<b data-testid="act" />}
        onDismiss={vi.fn()}
      >
        Msg
      </AlertBanner>,
    )
    const kids = Array.from(screen.getByRole('alert').children).map((c) => c.getAttribute('data-testid') ?? c.tagName)
    expect(kids).toEqual(['icon', 'SPAN', 'act', 'BUTTON'])
  })

  it('supports the neutral surface variant with role=status', () => {
    render(
      <AlertBanner variant="neutral" role="status" onDismiss={vi.fn()}>
        Picsou 1.4.0 is available
      </AlertBanner>,
    )
    const banner = screen.getByRole('status')
    expect(banner).toHaveClass(
      'flex flex-wrap items-center gap-3 rounded-xl bg-card px-4 py-2.5 text-sm text-foreground ring-1 ring-foreground/10',
      { exact: true },
    )
  })
})
