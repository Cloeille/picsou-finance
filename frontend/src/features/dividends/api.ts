import { api } from '@/lib/api-client'
import type { UpcomingDividend } from '@/types/api'

/**
 * Read-only forecast of dividends and SCPI distributions. The horizon is clamped
 * server-side (1..366, default 60); this client just forwards the requested window.
 * `accountId` is optional: omitted, the forecast is the household's; set, it is
 * that account's rows only. An absent id is left off the query string so the
 * budget card's request stays exactly what it was.
 */
export const dividendsApi = {
  upcoming: (horizonDays = 60, accountId?: number) =>
    api.get<UpcomingDividend[]>('/dividends/upcoming', {
      params: accountId == null ? { horizonDays } : { horizonDays, accountId },
    }).then(r => r.data),
}
