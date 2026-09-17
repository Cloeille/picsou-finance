import { useState, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAccounts, useAccountTree, useAccountDeletionImpact, useUpdateAccount, useDeleteAccount, useUpdateDebtMetadata } from '@/features/accounts/hooks'
import { useHistory } from '@/features/history/hooks'
import { useSavingsSuggestions } from '@/features/savings/hooks'
import { AccountForm } from '@/components/shared/AccountForm'
import { AddAccountModal } from '@/components/shared/AddAccountModal'
import { AddPropertyModal } from '@/components/property/AddPropertyModal'
import { ExportAccountsModal } from '@/components/shared/ExportAccountsModal'
import { AccountCard } from '@/components/shared/AccountCard'
import { AccountsStackedChart } from '@/components/shared/AccountsStackedChart'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Wallet, Pencil, Trash2, TrendingUp, TrendingDown, Download } from 'lucide-react'
import { cn } from '@/lib/utils'
import { HOLDING_ACCOUNT_TYPES } from '@/lib/constants'
import { accountInvestedAt, accountPnlAt, hasMeasurableGain } from '@/features/accounts/pnl'
import { useAppStore } from '@/stores/app-store'
import type { Account, AccountRequest, AccountType } from '@/types/api'

type AssetFilter = 'ALL' | 'STOCKS' | 'METALS' | 'SAVINGS' | 'CHECKING' | 'CRYPTO' | 'REAL_ESTATE' | 'DEBTS'

const FILTER_KEYS: AssetFilter[] = ['ALL', 'STOCKS', 'METALS', 'SAVINGS', 'CHECKING', 'CRYPTO', 'REAL_ESTATE', 'DEBTS']

const ASSET_FILTER_MAP: Record<AssetFilter, AccountType[] | null> = {
  ALL: null,
  STOCKS: ['PEA', 'COMPTE_TITRES', 'EMPLOYEE_SAVINGS', 'ASSURANCE_VIE'],
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
  ASSURANCE_VIE: 'STOCKS',
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
  openedAt?: string
}

// ─── Inline pocket card (smaller, with "alloué" tooltip) ─────────────────────

function PocketCard({ account, onClick }: { account: Account; onClick?: () => void }) {
  return (
    <Card
      className="cursor-pointer transition-shadow hover:shadow-md"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-3 sm:p-4">
        <div
          className="mt-1 h-8 w-1 shrink-0 rounded-full"
          style={{ backgroundColor: account.color }}
        />
        <div className="min-w-0 flex-1">
          <span className="truncate text-sm font-medium">{account.name}</span>
          <div className="mt-1">
            <CurrencyDisplay value={account.currentBalanceEur} className="text-base font-semibold" />
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

// ─── Main page ────────────────────────────────────────────────────────────────

export function AccountsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { data: accounts, isLoading } = useAccounts()
  // The demo adapter has no handler for the export route, and an unhandled route resolves to
  // {} -- which here would download a corrupt workbook instead of failing visibly.
  const demoMode = useAppStore(state => state.demoMode)
  const updateAccount = useUpdateAccount()
  const updateDebt = useUpdateDebtMetadata()
  const deleteAccount = useDeleteAccount()
  const { data: savingsSuggestions } = useSavingsSuggestions()
  const hasSavingsSuggestions = Array.isArray(savingsSuggestions) && savingsSuggestions.length > 0

  const [showCreateModal, setShowCreateModal] = useState(false)
  const [showPropertyModal, setShowPropertyModal] = useState(false)
  const [showExportModal, setShowExportModal] = useState(false)
  const [showEditForm, setShowEditForm] = useState(false)
  const [editingAccount, setEditingAccount] = useState<Account | null>(null)
  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [filter, setFilter] = useState<AssetFilter>('ALL')

  // ── Pocket grouping ──────────────────────────────────────────────────────────
  //
  // Pockets (accounts with parentAccountId set) are excluded from the flat list
  // to avoid double-counting their balance. They are rendered nested under their
  // parent Revolut wallet instead. See useAccountTree for the orphaned-pocket
  // fallback (parent wallet soft-deleted) that keeps such a pocket from vanishing.

  const { nonPocketAccounts, walletGroups: allWalletGroups, standaloneAccounts: allStandaloneAccounts } =
    useAccountTree(accounts)

  // Deleting the last account on a connection removes that connection too, and a bank one
  // costs a full OAuth re-authorisation to get back -- so the dialog names it first.
  const { data: deletionImpact } = useAccountDeletionImpact(deleteId)

  // All non-pocket IDs for history query (split mode for per-account breakdown)
  const allAccountIds = useMemo(() => nonPocketAccounts.map((a) => a.id), [nonPocketAccounts])
  const { data: historyData, isLoading: isHistoryLoading } = useHistory(allAccountIds, 12, true)

  // Non-pocket accounts filtered by type
  const filteredNonPockets = useMemo(() => {
    const types = ASSET_FILTER_MAP[filter]
    if (!types) return nonPocketAccounts
    return nonPocketAccounts.filter((a) => types.includes(a.type))
  }, [nonPocketAccounts, filter])

  // Wallet groups: parents that have child pockets (e.g. Revolut wallet), filtered by
  // the wallet's own type — pockets are shown in full regardless of the asset filter.
  const walletGroups = useMemo(() => {
    const types = ASSET_FILTER_MAP[filter]
    if (!types) return allWalletGroups
    return allWalletGroups.filter(({ wallet }) => types.includes(wallet.type))
  }, [allWalletGroups, filter])

  // Standalone accounts: non-pockets without any child pockets, filtered by type
  const standaloneAccounts = useMemo(() => {
    const types = ASSET_FILTER_MAP[filter]
    if (!types) return allStandaloneAccounts
    return allStandaloneAccounts.filter((a) => types.includes(a.type))
  }, [allStandaloneAccounts, filter])

  // Whether the current filter has a gain/loss worth showing: an investment account, whose
  // basis comes from its holdings, or a property, whose basis is its purchase price plus fees.
  // Cash-only filters are excluded on purpose -- their PnL is always 0.
  const hasPnl = filteredNonPockets.some(
    a => HOLDING_ACCOUNT_TYPES.includes(a.type) || hasMeasurableGain(a)
  )

  // Summary card values (pockets excluded — their balance is already in the wallet)
  const totalBalance = filteredNonPockets.reduce(
    (sum, a) => (a.type === 'LOAN' ? sum - a.currentBalanceEur : sum + a.currentBalanceEur),
    0,
  )

  // PnL from the latest history point for filtered non-pocket accounts
  const { pnl, pnlPct, totalInvested } = useMemo(() => {
    if (!historyData || !Array.isArray(historyData) || historyData.length === 0 || filteredNonPockets.length === 0) {
      return { pnl: 0, pnlPct: null, totalInvested: 0 }
    }
    const latest = historyData[historyData.length - 1]
    if (!latest || !latest.accounts) return { pnl: 0, pnlPct: null, totalInvested: 0 }

    let inv = 0
    let pnlSum = 0
    for (const a of filteredNonPockets) {
      const ap = latest.accounts[String(a.id)]
      if (ap) {
        inv += accountInvestedAt(a, ap)
        pnlSum += accountPnlAt(a, ap)
      }
    }
    const pct = inv > 0 ? ((pnlSum / inv) * 100).toFixed(1) : null
    return { pnl: pnlSum, pnlPct: pct, totalInvested: inv }
  }, [historyData, filteredNonPockets])

  const pnlPositive = pnl >= 0

  // Chart accounts: grouped by type when ALL, individual accounts otherwise
  const chartAccounts = useMemo(() => {
    if (!accounts) return []
    if (filter !== 'ALL') {
      return nonPocketAccounts.filter((a) => ASSET_FILTER_MAP[filter]!.includes(a.type))
    }
    return Object.values(TYPE_GROUP_META).map((meta) => ({
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
      hidden: false,
    }))
  }, [accounts, nonPocketAccounts, filter, t])

  // Chart PnL data from split history (non-pocket accounts only)
  const chartPnlData = useMemo(() => {
    if (!historyData || !Array.isArray(historyData) || !accounts) return []

    if (filter !== 'ALL') {
      const shown = nonPocketAccounts.filter(a => ASSET_FILTER_MAP[filter]!.includes(a.type))

      return historyData
        .filter((p) => p.accounts)
        .map((point) => {
          const row: { date: string; [key: string]: string | number } = { date: point.date! }
          for (const a of shown) {
            const ap = point.accounts![String(a.id)]
            row[String(a.id)] = ap ? accountPnlAt(a, ap) : 0
          }
          return row
        })
    }

    // ALL → aggregate PnL per type group, pockets excluded. Grouped by account rather than by
    // id string, because a property's PnL is only computable from the account itself (its cost
    // basis lives there).
    const groupMembers: Record<string, Account[]> = {}
    for (const a of nonPocketAccounts) {
      const group = TYPE_TO_GROUP[a.type]
      if (!groupMembers[group]) groupMembers[group] = []
      groupMembers[group].push(a)
    }

    return historyData
      .filter((p) => p.accounts)
      .map((point) => {
        const row: { date: string; [key: string]: string | number } = { date: point.date! }
        for (const [group, members] of Object.entries(groupMembers)) {
          let pnlSum = 0
          for (const a of members) {
            const ap = point.accounts![String(a.id)]
            if (ap) pnlSum += accountPnlAt(a, ap)
          }
          row[group] = pnlSum
        }
        return row
      })
  }, [historyData, accounts, nonPocketAccounts, filter])

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
      // Undefined leaves the stored date alone, which is what an account type that never offers
      // the field should do -- see AccountRequest.
      openedAt: data.openedAt || undefined,
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
      openedAt: editingAccount.openedAt ?? '',
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

  const hasAnyAccounts = (accounts?.length ?? 0) > 0

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('accounts.title')}
        actions={
          <div className="flex items-center gap-2">
            {!demoMode && (
              <Button
                onClick={() => setShowExportModal(true)}
                size="sm"
                variant="outline"
                disabled={!accounts || accounts.length === 0}
              >
                <Download className="size-4" />
                {t('accounts.export.button')}
              </Button>
            )}
            <Button onClick={handleOpenCreate} size="sm">
              <Plus className="size-4" />
              {addingProperty ? t('property.add.action') : t('accounts.addAccount')}
            </Button>
          </div>
        }
      />

      {/* Savings suggestions banner */}
      {hasSavingsSuggestions && (
        <div className="flex items-center justify-between rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 dark:border-emerald-900/50 dark:bg-emerald-950/30">
          <p className="text-sm text-emerald-800 dark:text-emerald-300">
            {t('savings.suggestionsBanner', { count: savingsSuggestions!.length })}
          </p>
          <Button
            size="sm"
            variant="outline"
            className="ml-4 shrink-0"
            onClick={() => navigate(`/accounts/${savingsSuggestions![0].accountId}`)}
          >
            {t('savings.configureSavings')}
          </Button>
        </div>
      )}

      {hasAnyAccounts && (
        <>
          <div className="flex flex-wrap items-center gap-1">
            {FILTER_KEYS.map((f) => (
              <button
                key={f}
                onClick={() => setFilter(f)}
                className={cn(
                  'inline-flex h-10 min-w-32 items-center justify-center rounded-md px-6 text-sm font-medium transition-[background-color,color]',
                  filter === f
                    ? 'bg-primary text-primary-foreground shadow-sm'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground',
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
              {hasPnl && totalInvested > 0 && (
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
                  <span className="text-sm text-muted-foreground">{t('dashboard.portfolioPerformance')}</span>
                </div>
              )}
            </CardContent>
          </Card>

          {/* PnL chart */}
          {hasPnl && (
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
      ) : filteredNonPockets.length === 0 ? (
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
        <div className="space-y-4">
          {/* Wallet groups: parent account + nested pocket sub-accounts */}
          {walletGroups.map(({ wallet, pockets: walletPockets }) => (
            <div key={wallet.id} className="space-y-2">
              {/* Parent wallet card */}
              <div className="relative group">
                <AccountCard
                  account={wallet}
                  onClick={() => navigate(`/accounts/${wallet.id}`)}
                />
                <div className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-7"
                    onClick={(e) => {
                      e.stopPropagation()
                      handleOpenEdit(wallet)
                    }}
                  >
                    <Pencil className="size-3.5" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-7 text-destructive hover:text-destructive"
                    onClick={(e) => {
                      e.stopPropagation()
                      setDeleteId(wallet.id)
                    }}
                  >
                    <Trash2 className="size-3.5" />
                  </Button>
                </div>
              </div>

              {/* Pocket sub-accounts */}
              <div className="ml-3 pl-4 border-l-2 border-border/40 space-y-2">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {t('pockets.subAccountsCount', { count: walletPockets.length })}
                </p>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {walletPockets.map((pocket) => (
                    <div key={pocket.id} className="relative group">
                      <PocketCard
                        account={pocket}
                        onClick={() => navigate(`/accounts/${pocket.id}`)}
                      />
                      <div className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7"
                          onClick={(e) => {
                            e.stopPropagation()
                            handleOpenEdit(pocket)
                          }}
                        >
                          <Pencil className="size-3.5" />
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ))}

          {/* Standalone accounts — normal responsive grid */}
          {standaloneAccounts.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {standaloneAccounts.map((account) => (
                <div key={account.id} className="relative group">
                  <AccountCard
                    account={account}
                    onClick={() => navigate(`/accounts/${account.id}`)}
                  />
                  <div className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-7"
                      onClick={(e) => {
                        e.stopPropagation()
                        handleOpenEdit(account)
                      }}
                    >
                      <Pencil className="size-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-7 text-destructive hover:text-destructive"
                      onClick={(e) => {
                        e.stopPropagation()
                        setDeleteId(account.id)
                      }}
                    >
                      <Trash2 className="size-3.5" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {showPropertyModal && (
        <AddPropertyModal open onOpenChange={setShowPropertyModal} />
      )}

      <ExportAccountsModal
        open={showExportModal}
        onOpenChange={setShowExportModal}
        accounts={accounts ?? []}
      />

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
