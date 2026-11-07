import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { SetupStepLbp } from './SetupStepLbp'

const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  mutateAsync: vi.fn(),
  ack: {
    isPending: false,
    isError: false,
    error: null as unknown,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('react-router', () => ({
  useNavigate: () => mocks.navigate,
}))

vi.mock('@/features/setup/hooks', () => ({
  useAcknowledgeIntegration: () => ({
    ...mocks.ack,
    mutateAsync: mocks.mutateAsync,
  }),
}))

describe('SetupStepLbp', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.ack.isPending = false
    mocks.ack.isError = false
    mocks.ack.error = null
    useSetupFlowStore.getState().reset()
  })

  it('marks La Banque Postale complete and navigates only after acknowledgement succeeds', async () => {
    mocks.mutateAsync.mockResolvedValueOnce(undefined)

    render(<SetupStepLbp />)
    fireEvent.click(screen.getByRole('button', { name: 'setup.lbp.cta' }))

    await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith('/setup/done'))
    expect(mocks.mutateAsync).toHaveBeenCalledWith('lbp')
    expect(useSetupFlowStore.getState().completedIntegrations).toContain('lbp')
  })

  it('stays on the step and exposes a retryable error when acknowledgement fails', async () => {
    mocks.mutateAsync.mockRejectedValueOnce({
      response: { status: 503, data: { detail: 'Unavailable' } },
    })
    const view = render(<SetupStepLbp />)

    fireEvent.click(screen.getByRole('button', { name: 'setup.lbp.cta' }))

    await waitFor(() => expect(mocks.mutateAsync).toHaveBeenCalledWith('lbp'))
    expect(mocks.navigate).not.toHaveBeenCalled()

    mocks.ack.isError = true
    mocks.ack.error = { response: { status: 503 } }
    view.rerender(<SetupStepLbp />)
    expect(screen.getByRole('alert').textContent).toBe('common.errors.serverError')
    expect(screen.getByRole('button', { name: 'setup.lbp.cta' })).toBeEnabled()
  })

  it('disables both actions while the acknowledgement is pending', () => {
    mocks.ack.isPending = true

    render(<SetupStepLbp />)

    expect(screen.getByRole('button', { name: 'setup.lbp.skip' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'setup.lbp.cta' })).toBeDisabled()
  })
})
