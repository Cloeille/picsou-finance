import '@testing-library/jest-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createElement, type ReactNode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => void values.set(key, String(value)),
    removeItem: key => void values.delete(key),
    clear: () => values.clear(),
    key: index => [...values.keys()][index] ?? null,
    get length() { return values.size },
  } as Storage
}

vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const {
  navigateFn,
  mutateAsync,
  deleteHook,
  mfaStatus,
  deletionImpact,
  mfaRefetch,
  impactRefetch,
  toastSuccess,
} = vi.hoisted(() => ({
  navigateFn: vi.fn(),
  mutateAsync: vi.fn(),
  deleteHook: vi.fn(),
  mfaStatus: vi.fn(),
  deletionImpact: vi.fn(),
  mfaRefetch: vi.fn(),
  impactRefetch: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock('react-router', () => ({ useNavigate: () => navigateFn }))
vi.mock('sonner', () => ({ toast: { success: toastSuccess } }))
vi.mock('@/features/mfa/hooks', () => ({ useMfaStatus: mfaStatus }))
vi.mock('@/features/account-deletion/hooks', () => ({
  useDeleteMyAccount: () => deleteHook(),
  useAccountDeletionImpact: deletionImpact,
}))

const { DeleteAccountDialog } = await import('./DeleteAccountDialog')
const { useAuthStore } = await import('@/stores/auth-store')

const BOB = { username: 'bob', role: 'MEMBER' as const, memberId: 9, displayName: 'Bob' }
const ADMIN = { username: 'admin', role: 'ADMIN' as const, memberId: 1, displayName: 'Admin' }

function wrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

function renderDialog(onOpenChange = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(createElement(DeleteAccountDialog, { open: true, onOpenChange }), {
    wrapper: wrapper(queryClient),
  })
  return { queryClient, onOpenChange }
}

function enterPasswordAndConfirmation(username: string) {
  fireEvent.change(screen.getByLabelText('settings.currentPassword'), {
    target: { value: 's3cret' },
  })
  fireEvent.change(screen.getByPlaceholderText(username), {
    target: { value: username },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.getState().logout()
  useAuthStore.getState().login(BOB)
  mfaStatus.mockReturnValue({
    data: { enabled: false },
    isPending: false,
    isError: false,
    isSuccess: true,
    refetch: mfaRefetch,
  })
  deletionImpact.mockReturnValue({
    data: { mode: 'DELETE_ACCOUNT' },
    isPending: false,
    isError: false,
    isSuccess: true,
    refetch: impactRefetch,
  })
  deleteHook.mockReturnValue({ mutateAsync, isPending: false })
  mutateAsync.mockResolvedValue({ mode: 'DELETE_ACCOUNT' })
})

describe('DeleteAccountDialog', () => {
  it('deletes a member with password re-auth and clears all client state', async () => {
    const { queryClient } = renderDialog()
    queryClient.setQueryData(['dashboard', '1m'], { netWorth: 999 })

    expect(screen.getByText('settings.deleteAccountDeleteDesc')).toBeInTheDocument()
    expect(screen.getByText('settings.deleteAccountSharedWarning')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: 'settings.deleteAccountSubmit' })
    expect(submit).toBeDisabled()

    enterPasswordAndConfirmation('bob')
    expect(submit).not.toBeDisabled()
    fireEvent.click(submit)

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({ reAuth: { password: 's3cret' } })
    })
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(queryClient.getQueryData(['dashboard', '1m'])).toBeUndefined()
    expect(toastSuccess).toHaveBeenCalledWith('settings.deleteAccountDeletedSuccess')
    expect(navigateFn).toHaveBeenCalledWith('/login', { replace: true })
  })

  it('requires the exact username before enabling the destructive action', () => {
    renderDialog()
    fireEvent.change(screen.getByLabelText('settings.currentPassword'), {
      target: { value: 's3cret' },
    })
    const confirmation = screen.getByPlaceholderText('bob')
    const submit = screen.getByRole('button', { name: 'settings.deleteAccountSubmit' })

    fireEvent.change(confirmation, { target: { value: 'Bob' } })
    expect(submit).toBeDisabled()
    fireEvent.click(submit)
    expect(mutateAsync).not.toHaveBeenCalled()

    fireEvent.change(confirmation, { target: { value: 'bob ' } })
    expect(submit).toBeDisabled()

    fireEvent.change(confirmation, { target: { value: 'bob' } })
    expect(submit).not.toBeDisabled()
  })

  it('shows the explicit final-admin reset and trusts the committed outcome', async () => {
    useAuthStore.getState().logout()
    useAuthStore.getState().login(ADMIN)
    deletionImpact.mockReturnValue({
      data: { mode: 'RESET_LAST_ADMIN' },
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: impactRefetch,
    })
    mutateAsync.mockResolvedValue({ mode: 'RESET_LAST_ADMIN' })
    renderDialog()

    expect(screen.getByText('settings.deleteAccountResetTitle')).toBeInTheDocument()
    expect(screen.getByText('settings.deleteAccountResetDesc')).toBeInTheDocument()
    enterPasswordAndConfirmation('admin')
    fireEvent.click(screen.getByRole('button', { name: 'settings.deleteAccountResetSubmit' }))

    await waitFor(() => {
      expect(toastSuccess).toHaveBeenCalledWith('settings.deleteAccountResetSuccess')
    })
  })

  it('uses the committed reset result when the administrator count changes after review', async () => {
    useAuthStore.getState().logout()
    useAuthStore.getState().login(ADMIN)
    mutateAsync.mockResolvedValue({ mode: 'RESET_LAST_ADMIN' })
    const { queryClient } = renderDialog()
    queryClient.setQueryData(['dashboard', '1m'], { netWorth: 999 })

    expect(screen.getByText('settings.deleteAccountDeleteDesc')).toBeInTheDocument()
    expect(screen.getByText('settings.deleteAccountAdminRaceNote')).toBeInTheDocument()
    enterPasswordAndConfirmation('admin')
    fireEvent.click(screen.getByRole('button', { name: 'settings.deleteAccountSubmit' }))

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({ reAuth: { password: 's3cret' } })
    })
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(queryClient.getQueryData(['dashboard', '1m'])).toBeUndefined()
    expect(toastSuccess).toHaveBeenCalledWith('settings.deleteAccountResetSuccess')
    expect(navigateFn).toHaveBeenCalledWith('/login', { replace: true })
  })

  it('keeps the confirmation controls scrollable within a short viewport', () => {
    useAuthStore.getState().logout()
    useAuthStore.getState().login(ADMIN)
    deletionImpact.mockReturnValue({
      data: { mode: 'RESET_LAST_ADMIN' },
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: impactRefetch,
    })
    renderDialog()

    const dialog = screen.getByRole('dialog')
    expect(dialog.className).toContain('max-h-[90vh]')
    expect(dialog.className).toContain('overflow-y-auto')
    expect(screen.getByRole('button', { name: 'settings.deleteAccountResetSubmit' }))
      .toBeInTheDocument()
  })

  it('asks for a TOTP code instead of a password when 2FA is enabled', () => {
    mfaStatus.mockReturnValue({
      data: { enabled: true },
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: mfaRefetch,
    })
    renderDialog()

    expect(screen.getByLabelText('auth.mfaCodeLabel')).toBeInTheDocument()
    expect(screen.queryByLabelText('settings.currentPassword')).not.toBeInTheDocument()
  })

  it('submits a TOTP re-authentication factor when 2FA is enabled', async () => {
    mfaStatus.mockReturnValue({
      data: { enabled: true },
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: mfaRefetch,
    })
    renderDialog()
    fireEvent.change(screen.getByLabelText('auth.mfaCodeLabel'), {
      target: { value: '123456' },
    })
    fireEvent.change(screen.getByPlaceholderText('bob'), {
      target: { value: 'bob' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'settings.deleteAccountSubmit' }))

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({ reAuth: { totpCode: '123456' } })
    })
  })

  it('does not fall back to password while MFA status is loading', () => {
    mfaStatus.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      isSuccess: false,
      refetch: mfaRefetch,
    })
    renderDialog()

    expect(mfaStatus).toHaveBeenCalledWith({ fresh: true })
    expect(screen.getByText('settings.deleteAccountReviewTitle')).toBeInTheDocument()
    expect(screen.getByText('settings.deleteAccountReviewDesc')).toBeInTheDocument()
    expect(screen.queryByText('settings.deleteAccountDeleteDesc')).not.toBeInTheDocument()
    expect(screen.queryByText('settings.deleteAccountResetDesc')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('settings.deleteAccountLoading')
    expect(screen.queryByLabelText('settings.currentPassword')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('auth.mfaCodeLabel')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'settings.deleteAccountSubmit' }))
      .not.toBeInTheDocument()
  })

  it('blocks submission and offers retry when impact loading fails', () => {
    deletionImpact.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      isSuccess: false,
      refetch: impactRefetch,
    })
    renderDialog()

    expect(screen.getByRole('alert')).toHaveTextContent('settings.deleteAccountLoadError')
    fireEvent.click(screen.getByRole('button', { name: 'settings.deleteAccountRetry' }))
    expect(impactRefetch).toHaveBeenCalledOnce()
    expect(mfaRefetch).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'settings.deleteAccountSubmit' }))
      .not.toBeInTheDocument()
  })

  it('cannot be dismissed while the destructive request is pending', () => {
    deleteHook.mockReturnValue({ mutateAsync, isPending: true })
    const { onOpenChange } = renderDialog()

    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'settings.mfaCancel' })).toBeDisabled()
    expect(screen.getByLabelText('settings.currentPassword')).toBeDisabled()
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-busy', 'true')

    fireEvent.click(screen.getByRole('button', { name: 'settings.mfaCancel' }))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('maps stable re-authentication codes and announces the error', async () => {
    mutateAsync.mockRejectedValueOnce({
      response: { status: 401, data: { code: 'REAUTH_FAILED', detail: 'opaque' } },
    })
    renderDialog()
    enterPasswordAndConfirmation('bob')
    fireEvent.click(screen.getByRole('button', { name: 'settings.deleteAccountSubmit' }))

    await waitFor(() => {
      expect(screen.getByRole('alert'))
        .toHaveTextContent('settings.deleteAccountInvalidCredentials')
    })
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(navigateFn).not.toHaveBeenCalled()
  })

  it('maps the account-deletion rate limit and keeps the session intact', async () => {
    mutateAsync.mockRejectedValueOnce({
      response: { status: 429, data: { code: 'ACCOUNT_DELETION_RATE_LIMITED' } },
    })
    renderDialog()
    enterPasswordAndConfirmation('bob')
    fireEvent.click(screen.getByRole('button', { name: 'settings.deleteAccountSubmit' }))

    await waitFor(() => {
      expect(screen.getByRole('alert'))
        .toHaveTextContent('settings.deleteAccountRateLimited')
    })
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(navigateFn).not.toHaveBeenCalled()
  })
})
