import type { QueryKey } from '@tanstack/react-query'
import {
  amexApi,
  amundiApi,
  boursoApi,
  bourseDirectApi,
  caisseEpargneApi,
  cicApi,
  cmesApi,
  corumApi,
  fortuneoApi,
  sofidyApi,
} from './api'
import type { SidecarApi } from './api'
import { ACCOUNT_VIEWS, syncKeys } from './keys'
import type {
  AmexOtpMethod,
  CorumAuthInitResponse,
  CorumSessionStatus,
  SidecarAuthInitResponse,
  SidecarSessionStatus,
} from '@/types/api'

/** Login form of the providers that ask for an identifier and a password. */
type LoginCredentials = { login: string; password: string }

/** What the shared panel submits: the login form plus any extra choice (`SidecarExtraField`). */
export interface Credentials extends LoginCredentials {
  [key: string]: string
}

export type SecondFactor = { processId: string; code?: string }

/** Also the views fed by the real-estate and SCPI positions a portfolio sidecar imports. */
const PORTFOLIO_VIEWS = [
  ...ACCOUNT_VIEWS,
  ['real-estate'],
  ['analysis'],
  ['scpi-funds'],
] as const satisfies readonly QueryKey[]

/**
 * Everything the generic session hooks need to drive one browser-sidecar
 * provider. The variable types are `never` here only so any entry fits;
 * `sidecarProviders` keeps each entry's precise types.
 */
export interface SidecarProviderEntry {
  queryKey: QueryKey
  /** Query keys invalidated once a polled sync reports SUCCESS. */
  refreshOnSuccess: readonly QueryKey[]
  /** Query keys invalidated, besides the status, once the session is cleared. Defaults to the account views. */
  refreshOnClear?: readonly QueryKey[]
  getStatus: () => Promise<SidecarSessionStatus>
  /** Absent when the caller drives the login itself (Caisse d'Epargne). */
  initiate?: (vars: never) => Promise<unknown>
  complete: (vars: never) => Promise<unknown>
  sync: () => Promise<unknown>
  clear: () => Promise<unknown>
  /** Logs in again from the credentials stored at the last login. */
  reconnect?: () => Promise<SidecarAuthInitResponse>
  /**
   * The mutations only invalidate the status, after a failure too, and wait for
   * the refetch; they never write their answer into the cache (Amex).
   */
  settleMutations?: true
  /**
   * `complete` is one long call: never retried, whatever the query client
   * defaults say, and its answer is a login result, not a status, so it is not
   * written to the status cache.
   */
  singleAttemptComplete?: true
  /** `initiate` is the whole login and answers with the session status, which it writes to the cache. */
  noSecondFactor?: true
}

/** Wires the five endpoints shared by providers whose api comes from `sidecarApi`. */
function loginFlow<TStatus, TInit>(api: SidecarApi<TStatus, TInit>) {
  return {
    getStatus: () => api.getStatus(),
    initiate: ({ login, password }: LoginCredentials) => api.initiateAuth(login, password),
    complete: ({ processId, code }: SecondFactor) => api.completeAuth(processId, code),
    sync: () => api.sync(),
    clear: () => api.clearSession(),
  }
}

const neverCompleted: (vars: SecondFactor) => Promise<CorumSessionStatus> = () =>
  Promise.reject(new Error('CORUM requires no second factor'))

/**
 * The browser-sidecar providers. Adding one means a new entry here, its api
 * object in `api.ts` and its types in `types/api.ts`: the session hooks in
 * `hooks.ts` and `SidecarSessionPanel` are generic over this table.
 */
export const sidecarProviders = {
  bourso: {
    queryKey: syncKeys.bourso(),
    refreshOnSuccess: ACCOUNT_VIEWS,
    getStatus: () => boursoApi.getStatus(),
    initiate: ({ customerId, password }: { customerId: string; password: string }) =>
      boursoApi.initiateAuth(customerId, password),
    complete: ({ processId }: { processId: string }) => boursoApi.completeAuth(processId),
    sync: () => boursoApi.sync(),
    clear: () => boursoApi.clearSession(),
  },

  /**
   * There is deliberately no `initiate`: a mutation keeps its variables in the
   * mutation cache, and the keypad ones are the user's password (as key
   * positions). The panel calls `caisseEpargneApi.initiateAuth` / `sendKeypad`
   * directly and holds the positions in component state only.
   */
  caisseEpargne: {
    queryKey: syncKeys.caisseEpargne(),
    refreshOnSuccess: ACCOUNT_VIEWS,
    getStatus: () => caisseEpargneApi.getStatus(),
    complete: ({ processId }: { processId: string }) => caisseEpargneApi.completeAuth(processId),
    sync: () => caisseEpargneApi.sync(),
    clear: () => caisseEpargneApi.clearSession(),
    singleAttemptComplete: true,
  },

  bourseDirect: {
    queryKey: syncKeys.bourseDirect(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    ...loginFlow(bourseDirectApi),
  },

  amundi: {
    queryKey: syncKeys.amundi(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    ...loginFlow(amundiApi),
  },

  cmes: {
    queryKey: syncKeys.cmes(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    ...loginFlow(cmesApi),
  },

  cic: {
    queryKey: syncKeys.cic(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    ...loginFlow(cicApi),
    reconnect: () => cicApi.reconnect(),
  },

  amex: {
    queryKey: syncKeys.amex(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    getStatus: () => amexApi.getStatus(),
    initiate: ({ login, password, method }: Credentials) =>
      amexApi.initiateAuth(login, password, method as AmexOtpMethod),
    complete: ({ processId, code }: SecondFactor) => amexApi.completeAuth(processId, code ?? ''),
    sync: () => amexApi.sync(),
    clear: () => amexApi.clearSession(),
    refreshOnClear: [],
    settleMutations: true,
  },

  fortuneo: {
    queryKey: syncKeys.fortuneo(),
    refreshOnSuccess: ACCOUNT_VIEWS,
    getStatus: () => fortuneoApi.getStatus(),
    initiate: ({ login, password }: LoginCredentials) => fortuneoApi.initiateAuth(login, password),
    complete: ({ processId, code }: { processId: string; code: string }) =>
      fortuneoApi.completeAuth(processId, code),
    sync: () => fortuneoApi.sync(),
    clear: () => fortuneoApi.clearSession(),
  },

  /**
   * CORUM needs no second factor, so `initiate` is the whole exchange: the
   * backend answers with the session status.
   *
   * The result carries both shapes — the status fields the panel polls, and the
   * `mfaRequired: false` the shared sidecar panel reads to decide it is connected.
   * Spreading the status into the response is what keeps `SidecarSessionPanel`
   * reusable here without a provider-specific branch inside it.
   *
   * `complete` is never called: it is there because the shared panel requires it.
   */
  corum: {
    queryKey: syncKeys.corum(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    getStatus: () => corumApi.getStatus(),
    initiate: async ({ login, password }: LoginCredentials): Promise<CorumSessionStatus & CorumAuthInitResponse> => ({
      ...(await corumApi.authenticate(login, password)),
      processId: null,
      mfaRequired: false as const,
      mfaType: null,
    }),
    complete: neverCompleted,
    sync: () => corumApi.sync(),
    clear: () => corumApi.clearSession(),
    noSecondFactor: true,
  },

  /**
   * The portal always asks for a six-digit code by e-mail, so `initiate` never
   * opens a session: it returns the process id the completion call needs. The
   * panel's `login` field carries the six-digit associate code, not an email
   * address.
   */
  sofidy: {
    queryKey: syncKeys.sofidy(),
    refreshOnSuccess: PORTFOLIO_VIEWS,
    getStatus: () => sofidyApi.getStatus(),
    initiate: ({ login, password }: LoginCredentials) => sofidyApi.initiateAuth(login, password),
    complete: ({ processId, code }: SecondFactor) => sofidyApi.completeAuth(processId, code ?? ''),
    sync: () => sofidyApi.sync(),
    clear: () => sofidyApi.clearSession(),
  },
} as const satisfies Record<string, SidecarProviderEntry>

type Providers = typeof sidecarProviders

export type SidecarProviderId = keyof Providers

/** Providers rendered by the shared `SidecarSessionPanel`. */
export type PanelProviderId = Exclude<SidecarProviderId, 'bourso' | 'caisseEpargne' | 'fortuneo'>

/** Ids whose entry defines `K` (`initiate` and `reconnect` are optional). */
export type ProviderIdWith<K extends string> = {
  [I in SidecarProviderId]: K extends keyof Providers[I] ? I : never
}[SidecarProviderId]

export type ProviderStatus<I extends SidecarProviderId> = Awaited<ReturnType<Providers[I]['getStatus']>>

export type ProviderInitiateVars<I extends ProviderIdWith<'initiate'>> = Parameters<Providers[I]['initiate']>[0]
export type ProviderInitiateResult<I extends ProviderIdWith<'initiate'>> = Awaited<ReturnType<Providers[I]['initiate']>>
export type ProviderCompleteVars<I extends SidecarProviderId> = Parameters<Providers[I]['complete']>[0]
export type ProviderCompleteResult<I extends SidecarProviderId> = Awaited<ReturnType<Providers[I]['complete']>>
