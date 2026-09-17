import axios from 'axios'

export type SetupState = 'PENDING_ADMIN' | 'IN_PROGRESS' | 'COMPLETE'

export interface SetupStatus {
  state: SetupState
  needsSetup: boolean
  integrations: Record<string, boolean>
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

  acknowledgeIntegration: (key: 'traderepublic' | 'revolut' | 'finary' | 'boursedirect' | 'fortuneo') =>
    setupClient.post<void>(`/integrations/${key}/acknowledge`).then(r => r.data),

  complete: () => setupClient.post<void>('/complete').then(r => r.data),
}
