import axios from 'axios'

export type SetupState = 'PENDING_ADMIN' | 'IN_PROGRESS' | 'COMPLETE'

export interface SetupSsoInfo {
  enabled: boolean
  providerName: string
  adminBootstrap: boolean
}

/**
 * What `GET /api/setup/status` says about an archive import: staged and restarting (`pending`),
 * applied by the new process (`done`), or refused at start with the previous data kept (`failed`).
 */
export type ArchiveImportStatus =
  | { state: 'pending' }
  | { state: 'done'; tables: number; rows: number }
  | { state: 'failed'; code: string }

export interface ArchiveImportAccepted {
  restarting: boolean
  tables: number
  rows: number
}

export interface SetupStatus {
  state: SetupState
  needsSetup: boolean
  integrations: Record<string, boolean>
  // Optional: the wizard's separate client has no demo adapter.
  sso?: SetupSsoInfo
  desktopMode?: boolean
  desktopLockEnabled?: boolean
  /** The account the desktop password lock opens. Only the app's own window receives it. */
  desktopUsername?: string
  legacyPostgresDetected?: boolean
  /** Absent when there is nothing to say about an archive import. */
  archiveImport?: ArchiveImportStatus
}

export interface DesktopQuickStartRequest {
  displayName?: string
}

export interface DesktopQuickStartResponse {
  username: string
  role: 'ADMIN' | 'MEMBER'
  memberId: number
  displayName: string
}

export interface SetupAdminRequest {
  username: string
  password: string
  displayName?: string
  avatarColor?: string
}

export interface SetupAdminResponse {
  username: string
  displayName: string
}

export interface SetupSecurityRequest {
  allowedOrigins: string[]
  secureCookies: boolean
}

export interface EnableBankingConfigRequest {
  applicationId: string
  redirectUri: string
}

export interface EnableBankingImportRequest {
  privatePem: string
}

export interface EnableBankingKeypairResponse {
  publicKeyPem: string
  regenerated: boolean
}

export interface EnableBankingTestResponse {
  ok: boolean
  code: string
  hint: string
}

export interface BoursoBankHealthResponse {
  ok: boolean
  url: string
  hint: string
}

export interface CryptoKeyGenerateResponse {
  existed: boolean
  path: string
}

/**
 * Dedicated axios instance for the wizard.
 *
 * The main app's `api` client has a 401 → refresh → /login interceptor that
 * would bounce unauthenticated setup requests to the login page. The wizard
 * is explicitly public, so it gets its own thin client with no interceptor
 * and no credential pass-through (there are no cookies to send pre-admin).
 */
const setupClient = axios.create({
  baseURL: '/api/setup',
  headers: { 'Content-Type': 'application/json' },
})

export const setupApi = {
  getStatus: () => setupClient.get<SetupStatus>('/status').then(r => r.data),

  submitAdmin: (body: SetupAdminRequest) =>
    setupClient.post<SetupAdminResponse>('/admin', body).then(r => r.data),

  submitSecurity: (body: SetupSecurityRequest) =>
    setupClient.post<void>('/security', body).then(r => r.data),

  writeEnableBankingConfig: (body: EnableBankingConfigRequest) =>
    setupClient.post<void>('/integrations/enablebanking/config', body).then(r => r.data),

  generateEnableBankingKeyPair: () =>
    setupClient.post<EnableBankingKeypairResponse>('/integrations/enablebanking/keypair')
      .then(r => r.data),

  importEnableBankingPrivateKey: (body: EnableBankingImportRequest) =>
    setupClient.post<EnableBankingKeypairResponse>('/integrations/enablebanking/keypair/import', body)
      .then(r => r.data),

  testEnableBanking: () =>
    setupClient.post<EnableBankingTestResponse>('/integrations/enablebanking/test')
      .then(r => r.data),

  checkBoursoBankSidecar: () =>
    setupClient.get<BoursoBankHealthResponse>('/integrations/boursobank/health')
      .then(r => r.data),

  generateCryptoKey: () =>
    setupClient.post<CryptoKeyGenerateResponse>('/integrations/crypto/generate-key')
      .then(r => r.data),

  acknowledgeIntegration: (key: 'bnp' | 'traderepublic' | 'revolut' | 'finary' | 'boursedirect' | 'fortuneo' | 'lbp') =>
    setupClient.post<void>(`/integrations/${key}/acknowledge`).then(r => r.data),

  complete: () => setupClient.post<void>('/complete').then(r => r.data),

  /**
   * Stages an encrypted archive; the server then restarts to apply it (202). The passphrase
   * travels in the multipart body only and is never kept. `withCredentials` sends the desktop
   * window's gate cookie, which the loopback-only desktop setup routes require.
   */
  importArchive: (
    file: File,
    passphrase: string,
    onUploadProgress?: (fraction: number) => void,
    signal?: AbortSignal,
  ) => {
    const form = new FormData()
    form.append('passphrase', passphrase)
    form.append('archive', file, file.name)
    return setupClient
      .post<ArchiveImportAccepted>('/import-archive', form, {
        withCredentials: true,
        signal,
        // Let the browser write the multipart boundary.
        headers: { 'Content-Type': undefined },
        onUploadProgress: event => onUploadProgress?.(event.progress ?? 0),
      })
      .then(r => r.data)
  },

  desktopQuickStart: (body: DesktopQuickStartRequest) =>
    setupClient
      .post<DesktopQuickStartResponse>('/desktop-quick-start', body, { withCredentials: true })
      .then(r => ({
        ...r.data,
        role: r.data.role as 'ADMIN' | 'MEMBER',
      })),
}
