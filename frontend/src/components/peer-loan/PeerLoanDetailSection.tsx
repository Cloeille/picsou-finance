import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { PencilSimple, Plus, Trash } from '@phosphor-icons/react'
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
 * What deleting a row does depends on its cash leg: Picsou's own movement goes with it, a
 * linked transaction only loses its link and stays where it is, and a row with no cash leg
 * touches no cash account at all. A response without the flags keeps the original wording.
 */
function deleteDescriptionKey(repayment: PeerLoanRepayment | null): string {
  if (repayment?.hasCashLeg === false) return 'peerLoan.deleteRepayment.descriptionNoCashLeg'
  if (repayment?.hasCashLeg && repayment.cashLegOwned === false) {
    return 'peerLoan.deleteRepayment.descriptionLinked'
  }
  return 'peerLoan.deleteRepayment.description'
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
  const [deletingRepayment, setDeletingRepayment] = useState<PeerLoanRepayment | null>(null)
  const [closeOpen, setCloseOpen] = useState(false)

  if (isLoading) {
    return (
      <Card>
        <CardContent>
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

  async function handleDeleteRepayment() {
    if (!deletingRepayment) return
    try {
      await deleteRepayment.mutateAsync(deletingRepayment.id)
      toast.success(t('peerLoan.repaymentDeleted'))
      setDeletingRepayment(null)
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
  const showForecast = data.configured && (!data.closedAt || data.plannedMonthlyAmount != null || data.curve.length > 1)
  const periodLabel = data.lentAt
    ? data.expectedBack
      ? t('peerLoan.summary.period', {
        from: formatLocalDate(data.lentAt, locale),
        to: formatLocalDate(data.expectedBack, locale),
      })
      : formatLocalDate(data.lentAt, locale)
    : null

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <CardTitle>{t('peerLoan.summary.title')}</CardTitle>
            <Badge variant={data.closedAt ? 'secondary' : 'default'}>
              {data.closedAt ? t('peerLoan.summary.closed') : t('peerLoan.summary.open')}
            </Badge>
          </div>
          {periodLabel && (
            <span className="text-xs text-muted-foreground">{periodLabel}</span>
          )}
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
                <Progress value={pct} />
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  {(data.principalRepaid > 0 || data.writtenOff === 0) && (
                    <span>
                      {t('common.labelColon', { label: t('peerLoan.summary.principalRepaid') })}{' '}
                      <CurrencyDisplay value={data.principalRepaid} className="font-medium text-foreground" />
                    </span>
                  )}
                  {!data.closedAt && data.interestAccrued > 0 && (
                    <span>
                      {t('common.labelColon', { label: t('peerLoan.summary.interestDue') })}{' '}
                      <CurrencyDisplay value={data.interestAccrued} className="font-medium text-foreground" />
                    </span>
                  )}
                  {data.writtenOff > 0 && (
                    <span className="text-destructive">
                      {t('common.labelColon', { label: t('peerLoan.summary.writtenOff') })}{' '}
                      <CurrencyDisplay value={data.writtenOff} className="font-medium" />
                    </span>
                  )}
                  {data.annualRate != null && data.annualRate > 0 && (
                    <span>
                      {t(data.ratePeriod === 'MONTHLY'
                        ? 'peerLoan.fields.rateMonthly'
                        : 'peerLoan.fields.rateAnnual')}{' '}
                      <span className="font-medium text-foreground">
                        {formatPercent(
                          data.ratePeriod === 'MONTHLY' ? data.annualRate * 100 / 12 : data.annualRate * 100,
                          { fractionDigits: 2 },
                          locale,
                        )}
                      </span>
                    </span>
                  )}
                </div>
              </div>

              {!data.linkedAccountId && (
                <p className="rounded-lg border border-dashed border-border/80 px-3 py-2 text-sm text-muted-foreground">
                  {t('peerLoan.cashflowHint')}
                </p>
              )}

              {!data.closedAt ? (
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" onClick={() => { setEditingRepayment(null); setRepaymentOpen(true) }}>
                    <Plus className="size-3.5" aria-hidden="true" />
                    {t('peerLoan.addRepayment')}
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setTermsOpen(true)}>
                    <PencilSimple className="size-3.5" aria-hidden="true" />
                    {t('peerLoan.editTerms')}
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setCloseOpen(true)}>
                    {t('peerLoan.actions.close')}
                  </Button>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">{t('peerLoan.closedHint')}</p>
              )}
            </>
          )}
        </CardContent>
      </Card>

      {showForecast && (
        <Card>
          <CardHeader>
            <CardTitle>{t('peerLoan.forecast.title')}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {data.plannedMonthlyAmount != null && data.plannedMonths != null ? (
              <>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
                  <Stat
                    label={t('peerLoan.fields.plannedMonthly')}
                    value={<CurrencyDisplay value={data.plannedMonthlyAmount} />}
                  />
                  <Stat
                    label={t('peerLoan.forecast.scheduled')}
                    value={data.scheduledRemaining != null
                      ? <CurrencyDisplay value={data.scheduledRemaining} />
                      : '-'}
                  />
                  <Stat
                    label={t('peerLoan.forecast.actual')}
                    value={<CurrencyDisplay value={data.principalRemaining} />}
                  />
                  <Stat
                    label={t('peerLoan.forecast.plannedInterest')}
                    value={data.plannedInterest != null
                      ? <CurrencyDisplay value={data.plannedInterest} />
                      : '-'}
                  />
                  <Stat
                    label={t('peerLoan.summary.interestCalculated')}
                    value={<CurrencyDisplay value={data.interestAccrued + data.interestReceived} />}
                  />
                </div>
                <p className="text-sm text-muted-foreground">
                  {data.installmentsBehind == null || data.installmentsBehind === 0
                    ? t('peerLoan.forecast.onTrack')
                    : data.installmentsBehind > 0
                      ? t('peerLoan.forecast.behind', { count: data.installmentsBehind })
                      : t('peerLoan.forecast.ahead', { count: Math.abs(data.installmentsBehind) })}
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
        <CardHeader>
          <CardTitle>{t('peerLoan.repaymentsTitle')}</CardTitle>
        </CardHeader>
        <CardContent>
          {ledger.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('peerLoan.empty')}</p>
          ) : (
            <div className="divide-y divide-border/60">
              {ledger.map(repayment => {
                const noteText = repayment.kind === 'WRITE_OFF' && repayment.note === 'Closure write-off'
                  ? t('peerLoan.ledger.closureWriteOffNote')
                  : repayment.note
                const metaParts = [
                  noteText,
                  repayment.interestPart != null && repayment.interestPart > 0
                    ? t('peerLoan.ledger.interestPart', { amount: money.amount(repayment.interestPart) })
                    : null,
                ].filter(Boolean)

                return (
                  <div
                    key={repayment.id}
                    className="flex flex-col gap-3 py-3 first:pt-0 last:pb-0 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div className="min-w-0 flex-1 space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-medium text-foreground">
                          {formatLocalDate(repayment.paidOn, locale)}
                        </span>
                        <Badge variant={KIND_VARIANT[repayment.kind]}>
                          {t(`peerLoan.kinds.${repayment.kind}`)}
                        </Badge>
                        {repayment.hasCashLeg === false && (
                          <Badge variant="outline">{t('peerLoan.ledger.noCashLeg')}</Badge>
                        )}
                      </div>
                      {metaParts.length > 0 && (
                        <p className="truncate text-xs text-muted-foreground">
                          {metaParts.join(' · ')}
                        </p>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center justify-between gap-2 sm:justify-end">
                      <span
                        className={repayment.kind === 'WRITE_OFF'
                          ? 'text-sm font-semibold tabular-nums text-destructive'
                          : 'text-sm font-semibold tabular-nums text-emerald-600 dark:text-emerald-400'}
                      >
                        {repayment.kind === 'WRITE_OFF' ? '−' : '+'}
                        <CurrencyDisplay value={repayment.amount} />
                      </span>
                      <div className="flex items-center gap-1.5">
                        {!data.closedAt && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => { setEditingRepayment(repayment); setRepaymentOpen(true) }}
                          >
                            <PencilSimple className="size-3.5" aria-hidden="true" />
                            {t('common.edit')}
                          </Button>
                        )}
                        {/* Deletion stays available after closure: a loan auto-closed by its final
                            repayment has no write-off, and the member must still be able to fix it. */}
                        <Button
                          size="sm"
                          variant="outline"
                          className="text-destructive hover:border-destructive/40 hover:bg-destructive/10 hover:text-destructive"
                          onClick={() => setDeletingRepayment(repayment)}
                        >
                          <Trash className="size-3.5" aria-hidden="true" />
                          {t('common.delete')}
                        </Button>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
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
        remainingDue={data.principalRemaining + data.interestAccrued}
        onSubmit={handleRepayment}
        loading={addRepayment.isPending || updateRepayment.isPending}
      />

      <ConfirmDialog
        open={deletingRepayment != null}
        onOpenChange={(open) => { if (!open) setDeletingRepayment(null) }}
        title={t('peerLoan.deleteRepayment.title')}
        description={t(deleteDescriptionKey(deletingRepayment), {
          amount: money.amount(deletingRepayment?.amount ?? 0),
        })}
        confirmLabel={t('peerLoan.deleteRepayment.confirm')}
        onConfirm={handleDeleteRepayment}
        loading={deleteRepayment.isPending}
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
    <div className="rounded-lg border border-border/60 p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-base font-semibold tabular-nums text-foreground">{value}</p>
    </div>
  )
}
