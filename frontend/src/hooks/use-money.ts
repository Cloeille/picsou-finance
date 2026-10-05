import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { makeMoneyFormatter, type MoneyFormatter } from '@/lib/money'
import { localeFromLanguage } from '@/lib/utils'
import { useAppStore } from '@/stores/app-store'

/**
 * The formatter every displayed amount goes through.
 *
 * A hook rather than a plain function, and that is the load-bearing part: reading the flag with
 * `useAppStore.getState()` would return the right string but would not re-render the component
 * holding the closure, so a chart's axis would keep its old ticks until something else happened
 * to re-render it. `formatDate` in `lib/utils.ts` already makes that mistake — it only looks
 * correct because the date-format control lives on /settings and navigating away remounts
 * everything. The eye button is visible on every page, so that accident would not save it.
 *
 * The returned object is memoised, so it is stable while nothing changes and gets a new identity
 * on toggle — which is what makes a `tickFormatter` prop change and the axis redraw.
 */
export function useMoney(): MoneyFormatter {
  const { i18n } = useTranslation()
  const hidden = useAppStore((s) => s.hideAmounts)
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  return useMemo(() => makeMoneyFormatter({ hidden, locale }), [hidden, locale])
}
