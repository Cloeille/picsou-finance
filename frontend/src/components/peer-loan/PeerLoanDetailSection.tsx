import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { useMoney } from '@/hooks/use-money'
import { useAccounts } from '@/features/accounts/hooks'
import {
  useAddPeerLoanRepayment,
  useClosePeerLoan,
  useDeletePeerLoanRepayment,
  usePeerLoan,
  useSavePeerLoanTerms,
  useUpdatePeerLoanRepayment,
} from '@/features/peer-loans/hooks'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { extractErrorMessage } from '@/lib/errors'
import { formatLocalDate, formatPercent, localeFromLanguage } from '@/lib/utils'
import { CASH_ACCOUNT_TYPES } from '@/lib/constants'
import { PeerLoanTermsDialog } from './PeerLoanTermsDialog'
import { PeerLoanRepaymentDialog } from './PeerLoanRepaymentDialog'
import { PeerLoanForecastChart } from './PeerLoanForecastChart'
import type {
  PeerLoanRepayment,
  PeerLoanRepaymentKind,
  PeerLoanRepaymentRequest,
  PeerLoanTermsRequest,
} from '@/types/api'

interface PeerLoanDetailSectionProps {
  accountId: number
}

const KIND_VARIANT: Record<PeerLoanRepaymentKind, 'default' | 'secondary' | 'destructive'> = {
  PRINCIPAL: 'default',
  INTEREST: 'secondary',
  WRITE_OFF: 'destructive',
}

/**
 * The peer-loan detail view: the nominal still owed, the dated ledger, the optional
 * forecast, and the borrower's factual history. No risk score anywhere — the numbers
 * are events, not predictions.
 */
export function PeerLoanDetailSection({ accountId }: PeerLoanDetailSectionProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const money = useMoney()

  const { data, isLoading, isError } = usePeerLoan(accountId)
  const { data: accounts } = useAccounts()
  const saveTerms = useSavePeerLoanTerms(accountId)
  const addRepayment = useAddPeerLoanRepayment(accountId)
  const updateRepayment = useUpdatePeerLoanRepayment(accountId)
  const deleteRepayment = useDeletePeerLoanRepayment(accountId)
  const closeLoan = useClosePeerLoan(accountId)

  const [termsOpen, setTermsOpen] = useState(false)
  const [repaymentOpen, setRepaymentOpen] = useState(false)
  const [editingRepayment, setEditingRepayment] = useState<PeerLoanRepayment | null>(null)
  const [closeOpen, setCloseOpen] = useState(false)

  if (isLoading) {
    return (
      <Card>
        <CardContent className="pt-6">
          <Skeleton className="h-32 w-full" />
        </CardContent>
      </Card>
    )
  }
  if (isError || !data) return null

  const failure = (err: unknown) => toast.error(extractErrorMessage(err, t('common.error')))

  // The linked account is the cash counterpart of the asset swap. Offering only
  // spendable accounts keeps a loan from being pointed at a holding or another loan.
  const cashAccounts = (accounts ?? []).filter(account =>
    account.id !== accountId && CASH_ACCOUNT_TYPES.includes(account.type),
  )

  async function handleSaveTerms(values: PeerLoanTermsRequest) {
    try {
      await saveTerms.mutateAsync(values)
      toast.success(t('peerLoan.saved'))
    } catch (err) {
      failure(err)
      throw err
    }
  }

  async function handleRepayment(values: PeerLoanRepaymentRequest) {
    try {
      if (editingRepayment) {
        await updateRepayment.mutateAsync({ repaymentId: editingRepayment.id, data: values })
      } else {
        await addRepayment.mutateAsync(values)
      }
      toast.success(t('peerLoan.repaymentSaved'))
      setEditingRepayment(null)
    } catch (err) {
      failure(err)
      throw err
    }
  }

  async function handleDeleteRepayment(repayment: PeerLoanRepayment) {
    try {
      await deleteRepayment.mutateAsync(repayment.id)
      toast.success(t('peerLoan.repaymentDeleted'))
    } catch (err) {
      failure(err)
    }
  }

  async function handleClose() {
    try {
      await closeLoan.mutateAsync(undefined)
      toast.success(t('peerLoan.closed'))
      setCloseOpen(false)
    } catch (err) {
      failure(err)
    }
  }

  const pct = Math.max(0, Math.min(100, data.repaidPct))
  const ledger = [...data.repayments].reverse()

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex flex-wrap items-center gap-2 text-base">
            <span>{data.accountName}</span>
            <Badge variant={data.closedAt ? 'secondary' : 'default'}>
              {data.closedAt ? t('peerLoan.summary.closed') : t('peerLoan.summary.open')}
            </Badge>
            {data.lentAt && (
              <span className="text-xs font-normal text-muted-foreground">
                {formatLocalDate(data.lentAt)}
                {data.expectedBack ? ` → ${formatLocalDate(data.expectedBack)}` : ''}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {!data.configured ? (
            <div className="space-y-3">
              <p className="text-sm text-muted-foreground">{t('peerLoan.setupHint')}</p>
              <Button size="sm" onClick={() => setTermsOpen(true)}>{t('peerLoan.configure')}</Button>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <Stat label={t('peerLoan.summary.lent')} value={<CurrencyDisplay value={data.principal} />} />
                <Stat label={t('peerLoan.summary.remaining')} value={<CurrencyDisplay value={data.principalRemaining} />} />
                <Stat
                  label={t('peerLoan.summary.repaid')}
                  value={formatPercent(pct, { fractionDigits: 1 }, locale)}
                />
                <Stat
                  label={t('peerLoan.summary.interestCalculated')}
                  value={<CurrencyDisplay value={data.interestAccrued + data.interestReceived} />}
                />
              </div>

              <div className="space-y-2">
                <Progress value={pct} className="h-2" />
                <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  <span className="text-emerald-500">
                    {t('peerLoan.summary.principalRepaid')} <CurrencyDisplay value={data.principalRepaid} />
                  </span>
                  {!data.closedAt && data.interestAccrued > 0 && (
                    <span className="text-foreground">
                      {t('peerLoan.summary.interestDue')} <CurrencyDisplay value={data.interestAccrued} />
                    </span>
                  )}
                  {data.writtenOff > 0 && (
                    <span className="text-red-500">
                      {t('peerLoan.summary.writtenOff')} <CurrencyDisplay value={data.writtenOff} />
                    </span>
                  )}
                  {data.annualRate != null && data.annualRate > 0 && (
                    <span>
                      {t(data.ratePeriod === 'MONTHLY'
                        ? 'peerLoan.fields.rateMonthly'
                        : 'peerLoan.fields.rateAnnual')}{' '}
                      {formatPercent(
                        data.ratePeriod === 'MONTHLY' ? data.annualRate * 100 / 12 : data.annualRate * 100,
                        { fractionDigits: 2 },
                        locale,
                      )}
                    </span>
                  )}
                </div>
              </div>

              {!data.linkedAccountId && (
                <p className="rounded-md border border-dashed p-2 text-xs text-muted-foreground">
                  {t('peerLoan.cashflowHint')}
                </p>
              )}

              <div className="flex flex-wrap gap-2">
                {!data.closedAt && (
                  <>
                    <Button size="sm" onClick={() => { setEditingRepayment(null); setRepaymentOpen(true) }}>
                      + {t('peerLoan.addRepayment')}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setTermsOpen(true)}>
                      {t('peerLoan.editTerms')}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setCloseOpen(true)}>
                      {t('peerLoan.actions.close')}
                    </Button>
                  </>
                )}
                {data.closedAt && (
                  <p className="text-xs text-muted-foreground">{t('peerLoan.closedHint')}</p>
                )}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      {data.configured && (
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">{t('peerLoan.forecast.title')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {data.plannedMonthlyAmount != null && data.plannedMonths != null ? (
              <>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  <Stat
                    label={t('peerLoan.fields.plannedMonthly')}
                    value={<CurrencyDisplay value={data.plannedMonthlyAmount} />}
                  />
                  <Stat
                    label={t('peerLoan.forecast.scheduled')}
                    value={data.scheduledRemaining != null
                      ? <CurrencyDisplay value={data.scheduledRemaining} />
                      : '—'}
                  />
                  <Stat
                    label={t('peerLoan.forecast.actual')}
                    value={<CurrencyDisplay value={data.principalRemaining} />}
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <Stat
                    label={t('peerLoan.forecast.plannedInterest')}
                    value={data.plannedInterest != null
                      ? <CurrencyDisplay value={data.plannedInterest} />
                      : '—'}
                  />
                  <Stat
                    label={t('peerLoan.summary.interestCalculated')}
                    value={<CurrencyDisplay value={data.interestAccrued + data.interestReceived} />}
                  />
                </div>
                <p className="text-xs text-muted-foreground">
                  {data.installmentsBehind == null || data.installmentsBehind === 0
                    ? t('peerLoan.forecast.onTrack')
                    : data.installmentsBehind > 0
                      ? t('peerLoan.forecast.behind', { count: data.installmentsBehind })
                      : t('peerLoan.forecast.ahead', { count: Math.abs(data.installmentsBehind) })}
                  {' · '}
                  {t('peerLoan.forecast.disclaimer')}
                </p>
              </>
            ) : (
              <p className="text-sm text-muted-foreground">{t('peerLoan.noPlan')}</p>
            )}
            {data.curve.length > 1 && (
              <PeerLoanForecastChart
                data={data.curve}
                hasPlan={data.plannedMonthlyAmount != null}
              />
            )}
          </CardContent>
        </Card>
      )}

      {data.configured && (
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">{t('peerLoan.repaymentsTitle')}</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          {ledger.length === 0 ? (
            <p className="px-4 py-6 text-sm text-muted-foreground">{t('peerLoan.empty')}</p>
          ) : (
            ledger.map(repayment => (
              <div
                key={repayment.id}
                className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3 last:border-0"
              >
                <div className="flex min-w-0 flex-wrap items-center gap-2">
                  <span className="text-sm text-muted-foreground">{formatLocalDate(repayment.paidOn)}</span>
                  <Badge variant={KIND_VARIANT[repayment.kind]}>{t(`peerLoan.kinds.${repayment.kind}`)}</Badge>
                  {repayment.note && <span className="truncate text-xs text-muted-foreground">{repayment.note}</span>}
                  {repayment.interestPart != null && repayment.interestPart > 0 && (
                    <span className="text-xs text-muted-foreground">
                      {t('peerLoan.ledger.interestPart', { amount: money.amount(repayment.interestPart) })}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <span className={repayment.kind === 'WRITE_OFF' ? 'text-red-500' : 'text-emerald-500'}>
                    {repayment.kind === 'WRITE_OFF' ? '−' : '+'}
                    <CurrencyDisplay value={repayment.amount} className="text-sm font-semibold" />
                  </span>
                  {!data.closedAt && (
                    <>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => { setEditingRepayment(repayment); setRepaymentOpen(true) }}
                      >
                        {t('common.edit')}
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => handleDeleteRepayment(repayment)}>
                        {t('common.delete')}
                      </Button>
                    </>
                  )}
                </div>
              </div>
            ))
          )}
        </CardContent>
      </Card>
      )}

      <PeerLoanTermsDialog
        open={termsOpen}
        onOpenChange={setTermsOpen}
        initial={data}
        cashAccounts={cashAccounts}
        onSubmit={handleSaveTerms}
        loading={saveTerms.isPending}
      />

      <PeerLoanRepaymentDialog
        open={repaymentOpen}
        onOpenChange={(open) => {
          setRepaymentOpen(open)
          if (!open) setEditingRepayment(null)
        }}
        initial={editingRepayment}
        accruedInterest={data.interestAccrued}
        onSubmit={handleRepayment}
        loading={addRepayment.isPending || updateRepayment.isPending}
      />

      <ConfirmDialog
        open={closeOpen}
        onOpenChange={setCloseOpen}
        title={t('peerLoan.close.title')}
        description={t('peerLoan.close.description', {
          amount: money.amount(data.principalRemaining),
        })}
        confirmLabel={t('peerLoan.close.confirm')}
        onConfirm={handleClose}
        loading={closeLoan.isPending}
      />
    </div>
  )
}

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className="text-base font-semibold tabular-nums">{value}</span>
    </div>
  )
}
