import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SetupSsoInfo, SetupStatus } from '@/features/setup/api'
import en from '@/i18n/locales/en.json'

// Real-English lookup: a wrong key path fails the assertion instead of echoing the key.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      const value = key
        .split('.')
        .reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], en)
      const str = typeof value === 'string' ? value : key
      return Object.entries(opts ?? {}).reduce((acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)), str)
    },
  }),
}))

// zustand stores read localStorage/sessionStorage at module evaluation (node 26 jsdom gap).
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const setup = vi.hoisted(() => ({ getStatus: vi.fn(), submitAdmin: vi.fn() }))
vi.mock('@/features/setup/api', () => ({ setupApi: setup }))
const { submitSsoStart } = vi.hoisted(() => ({ submitSsoStart: vi.fn() }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart }))

const { SetupStepAdmin } = await import('./SetupStepAdmin')
const { SETUP_STATUS_KEY } = await import('@/features/setup/hooks')

const SSO_ADMIN: SetupSsoInfo = { enabled: true, providerName: 'Zitadel', adminBootstrap: true }

function renderStep(sso: SetupSsoInfo | undefined, entry = '/setup/admin') {
  const status: SetupStatus = { state: 'PENDING_ADMIN', needsSetup: true, integrations: {}, ...(sso ? { sso } : {}) }
  setup.getStatus.mockResolvedValue(status)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // SetupOnly loads the status before any wizard step renders.
  queryClient.setQueryData(SETUP_STATUS_KEY, status)
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <SetupStepAdmin />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function passwordFormFields() {
  return [
    screen.queryByLabelText(en.setup.admin.username),
    screen.queryByLabelText(en.setup.admin.password),
    screen.queryByRole('button', { name: en.setup.admin.cta }),
  ].filter(element => element !== null)
}

describe('SetupStepAdmin', () => {
  beforeEach(() => {
    Object.values(setup).forEach(fn => fn.mockReset())
    submitSsoStart.mockReset()
  })

  it('offers only the provider when the first admin must come from the SSO admin group', () => {
    renderStep(SSO_ADMIN)

    expect(screen.getByRole('button', { name: 'Continue with Zitadel' })).toBeEnabled()
    expect(passwordFormFields()).toEqual([])
  })

  it('starts the SETUP intent from the provider button', () => {
    renderStep(SSO_ADMIN)

    fireEvent.click(screen.getByRole('button', { name: 'Continue with Zitadel' }))

    expect(submitSsoStart).toHaveBeenCalledTimes(1)
    expect(submitSsoStart).toHaveBeenCalledWith({ intent: 'setup' })
  })

  it('shows the misconfiguration notice with neither a form nor a button when SSO is required but off', () => {
    renderStep({ enabled: false, providerName: 'SSO', adminBootstrap: true })

    expect(screen.getByText(en.setup.admin.ssoMisconfigured)).toBeInTheDocument()
    expect(screen.queryAllByRole('button')).toEqual([])
    expect(screen.queryAllByRole('textbox')).toEqual([])
  })

  it.each([
    ['SSO is on without an admin group', { enabled: true, providerName: 'Zitadel', adminBootstrap: false }],
    ['the status carries no SSO information', undefined],
  ])('keeps the password form when %s', (_, sso) => {
    renderStep(sso)

    expect(passwordFormFields()).toHaveLength(3)
    expect(screen.queryByRole('button', { name: /^Continue with/ })).not.toBeInTheDocument()
  })

  it.each([
    ['NOT_IN_ADMIN_GROUP', en.ssoErrors.NOT_IN_ADMIN_GROUP],
    ['NOT_A_REAL_CODE', en.ssoErrors.generic],
  ])('translates the %s refusal from the IdP round trip, never showing the raw code', (code, message) => {
    renderStep(SSO_ADMIN, `/setup/admin?ssoError=${code}`)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(message)
    expect(alert).not.toHaveTextContent(code)
    expect(screen.getByRole('button', { name: 'Continue with Zitadel' })).toBeEnabled()
  })

  it('translates the SSO_ADMIN_REQUIRED refusal of a password admin', async () => {
    setup.submitAdmin.mockRejectedValue({
      response: {
        status: 409,
        data: { code: 'SSO_ADMIN_REQUIRED', detail: 'The first admin must sign in through the configured SSO admin group.' },
      },
    })
    renderStep(undefined)
    fireEvent.change(screen.getByLabelText(en.setup.admin.username), { target: { value: 'alice' } })
    fireEvent.change(screen.getByLabelText(en.setup.admin.password), { target: { value: 'correct-horse-1' } })
    const submit = screen.getByRole('button', { name: en.setup.admin.cta })
    await waitFor(() => expect(submit).toBeEnabled())

    fireEvent.click(submit)

    expect(await screen.findByRole('alert')).toHaveTextContent(en.ssoErrors.SSO_ADMIN_REQUIRED)
    expect(setup.submitAdmin).toHaveBeenCalledTimes(1)
  })
})
