import { api } from '@/lib/api-client'
import type { AiCallLogPage } from '@/types/api'

export interface AdminSecuritySettings {
  allowedOrigins: string[]
  secureCookies: boolean
}

/** The user-editable credential fields (the PUT body). The Key ID is derived
 *  server-side from the Application ID (they are the same value in EB). */
export interface AdminEnableBankingCredentials {
  applicationId: string
  redirectUri: string
}

/** Read shape: credentials plus the server-derived key-present flag. */
export interface AdminEnableBankingSettings extends AdminEnableBankingCredentials {
  privateKeyPresent: boolean
}

export interface EnableBankingKeypairResponse {
  publicKeyPem: string
  regenerated: boolean
}

export interface AdminAiSettings {
  provider: string   // 'none' | 'openai' | 'openrouter' | 'anthropic' | 'ollama'
  model: string
  baseUrl: string
  apiKeyPresent: boolean
  maxConcurrency: number
}

/** Write body. apiKey omitted/empty = keep the existing stored key. */
export interface AdminAiRequest {
  provider: string
  model: string
  baseUrl: string
  apiKey?: string
  maxConcurrency?: number
}

export interface AiTestResult {
  ok: boolean
  message: string
}

export interface AdminSettings {
  security: AdminSecuritySettings
  enableBanking: AdminEnableBankingSettings
  integrations: Record<string, boolean>
  ai: AdminAiSettings
}

export const adminApi = {
  getSettings: () =>
    api.get<AdminSettings>('/admin/settings').then(r => r.data),

  updateSecurity: (body: AdminSecuritySettings) =>
    api.put<void>('/admin/settings/security', body).then(r => r.data),

  updateEnableBanking: (body: AdminEnableBankingCredentials) =>
    api.put<void>('/admin/settings/enablebanking', body).then(r => r.data),

  generateEnableBankingKeyPair: () =>
    api.post<EnableBankingKeypairResponse>('/admin/settings/enablebanking/keypair')
      .then(r => r.data),

  importEnableBankingPrivateKey: (privatePem: string) =>
    api.post<EnableBankingKeypairResponse>('/admin/settings/enablebanking/keypair/import', { privatePem })
      .then(r => r.data),

  toggleIntegration: (key: string, enabled: boolean) =>
    api.patch<void>(`/admin/settings/integrations/${key}`, null, { params: { enabled } })
      .then(r => r.data),

  reloadCorsFromEnv: () =>
    api.post<{ allowedOrigins: string[] }>('/admin/settings/cors/reload-from-env')
      .then(r => r.data),

  updateAi: (body: AdminAiRequest) =>
    api.put<void>('/admin/settings/ai', body).then(r => r.data),

  testAi: (body: AdminAiRequest) =>
    api.post<AiTestResult>('/admin/settings/ai/test', body).then(r => r.data),

  listAiCalls: (limit: number, offset: number) =>
    api.get<AiCallLogPage>('/admin/ai-calls', { params: { limit, offset } }).then(r => r.data),

  getEbCallLog: () =>
    api.get<EbCallEntry[]>('/admin/enablebanking/call-log').then(r => r.data),

  clearEbCallLog: () =>
    api.delete<void>('/admin/enablebanking/call-log').then(r => r.data),
}

export interface EbCallEntry {
  timestamp: string
  method: string
  url: string
  requestBody: string
  responseStatus: number
  responseBody: string
}
