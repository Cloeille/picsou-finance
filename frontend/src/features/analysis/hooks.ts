import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { analysisApi } from './api'
import { useMemberProfile } from '@/features/profile/hooks'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { ageAt, formatDate, localeFromLanguage } from '@/lib/utils'
import { useAppStore } from '@/stores/app-store'
import type { AllocationTargetsRequest, HoldingClassificationRequest } from '@/types/api'

/**
 * How a projected date is written wherever the two projection charts show one.
 *
 * Two things it fixes, both about a reader making sense of a point twenty years out:
 *
 * - **The date follows the app's format setting.** The tooltips printed the payload's raw
 *   `yyyy-MM-dd`, so someone who had chosen `DD-MM-YYYY` in Settings saw `2042-08-31` here and
 *   `31-08-2042` everywhere else.
 * - **It carries the member's age**, when a birth date is known. "2042" is an abstraction; "at
 *   58" is the thing being decided about. `MemberProfileResponse.age` is today's age and no use
 *   here — this is the age *on that date*, which only the client can work out per point.
 *
 * Lives here rather than beside the charts so the two tabs of one card cannot drift apart, and
 * as a hook because the answer depends on the language, the format setting and the profile.
 */
export function useProjectionDateLabel() {
  const { t, i18n } = useTranslation()
  // Subscribed, not read through getState(): a tooltip open while the setting changes should
  // redraw with it, which is the same reason useMoney is a hook.
  const dateFormat = useAppStore((s) => s.dateFormat)
  const { data: profile } = useMemberProfile()

  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const birthDate = profile?.birthDate ?? null

  return useCallback(
    (date: string) => {
      const formatted = formatDate(date, locale, dateFormat)
      const age = birthDate == null ? null : ageAt(birthDate, date)
      return age == null ? formatted : `${formatted} · ${t('analysis.projection.atAge', { age })}`
    },
    [locale, dateFormat, birthDate, t],
  )
}

export function useWealthPyramid() {
  return useQuery({
    queryKey: ['analysis', 'pyramid'],
    queryFn: analysisApi.pyramid,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useDiversification() {
  return useQuery({
    queryKey: ['analysis', 'diversification'],
    queryFn: analysisApi.diversification,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useProjection(years: number) {
  return useQuery({
    queryKey: ['analysis', 'projection', years],
    queryFn: () => analysisApi.projection(years),
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useAllocationTargets() {
  return useQuery({
    queryKey: ['analysis', 'targets'],
    queryFn: analysisApi.targets,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

/**
 * Only fetched when the targets dialog is open: it scans six months of transactions, and the
 * suggestion is meaningless until someone is looking at the field it fills.
 */
export function useEssentialExpenseEstimate(enabled: boolean) {
  return useQuery({
    queryKey: ['analysis', 'expense-estimate'],
    queryFn: analysisApi.expenseEstimate,
    enabled,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useSaveAllocationTargets() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AllocationTargetsRequest) => analysisApi.saveTargets(body),
    // The pyramid is scored against these targets, so it is stale the moment they change.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['analysis'] }),
  })
}

/**
 * The classification in force for one ticker. Only fetched while the editor is open — it is a
 * per-ticker round trip and nothing shows it until someone asks to change something.
 */
export function useHoldingClassification(
  accountId: number | null,
  ticker: string | null,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['analysis', 'classification', accountId, ticker],
    queryFn: () => analysisApi.holdingClassification(accountId!, ticker!),
    enabled: enabled && accountId != null && !!ticker,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useClassifyHolding() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      accountId,
      ticker,
      body,
    }: {
      accountId: number
      ticker: string
      body: HoldingClassificationRequest
    }) => analysisApi.classifyHolding(accountId, ticker, body),
    // Both breakdowns and the pyramid read the override, so the whole namespace is stale.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['analysis'] }),
  })
}

/**
 * Warms the profiles now instead of waiting for the weekly pass.
 *
 * <p>Nothing is invalidated on success: the server answers 202 the moment the work is queued, so
 * the data is not ready yet and refetching here would just re-read the same empty table. The
 * caller refetches when it decides enough time has passed.
 */
export function useRefreshSecurityProfiles() {
  return useMutation({
    mutationFn: () => analysisApi.refreshSecurityProfiles(),
  })
}
