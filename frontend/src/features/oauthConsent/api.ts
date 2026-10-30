import { api } from '@/lib/api-client'

/**
 * Backend response shape for `GET /api/oauth2/consent-info` (see
 * `com.picsou.dto.ConsentInfoResponse`). This one endpoint is snake_case — it mirrors the OAuth2
 * wire vocabulary (`client_id`, `scope`, `state`) rather than the rest of Picsou's camelCase API —
 * so it is mapped to a camelCase shape below before anything else in the app touches it.
 */
interface ConsentInfoRaw {
  client_name: string
  requested_scopes: string[]
  state: string | null
}

/** The pending `/oauth2/authorize` request the consent screen renders and approves/denies. */
export interface ConsentInfo {
  clientName: string
  requestedScopes: string[]
  state: string | null
}

export interface GetConsentInfoParams {
  clientId: string
  /** Space-delimited scope string, exactly as received in the `/consent` redirect's query string. */
  scope?: string
  state?: string
}

export const oauthConsentApi = {
  getConsentInfo: ({ clientId, scope, state }: GetConsentInfoParams) =>
    api
      .get<ConsentInfoRaw>('/oauth2/consent-info', {
        params: { client_id: clientId, scope, state },
      })
      .then(
        (r): ConsentInfo => ({
          clientName: r.data.client_name,
          requestedScopes: r.data.requested_scopes,
          state: r.data.state,
        }),
      ),
}
