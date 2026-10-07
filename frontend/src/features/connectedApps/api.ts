import { api } from '@/lib/api-client'

/**
 * An OAuth2-connected remote-MCP client (e.g. claude.ai) the current user has approved via the
 * `/consent` screen. Mirrors backend `ConnectedAppResponse` — camelCase, unlike the OAuth2
 * `consent-info` endpoint (see `features/oauthConsent/api.ts`), because this one is a regular
 * first-party `/api/**` resource, not part of the OAuth2 wire vocabulary.
 */
export interface ConnectedApp {
  id: string
  clientName: string
  scopes: string[]
  issuedAt: string
  lastUsedAt: string | null
}

export const connectedAppsApi = {
  list: () => api.get<ConnectedApp[]>('/connected-apps').then((r) => r.data),

  revoke: (id: string) => api.delete(`/connected-apps/${id}`),
}
