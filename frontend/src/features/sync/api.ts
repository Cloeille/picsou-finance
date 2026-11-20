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
  TrAuthInitResponse,
  TrSessionStatus,
  BoursoAuthInitResponse,
  CaisseEpargneSessionStatus,
  CaisseEpargneAuthInitResponse,
  CaisseEpargneAuthKeypadResponse,
  CaisseEpargneAuthCompleteResponse,
  CaisseEpargneDeleteResponse,
  BanquePopulaireSessionStatus,
  BanquePopulaireAuthInitResponse,
  BanquePopulaireAuthKeypadResponse,
  BanquePopulaireAuthCompleteResponse,
  BanquePopulaireDeleteResponse,
  LbpSessionStatus,
  LbpAuthInitResponse,
  LbpAuthKeypadResponse,
  LbpAuthCompleteResponse,
  LbpDeleteResponse,
  BnpBrand,
  BnpSessionStatus,
  BnpAuthInitResponse,
  BnpAuthKeypadResponse,
  BnpAuthCompleteResponse,
  BnpDeleteResponse,
  RevolutSessionStatus,
  SyncProgress,
  BourseDirectSessionStatus,
  BourseDirectAuthInitResponse,
  DegiroSessionStatus,
  DegiroAuthInitResponse,
  AmundiSessionStatus,
  AmundiAuthInitResponse,
  CmesSessionStatus,
  CmesAuthInitResponse,
  NatixisSessionStatus,
  NatixisAuthInitResponse,
  CicSessionStatus,
  CicAuthInitResponse,
  AmexSessionStatus,
  AmexAuthInitResponse,
  AmexOtpMethod,
  FortuneoSessionStatus,
  FortuneoAuthInitResponse,
  CorumSessionStatus,
  SofidySessionStatus,
  SofidyAuthInitResponse,
  IbkrConnectionStatus,
  SimplefinConnectionStatus,
  WiseConnectionStatus,
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
      .post<Account[]>('/sync/complete', { code, state: state ?? undefined })
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

const TR_COMPLETE_TIMEOUT_MS = 180_000

export const trApi = {
  initiateAuth: (phoneNumber: string, pin: string) =>
    api
      .post<TrAuthInitResponse>('/tr/auth/initiate', { phoneNumber, pin })
      .then(r => r.data),

  /** Re-login with the phone stored in the session: only the PIN or password is sent. */
  reconnectAuth: (pin: string) =>
    api.post<TrAuthInitResponse>('/tr/auth/reconnect', { pin }).then(r => r.data),

  /**
   * With no `tan` the request stays open while the member confirms in the Trade Republic app
   * (up to ~150 s), hence the long timeout. With a `tan` it answers at once.
   */
  completeAuth: (processId: string, tan?: string) =>
    api
      .post<TrSessionStatus>(
        '/tr/auth/complete',
        tan === undefined ? { processId } : { processId, tan },
        { timeout: TR_COMPLETE_TIMEOUT_MS },
      )
      .then(r => r.data),

  /** The sync is queued, not finished: poll {@link trApi.getSessionStatus}. */
  sync: () =>
    api.post<TrSessionStatus>('/tr/sync').then(r => r.data),

  getSessionStatus: () =>
    api.get<TrSessionStatus>('/tr/status').then(r => r.data),

  importCsv: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<Account[]>('/tr/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
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

// --- Caisse d'Epargne ---

export const caisseEpargneApi = {
  // One attempt only: a wrong password spends a bank attempt and can lock the
  // account, so nothing may replay this call after a failure. The identifier
  // alone goes out: the password is clicked on the bank's keypad by the user.
  initiateAuth: (customerId: string) =>
    api
      .post<CaisseEpargneAuthInitResponse>('/caisse-epargne/auth/initiate', { customerId })
      .then(r => r.data),

  // Key positions (0..9 in the pad's DOM order), never digits. Single use: the
  // backend rejects a second call on the same process, and so must nothing here.
  sendKeypad: (processId: string, positions: number[]) =>
    api
      .post<CaisseEpargneAuthKeypadResponse>('/caisse-epargne/auth/keypad', { processId, positions })
      .then(r => r.data),

  // No code: the user approves the Sécur'Pass push on their phone and the
  // request stays open until they do (the backend waits up to 170 s). Bounded a little
  // above that: a request that never answers then rejects without a status, which the
  // panel treats as a lost answer and recovers from by polling the session.
  completeAuth: (processId: string) =>
    api
      .post<CaisseEpargneAuthCompleteResponse>('/caisse-epargne/auth/complete', { processId }, { timeout: 180_000 })
      .then(r => r.data),

  sync: () =>
    api.post<CaisseEpargneSessionStatus>('/caisse-epargne/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<CaisseEpargneSessionStatus>('/caisse-epargne/status', { skipGlobalErrorRedirect: true })
      .then(r => r.data),

  /** One status read that gives up after `timeoutMs`, outside the query cache. */
  getStatusWithin: (timeoutMs: number) =>
    api
      .get<CaisseEpargneSessionStatus>('/caisse-epargne/status', {
        skipGlobalErrorRedirect: true,
        timeout: timeoutMs,
      })
      .then(r => r.data),

  clearSession: () =>
    api.delete<CaisseEpargneDeleteResponse>('/caisse-epargne/session').then(r => r.data),
}

// --- Banque Populaire ---

// Same BPCE platform and the same rules as Caisse d'Epargne above: one attempt only,
// key positions never digits, a long Sécur'Pass completion. The login adds the regional
// bank (`BanquePopulaireRegion`) the bank's page asks for before the identifier.
export const banquePopulaireApi = {
  initiateAuth: (customerId: string, region: string) =>
    api
      .post<BanquePopulaireAuthInitResponse>('/banque-populaire/auth/initiate', { customerId, region })
      .then(r => r.data),

  sendKeypad: (processId: string, positions: number[]) =>
    api
      .post<BanquePopulaireAuthKeypadResponse>('/banque-populaire/auth/keypad', { processId, positions })
      .then(r => r.data),

  completeAuth: (processId: string) =>
    api
      .post<BanquePopulaireAuthCompleteResponse>('/banque-populaire/auth/complete', { processId }, { timeout: 180_000 })
      .then(r => r.data),

  sync: () =>
    api.post<BanquePopulaireSessionStatus>('/banque-populaire/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<BanquePopulaireSessionStatus>('/banque-populaire/status', { skipGlobalErrorRedirect: true })
      .then(r => r.data),

  /** One status read that gives up after `timeoutMs`, outside the query cache. */
  getStatusWithin: (timeoutMs: number) =>
    api
      .get<BanquePopulaireSessionStatus>('/banque-populaire/status', {
        skipGlobalErrorRedirect: true,
        timeout: timeoutMs,
      })
      .then(r => r.data),

  clearSession: () =>
    api.delete<BanquePopulaireDeleteResponse>('/banque-populaire/session').then(r => r.data),
}

// --- La Banque Postale ---

export const lbpApi = {
  // One attempt only: a wrong password spends a bank attempt. The identifier
  // alone goes out; the password is clicked on the bank's shuffled keypad.
  // `rememberIdentifier` asks the backend to keep it encrypted, never the password.
  initiateAuth: (customerId: string, rememberIdentifier = false) =>
    api.post<LbpAuthInitResponse>('/lbp/auth/initiate', { customerId, rememberIdentifier }).then(r => r.data),

  // Key positions (0..9 in the keypad's DOM order), never digits. Single use.
  sendKeypad: (processId: string, positions: number[]) =>
    api.post<LbpAuthKeypadResponse>('/lbp/auth/keypad', { processId, positions }).then(r => r.data),

  // A notification is sent to the bank's mobile app; the request stays open
  // until the human approves it (the backend waits up to 170 s).
  completeAuth: (processId: string) =>
    api
      .post<LbpAuthCompleteResponse>('/lbp/auth/complete', { processId }, { timeout: 180_000 })
      .then(r => r.data),

  sync: () => api.post<LbpSessionStatus>('/lbp/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<LbpSessionStatus>('/lbp/status', { skipGlobalErrorRedirect: true })
      .then(r => r.data),

  /** One status read that gives up after `timeoutMs`, outside the query cache. */
  getStatusWithin: (timeoutMs: number) =>
    api
      .get<LbpSessionStatus>('/lbp/status', {
        skipGlobalErrorRedirect: true,
        timeout: timeoutMs,
      })
      .then(r => r.data),

  clearSession: () => api.delete<LbpDeleteResponse>('/lbp/session').then(r => r.data),
}

// --- BNP Paribas / Hello bank! ---

export const bnpApi = {
  // One attempt only: a wrong password spends a bank attempt. The customer number alone
  // goes out; the password is clicked on the bank's shuffled keypad.
  initiateAuth: (customerId: string, brand: BnpBrand) =>
    api.post<BnpAuthInitResponse>('/bnp/auth/initiate', { customerId, brand }).then(r => r.data),

  // Tile positions (0..9 in reading order), never digits. Single use.
  sendKeypad: (processId: string, positions: number[]) =>
    api.post<BnpAuthKeypadResponse>('/bnp/auth/keypad', { processId, positions }).then(r => r.data),

  // Without a code the request stays open until the member approves in the bank's app (the
  // backend waits up to 200 s); with one it submits the SMS code.
  completeAuth: (processId: string, code?: string) =>
    api
      .post<BnpAuthCompleteResponse>(
        '/bnp/auth/complete',
        code === undefined ? { processId } : { processId, code },
        { timeout: 210_000 },
      )
      .then(r => r.data),

  sync: () => api.post<BnpSessionStatus>('/bnp/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<BnpSessionStatus>('/bnp/status', { skipGlobalErrorRedirect: true })
      .then(r => r.data),

  /** One status read that gives up after `timeoutMs`, outside the query cache. */
  getStatusWithin: (timeoutMs: number) =>
    api
      .get<BnpSessionStatus>('/bnp/status', { skipGlobalErrorRedirect: true, timeout: timeoutMs })
      .then(r => r.data),

  clearSession: () => api.delete<BnpDeleteResponse>('/bnp/session').then(r => r.data),
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

// --- Browser-sidecar providers sharing one HTTP contract ---

/**
 * Bourse Direct, Amundi, CMES, Natixis and CIC expose the same five endpoints under their
 * own prefix; a provider that deviates (BoursoBank, Amex, Sofidy...) keeps its
 * own object below. `code` is the SMS code, omitted for a mobile confirmation.
 */
function sidecarApi<TStatus, TInit>(prefix: string) {
  return {
    initiateAuth: (login: string, password: string) =>
      api.post<TInit>(`${prefix}/auth/initiate`, { login, password }).then(r => r.data),

    completeAuth: (processId: string, code?: string) =>
      api.post<TStatus>(`${prefix}/auth/complete`, { processId, code }).then(r => r.data),

    sync: () => api.post<TStatus>(`${prefix}/sync`).then(r => r.data),

    getStatus: () =>
      api.get<TStatus>(`${prefix}/status`, { skipGlobalErrorRedirect: true }).then(r => r.data),

    clearSession: () => api.delete(`${prefix}/session`),
  }
}

/** Shape of a `sidecarApi` object, for code generic over those providers. */
export type SidecarApi<TStatus, TInit> = ReturnType<typeof sidecarApi<TStatus, TInit>>

// --- Bourse Direct ---

export const bourseDirectApi = sidecarApi<BourseDirectSessionStatus, BourseDirectAuthInitResponse>('/bourse-direct')

// --- Amundi Épargne Salariale (`code` is omitted for an app push: the user approves on their phone) ---

export const amundiApi = sidecarApi<AmundiSessionStatus, AmundiAuthInitResponse>('/amundi')

// --- Crédit Mutuel Épargne Salariale (CIC savers too) ---

export const cmesApi = sidecarApi<CmesSessionStatus, CmesAuthInitResponse>('/cmes')

// --- Natixis Interépargne (the login is an e-mail, sent as `email`; the code arrives by SMS, e-mail or voice call) ---

export const natixisApi = {
  ...sidecarApi<NatixisSessionStatus, NatixisAuthInitResponse>('/natixis'),
  initiateAuth: (email: string, password: string) =>
    api.post<NatixisAuthInitResponse>('/natixis/auth/initiate', { email, password }).then(r => r.data),
}

// --- CIC online banking (livrets the PSD2 connection does not expose) ---

export const cicApi = {
  ...sidecarApi<CicSessionStatus, CicAuthInitResponse>('/cic'),

  // Logs in again from the credentials stored at the last login.
  reconnect: () =>
    api.post<CicAuthInitResponse>('/cic/auth/reconnect').then(r => r.data),
}

// --- American Express ---

export const amexApi = {
  /** `remember` stores the credentials encrypted for the unattended re-login; false stores none. */
  initiateAuth: (login: string, password: string, method: AmexOtpMethod, remember = false) =>
    api
      .post<AmexAuthInitResponse>('/amex/auth/initiate', { login, password, method, remember })
      .then(r => r.data),

  completeAuth: (processId: string, code: string) =>
    api
      .post<AmexSessionStatus>('/amex/auth/complete', { processId, otp: code })
      .then(r => r.data),

  sync: () => api.post<AmexSessionStatus>('/amex/sync').then(r => r.data),

  recoverHistory: () => api.post<AmexSessionStatus>('/amex/history-recovery').then(r => r.data),

  getStatus: () =>
    api
      .get<AmexSessionStatus>('/amex/status', {
        skipGlobalErrorRedirect: true,
      })
      .then(r => r.data),

  clearSession: () => api.delete('/amex/session'),
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

// --- CORUM client space ---

/**
 * CORUM asks for no second factor, so `authenticate` is the whole exchange and
 * answers with the session status directly. The panel reads it as "no MFA
 * required" -- see `SidecarSessionPanel`'s `TInit` contract.
 */
export const corumApi = {
  /** `remember` stores the credentials encrypted for the unattended re-login; false clears them. */
  authenticate: (login: string, password: string, remember = false) =>
    api
      .post<CorumSessionStatus>('/corum/auth', { login, password, remember })
      .then(r => r.data),

  sync: () => api.post<CorumSessionStatus>('/corum/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<CorumSessionStatus>('/corum/status', {
        skipGlobalErrorRedirect: true,
      })
      .then(r => r.data),

  clearSession: () => api.delete('/corum/session'),

}

// --- Sofidy client space ---

/**
 * Sofidy always asks for a verification code by e-mail after the password, so
 * the login is two calls: this one starts it, `completeAuth` opens the session.
 */
export const sofidyApi = {
  initiateAuth: (associateCode: string, password: string) =>
    api
      .post<SofidyAuthInitResponse>('/sofidy/auth/initiate', {
        associateCode,
        password,
      })
      .then(r => r.data),

  completeAuth: (processId: string, code: string) =>
    api
      .post<SofidySessionStatus>('/sofidy/auth/complete', { processId, code })
      .then(r => r.data),

  sync: () => api.post<SofidySessionStatus>('/sofidy/sync').then(r => r.data),

  getStatus: () =>
    api
      .get<SofidySessionStatus>('/sofidy/status', {
        skipGlobalErrorRedirect: true,
      })
      .then(r => r.data),

  clearSession: () => api.delete('/sofidy/session'),
}

export const ibkrApi = {
  getStatus: () => api.get<IbkrConnectionStatus>('/ibkr/status').then(r => r.data),

  connect: (token: string, queryId: string) =>
    api.post('/ibkr/connect', { token, queryId }).then(r => r.data),

  sync: () => api.post<Account[]>('/ibkr/sync').then(r => r.data),

  disconnect: () => api.delete('/ibkr/connection').then(r => r.data),
}

export const simplefinApi = {
  getStatus: () => api.get<SimplefinConnectionStatus>('/simplefin/status').then(r => r.data),

  connect: (token: string) =>
    api.post('/simplefin/connect', { token }).then(r => r.data),

  sync: () => api.post<Account[]>('/simplefin/sync').then(r => r.data),

  disconnect: () => api.delete('/simplefin/connection').then(r => r.data),
}

export const wiseApi = {
  getStatus: () => api.get<WiseConnectionStatus>('/wise/status').then(r => r.data),

  connect: (token: string) => api.post<Account[]>('/wise/connect', { token }).then(r => r.data),

  sync: () => api.post<Account[]>('/wise/sync').then(r => r.data),

  disconnect: () => api.delete('/wise/connection').then(r => r.data),
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
    return api
      .post<FinaryPreviewResponse>('/finary/preview', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then(r => r.data)
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
