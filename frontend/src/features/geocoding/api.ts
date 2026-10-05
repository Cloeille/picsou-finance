import { api } from '@/lib/api-client'
import type { GeocodeSuggestion } from '@/types/api'

export const geocodingApi = {
  /**
   * Address suggestions, proxied through the backend.
   *
   * Server-side rather than calling IGN from the browser: it keeps the external dependency
   * behind the ports-and-adapters boundary, and it is the only place a rate limit can
   * actually be enforced.
   */
  search: (q: string, limit = 5) =>
    api.get<GeocodeSuggestion[]>('/geocode', { params: { q, limit } }).then(r => r.data),
}
