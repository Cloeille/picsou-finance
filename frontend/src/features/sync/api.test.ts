import { ZodError } from 'zod'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({ api: apiMocks }))

import { fortuneoApi } from './api'

const idleStatus = {
  isActive: true,
  expiresAt: null,
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  syncStatus: 'IDLE',
  lastSyncError: null,
}

describe('fortuneoApi response validation', () => {
  beforeEach(() => vi.clearAllMocks())

  it('accepts a valid session status', async () => {
    apiMocks.get.mockResolvedValue({ data: idleStatus })

    await expect(fortuneoApi.getStatus()).resolves.toEqual(idleStatus)
  })

  it('normalizes nullable fields omitted from an inactive status', async () => {
    apiMocks.get.mockResolvedValue({
      data: { isActive: false, syncStatus: 'IDLE' },
    })

    await expect(fortuneoApi.getStatus()).resolves.toEqual({
      isActive: false,
      expiresAt: null,
      lastSyncStartedAt: null,
      lastSyncCompletedAt: null,
      syncStatus: 'IDLE',
      lastSyncError: null,
    })
  })

  it('accepts the actionable investor-profile failure', async () => {
    apiMocks.get.mockResolvedValue({
      data: {
        ...idleStatus,
        syncStatus: 'FAILED',
        lastSyncError: 'INVESTOR_PROFILE_REQUIRED',
      },
    })

    await expect(fortuneoApi.getStatus()).resolves.toMatchObject({
      syncStatus: 'FAILED',
      lastSyncError: 'INVESTOR_PROFILE_REQUIRED',
    })
  })

  it('rejects a failed status without an error code', async () => {
    apiMocks.get.mockResolvedValue({
      data: { ...idleStatus, syncStatus: 'FAILED', lastSyncError: null },
    })

    await expect(fortuneoApi.getStatus()).rejects.toBeInstanceOf(ZodError)
  })

  it('rejects an unknown sync state instead of passing it to the UI', async () => {
    apiMocks.post.mockResolvedValue({
      data: { ...idleStatus, syncStatus: 'STUCK' },
    })

    await expect(fortuneoApi.sync()).rejects.toBeInstanceOf(ZodError)
  })

  it('accepts a completed authentication whose null fields the backend omits', async () => {
    // Spring is configured with `default-property-inclusion: non_null`, so an
    // AuthInitResponse(null, false, null) reaches the browser as `{mfaRequired:
    // false}` -- the null keys are absent, not null. Requiring them made every
    // no-MFA login surface a validation error even though the session had been
    // stored and the sync queued.
    apiMocks.post.mockResolvedValue({ data: { mfaRequired: false } })

    await expect(fortuneoApi.initiateAuth('anonymous', 'redacted')).resolves.toEqual({
      processId: null,
      mfaRequired: false,
      mfaType: null,
    })
  })

  it('still accepts an explicit null for those fields', async () => {
    apiMocks.post.mockResolvedValue({
      data: { processId: null, mfaRequired: false, mfaType: null },
    })

    await expect(fortuneoApi.initiateAuth('anonymous', 'redacted')).resolves.toEqual({
      processId: null,
      mfaRequired: false,
      mfaType: null,
    })
  })

  it('rejects a completed authentication that still carries a process id', async () => {
    // Absent means "no challenge"; a present id would mean the two halves of the
    // response disagree, which must not be silently normalised away.
    apiMocks.post.mockResolvedValue({
      data: { processId: 'still-pending', mfaRequired: false, mfaType: null },
    })

    await expect(fortuneoApi.initiateAuth('anonymous', 'redacted'))
      .rejects.toBeInstanceOf(ZodError)
  })

  it('enforces the MFA initiation discriminant', async () => {
    apiMocks.post.mockResolvedValue({
      data: { processId: null, mfaRequired: true, mfaType: 'OTP' },
    })

    await expect(fortuneoApi.initiateAuth('anonymous', 'redacted'))
      .rejects.toBeInstanceOf(ZodError)
  })
})
