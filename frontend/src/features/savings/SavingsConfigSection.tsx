import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSetSavingsConfig, useDeleteSavingsConfig, useSavingsInterest } from './hooks'
import type { SavingsProduct, RateBasis, SavingsConfig } from '@/types/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Input } from '@/components/ui/input'
import { Info, Loader2 } from 'lucide-react'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { formatLocalDate } from '@/lib/utils'

const REGULATED: SavingsProduct[] = ['LIVRET_A', 'LDDS', 'LEP']

const DEFAULT_RATES: Record<SavingsProduct, number> = {
  LIVRET_A: 2.40,
  LDDS: 2.40,
  LEP: 3.50,
  COMMERCIAL: 2.00,
}

interface SavingsConfigSectionProps {
  accountId: number
  initialConfig?: SavingsConfig | null
  /** Detector suggestion for an unconfigured account — pre-fills the form. */
  suggestedProduct?: SavingsProduct
  suggestedRate?: number | null
}

export function SavingsConfigSection({
  accountId, initialConfig, suggestedProduct, suggestedRate,
}: SavingsConfigSectionProps) {
  const { t } = useTranslation()
  const setConfig = useSetSavingsConfig()
  const deleteConfig = useDeleteSavingsConfig()

  // A saved config wins; otherwise fall back to the detector suggestion, then a sane default.
  const initialProduct: SavingsProduct = initialConfig?.product ?? suggestedProduct ?? 'LIVRET_A'
  const [product, setProduct] = useState<SavingsProduct>(initialProduct)
  const [annualRate, setAnnualRate] = useState<string>(
    String(initialConfig?.annualRate ?? suggestedRate ?? DEFAULT_RATES[initialProduct])
  )
  const [rateBasis, setRateBasis] = useState<RateBasis>(initialConfig?.rateBasis ?? 'NET')
  const [taxRatePct, setTaxRatePct] = useState<string>(
    String(initialConfig?.taxRatePct ?? 30)
  )
  const [ceiling, setCeiling] = useState<string>(
    initialConfig?.ceiling != null ? String(initialConfig.ceiling) : ''
  )
  const [confirmDelete, setConfirmDelete] = useState(false)

  const isRegulated = REGULATED.includes(product)

  const handleProductChange = (newProduct: SavingsProduct) => {
    setProduct(newProduct)
    // Reset rate to sensible default when switching products
    setAnnualRate(String(DEFAULT_RATES[newProduct]))
    // Regulated products are always NET — lock the basis
    if (REGULATED.includes(newProduct)) setRateBasis('NET')
  }

  const hasSavedConfig = !!initialConfig

  const { data: interest, isLoading: isInterestLoading } = useSavingsInterest(
    accountId,
    hasSavedConfig
  )

  const handleSave = () => {
    const rate = parseFloat(annualRate)
    if (isNaN(rate) || rate <= 0) return
    setConfig.mutate({
      accountId,
      data: {
        product,
        annualRate: rate,
        rateBasis: isRegulated ? 'NET' : rateBasis,
        taxRatePct: !isRegulated && rateBasis === 'GROSS' ? (parseFloat(taxRatePct) || 30) : null,
        ceiling: ceiling ? parseFloat(ceiling) || null : null,
      },
    })
  }

  const handleDelete = () => {
    if (!confirmDelete) { setConfirmDelete(true); return }
    deleteConfig.mutate(accountId, { onSuccess: () => setConfirmDelete(false) })
  }

  const currentYear = new Date().getFullYear()

  return (
    <div className="space-y-4">
      {/* Config card */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('savings.configSection')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* Product */}
          <div className="space-y-1.5">
            <Label>{t('savings.product')}</Label>
            <select
              value={product}
              onChange={(e) => handleProductChange(e.target.value as SavingsProduct)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring sm:w-64"
            >
              {(['LIVRET_A', 'LDDS', 'LEP', 'COMMERCIAL'] as SavingsProduct[]).map(p => (
                <option key={p} value={p}>
                  {t(`savings.products.${p}`)}
                </option>
              ))}
            </select>
          </div>

          {/* Annual rate */}
          <div className="space-y-1.5">
            <Label>{t('savings.annualRate')}</Label>
            <Input
              type="number"
              step="0.01"
              min="0"
              max="20"
              value={annualRate}
              onChange={(e) => setAnnualRate(e.target.value)}
              className="w-full sm:w-32"
            />
          </div>

          {/* Gross/Net toggle — only for COMMERCIAL */}
          {!isRegulated && (
            <div className="space-y-1.5">
              <Label>{t('savings.rateBasis')}</Label>
              <div className="flex gap-2">
                {(['GROSS', 'NET'] as RateBasis[]).map(b => (
                  <button
                    key={b}
                    type="button"
                    onClick={() => setRateBasis(b)}
                    className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                      rateBasis === b
                        ? 'bg-primary text-primary-foreground'
                        : 'text-muted-foreground hover:bg-muted'
                    }`}
                  >
                    {t(`savings.${b.toLowerCase() as 'gross' | 'net'}`)}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Tax rate — only for COMMERCIAL + GROSS */}
          {!isRegulated && rateBasis === 'GROSS' && (
            <div className="space-y-1.5">
              <Label>
                {t('savings.taxRate')}
                <span className="ml-1.5 text-xs text-muted-foreground">{t('savings.taxRateHint')}</span>
              </Label>
              <Input
                type="number"
                step="0.1"
                min="0"
                max="100"
                value={taxRatePct}
                onChange={(e) => setTaxRatePct(e.target.value)}
                className="w-full sm:w-32"
              />
            </div>
          )}

          {/* Regulated net note */}
          {isRegulated && (
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Info size={12} />
              {t('savings.regulatedNet')}
            </p>
          )}

          {/* Ceiling — optional */}
          <div className="space-y-1.5">
            <Label>
              {t('savings.ceiling')}
              <span className="ml-1.5 text-xs text-muted-foreground">{t('savings.ceilingOptional')}</span>
            </Label>
            <Input
              type="number"
              step="1"
              min="0"
              value={ceiling}
              onChange={(e) => setCeiling(e.target.value)}
              className="w-full sm:w-48"
            />
          </div>

          {/* Actions */}
          <div className="flex flex-wrap gap-2 pt-2">
            <Button
              size="sm"
              onClick={handleSave}
              disabled={setConfig.isPending}
            >
              {setConfig.isPending && <Loader2 size={14} className="mr-1.5 animate-spin" />}
              {t('savings.saveConfig')}
            </Button>
            {hasSavedConfig && (
              <Button
                size="sm"
                variant={confirmDelete ? 'destructive' : 'outline'}
                onClick={handleDelete}
                disabled={deleteConfig.isPending}
              >
                {deleteConfig.isPending && <Loader2 size={14} className="mr-1.5 animate-spin" />}
                {confirmDelete ? t('savings.deleteConfigConfirm') : t('savings.deleteConfig')}
              </Button>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Projection card — only when there is a saved config */}
      {hasSavedConfig && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">
              {t('savings.projectionTitle', { year: currentYear })}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {isInterestLoading ? (
              <Loader2 className="animate-spin text-muted-foreground" size={20} />
            ) : interest ? (
              <div className="space-y-3">
                {/* Mobile-responsive grid */}
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <div>
                    <p className="text-xs text-muted-foreground">{t('savings.projectionYtd')}</p>
                    <CurrencyDisplay
                      value={interest.estimatedInterestYtd}
                      className="text-lg font-semibold text-emerald-600 dark:text-emerald-400"
                    />
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">{t('savings.projectionFullYear')}</p>
                    <CurrencyDisplay value={interest.projectedInterestFullYear} className="text-lg font-semibold" />
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">{t('savings.projectionNextCapitalization')}</p>
                    <p className="text-lg font-semibold">{formatLocalDate(interest.nextCapitalizationDate)}</p>
                  </div>
                </div>
                <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
                  <Info size={12} className="mt-0.5 shrink-0" />
                  {t('savings.projectionDisclaimer')}
                </p>
              </div>
            ) : null}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
