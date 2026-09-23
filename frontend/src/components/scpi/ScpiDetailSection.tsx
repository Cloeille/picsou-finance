import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useUpdateScpiPosition } from '@/features/accounts/hooks'
import { formatApiError } from '@/lib/errors'
import { parseAmount } from '@/lib/utils'
import type { Account, DividendPolicy, ScpiPositionRequest } from '@/types/api'

const selectControlClassName =
  'flex h-10 w-full rounded-md border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]'

const num = (v: string): number | undefined => (v.trim() === '' ? undefined : parseAmount(v))

/**
 * The share, the two prices, and nothing that belongs to a house.
 * The balance shown above this card is the withdrawal value, never the subscription price.
 */
export function ScpiDetailSection({ account }: { account: Account }) {
  const { t } = useTranslation()
  const updatePosition = useUpdateScpiPosition()
  const position = account.scpi
  const canEdit = account.isOwner !== false
  const [editing, setEditing] = useState(canEdit && !position)
  const [shareCount, setShareCount] = useState(position?.shareCount?.toString() ?? '')
  const [subscriptionPrice, setSubscriptionPrice] = useState(position?.subscriptionPriceEur?.toString() ?? '')
  const [withdrawalPrice, setWithdrawalPrice] = useState(position?.withdrawalPriceEur?.toString() ?? '')
  const [dividendPolicy, setDividendPolicy] = useState<DividendPolicy>(position?.dividendPolicy ?? 'CASH')
  const [jouissanceDate, setJouissanceDate] = useState(position?.jouissanceDate ?? '')
  const [isin, setIsin] = useState(position?.isin ?? '')
  const [managementCompany, setManagementCompany] = useState(position?.managementCompany ?? '')

  const shares = num(shareCount)
  const canSave = shares != null && shares >= 0

  async function save() {
    if (!canSave || shares == null) return
    const data: ScpiPositionRequest = {
      isin: isin.trim() || null,
      managementCompany: managementCompany.trim() || null,
      shareCount: shares,
      subscriptionPriceEur: num(subscriptionPrice) ?? null,
      withdrawalPriceEur: num(withdrawalPrice) ?? null,
      dividendPolicy,
      jouissanceDate: jouissanceDate || null,
    }
    await updatePosition.mutateAsync({ id: account.id, data })
    setEditing(false)
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-base">{t('scpi.position')}</CardTitle>
        {canEdit && position && !editing && (
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}>
            {t('scpi.edit')}
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        {position?.valuationStatus === 'PRICE_INCOMPLETE' && (
          <p className="text-sm text-amber-600 dark:text-amber-400">{t('scpi.incomplete')}</p>
        )}

        {position && !editing && (
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <dt className="text-muted-foreground">{t('scpi.shareCount')}</dt>
              <dd className="tabular-nums font-medium">{position.shareCount}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">{t('scpi.withdrawalValue')}</dt>
              <dd>
                {position.withdrawalValueEur != null
                  ? <CurrencyDisplay value={position.withdrawalValueEur} />
                  : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">{t('scpi.withdrawalPrice')}</dt>
              <dd>
                {position.withdrawalPriceEur != null
                  ? <CurrencyDisplay value={position.withdrawalPriceEur} />
                  : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">{t('scpi.subscriptionPrice')}</dt>
              <dd>
                {position.subscriptionPriceEur != null
                  ? <CurrencyDisplay value={position.subscriptionPriceEur} />
                  : '—'}
              </dd>
            </div>
            <div className="col-span-2 text-xs text-muted-foreground">{t('scpi.subscriptionHint')}</div>
            <div>
              <dt className="text-muted-foreground">{t('scpi.dividendPolicy')}</dt>
              <dd>{t(position.dividendPolicy === 'REINVEST' ? 'scpi.reinvest' : 'scpi.cash')}</dd>
            </div>
            {position.jouissanceDate && (
              <div>
                <dt className="text-muted-foreground">{t('scpi.jouissanceDate')}</dt>
                <dd>{position.jouissanceDate}</dd>
              </div>
            )}
          </dl>
        )}

        {canEdit && editing && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="scpi-edit-company">{t('scpi.managementCompany')}</Label>
                <Input id="scpi-edit-company" value={managementCompany} onChange={(e) => setManagementCompany(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="scpi-edit-isin">{t('scpi.isin')}</Label>
                <Input id="scpi-edit-isin" value={isin} maxLength={12} onChange={(e) => setIsin(e.target.value)} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="scpi-edit-shares">{t('scpi.shareCount')}</Label>
                <NumericInput id="scpi-edit-shares" value={shareCount} onChange={(e) => setShareCount(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="scpi-edit-withdrawal">{t('scpi.withdrawalPrice')}</Label>
                <NumericInput id="scpi-edit-withdrawal" value={withdrawalPrice} onChange={(e) => setWithdrawalPrice(e.target.value)} />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="scpi-edit-subscription">{t('scpi.subscriptionPrice')}</Label>
              <NumericInput id="scpi-edit-subscription" value={subscriptionPrice} onChange={(e) => setSubscriptionPrice(e.target.value)} />
              <p className="text-xs text-muted-foreground">{t('scpi.subscriptionHint')}</p>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="scpi-edit-dividend">{t('scpi.dividendPolicy')}</Label>
                <select
                  id="scpi-edit-dividend"
                  className={selectControlClassName}
                  value={dividendPolicy}
                  onChange={(e) => setDividendPolicy(e.target.value as DividendPolicy)}
                >
                  <option value="CASH">{t('scpi.cash')}</option>
                  <option value="REINVEST">{t('scpi.reinvest')}</option>
                </select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="scpi-edit-jouissance">{t('scpi.jouissanceDate')}</Label>
                <DateInput id="scpi-edit-jouissance" value={jouissanceDate} onChange={setJouissanceDate} />
              </div>
            </div>
            {updatePosition.error && (
              <p className="text-sm text-destructive">{formatApiError(updatePosition.error, t, 'common.error')}</p>
            )}
            <div className="flex gap-2">
              <Button type="button" onClick={save} disabled={!canSave || updatePosition.isPending}>
                {t('scpi.save')}
              </Button>
              {position && (
                <Button type="button" variant="outline" onClick={() => setEditing(false)}>
                  {t('common.cancel')}
                </Button>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
