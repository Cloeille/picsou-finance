import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { SELECT_CONTROL_CLASS } from '@/lib/constants'
import { useClassifyHolding, useHoldingClassification } from '@/features/analysis/hooks'
import type {
  HoldingClassificationRequest,
  HoldingClassificationView,
  WealthTier,
} from '@/types/api'

/**
 * The Morningstar sectors, in the order the breakdown legend uses. These are the same keys the
 * ETF look-through already emits, so a hand-classified share lands in the very same bucket as a
 * fund's slice rather than starting a parallel vocabulary.
 */
const SECTOR_KEYS = [
  'technology',
  'communication_services',
  'consumer_cyclical',
  'consumer_defensive',
  'healthcare',
  'industrials',
  'utilities',
  'basic_materials',
  'energy',
  'financial_services',
  'real_estate',
] as const

/**
 * Where a holding no data source covers can go.
 *
 * <p>Offered apart from the real sectors and regions because it is a different kind of answer:
 * an ELTIF or an unquoted fund is not badly classified, it is outside the listed world
 * altogether. Without these it would sit in the "to classify" list forever, dragging coverage
 * down and implying a lookup that will never succeed.
 */
const UNLISTED_SECTOR = 'private_equity'
const UNLISTED_COUNTRY = 'XU'

/** The countries the breakdown already knows how to name, ISO 3166-1 alpha-2. */
const COUNTRY_KEYS = [
  'US', 'FR', 'DE', 'GB', 'NL', 'CH', 'IE', 'LU', 'BE', 'ES', 'IT', 'SE', 'DK', 'FI', 'NO',
  'CA', 'JP', 'CN', 'HK', 'KR', 'TW', 'IN', 'SG', 'AU', 'BR',
] as const

const TIER_KEYS: WealthTier[] = [
  'SAFETY_NET',
  'REAL_ESTATE',
  'EQUITY',
  'CRYPTO',
  'ALTERNATIVE',
]

/**
 * The editor body, mounted only once the current classification has loaded and keyed on it, so
 * every field can seed from a lazy `useState` initializer instead of an effect — the shape the
 * React Compiler's `set-state-in-effect` rule requires, and what the sibling modals already do.
 */
function ClassificationForm({
  ticker,
  name,
  current,
  saving,
  onSubmit,
  onClose,
}: {
  ticker: string
  name: string | null
  current: HoldingClassificationView
  saving: boolean
  onSubmit: (body: HoldingClassificationRequest) => void
  onClose: () => void
}) {
  const { t } = useTranslation()

  const [sector, setSector] = useState(() => current.sectorKey ?? '')
  const [country, setCountry] = useState(() => current.countryKey ?? '')
  const [tier, setTier] = useState<string>(() => current.wealthTier ?? '')

  function submit() {
    // Empty means "stop overriding this field" — the backend drops the row once all three are
    // null, so clearing a mistake restores the provider's own answer rather than freezing a blank.
    onSubmit({
      sectorKey: sector === '' ? null : sector,
      countryKey: country === '' ? null : country,
      wealthTier: tier === '' ? null : (tier as WealthTier),
    })
  }

  const inferredSector = current.inferredSectorKey
  const inferredCountry = current.inferredCountryKey

  return (
    <>
      <div className="space-y-6">
        <div className="space-y-2">
          <Label htmlFor="classification-sector">
            {t('analysis.classification.sector')}
          </Label>
          <select
            id="classification-sector"
            className={SELECT_CONTROL_CLASS}
            value={sector}
            onChange={(e) => setSector(e.target.value)}
          >
            <option value="">{t('analysis.classification.inherit')}</option>
            {SECTOR_KEYS.map((key) => (
              <option key={key} value={key}>
                {t(`holdings.insight.sectorNames.${key}`, key)}
              </option>
            ))}
            <option disabled>──────────</option>
            <option value={UNLISTED_SECTOR}>
              {t(`holdings.insight.sectorNames.${UNLISTED_SECTOR}`)}
            </option>
          </select>
          {/* Shown, never pre-selected: adopting a guess by default would turn it into a permanent
              hand-made override that stops tracking the provider. */}
          {inferredSector && (
            <p className="text-sm text-muted-foreground">
              {t('analysis.classification.inferred', {
                value: t(`holdings.insight.sectorNames.${inferredSector}`, inferredSector),
              })}
            </p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="classification-country">
            {t('analysis.classification.country')}
          </Label>
          <select
            id="classification-country"
            className={SELECT_CONTROL_CLASS}
            value={country}
            onChange={(e) => setCountry(e.target.value)}
          >
            <option value="">{t('analysis.classification.inherit')}</option>
            {COUNTRY_KEYS.map((key) => (
              <option key={key} value={key}>
                {t(`holdings.insight.countryNames.${key}`, key)}
              </option>
            ))}
            <option disabled>──────────</option>
            <option value={UNLISTED_COUNTRY}>
              {t(`holdings.insight.countryNames.${UNLISTED_COUNTRY}`)}
            </option>
          </select>
          {inferredCountry && (
            <p className="text-sm text-muted-foreground">
              {t('analysis.classification.inferred', {
                value: t(`holdings.insight.countryNames.${inferredCountry}`, inferredCountry),
              })}
            </p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="classification-tier">{t('analysis.classification.tier')}</Label>
          <select
            id="classification-tier"
            className={SELECT_CONTROL_CLASS}
            value={tier}
            onChange={(e) => setTier(e.target.value)}
          >
            <option value="">{t('analysis.classification.inherit')}</option>
            {TIER_KEYS.map((key) => (
              <option key={key} value={key}>
                {t(`analysis.tiers.${key}`, key)}
              </option>
            ))}
          </select>
          <p className="text-sm text-muted-foreground">
            {t('analysis.classification.tierHint')}
          </p>
        </div>

        <p className="text-sm text-muted-foreground">
          {t('analysis.classification.scopeHint', { ticker, name: name ?? ticker })}
        </p>
      </div>

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button type="button" onClick={submit} disabled={saving}>
          {t('common.save')}
        </Button>
      </DialogFooter>
    </>
  )
}

export function HoldingClassificationModal({
  open,
  accountId,
  ticker,
  name,
  onClose,
}: {
  open: boolean
  accountId: number | null
  ticker: string | null
  name?: string | null
  onClose: () => void
}) {
  const { t } = useTranslation()
  const current = useHoldingClassification(accountId, ticker, open)
  // Deliberately here and not inside the form.
  //
  // Saving invalidates the whole ['analysis'] namespace, which includes the per-ticker query this
  // modal reads. The refetch returns the values just saved, the form's remount key changes, and
  // React unmounts it — and TanStack Query drops the callbacks passed to mutate() when the
  // component that called it is gone (mutationObserver checks hasListeners()). So `onSuccess:
  // onClose` never fired: the row was written and the dialog just sat there.
  //
  // This component survives that remount, so its observer is still listening when the mutation
  // resolves. The keyed remount stays, because docs/conventions/frontend.md forbids re-seeding
  // form state from an effect.
  const classify = useClassifyHolding()

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('analysis.classification.title')}</DialogTitle>
          <DialogDescription>
            {name ? `${name} · ${ticker}` : ticker}
          </DialogDescription>
        </DialogHeader>

        {current.isPending || !current.data || accountId == null || !ticker ? (
          <div className="space-y-4">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : (
          <ClassificationForm
            // Remounts when the loaded classification changes, so the lazy initializers re-seed.
            key={`${current.data.ticker}-${current.data.sectorKey}-${current.data.countryKey}-${current.data.wealthTier}`}
            ticker={ticker}
            name={name ?? null}
            current={current.data}
            saving={classify.isPending}
            onSubmit={(body) =>
              classify.mutate({ accountId, ticker, body }, { onSuccess: onClose })
            }
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}
