import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FamilyMemberItem } from '@/features/family/api'
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

const { listMembers } = vi.hoisted(() => ({ listMembers: vi.fn() }))
vi.mock('@/features/family/api', () => ({ familyApi: { listMembers } }))
const { adminUnlinkIdentities } = vi.hoisted(() => ({ adminUnlinkIdentities: vi.fn() }))
vi.mock('@/features/sso/api', () => ({ ssoApi: { adminUnlinkIdentities } }))
const { toast } = vi.hoisted(() => ({ toast: { error: vi.fn() } }))
vi.mock('sonner', () => ({ toast }))

const { MembersSection } = await import('./MembersSection')
const { useAuthStore } = await import('@/stores/auth-store')

function member(overrides: Partial<FamilyMemberItem> & Pick<FamilyMemberItem, 'id' | 'displayName'>): FamilyMemberItem {
  return {
    avatarColor: '#6366f1',
    managed: true,
    hasLogin: true,
    activated: true,
    loginName: overrides.displayName.toLowerCase(),
    mfaEnabled: false,
    hasPassword: false,
    ssoLinked: false,
    ...overrides,
  }
}

// Alice is the signed-in admin; Dora signs in with SSO only, Bob with a password, Carol with both.
const ALICE = member({ id: 1, displayName: 'Alice', managed: false, ssoLinked: true })
const DORA = member({ id: 2, displayName: 'Dora', ssoLinked: true })
const BOB = member({ id: 3, displayName: 'Bob', hasPassword: true })
const CAROL = member({ id: 4, displayName: 'Carol', hasPassword: true, ssoLinked: true })
const MEMBERS = [ALICE, DORA, BOB, CAROL]

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MembersSection />
    </QueryClientProvider>,
  )
}

async function row(name: string) {
  const label = await screen.findByText(name)
  const item = label.closest('li')
  if (!item) throw new Error(`no member row for ${name}`)
  return within(item)
}

async function signInMethods(name: string) {
  const memberRow = await row(name)
  return [en.admin.members.signInPassword, en.admin.members.signInSso].filter(badge => memberRow.queryByText(badge))
}

async function openUnlink(name: string) {
  fireEvent.click((await row(name)).getByRole('button', { name: en.admin.members.unlinkSso }))
  return screen.findByRole('dialog')
}

describe('MembersSection sign-in methods', () => {
  beforeEach(() => {
    listMembers.mockReset()
    adminUnlinkIdentities.mockReset()
    toast.error.mockReset()
    listMembers.mockResolvedValue(MEMBERS)
    useAuthStore.setState({
      user: { username: 'alice', role: 'ADMIN', memberId: ALICE.id, displayName: 'Alice' },
      isAuthenticated: true,
    })
  })

  it('badges each member with the sign-in methods the server reports', async () => {
    renderSection()

    expect(await signInMethods('Dora')).toEqual([en.admin.members.signInSso])
    expect(await signInMethods('Bob')).toEqual([en.admin.members.signInPassword])
    expect(await signInMethods('Carol')).toEqual([en.admin.members.signInPassword, en.admin.members.signInSso])
  })

  it('offers Unlink SSO only for other members with a linked identity', async () => {
    renderSection()

    const unlinkable = await Promise.all(
      MEMBERS.map(async m => (await row(m.displayName)).queryByRole('button', { name: en.admin.members.unlinkSso }) !== null),
    )
    expect(unlinkable).toEqual([false, true, false, true])
  })

  it('unlinks the member after confirmation and shows the refreshed list', async () => {
    adminUnlinkIdentities.mockResolvedValue({})
    renderSection()
    const dialog = await openUnlink('Dora')
    expect(dialog).toHaveAccessibleName('Unlink SSO for Dora?')
    expect(dialog).toHaveAccessibleDescription(en.admin.members.unlinkSsoDescription)
    listMembers.mockResolvedValue([ALICE, { ...DORA, ssoLinked: false }, BOB, CAROL])

    fireEvent.click(within(dialog).getByRole('button', { name: en.admin.members.unlinkSso }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(adminUnlinkIdentities).toHaveBeenCalledTimes(1)
    expect(adminUnlinkIdentities.mock.calls[0][0]).toBe(DORA.id)
    await waitFor(async () => expect(await signInMethods('Dora')).toEqual([]))
  })

  it('unlinks nothing when the confirmation is cancelled', async () => {
    renderSection()
    const dialog = await openUnlink('Dora')

    fireEvent.click(within(dialog).getByRole('button', { name: en.common.cancel }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(adminUnlinkIdentities).not.toHaveBeenCalled()
  })

  it('lets the confirmation close while the unlink runs, without allowing a second request', async () => {
    adminUnlinkIdentities.mockReturnValue(new Promise(() => {}))
    renderSection()
    const dialog = await openUnlink('Dora')
    const confirm = within(dialog).getByRole('button', { name: en.admin.members.unlinkSso })

    fireEvent.click(confirm)
    await waitFor(() => expect(confirm).toBeDisabled())
    fireEvent.click(confirm)
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const reopened = await openUnlink('Dora')
    expect(within(reopened).getByRole('button', { name: en.admin.members.unlinkSso })).toBeDisabled()
    expect(adminUnlinkIdentities).toHaveBeenCalledTimes(1)
  })

  it('reports an unlink that fails after its confirmation closed, outside the next member\'s dialog', async () => {
    let failUnlink: (reason: unknown) => void = () => {}
    adminUnlinkIdentities.mockReturnValue(new Promise((_, reject) => { failUnlink = reject }))
    renderSection()
    const dialog = await openUnlink('Dora')
    const confirm = within(dialog).getByRole('button', { name: en.admin.members.unlinkSso })
    fireEvent.click(confirm)
    await waitFor(() => expect(confirm).toBeDisabled())
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await act(async () => { failUnlink({ response: { status: 500, data: {} } }) })

    expect(toast.error).toHaveBeenCalledWith(en.admin.members.unlinkSsoFailed.replace('{{name}}', 'Dora'), {
      description: en.common.errors.serverError,
    })
    const carol = await openUnlink('Carol')
    expect(within(carol).queryByRole('alert')).not.toBeInTheDocument()
    expect(within(carol).getByRole('button', { name: en.admin.members.unlinkSso })).toBeEnabled()
  })

  it('leaves the next member\'s confirmation open when an earlier unlink succeeds', async () => {
    let finishUnlink: (value: unknown) => void = () => {}
    adminUnlinkIdentities.mockReturnValue(new Promise(resolve => { finishUnlink = resolve }))
    renderSection()
    const dialog = await openUnlink('Dora')
    const confirm = within(dialog).getByRole('button', { name: en.admin.members.unlinkSso })
    fireEvent.click(confirm)
    await waitFor(() => expect(confirm).toBeDisabled())
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const carol = await openUnlink('Carol')

    await act(async () => { finishUnlink({}) })

    expect(screen.getByRole('dialog')).toBe(carol)
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('keeps the confirmation open with the error when the unlink fails', async () => {
    adminUnlinkIdentities.mockRejectedValue({ response: { status: 500, data: {} } })
    renderSection()
    const dialog = await openUnlink('Dora')

    fireEvent.click(within(dialog).getByRole('button', { name: en.admin.members.unlinkSso }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(en.common.errors.serverError)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
