import { useState, useRef } from 'react'
import { toast } from 'sonner'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  useAccount, useAccountHistory, useHoldingsWithLivePrices, useAccountPositions,
  useAccountTransactions, useAddTransaction, useDeleteTransaction,
  useUpdateTransaction, useUpdateHolding, useDeleteHolding, useImportTRTransactions
} from '@/features/accounts/hooks'
import { useCategories, useCategorize } from '@/features/budget/hooks'
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
import { SavingsConfigSection } from '@/features/savings/SavingsConfigSection'
import { useSavingsSuggestions } from '@/features/savings/hooks'
import { PropertyDetailSection } from '@/components/property/PropertyDetailSection'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ArrowLeft, Calendar, TrendingUp, TrendingDown, Upload } from 'lucide-react'
import { formatLocalDate } from '@/lib/utils'
import { accountTypeLabelKey, HOLDING_ACCOUNT_TYPES } from '@/lib/constants'
import { type TimeRange } from '@/components/shared/TimeRangeSelector'
import type { HoldingResponse, Transaction } from '@/types/api'


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
  const deleteHoldingMutation = useDeleteHolding(accountId)
  const updateHoldingMutation = useUpdateHolding(accountId)
  const importTRMutation = useImportTRTransactions(accountId)
  const { data: pnlData } = useHistory(accountId ? [accountId] : [], 12)
  const { data: savingsSuggestions } = useSavingsSuggestions()
  const { data: categories } = useCategories()
  const categorizeMutation = useCategorize()
  const qc = useQueryClient()

  const [showHistory, setShowHistory] = useState(false)
  const [showAddTx, setShowAddTx] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [editingTx, setEditingTx] = useState<Transaction | null>(null)
  const [editingHolding, setEditingHolding] = useState<HoldingResponse | null>(null)
  const [range, setRange] = useState<TimeRange>('1Y')
  const fileInputRef = useRef<HTMLInputElement>(null)

  function handleFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (file) {
      importTRMutation.mutate(file, {
        onSuccess: (result) => {
          if (result && result.inserted > 0) {
            toast.success(
              result.skipped > 0
                ? t('accounts.importSuccessWithSkipped', { count: result.inserted, skipped: result.skipped })
                : t('accounts.importSuccess', { count: result.inserted }),
            )
          } else {
            toast.info(t('accounts.importAlreadyDone'))
          }
        },
        onError: (err) => {
          toast.error(t('accounts.importError', { message: err instanceof Error ? err.message : 'Unknown error' }))
        },
      })
    }
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  function handleCategorize(txId: number, categoryId: number) {
    categorizeMutation.mutate(
      { id: txId, data: { categoryId, createRule: false } },
      {
        // The hook's own onSuccess already invalidates ['budget'] and ['dashboard'].
        // Additionally refresh the account transaction list so the new category shows.
        onSuccess: () => qc.invalidateQueries({ queryKey: ['accounts', accountId, 'transactions'] }),
      },
    )
  }

  if (!account && !isLoading) return null

  const chartData = (history ?? []).map(s => ({ date: s.date, balance: s.balance }))
  const isLoan = account?.type === 'LOAN'
  // A freshly bank-synced livret is typed CHECKING until configured, so also surface the
  // section when it already has a config or the detector flagged it as a savings candidate.
  const savingsSuggestion = Array.isArray(savingsSuggestions)
    ? savingsSuggestions.find(s => s.accountId === account?.id)
    : undefined
  const isSavings = account
    ? (account.type === 'SAVINGS' || account.type === 'LEP' || !!account.savingsConfig || !!savingsSuggestion)
    : false
  const isRealEstate = account?.type === 'REAL_ESTATE'
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

  // History + holdings + transactions + snapshots. Rendered flat for most accounts,
  // or inside the "Aperçu" tab for savings accounts (config lives in its own tab).
  const overviewSections = (
    <>
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

      {/* Holdings */}
      {showHoldings && (
        holdings ? (
          <HoldingsTable
            holdings={holdings}
            onEdit={setEditingHolding}
            onDelete={(h) => deleteHoldingMutation.mutate(h.ticker)}
          />
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
              <input
                type="file"
                accept=".csv"
                className="hidden"
                ref={fileInputRef}
                onChange={handleFileChange}
              />
              {account?.provider === 'Trade Republic' && account?.type === 'CHECKING' && (
                <Button size="sm" variant="outline" onClick={() => fileInputRef.current?.click()} disabled={importTRMutation.isPending}>
                  {importTRMutation.isPending ? t('common.loading') : t('accounts.importCsvTR')}
                </Button>
              )}
              {showHoldings && (
                <Button size="sm" variant="outline" onClick={() => setShowImport(true)}>
                  <Upload className="mr-1.5 size-4" />
                  {t('import.importCsv')}
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => setShowAddTx(true)}>
                + Ajouter
              </Button>
            </div>
          </div>
          <TransactionsList
            transactions={transactions}
            onDelete={(txId) => deleteTxMutation.mutate(txId)}
            onEdit={(tx) => setEditingTx(tx)}
            categories={categories}
            onCategorize={handleCategorize}
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
    </>
  )

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
                <CurrencyDisplay value={account.currentBalance} currency={account.currency} />
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
                <span className="text-sm text-muted-foreground">{t('dashboard.portfolioPerformance')}</span>
              </div>
            )}
          </CardContent>
        </Card>
      ) : null}

      {/* Loan detail */}
      {isLoan && account && <LoanDetailSection accountId={account.id} />}

      {/* Property detail: description, valuation, financing and ownership split */}
      {isRealEstate && account && <PropertyDetailSection account={account} />}

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
          otherwise the flat table.

          Both are keyed on the account id so their column sort resets when the reader moves to
          another account. This route keeps the same component across a change of :id, so without
          the key a sort chosen on one portfolio would silently carry over to the next. */}
      {showHoldings && (
        holdings ? (
          positions && positions.length > 0 ? (
            <PositionsByProduct key={accountId} positions={positions} />
          ) : (
            <HoldingsTable
              key={accountId}
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
      {showHoldings && <RealizedPnlSection key={accountId} accountId={accountId} enabled={showHoldings} />}

      {/* Savings accounts: split into Overview / Config tabs so the page stays clean. */}
      {isSavings && account ? (
        <Tabs defaultValue="overview">
          <TabsList>
            <TabsTrigger value="overview">{t('accounts.tabOverview')}</TabsTrigger>
            <TabsTrigger value="config">{t('savings.configSection')}</TabsTrigger>
          </TabsList>
          <TabsContent value="overview" className="mt-4 space-y-4">
            {overviewSections}
          </TabsContent>
          <TabsContent value="config" className="mt-4">
            <SavingsConfigSection
              accountId={account.id}
              initialConfig={account.savingsConfig}
              suggestedProduct={savingsSuggestion?.suggestedProduct}
              suggestedRate={savingsSuggestion?.defaultAnnualRate}
            />
          </TabsContent>
        </Tabs>
      ) : (
        overviewSections
      )}

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
      ) : null)}

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
            categoryId: editingTx.categoryId ?? undefined,
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
