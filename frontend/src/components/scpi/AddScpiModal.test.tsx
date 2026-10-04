import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AddScpiModal } from './AddScpiModal'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
}))

vi.mock('@/features/accounts/hooks', () => ({
  useCreateAccount: () => ({ mutateAsync: vi.fn(), isPending: false, error: null }),
  useUpdateScpiPosition: () => ({ mutateAsync: vi.fn(), isPending: false, error: null }),
}))

vi.mock('@/components/shared/DateInput', () => ({
  DateInput: () => <input aria-label="scpi.jouissanceDate" />,
}))

describe('AddScpiModal', () => {
  it('asks for the two prices and never for an address', () => {
    render(<AddScpiModal open onOpenChange={() => {}} />)

    expect(screen.getByLabelText('scpi.shareCount')).toBeInTheDocument()
    expect(screen.getByLabelText('scpi.withdrawalPrice')).toBeInTheDocument()
    expect(screen.getByLabelText('scpi.subscriptionPrice')).toBeInTheDocument()
    expect(screen.queryByLabelText(/address/i)).toBeNull()
    expect(screen.queryByLabelText(/surface/i)).toBeNull()
  })
})
