import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AlertBanner } from './AlertBanner'

describe('AlertBanner', () => {
  it('renders the message with the default inline classes', () => {
    render(<AlertBanner>Something broke</AlertBanner>)
    const message = screen.getByText('Something broke')
    expect(message.parentElement).toHaveClass(
      'flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive',
      { exact: true },
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('calls onDismiss from the x button', () => {
    const onDismiss = vi.fn()
    render(<AlertBanner onDismiss={onDismiss}>Oops</AlertBanner>)
    fireEvent.click(screen.getByRole('button', { name: 'x' }))
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

  it('renders icon before the message and a custom action after it', () => {
    render(
      <AlertBanner variant="roomy" role="alert" icon={<i data-testid="icon" />} action={<b data-testid="act" />}>
        Msg
      </AlertBanner>,
    )
    const kids = Array.from(screen.getByRole('alert').children).map((c) => c.getAttribute('data-testid') ?? c.tagName)
    expect(kids).toEqual(['icon', 'SPAN', 'act'])
  })
})
