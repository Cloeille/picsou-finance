import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  useAccount, useAccountHistory, useHoldingsWithLivePrices, useAccountPositions,
  useAccountTransactions, useAddTransaction, useDeleteTransaction,
  useUpdateTransaction, useUpdateHolding, useDeleteHolding
} from '@/features/accounts/hooks'
import { useHistory } from '@/features/history/hooks'
import { BalanceHistoryChart } from '@/components/shared/BalanceHistoryChart'
import { NetWorthChart } from '@/components/shared/NetWorthChart'
import { HoldingsTable } from '@/components/shared/HoldingsTable'
import { PositionsByProduct } from '@/components/shared/PositionsByProduct'
import { RealizedPnlSection } from '@/components/shared/RealizedPnlSection'
import { TransactionsList } from '@/components/shared/TransactionsList'
import { AddTransactionModal } from '@/components/shared/AddTransactionModal'
import { ImportTransactionsModal } from '@/components/shared/ImportTransactionsModal'
import { EditHoldingModal } from '@/components/shared/EditHoldingModal'
import { MonthEndBalanceModal } from '@/components/shared/MonthEndBalanceModal'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { PageHeader } from '@/components/shared/PageHeader'
import { LoanDetailSection } from '@/components/loan/LoanDetailSection'
import { PropertyDetailSection } from '@/components/property/PropertyDetailSection'
import { ScpiDetailSection } from '@/components/scpi/ScpiDetailSection'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ArrowLeft, Calendar, TrendingUp, TrendingDown, Upload } from 'lucide-react'
import { formatLocalDate } from '@/lib/utils'
import { accountTypeLabelKey } from '@/lib/constants'
import { type TimeRange } from '@/components/shared/TimeRangeSelector'
import type { HoldingResponse, Transaction } from '@/types/api'

const HOLDING_ACCOUNT_TYPES = ['PEA', 'COMPTE_TITRES', 'CRYPTO', 'EMPLOYEE_SAVINGS']

export function AccountDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const accountId = parseInt(id!, 10)

  const { data: account, isLoading } = useAccount(accountId)
  const { data: history } = useAccountHistory(accountId)
  const { data: holdings } = useHoldingsWithLivePrices(accountId)
  const { data: positions } = useAccountPositions(accountId)
  const { data: transactions } = useAccountTransactions(accountId)
  const addTxMutation = useAddTransaction(accountId)
  const deleteTxMutation = useDeleteTransaction(accountId)
  const updateTxMutation = useUpdateTransaction(accountId)
  const updateHoldingMutation = useUpdateHolding(accountId)
  const deleteHoldingMutation = useDeleteHolding(accountId)
  const { data: pnlData } = useHistory(accountId ? [accountId] : [], 12)

  const [showHistory, setShowHistory] = useState(false)
  const [showAddTx, setShowAddTx] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [editingTx, setEditingTx] = useState<Transaction | null>(null)
  const [editingHolding, setEditingHolding] = useState<HoldingResponse | null>(null)
  const [range, setRange] = useState<TimeRange>('1Y')

  if (!account && !isLoading) return null

  const chartData = (history ?? []).map(s => ({ date: s.date, balance: s.balance }))
  const isLoan = account?.type === 'LOAN'
  const isRealEstate = account?.type === 'REAL_ESTATE'
  const isScpi = account?.type === 'SCPI'
  const showHoldings = account ? HOLDING_ACCOUNT_TYPES.includes(account.type) : false
  const recentSnapshots = [...(history ?? [])].reverse().slice(0, 10)

  // The backend owns EUR valuation and falls back atomically to the broker snapshot.
  const displayBalance = account?.currentBalanceEur ?? 0

  // PnL from unified history endpoint (pre-computed by backend)
  const pnlLatest = pnlData && pnlData.length > 0 ? pnlData[pnlData.length - 1] : null
  const pnl = pnlLatest && pnlLatest.invested > 0 ? pnlLatest.pnl : null
  const pnlPct = pnlLatest && pnlLatest.invested > 0
    ? ((pnlLatest.pnl / pnlLatest.invested) * 100).toFixed(1) : null
  const pnlPositive = pnl !== null && pnl >= 0

  return (
    <div className="space-y-4">
      <PageHeader
        surtitle={
          account
            ? `${t(accountTypeLabelKey(account.type))}${account.provider ? ` · ${account.provider}` : ''}`
            : undefined
        }
        title={account?.name ?? ''}
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate('/accounts')}
            >
              <ArrowLeft size={14} className="mr-1.5" />
              {t('common.back')}
            </Button>
            <Button
              size="sm"
              onClick={() => setShowHistory(true)}
            >
              <Calendar size={14} className="mr-1.5" />
              {t('accounts.snapshots')}
            </Button>
          </div>
        }
      />

      {/* Balance card */}
      {isLoading && !account ? (
        <Card>
          <CardContent className="pt-6">
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-5 w-32" />
          </CardContent>
        </Card>
      ) : account ? (
        <Card>
          <CardHeader>
            <CardTitle>
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full shrink-0" style={{ backgroundColor: account.color }} />
                {account.name}
                <AccountTypeBadge type={account.type} />
              </div>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-xs text-muted-foreground mb-1">{t('accounts.currentBalance')}</p>
            <CurrencyDisplay
              value={displayBalance}
              className={`text-3xl font-bold ${isLoan ? 'text-red-500' : 'text-foreground'}`}
            />
            {showHoldings && account.cashBalance != null && (
              <p className="mt-1 text-xs text-muted-foreground">
                {t('accounts.cashBalance')}: <CurrencyDisplay value={account.cashBalance} />
              </p>
            )}
            {account.currency !== 'EUR' && (
              <p className="text-xs text-muted-foreground mt-0.5">
                {account.currentBalance} {account.currency}
                {account.ticker ? ` (${account.ticker})` : ''}
              </p>
            )}
            {showHoldings && pnl !== null && (
              <div className="mt-3 flex items-center gap-2">
                {pnlPositive
                  ? <TrendingUp className="text-emerald-500" size={16} />
                  : <TrendingDown className="text-red-500" size={16} />}
                <span className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`}>
                  <CurrencyDisplay value={pnl} />
                  {pnlPct !== null && (
                    <span className="ml-1 font-normal text-muted-foreground">
                      ({pnlPositive ? '+' : ''}{pnlPct}%)
                    </span>
                  )}
                </span>
                <span className="text-sm text-muted-foreground">{t('dashboard.netWorthChange')}</span>
              </div>
            )}
          </CardContent>
        </Card>
      ) : null}

      {/* Loan detail */}
      {isLoan && account && <LoanDetailSection accountId={account.id} />}

      {/* Property detail: description, valuation, financing and ownership split */}
      {isRealEstate && account && <PropertyDetailSection account={account} />}

      {isScpi && account && <ScpiDetailSection account={account} />}

      {/* History chart */}
      {!isLoan && showHoldings && pnlData && pnlData.length > 1 ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('dashboard.gainLoss')}</CardTitle>
          </CardHeader>
          <CardContent>
            <NetWorthChart data={pnlData} range={range} onRangeChange={setRange} />
          </CardContent>
        </Card>
      ) : !isLoan && chartData.length > 1 ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('accounts.history')}</CardTitle>
          </CardHeader>
          <CardContent>
            <BalanceHistoryChart data={chartData} />
          </CardContent>
        </Card>
      ) : null}

      {/* Holdings — grouped by product when the connector reports one (crypto exchanges),
          otherwise the flat table. */}
      {showHoldings && (
        holdings ? (
          positions && positions.length > 0 ? (
            <PositionsByProduct positions={positions} />
          ) : (
            <HoldingsTable
              holdings={holdings}
              onEdit={setEditingHolding}
              onDelete={(h) => deleteHoldingMutation.mutate(h.ticker)}
            />
          )
        ) : (
          <Card>
            <CardContent className="pt-6">
              <Skeleton className="h-32 w-full" />
            </CardContent>
          </Card>
        )
      )}

      {/* Realized P&L on closed positions (investment accounts only) */}
      {showHoldings && <RealizedPnlSection accountId={accountId} enabled={showHoldings} />}

      {/* Transactions */}
      {!isLoan && (transactions ? (
        <>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-base font-semibold">{t('accounts.transactions')}</h3>
            <div className="flex items-center gap-2">
              {showHoldings && (
                <Button size="sm" variant="outline" onClick={() => setShowImport(true)}>
                  <Upload className="mr-1.5 size-4" />
                  {t('import.importCsv')}
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => setShowAddTx(true)}>
                + {t('common.add')}
              </Button>
            </div>
          </div>
          <TransactionsList
            transactions={transactions}
            onDelete={(txId) => deleteTxMutation.mutate(txId)}
            onEdit={(tx) => setEditingTx(tx)}
          />
        </>
      ) : (
        <Card>
          <CardContent className="pt-6">
            <Skeleton className="h-32 w-full" />
          </CardContent>
        </Card>
      ))}

      {/* Snapshot list */}
      {!isLoan && recentSnapshots.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('accounts.snapshots')}</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {recentSnapshots.map(snap => (
              <div
                key={snap.id}
                className="flex items-center justify-between px-6 py-3 border-b last:border-0"
              >
                <span className="text-sm text-muted-foreground">
                  {formatLocalDate(snap.date)}
                </span>
                <CurrencyDisplay value={snap.balance} className="text-sm font-semibold" />
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* Add Transaction modal */}
      {account && (
        <AddTransactionModal
          open={showAddTx}
          onOpenChange={setShowAddTx}
          accountId={account.id}
          accountType={account.type}
          onSubmit={async (data) => { await addTxMutation.mutateAsync(data) }}
          isLoading={addTxMutation.isPending}
        />
      )}

      {/* Import CSV modal (investment accounts) */}
      {account && showHoldings && (
        <ImportTransactionsModal
          open={showImport}
          onOpenChange={setShowImport}
          accountId={account.id}
        />
      )}

      {/* Edit Transaction modal */}
      {account && editingTx && (
        <AddTransactionModal
          open={!!editingTx}
          onOpenChange={(open) => { if (!open) setEditingTx(null) }}
          accountId={account.id}
          accountType={account.type}
          initialValues={{
            id: editingTx.id,
            date: editingTx.date,
            description: editingTx.description,
            amount: editingTx.amount,
            txType: editingTx.txType,
            ticker: editingTx.ticker ?? undefined,
            name: editingTx.name ?? undefined,
            quantity: editingTx.quantity ?? undefined,
            pricePerUnit: editingTx.pricePerUnit ?? undefined,
            currency: editingTx.nativeCurrency,
          }}
          onSubmit={async (data) => {
            await updateTxMutation.mutateAsync({ txId: editingTx.id, data })
            setEditingTx(null)
          }}
          isLoading={updateTxMutation.isPending}
        />
      )}

      {/* Edit Holding modal */}
      <EditHoldingModal
        open={!!editingHolding}
        onOpenChange={(open) => { if (!open) setEditingHolding(null) }}
        holding={editingHolding}
        // Synced accounts (on-chain wallets, exchanges) own the quantity from
        // the chain/exchange, so only the cost basis is editable there.
        quantityReadOnly={account ? !account.isManual : false}
        onSubmit={async (ticker, quantity, averageBuyIn) => {
          await updateHoldingMutation.mutateAsync({ ticker, data: { quantity, averageBuyIn } })
          setEditingHolding(null)
        }}
        isLoading={updateHoldingMutation.isPending}
      />

      {/* Monthly history dialog */}
      <MonthEndBalanceModal
        open={showHistory}
        onClose={() => setShowHistory(false)}
        accountId={accountId}
        history={history}
      />
    </div>
  )
}
