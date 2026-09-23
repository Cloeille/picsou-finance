import { useState, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAccounts, useAccountDeletionImpact, useUpdateAccount, useDeleteAccount, useUpdateDebtMetadata } from '@/features/accounts/hooks'
import { useHistory } from '@/features/history/hooks'
import { AccountForm } from '@/components/shared/AccountForm'
import { AddAccountModal } from '@/components/shared/AddAccountModal'
import { AddPropertyModal } from '@/components/property/AddPropertyModal'
import { AddScpiModal } from '@/components/scpi/AddScpiModal'
import { AccountCard } from '@/components/shared/AccountCard'
import { AccountsStackedChart } from '@/components/shared/AccountsStackedChart'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Wallet, Pencil, Trash2, TrendingUp, TrendingDown } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { Account, AccountRequest, AccountType } from '@/types/api'

type AssetFilter = 'ALL' | 'STOCKS' | 'METALS' | 'SAVINGS' | 'CHECKING' | 'CRYPTO' | 'REAL_ESTATE' | 'DEBTS'

const FILTER_KEYS: AssetFilter[] = ['ALL', 'STOCKS', 'METALS', 'SAVINGS', 'CHECKING', 'CRYPTO', 'REAL_ESTATE', 'DEBTS']

const ASSET_FILTER_MAP: Record<AssetFilter, AccountType[] | null> = {
  ALL: null,
  STOCKS: ['PEA', 'COMPTE_TITRES', 'EMPLOYEE_SAVINGS'],
  METALS: ['OTHER'],
  SAVINGS: ['LEP', 'LIVRET_A', 'LDDS', 'LIVRET_JEUNE', 'PEL', 'CEL', 'SAVINGS'],
  CHECKING: ['CHECKING'],
  CRYPTO: ['CRYPTO'],
  REAL_ESTATE: ['REAL_ESTATE', 'SCPI'],
  DEBTS: ['LOAN'],
}

const TYPE_GROUP_META: Record<string, { key: string; labelKey: string; color: string }> = {
  STOCKS:      { key: 'STOCKS',      labelKey: 'accounts.filters.STOCKS',      color: '#6366f1' },
  METALS:      { key: 'METALS',      labelKey: 'accounts.filters.METALS',      color: '#eab308' },
  SAVINGS:     { key: 'SAVINGS',     labelKey: 'accounts.filters.SAVINGS',     color: '#22c55e' },
  CHECKING:    { key: 'CHECKING',    labelKey: 'accounts.filters.CHECKING',    color: '#0ea5e9' },
  CRYPTO:      { key: 'CRYPTO',      labelKey: 'accounts.filters.CRYPTO',      color: '#f97316' },
  REAL_ESTATE: { key: 'REAL_ESTATE', labelKey: 'accounts.filters.REAL_ESTATE', color: '#a855f7' },
  DEBTS:       { key: 'DEBTS',       labelKey: 'accounts.filters.DEBTS',       color: '#ef4444' },
}

const TYPE_TO_GROUP: Record<AccountType, string> = {
  PEA: 'STOCKS',
  COMPTE_TITRES: 'STOCKS',
  EMPLOYEE_SAVINGS: 'STOCKS',
  OTHER: 'METALS',
  LEP: 'SAVINGS',
  LIVRET_A: 'SAVINGS',
  LDDS: 'SAVINGS',
  LIVRET_JEUNE: 'SAVINGS',
  PEL: 'SAVINGS',
  CEL: 'SAVINGS',
  SAVINGS: 'SAVINGS',
  CHECKING: 'CHECKING',
  CRYPTO: 'CRYPTO',
  REAL_ESTATE: 'REAL_ESTATE',
  SCPI: 'REAL_ESTATE',
  LOAN: 'DEBTS',
}

const HOLDING_ACCOUNT_TYPES: AccountType[] = ['PEA', 'COMPTE_TITRES', 'CRYPTO', 'EMPLOYEE_SAVINGS']

type AccountFormData = {
  name: string
  type: AccountType
  provider?: string
  currency: string
  currentBalance?: number
  isManual: boolean
  color: string
  ticker?: string
  logoKey?: string
  institutionId?: string
  borrowedAmount?: number
  interestRatePct?: number
  monthlyPayment?: number
  insuranceMonthly?: number
  fileFees?: number
  startDate?: string
  endDate?: string
  linkedAccountId?: number
}

export function AccountsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { data: accounts, isLoading } = useAccounts()
  const updateAccount = useUpdateAccount()
  const updateDebt = useUpdateDebtMetadata()
  const deleteAccount = useDeleteAccount()

  const [showCreateModal, setShowCreateModal] = useState(false)
  const [showPropertyModal, setShowPropertyModal] = useState(false)
  const [showScpiModal, setShowScpiModal] = useState(false)
  const [showEditForm, setShowEditForm] = useState(false)
  const [editingAccount, setEditingAccount] = useState<Account | null>(null)
  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [filter, setFilter] = useState<AssetFilter>('ALL')

  // Deleting the last account on a connection removes that connection too, and a bank one
  // costs a full OAuth re-authorisation to get back -- so the dialog names it first.
  const { data: deletionImpact } = useAccountDeletionImpact(deleteId)

  // All account IDs for history query (split mode for per-account breakdown)
  const allAccountIds = useMemo(() => (accounts ?? []).map(a => a.id), [accounts])
  const { data: historyData, isLoading: isHistoryLoading } = useHistory(allAccountIds, 12, true)

  // Grid accounts: always individual, filtered by type
  const filteredAccounts = useMemo(() => {
    if (!accounts) return []
    const types = ASSET_FILTER_MAP[filter]
    if (!types) return accounts
    return accounts.filter(a => types.includes(a.type))
  }, [accounts, filter])

  // Whether current filter contains investment accounts (for PnL display)
  const hasHoldings = filteredAccounts.some(a => HOLDING_ACCOUNT_TYPES.includes(a.type))

  // Summary card values
  const totalBalance = filteredAccounts.reduce((sum, a) =>
    a.type === 'LOAN' ? sum - a.currentBalanceEur : sum + a.currentBalanceEur, 0)

  // PnL from the latest history point for filtered accounts
  const { pnl, pnlPct, totalInvested } = useMemo(() => {
    if (!historyData || historyData.length === 0 || filteredAccounts.length === 0) {
      return { pnl: 0, pnlPct: null, totalInvested: 0 }
    }
    const latest = historyData[historyData.length - 1]
    if (!latest.accounts) return { pnl: 0, pnlPct: null, totalInvested: 0 }

    let inv = 0
    let pnlSum = 0
    for (const a of filteredAccounts) {
      const ap = latest.accounts[String(a.id)]
      if (ap) {
        inv += ap.invested
        pnlSum += ap.pnl
      }
    }
    const pct = inv > 0 ? ((pnlSum / inv) * 100).toFixed(1) : null
    return { pnl: pnlSum, pnlPct: pct, totalInvested: inv }
  }, [historyData, filteredAccounts])

  const pnlPositive = pnl >= 0

  // Chart accounts: grouped by type when ALL, individual accounts otherwise
  const chartAccounts = useMemo(() => {
    if (!accounts) return []
    if (filter !== 'ALL') {
      return accounts.filter(a => ASSET_FILTER_MAP[filter]!.includes(a.type))
    }
    return Object.values(TYPE_GROUP_META).map(meta => ({
      id: meta.key as unknown as number,
      name: t(meta.labelKey),
      type: 'OTHER' as AccountType,
      provider: null,
      currency: 'EUR',
      currentBalance: 0,
      currentBalanceEur: 0,
      lastSyncedAt: null,
      isManual: false,
      color: meta.color,
      ticker: null,
      logoUrl: null,
      logoKey: null,
      createdAt: '',
    }))
  }, [accounts, filter, t])

  // Chart PnL data from split history
  const chartPnlData = useMemo(() => {
    if (!historyData || !accounts) return []

    if (filter !== 'ALL') {
      const ids = accounts
        .filter(a => ASSET_FILTER_MAP[filter]!.includes(a.type))
        .map(a => String(a.id))

      return historyData
        .filter(p => p.accounts)
        .map(point => {
          const row: { date: string; [key: string]: string | number } = { date: point.date! }
          for (const id of ids) {
            const ap = point.accounts![id]
            row[id] = ap ? ap.pnl : 0
          }
          return row
        })
    }

    // ALL → aggregate PnL per type group
    const groupIds: Record<string, Set<string>> = {}
    for (const a of accounts) {
      const group = TYPE_TO_GROUP[a.type]
      if (!groupIds[group]) groupIds[group] = new Set()
      groupIds[group].add(String(a.id))
    }

    return historyData
      .filter(p => p.accounts)
      .map(point => {
        const row: { date: string; [key: string]: string | number } = { date: point.date! }
        for (const [group, ids] of Object.entries(groupIds)) {
          let pnlSum = 0
          for (const id of ids) {
            const ap = point.accounts![id]
            if (ap) pnlSum += ap.pnl
          }
          row[group] = pnlSum
        }
        return row
      })
  }, [historyData, accounts, filter])

  // With the Immobilier filter on, "add an account" almost certainly means "add a property",
  // so the primary action goes straight to the guided flow instead of the generic picker.
  const addingProperty = filter === 'REAL_ESTATE'

  function handleOpenCreate() {
    if (addingProperty) {
      setShowPropertyModal(true)
      return
    }
    setShowCreateModal(true)
  }

  function handleOpenEdit(account: Account) {
    setEditingAccount(account)
    setShowEditForm(true)
  }

  function handleEditFormOpenChange(open: boolean) {
    setShowEditForm(open)
    if (!open) setEditingAccount(null)
  }

  async function handleEditSubmit(data: AccountFormData) {
    if (!editingAccount) return
    const request: AccountRequest = {
      name: data.name,
      type: data.type,
      provider: data.provider || undefined,
      currency: data.currency,
      currentBalance: data.currentBalance,
      isManual: data.isManual,
      color: data.color,
      ticker: data.ticker || undefined,
      // Empty rather than absent for every account without a logo choice; the backend keeps
      // whatever it already stores when this is undefined.
      logoKey: data.logoKey || undefined,
      // Set only when a bank was picked from the catalog; the backend resolves its logo from it.
      institutionId: data.institutionId,
    }
    await updateAccount.mutateAsync({ id: editingAccount.id, data: request })
    if (data.type === 'LOAN' && data.borrowedAmount && data.borrowedAmount > 0) {
      await updateDebt.mutateAsync({
        id: editingAccount.id,
        data: {
          borrowedAmount: data.borrowedAmount,
          interestRate: data.interestRatePct != null ? data.interestRatePct / 100 : undefined,
          monthlyPayment: data.monthlyPayment,
          insuranceMonthly: data.insuranceMonthly,
          fileFees: data.fileFees,
          lenderName: data.provider || undefined,
          startDate: data.startDate || undefined,
          endDate: data.endDate || undefined,
          // null, not undefined: an omitted key would leave a previously linked property
          // attached when the user picks "no linked asset".
          linkedAccountId: data.linkedAccountId ?? null,
        },
      })
    }
    setShowEditForm(false)
    setEditingAccount(null)
  }

  async function handleConfirmDelete() {
    if (deleteId === null) return
    await deleteAccount.mutateAsync(deleteId)
    setDeleteId(null)
  }

  const defaultValues: Partial<AccountFormData> | undefined = useMemo(() => {
    if (!editingAccount) return undefined
    const debt = editingAccount.debt
    return {
      name: editingAccount.name,
      type: editingAccount.type,
      provider: (editingAccount.type === 'LOAN' ? debt?.lenderName : editingAccount.provider) ?? '',
      currency: editingAccount.currency,
      currentBalance: editingAccount.currentBalance,
      isManual: editingAccount.isManual,
      color: editingAccount.color,
      ticker: editingAccount.ticker ?? '',
      logoKey: editingAccount.logoKey ?? '',
      ...(debt
        ? {
            borrowedAmount: debt.borrowedAmount,
            interestRatePct: debt.interestRate != null ? debt.interestRate * 100 : undefined,
            monthlyPayment: debt.monthlyPayment ?? undefined,
            insuranceMonthly: debt.insuranceMonthly ?? undefined,
            fileFees: debt.fileFees ?? undefined,
            startDate: debt.startDate ?? '',
            endDate: debt.endDate ?? '',
            linkedAccountId: debt.linkedAccountId ?? undefined,
          }
        : {}),
    }
  }, [editingAccount])

  const isMutating = updateAccount.isPending || updateDebt.isPending

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('accounts.title')}
        actions={
          addingProperty ? (
            <div className="flex items-center gap-2">
              <Button onClick={() => setShowPropertyModal(true)} size="sm">
                <Plus className="size-4" />
                {t('property.add.action')}
              </Button>
              <Button onClick={() => setShowScpiModal(true)} size="sm" variant="outline">
                <Plus className="size-4" />
                {t('scpi.add.action')}
              </Button>
            </div>
          ) : (
            <Button onClick={handleOpenCreate} size="sm">
              <Plus className="size-4" />
              {t('accounts.addAccount')}
            </Button>
          )
        }
      />

      {accounts && accounts.length > 0 && (
        <>
          <div className="flex flex-wrap items-center gap-2">
            {FILTER_KEYS.map(f => (
              <button
                key={f}
                onClick={() => setFilter(f)}
                className={cn(
                  'inline-flex h-10 min-w-32 items-center justify-center rounded-md px-6 text-sm font-medium transition-[background-color,color]',
                  filter === f
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                )}
              >
                {t(`accounts.filters.${f}`)}
              </button>
            ))}
          </div>

          {/* Summary card */}
          <Card>
            <CardContent>
              <CardTitle>{t('accounts.total')}</CardTitle>
              <CurrencyDisplay value={totalBalance} className="text-4xl font-bold" />
              {hasHoldings && totalInvested > 0 && (
                <div className="mt-3 flex items-center gap-2">
                  {pnlPositive
                    ? <TrendingUp className="text-emerald-500" size={18} />
                    : <TrendingDown className="text-red-500" size={18} />}
                  <span className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`}>
                    <CurrencyDisplay value={pnl} showSign />
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

          {/* PnL chart */}
          {hasHoldings && (
            <Card>
              <CardHeader>
                <CardTitle>{t('accounts.pnl')}</CardTitle>
              </CardHeader>
              <CardContent>
                {isHistoryLoading ? (
                  <Skeleton className="h-[250px] w-full rounded-xl" />
                ) : (
                  <AccountsStackedChart accounts={chartAccounts} data={chartPnlData} />
                )}
              </CardContent>
            </Card>
          )}
        </>
      )}

      {isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full rounded-xl" />
          ))}
        </div>
      ) : filteredAccounts.length === 0 ? (
        <EmptyState
          className="min-h-[calc(100vh-14rem)]"
          icon={<Wallet className="size-12" />}
          title={t('accounts.noAccounts')}
          action={{
            label: addingProperty ? t('property.add.action') : t('accounts.addAccount'),
            onClick: handleOpenCreate,
          }}
        />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredAccounts.map((account) => (
            <div key={account.id} className="relative group">
              <AccountCard
                account={account}
                onClick={() => navigate(`/accounts/${account.id}`)}
              />
              <div className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={(e) => {
                    e.stopPropagation()
                    handleOpenEdit(account)
                  }}
                >
                  <Pencil className="size-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="text-destructive hover:text-destructive"
                  onClick={(e) => {
                    e.stopPropagation()
                    setDeleteId(account.id)
                  }}
                >
                  <Trash2 className="size-4" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {showPropertyModal && (
        <AddPropertyModal open onOpenChange={setShowPropertyModal} />
      )}

      {showScpiModal && (
        <AddScpiModal open onOpenChange={setShowScpiModal} />
      )}

      <AddAccountModal
        open={showCreateModal}
        onOpenChange={setShowCreateModal}
      />

      <AccountForm
        open={showEditForm}
        onOpenChange={handleEditFormOpenChange}
        onSubmit={handleEditSubmit}
        defaultValues={defaultValues}
        accounts={accounts}
        title={t('accounts.editAccount')}
        loading={isMutating}
      />

      <ConfirmDialog
        open={deleteId !== null}
        onOpenChange={(open) => { if (!open) setDeleteId(null) }}
        title={t('accounts.deleteAccount')}
        description={
          deletionImpact?.removesConnection
            ? `${t('accounts.deleteConfirm')} ${t('accounts.deleteRemovesConnection', { connection: deletionImpact.connectionLabel })}`
            : t('accounts.deleteConfirm')
        }
        onConfirm={handleConfirmDelete}
        loading={deleteAccount.isPending}
        variant="destructive"
      />
    </div>
  )
}
