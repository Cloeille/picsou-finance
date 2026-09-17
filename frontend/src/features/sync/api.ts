import { api } from '@/lib/api-client'
import { z } from 'zod'
import type {
  Account,
  ExchangeType,
  ChainType,
  ExchangeStatus,
  Institution,
  WalletStatus,
  FinaryPreviewResponse,
  FinaryConnectionStatus,
  FinaryAccountMapping,
  FinaryImportRequest,
  FinaryImportResultResponse,
  FinaryAutoSyncResponse,
  BoursoSessionStatus,
  BoursoAuthInitResponse,
  RevolutSessionStatus,
  SyncProgress,
  BourseDirectSessionStatus,
  BourseDirectAuthInitResponse,
  DegiroSessionStatus,
  DegiroAuthInitResponse,
  AmundiSessionStatus,
  AmundiAuthInitResponse,
  FortuneoSessionStatus,
  FortuneoAuthInitResponse,
  IbkrConnectionStatus,
} from '@/types/api'

// --- Bank Sync (Enable Banking) ---

export const bankSyncApi = {
  searchInstitutions: (query: string, country: string) =>
    api
      .get<Institution[]>('/sync/institutions', { params: { query, country }, skipGlobalErrorRedirect: true })
      .then(r => r.data),

  listCountries: () => api.get<string[]>('/sync/countries', { skipGlobalErrorRedirect: true }).then(r => r.data),

  initiate: (institutionId: string, institutionName: string) =>
    api
      .post<{ requisitionId: string; authLink: string }>('/sync/initiate', { institutionId, institutionName })
      .then(r => r.data),

  complete: (code: string, state?: string | null) =>
    api
      .get<Account[]>('/sync/complete', { params: { code, state: state ?? undefined } })
      .then(r => r.data),

  getStatus: () =>
    api
      .get<
        {
          id: number
          requisitionId: string
          institutionId: string
          institutionName: string
          status: string
          authLink: string | null
          lastSyncedAt: string | null
        }[]
      >('/sync/status')
      .then(r => r.data),

  retry: (id: number) =>
    api.post<Account[]>(`/sync/${id}/retry`).then(r => r.data),

  reconnect: (id: number) =>
    api.post<{ requisitionId: string; authLink: string }>(`/sync/${id}/reconnect`).then(r => r.data),

  deleteConnection: (id: number) =>
    api.delete(`/sync/${id}`),
}

// --- Trade Republic ---

export const trApi = {
  initiateAuth: (phoneNumber: string, pin: string) =>
    api
      .post<{ processId: string }>('/tr/auth/initiate', { phoneNumber, pin })
      .then(r => r.data),

  completeAuth: (processId: string, tan: string) =>
    api
      .post<Account[]>('/tr/auth/complete', { processId, tan })
      .then(r => r.data),

  sync: () =>
    api.post<Account[]>('/tr/sync').then(r => r.data),

  // Not wired up on the backend yet (Increment 2 of the sync-progress work) — harmless
  // to add the client fn now, `useSyncProgress('tr', …)` simply won't be enabled until then.
  getSyncProgress: () =>
    api.get<SyncProgress>('/tr/sync/progress').then(r => r.data),

  getSessionStatus: () =>
    api
      .get<{ isActive: boolean; expiresAt: string | null }>('/tr/status')
      .then(r => r.data),

  importCsv: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<Account[]>('/tr/import', form).then(r => r.data)
  },

  clearSession: () =>
    api.delete('/tr/session'),
}

// --- Crypto Exchanges ---

export const cryptoExchangeApi = {
  // apiSecret is optional: single-key exchanges (Meria) must not send one, and axios drops an
  // undefined field from the JSON body entirely.
  add: (type: ExchangeType, apiKey: string, apiSecret?: string) =>
    api
      .post<Account>('/crypto/exchange', { type, apiKey, apiSecret })
      .then(r => r.data),

  sync: (id: number) =>
    api.post<Account[]>(`/crypto/exchange/${id}/sync`).then(r => r.data),

  getStatuses: () =>
    api
      .get<ExchangeStatus[]>('/crypto/exchange/status')
      .then(r => r.data),

  remove: (id: number) =>
    api.delete(`/crypto/exchange/${id}`),
}

// --- Crypto Wallets ---

export const cryptoWalletApi = {
  add: (chain: ChainType, address: string, label?: string) =>
    api
      .post<Account>('/crypto/wallet', { chain, address, label })
      .then(r => r.data),

  sync: (id: number) =>
    api.post<Account[]>(`/crypto/wallet/${id}/sync`).then(r => r.data),

  list: () =>
    api
      .get<WalletStatus[]>('/crypto/wallet')
      .then(r => r.data),

  remove: (id: number) =>
    api.delete(`/crypto/wallet/${id}`),
}

// --- BoursoBank ---

export const boursoApi = {
  initiateAuth: (customerId: string, password: string) =>
    api
      .post<BoursoAuthInitResponse>('/bourso/auth/initiate', { customerId, password })
      .then(r => r.data),

  // No code: the user approves the push in the BoursoBank app, and the request
  // stays open until they do.
  completeAuth: (processId: string) =>
    api
      .post<BoursoSessionStatus>('/bourso/auth/complete', { processId })
      .then(r => r.data),

  sync: () =>
    api.post<BoursoSessionStatus>('/bourso/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<BoursoSessionStatus>('/bourso/status', { skipGlobalErrorRedirect: true })
      .then(r => r.data),

  clearSession: () =>
    api.delete('/bourso/session'),
}

// --- Revolut ---

export const revolutApi = {
  getSessionStatus: () =>
    api.get<RevolutSessionStatus>('/revolut/status').then(r => r.data),

  startSync: (body: { phoneNumber?: string; passcode?: string }) =>
    api.post<SyncProgress>('/revolut/sync', body).then(r => r.data),

  getSyncProgress: () =>
    api.get<SyncProgress>('/revolut/sync/progress').then(r => r.data),

  confirmSync: (selectedExternalIds: string[], remember: boolean, voluntary: boolean) =>
    api.post<void>('/revolut/sync/confirm', { selectedExternalIds, remember, voluntary }).then(r => r.data),

  clearSession: () =>
    api.delete('/revolut/session'),
}

// --- DEGIRO ---

export const degiroApi = {
  initiateAuth: (username: string, password: string) =>
    api
      .post<DegiroAuthInitResponse>('/degiro/auth/initiate', { username, password })
      .then(r => r.data),

  completeAuth: (processId: string, code: string) =>
    api
      .post<DegiroSessionStatus>('/degiro/auth/complete', { processId, code })
      .then(r => r.data),

  sync: () =>
    api.post<Account>('/degiro/sync').then(r => r.data),

  getStatus: () =>
    api.get<DegiroSessionStatus>('/degiro/status').then(r => r.data),

  clearSession: () =>
    api.delete('/degiro/session'),
}

// --- Bourse Direct ---

export const bourseDirectApi = {
  initiateAuth: (login: string, password: string) =>
    api
      .post<BourseDirectAuthInitResponse>('/bourse-direct/auth/initiate', { login, password })
      .then(r => r.data),

  completeAuth: (processId: string, code: string) =>
    api
      .post<BourseDirectSessionStatus>('/bourse-direct/auth/complete', { processId, code })
      .then(r => r.data),

  sync: () =>
    api.post<BourseDirectSessionStatus>('/bourse-direct/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<BourseDirectSessionStatus>('/bourse-direct/status', {
        skipGlobalErrorRedirect: true,
      })
      .then(r => r.data),

  clearSession: () => api.delete('/bourse-direct/session'),
}

// --- Amundi Épargne Salariale ---

export const amundiApi = {
  initiateAuth: (login: string, password: string) =>
    api
      .post<AmundiAuthInitResponse>('/amundi/auth/initiate', { login, password })
      .then(r => r.data),

  // `code` is omitted for an app push: the user approves on their phone.
  completeAuth: (processId: string, code?: string) =>
    api
      .post<AmundiSessionStatus>('/amundi/auth/complete', { processId, code })
      .then(r => r.data),

  sync: () => api.post<AmundiSessionStatus>('/amundi/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<AmundiSessionStatus>('/amundi/status', {
        skipGlobalErrorRedirect: true,
      })
      .then(r => r.data),

  clearSession: () => api.delete('/amundi/session'),
}

// --- Fortuneo ---

const fortuneoErrorCodeSchema = z.enum([
  'INVALID_CREDENTIALS',
  'INVALID_OTP',
  'AUTH_ATTEMPT_EXPIRED',
  'SESSION_EXPIRED',
  'INVESTOR_PROFILE_REQUIRED',
  'PORTFOLIO_INCOMPLETE',
  'UPSTREAM_FORMAT_CHANGED',
  'UPSTREAM_UNAVAILABLE',
  'INVALID_DATA',
  'INTERNAL_ERROR',
])

const nullableFortuneoInstantSchema = z
  .string()
  .datetime({ offset: true })
  .nullish()
  .transform(value => value ?? null)

const fortuneoStatusBaseSchema = z.object({
  isActive: z.boolean(),
  expiresAt: nullableFortuneoInstantSchema,
  lastSyncStartedAt: nullableFortuneoInstantSchema,
  lastSyncCompletedAt: nullableFortuneoInstantSchema,
})

const fortuneoSessionStatusSchema = z.discriminatedUnion('syncStatus', [
  fortuneoStatusBaseSchema.extend({
    syncStatus: z.literal('FAILED'),
    lastSyncError: fortuneoErrorCodeSchema,
  }),
  fortuneoStatusBaseSchema.extend({
    syncStatus: z.enum(['IDLE', 'QUEUED', 'RUNNING', 'SUCCESS']),
    lastSyncError: z.null().optional().transform(() => null),
  }),
])

const fortuneoAuthInitResponseSchema = z.discriminatedUnion('mfaRequired', [
  z.object({
    processId: z.string().min(1),
    mfaRequired: z.literal(true),
    mfaType: z.string().min(1),
  }),
  z.object({
    // Spring serializes with `default-property-inclusion: non_null`, so the
    // backend omits these keys entirely rather than sending them as null.
    // Requiring a literal null made every no-MFA login surface a validation
    // error even though the session had been stored and the sync queued.
    // Same treatment as `lastSyncError` above.
    processId: z.null().optional().transform(() => null),
    mfaRequired: z.literal(false),
    mfaType: z.null().optional().transform(() => null),
  }),
])

const parseFortuneoStatus = (data: unknown): FortuneoSessionStatus =>
  fortuneoSessionStatusSchema.parse(data)

const parseFortuneoAuthInit = (data: unknown): FortuneoAuthInitResponse =>
  fortuneoAuthInitResponseSchema.parse(data)

export const fortuneoApi = {
  initiateAuth: (login: string, password: string) =>
    api
      .post<unknown>('/fortuneo/auth/initiate', { login, password })
      .then(r => parseFortuneoAuthInit(r.data)),

  completeAuth: (processId: string, code: string) =>
    api
      .post<unknown>('/fortuneo/auth/complete', { processId, code })
      .then(r => parseFortuneoStatus(r.data)),

  sync: () =>
    api.post<unknown>('/fortuneo/sync').then(r => parseFortuneoStatus(r.data)),

  getStatus: () =>
    api
      .get<unknown>('/fortuneo/status', {
        skipGlobalErrorRedirect: true,
      })
      .then(r => parseFortuneoStatus(r.data)),

  clearSession: () => api.delete('/fortuneo/session'),
}

export const ibkrApi = {
  getStatus: () => api.get<IbkrConnectionStatus>('/ibkr/status').then(r => r.data),

  connect: (token: string, queryId: string) =>
    api.post('/ibkr/connect', { token, queryId }).then(r => r.data),

  sync: () => api.post<Account[]>('/ibkr/sync').then(r => r.data),

  disconnect: () => api.delete('/ibkr/connection').then(r => r.data),
}

// --- Finary ---

export const finaryApi = {
  getStatus: () =>
    api.get<FinaryConnectionStatus>('/finary/status').then(r => r.data),

  login: (email: string, password: string) =>
    api.post('/finary/login', { email, password }).then(r => r.data),

  checkTotp: () =>
    api.post<{ totpRequired: boolean }>('/finary/check-totp').then(r => r.data),

  deleteSession: () =>
    api.delete('/finary/session').then(r => r.data),

  previewFile: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<FinaryPreviewResponse>('/finary/preview', form).then(r => r.data)
  },

  previewApi: (totp?: string) =>
    api
      .post<FinaryPreviewResponse>(`/finary/api-sync/preview${totp ? `?totp=${totp}` : ''}`)
      .then(r => r.data),

  import: (request: FinaryImportRequest) =>
    api
      .post<FinaryImportResultResponse>('/finary/import', request)
      .then(r => r.data),

  executeApiSync: (syncToken: string, mappings: FinaryAccountMapping[]) =>
    api
      .post<FinaryImportResultResponse>('/finary/api-sync/execute', { syncToken, mappings })
      .then(r => r.data),

  autoSync: () =>
    api
      .post<FinaryAutoSyncResponse>('/finary/api-sync/auto')
      .then(r => r.data),
}
