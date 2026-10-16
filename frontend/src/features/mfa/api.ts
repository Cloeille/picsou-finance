import { api } from '@/lib/api-client'

export interface MfaStatus {
  enabled: boolean
  enrolledAt: string | null
  remainingRecoveryCodes: number
}

export interface MfaEnrollInitResponse {
  qrCodeDataUri: string
  secret: string
}

export interface RecoveryCodes {
  recoveryCodes: string[]
}

/** REMEMBER_ME: a browser "Remember me" session. IOS_APP: the native app, signed in via OAuth2. */
export type SessionKind = 'REMEMBER_ME' | 'IOS_APP'

export interface SessionItem {
  /** Opaque: pass it back unchanged to revoke the row. */
  id: string
  kind: SessionKind
  userAgent?: string | null
  ipPrefix?: string | null
  createdAt: string
  lastUsedAt: string
  expiresAt: string
  trustedFor2fa: boolean
  current: boolean
}

/**
 * Login response is a discriminated union: when MFA is required the server
 * returns just `{ mfaRequired: true, username }` and sets the mfa_challenge
 * cookie; the client must redirect to the MFA challenge page. Otherwise the
 * normal user payload is returned.
 */
export type LoginOutcome =
  | {
      mfaRequired: true
      username: string
    }
  | {
      mfaRequired: false
      username: string
      role: 'ADMIN' | 'MEMBER'
      memberId: number
      displayName: string
    }

interface LoginResponseBody {
  mfaRequired?: boolean
  username: string
  role?: string
  memberId?: number
  displayName?: string
}

export const mfaApi = {
  // ─── login + verify (public) ────────────────────────────────────────

  loginWithRememberMe: (username: string, password: string, rememberMe: boolean): Promise<LoginOutcome> =>
    api
      .post<LoginResponseBody>('/auth/login', { username, password, rememberMe })
      .then(r => normaliseLogin(r.data)),

  verifyMfa: (code: string, isRecoveryCode: boolean, trustDevice: boolean): Promise<LoginOutcome> =>
    api
      .post<LoginResponseBody>('/auth/mfa/verify', {
        code,
        isRecoveryCode,
        trustDevice,
      })
      .then(r => normaliseLogin(r.data)),

  // ─── settings flow (authenticated) ──────────────────────────────────

  getStatus: () => api.get<MfaStatus>('/auth/mfa/status').then(r => r.data),

  enrollInit: (currentPassword: string) =>
    api
      .post<MfaEnrollInitResponse>('/auth/mfa/enroll/init', { currentPassword })
      .then(r => r.data),

  enrollVerify: (code: string) =>
    api.post<RecoveryCodes>('/auth/mfa/enroll/verify', { code }).then(r => r.data),

  disable: (currentPassword: string, code: string, isRecoveryCode: boolean) =>
    api.post('/auth/mfa/disable', { currentPassword, code, isRecoveryCode }),

  regenerateRecoveryCodes: (currentPassword: string, code: string) =>
    api
      .post<RecoveryCodes>('/auth/mfa/recovery-codes/regenerate', { currentPassword, code })
      .then(r => r.data),

  // ─── persistent sessions (authenticated) ────────────────────────────

  listSessions: () => api.get<SessionItem[]>('/auth/sessions').then(r => r.data),

  revokeSession: (id: string) => api.delete(`/auth/sessions/${id}`),

  revokeAllSessionsExceptCurrent: () => api.delete('/auth/sessions'),

  // ─── admin (admin-only) ─────────────────────────────────────────────

  forceDisableMfa: (memberId: number) =>
    api.delete(`/admin/members/${memberId}/mfa`),
}

function normaliseLogin(body: LoginResponseBody): LoginOutcome {
  if (body.mfaRequired) {
    return { mfaRequired: true, username: body.username }
  }
  return {
    mfaRequired: false,
    username: body.username,
    // The non-MFA branch ALWAYS comes back with these populated; the optional
    // typing on the body is purely to share the response shape with the
    // mfa-required branch above.
    role: (body.role as 'ADMIN' | 'MEMBER') ?? 'MEMBER',
    memberId: body.memberId ?? 0,
    displayName: body.displayName ?? body.username,
  }
}
