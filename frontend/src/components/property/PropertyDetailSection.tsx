import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pencil } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PropertyMetadataForm } from './PropertyMetadataForm'
import { PropertyValuationCard } from './PropertyValuationCard'
import { PropertyValuationChart } from './PropertyValuationChart'
import { PropertyFinancingCard } from './PropertyFinancingCard'
import { OwnershipEditor } from './OwnershipEditor'
import { useRealEstateSummary } from '@/features/accounts/hooks'
import type { Account } from '@/types/api'

interface PropertyDetailSectionProps {
  account: Account
}

/**
 * Everything specific to a property, mounted from the account detail page.
 *
 * <p>Mirrors the shape of the loan slice: a set of focused cards rather than one page-level
 * component, so each can be read and changed on its own.
 */
export function PropertyDetailSection({ account }: PropertyDetailSectionProps) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState(false)
  const { data: summary } = useRealEstateSummary()

  const metadata = account.realEstate
  const line = summary?.properties.find(p => p.accountId === account.id)

  // sharePercent is only sent when the member owns less than all of it, so its presence is
  // exactly the "someone else has a say here" signal. Ownership is a separate question:
  // a co-owner reads the property but may not edit it, so write actions key off isOwner.
  const isCoOwned = account.sharePercent != null
  const isOwner = account.isOwner !== false

  if (!metadata && !isOwner) {
    // A co-owner landing on a property whose owner has not filled in the details yet has
    // nothing to see and nothing they are allowed to do about it.
    return null
  }

  if (!metadata || editing) {
    return (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">
            {metadata ? t('property.form.editTitle') : t('property.form.createTitle')}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {!metadata && (
            <p className="mb-4 text-sm text-muted-foreground">{t('property.form.intro')}</p>
          )}
          {/* Keyed on the metadata identity so reopening the form remounts it with fresh
              defaults, rather than syncing state in an effect. */}
          <PropertyMetadataForm
            key={metadata ? `edit-${account.id}` : `create-${account.id}`}
            accountId={account.id}
            metadata={metadata}
            onSaved={() => setEditing(false)}
          />
          {metadata && (
            <div className="mt-3 flex justify-end">
              <Button variant="ghost" size="sm" onClick={() => setEditing(false)}>
                {t('common.cancel')}
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    )
  }

  const gain = account.currentBalanceEur - metadata.costBasis
  const gainPct = metadata.costBasis > 0 ? (gain / metadata.costBasis) * 100 : null

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2 pb-3">
          <CardTitle className="text-base">{t('property.details.title')}</CardTitle>
          {isOwner && (
            <Button size="sm" variant="outline" onClick={() => setEditing(true)}>
              <Pencil className="mr-2 size-4" />
              {t('common.edit')}
            </Button>
          )}
        </CardHeader>
        <CardContent className="space-y-4">
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {metadata.propertyType && (
              <Fact label={t('property.form.propertyType')}
                    value={t(`property.kind.${metadata.propertyType}`, { defaultValue: metadata.propertyType })} />
            )}
            {metadata.category && (
              <Fact label={t('property.form.category')} value={t(`property.category.${metadata.category}`)} />
            )}
            {metadata.surfaceArea != null && (
              <Fact label={t('property.form.surfaceArea')} value={`${metadata.surfaceArea} m²`} />
            )}
            {metadata.rooms != null && (
              <Fact label={t('property.form.rooms')} value={String(metadata.rooms)} />
            )}
            {metadata.constructionYear != null && (
              <Fact label={t('property.form.constructionYear')} value={String(metadata.constructionYear)} />
            )}
            {metadata.energyClass && (
              <Fact label={t('property.form.energyClass')} value={metadata.energyClass} />
            )}
          </dl>

          {metadata.address && (
            <p className="text-sm text-muted-foreground">
              {[metadata.address, metadata.postalCode, metadata.city].filter(Boolean).join(', ')}
            </p>
          )}

          <div className="grid grid-cols-2 gap-3 border-t pt-3 sm:grid-cols-3">
            <Fact label={t('property.details.costBasis')}
                  value={<CurrencyDisplay value={metadata.costBasis} />} />
            <Fact label={t('property.details.currentValue')}
                  value={<CurrencyDisplay value={account.currentBalanceEur} />} />
            <Fact
              label={t('property.details.gain')}
              value={
                <span className={gain >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'}>
                  <CurrencyDisplay value={gain} showSign />
                  {gainPct != null && <span className="ml-1 text-xs">({gainPct.toFixed(1)} %)</span>}
                </span>
              }
            />
          </div>

          {isCoOwned && (
            <p className="text-xs text-muted-foreground">
              {t('property.details.coOwnedNote', { percent: Number(account.sharePercent).toFixed(1) })}
            </p>
          )}
        </CardContent>
      </Card>

      {/* Re-valuing writes the account balance, so it is an owner-only action. */}
      {isOwner && (
        <PropertyValuationCard
          accountId={account.id}
          metadata={metadata}
          currentValue={account.currentBalanceEur}
        />
      )}

      {line && <PropertyFinancingCard line={line} />}

      <PropertyValuationChart accountId={account.id} costBasis={metadata.costBasis} />

      <OwnershipEditor accountId={account.id} canEdit={isOwner} />
    </div>
  )
}

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium tabular-nums">{value}</dd>
    </div>
  )
}
