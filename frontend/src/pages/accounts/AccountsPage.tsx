import { useState, useMemo } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useAccounts, useAccountTree, useSetDisplayOrder, useSetListCollapsed } from '@/features/accounts/hooks'
import { orderAccountsForList, splitCollapsed } from '@/features/accounts/display'
import { useHistory } from '@/features/history/hooks'
import { useSavingsSuggestions } from '@/features/savings/hooks'
import { usePeerLoansOverview } from '@/features/peer-loans/hooks'
import { PeerLoansOverviewCard } from '@/components/peer-loan/PeerLoansOverviewCard'
import { AddAccountModal } from '@/components/shared/AddAccountModal'
import { AddPropertyModal } from '@/components/property/AddPropertyModal'
import { ExportAccountsModal } from '@/components/shared/ExportAccountsModal'
import { AddScpiModal } from '@/components/scpi/AddScpiModal'
import { AccountCard } from '@/components/shared/AccountCard'
import { AccountsStackedChart } from '@/components/shared/AccountsStackedChart'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { PageHeader } from '@/components/shared/PageHeader'
import { ScrollStrip } from '@/components/shared/ScrollStrip'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Plus, Wallet, TrendingUp, TrendingDown, Download, Eye, EyeOff, ChevronUp, ChevronDown, ArrowUpDown, Check, PiggyBank } from 'lucide-react'
import { cn, formatPercent } from '@/lib/utils'
import { HOLDING_ACCOUNT_TYPES, LIABILITY_ACCOUNT_TYPES } from '@/lib/constants'
import { accountInvestedAt, accountPnlAt, hasMeasurableGain } from '@/features/accounts/pnl'
import { useAppStore } from '@/stores/app-store'
import type { Account, AccountType } from '@/types/api'

type AssetFilter = 'ALL' | 'STOCKS' | 'METALS' | 'SAVINGS' | 'CHECKING' | 'CRYPTO' | 'REAL_ESTATE' | 'RECEIVABLES' | 'DEBTS' | 'OTHER'

const FILTER_KEYS: AssetFilter[] = ['ALL', 'STOCKS', 'METALS', 'SAVINGS', 'CHECKING', 'CRYPTO', 'REAL_ESTATE', 'RECEIVABLES', 'DEBTS', 'OTHER']
const CATEGORY_FILTER_KEYS: AssetFilter[] = FILTER_KEYS.filter((f) => f !== 'ALL')
const MAX_PRIMARY_CATEGORIES = 5

const ASSET_FILTER_MAP: Record<AssetFilter, AccountType[] | null> = {
  ALL: null,
  STOCKS: ['PEA', 'COMPTE_TITRES', 'EMPLOYEE_SAVINGS', 'ASSURANCE_VIE', 'PER', 'BROKERAGE'],
  METALS: ['PRECIOUS_METALS'],
  SAVINGS: ['LEP', 'LIVRET_A', 'LDDS', 'LIVRET_JEUNE', 'PEL', 'CEL', 'SAVINGS'],
  CHECKING: ['CHECKING'],
  CRYPTO: ['CRYPTO'],
  REAL_ESTATE: ['REAL_ESTATE', 'SCPI'],
  RECEIVABLES: ['PEER_LOAN'],
  DEBTS: LIABILITY_ACCOUNT_TYPES,
  OTHER: ['OTHER'],
}

const TYPE_GROUP_META: Record<string, { key: string; labelKey: string; color: string }> = {
  STOCKS:      { key: 'STOCKS',      labelKey: 'accounts.filters.STOCKS',      color: '#6366f1' },
  METALS:      { key: 'METALS',      labelKey: 'accounts.filters.METALS',      color: '#eab308' },
  SAVINGS:     { key: 'SAVINGS',     labelKey: 'accounts.filters.SAVINGS',     color: '#22c55e' },
  CHECKING:    { key: 'CHECKING',    labelKey: 'accounts.filters.CHECKING',    color: '#0ea5e9' },
  CRYPTO:      { key: 'CRYPTO',      labelKey: 'accounts.filters.CRYPTO',      color: '#f97316' },
  REAL_ESTATE: { key: 'REAL_ESTATE', labelKey: 'accounts.filters.REAL_ESTATE', color: '#a855f7' },
  RECEIVABLES: { key: 'RECEIVABLES', labelKey: 'accounts.filters.RECEIVABLES', color: '#0d9488' },
  DEBTS:       { key: 'DEBTS',       labelKey: 'accounts.filters.DEBTS',       color: '#ef4444' },
  OTHER:       { key: 'OTHER',       labelKey: 'accounts.filters.OTHER',       color: '#94a3b8' },
}

const TYPE_TO_GROUP: Record<AccountType, string> = {
  PEA: 'STOCKS',
  COMPTE_TITRES: 'STOCKS',
  EMPLOYEE_SAVINGS: 'STOCKS',
  ASSURANCE_VIE: 'STOCKS',
  PER: 'STOCKS',
  BROKERAGE: 'STOCKS',
  PRECIOUS_METALS: 'METALS',
  OTHER: 'OTHER',
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
  PEER_LOAN: 'RECEIVABLES',
  LOAN: 'DEBTS',
  CREDIT_CARD: 'DEBTS',
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

const CARD_ACTIONS_CLASS = 'absolute top-2 right-2 flex gap-1'

export function AccountsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { data: accounts, isLoading: isAccountsLoading, isError: isAccountsError, refetch: refetchAccounts } = useAccounts()
  // The demo adapter has no handler for the export route, and an unhandled route resolves to
  // {} -- which here would download a corrupt workbook instead of failing visibly.
  const demoMode = useAppStore(state => state.demoMode)
  const { data: savingsSuggestions } = useSavingsSuggestions()
  const hasSavingsSuggestions = Array.isArray(savingsSuggestions) && savingsSuggestions.length > 0

  const [showCreateModal, setShowCreateModal] = useState(false)
  const [createType, setCreateType] = useState<AccountType | undefined>()
  const [showPropertyModal, setShowPropertyModal] = useState(false)
  const [showExportModal, setShowExportModal] = useState(false)
  const [showScpiModal, setShowScpiModal] = useState(false)
  const [filter, setFilter] = useState<AssetFilter>('ALL')
  const [reorderMode, setReorderMode] = useState(false)
  const reorderActive = reorderMode && filter === 'ALL'
  const setDisplayOrder = useSetDisplayOrder()
  const setListCollapsed = useSetListCollapsed()
  const [showClosed, setShowClosed] = useState(false)

  // A closed peer loan is a 0 EUR receivable. It stays reachable under the Receivables
  // filter, but it is not an "active" account on the default list.
  const peerLoansOverview = usePeerLoansOverview()
  // Closed loans are filtered out with the overview response, so wait for it before
  // painting the list: otherwise a closed (0 EUR) loan flashes for a frame before vanishing.
  const isLoading = isAccountsLoading || peerLoansOverview.isLoading
  const closedPeerLoanIds = useMemo(
    () => new Set((peerLoansOverview.data?.loans ?? [])
      .filter((loan) => loan.closedAt)
      .map((loan) => loan.accountId)),
    [peerLoansOverview.data],
  )
  const visibleAccounts = useMemo(() => {
    if (!accounts) return accounts
    const hideClosed = closedPeerLoanIds.size > 0
      && (filter === 'ALL' || (filter === 'RECEIVABLES' && !showClosed))
    return hideClosed ? accounts.filter((account) => !closedPeerLoanIds.has(account.id)) : accounts
  }, [accounts, filter, showClosed, closedPeerLoanIds])

  // The Créances tab shows the member-wide overview in place of the generic summary. The
  // closed toggle only filters the cards: the table keeps every loan so its history stays
  // readable, and the chart always reflects the full ledger.
  const showsReceivablesOverview = filter === 'RECEIVABLES'
    && (peerLoansOverview.data?.loans.length ?? 0) > 0

  // ── Pocket grouping ──────────────────────────────────────────────────────────
  //
  // Pockets (accounts with parentAccountId set) are excluded from the flat list
  // to avoid double-counting their balance. They are rendered nested under their
  // parent Revolut wallet instead. See useAccountTree for the orphaned-pocket
  // fallback (parent wallet soft-deleted) that keeps such a pocket from vanishing.

  const { nonPocketAccounts: allNonPocketAccounts } = useAccountTree(accounts)
  const { nonPocketAccounts, walletGroups: allWalletGroups, standaloneAccounts: allStandaloneAccounts } =
    useAccountTree(visibleAccounts)

  const { primaryFilters, overflowPopulatedFilters, overflowEmptyFilters } = useMemo(() => {
    const populated: AssetFilter[] = []
    const empty: AssetFilter[] = []
    for (const f of CATEGORY_FILTER_KEYS) {
      const types = ASSET_FILTER_MAP[f]
      if (types && allNonPocketAccounts.some((a) => types.includes(a.type))) {
        populated.push(f)
      } else {
        empty.push(f)
      }
    }
    return {
      primaryFilters: ['ALL', ...populated.slice(0, MAX_PRIMARY_CATEGORIES)] as AssetFilter[],
      overflowPopulatedFilters: populated.slice(MAX_PRIMARY_CATEGORIES),
      overflowEmptyFilters: empty,
    }
  }, [allNonPocketAccounts])
  const overflowFilters = useMemo(
    () => [...overflowPopulatedFilters, ...overflowEmptyFilters],
    [overflowPopulatedFilters, overflowEmptyFilters],
  )
  const isOverflowFilterActive = overflowFilters.includes(filter)

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

  // Manual display order + collapse apply to the Tout tab only. Totals, PnL and the chart above
  // keep using filteredNonPockets/walletGroups, so collapsed accounts stay counted everywhere.
  const orderedStandalone = useMemo(
    () => (filter === 'ALL' ? orderAccountsForList(standaloneAccounts) : standaloneAccounts),
    [standaloneAccounts, filter]
  )
  const orderedWalletGroups = useMemo(
    () => (filter === 'ALL' ? orderAccountsForList(walletGroups, (g) => g.wallet) : walletGroups),
    [walletGroups, filter]
  )
  const { shown: shownStandalone, collapsed: collapsedStandalone } = useMemo(
    () =>
      filter === 'ALL'
        ? splitCollapsed(orderedStandalone)
        : { shown: orderedStandalone, collapsed: [] as Account[] },
    [orderedStandalone, filter]
  )
  const { shown: shownWalletGroups, collapsed: collapsedWalletGroups } = useMemo(
    () =>
      filter === 'ALL'
        ? splitCollapsed(orderedWalletGroups, (g) => g.wallet)
        : { shown: orderedWalletGroups, collapsed: [] as typeof orderedWalletGroups },
    [orderedWalletGroups, filter]
  )
  // Reorder mode shows everything (to un-collapse too); normal view hides collapsed behind the recap.
  const displayStandalone = reorderActive ? orderedStandalone : shownStandalone
  const displayWalletGroups = reorderActive ? orderedWalletGroups : shownWalletGroups
  const collapsedCount = collapsedStandalone.length + collapsedWalletGroups.length
  const collapsedTotal = useMemo(() => {
    const signed = (a: Account) =>
      a.type === 'LOAN' ? -(a.currentBalanceEur ?? 0) : (a.currentBalanceEur ?? 0)
    return (
      collapsedStandalone.reduce((sum, a) => sum + signed(a), 0) +
      collapsedWalletGroups.reduce((sum, g) => sum + signed(g.wallet), 0)
    )
  }, [collapsedStandalone, collapsedWalletGroups])

  // Display prefs are per (member, account): every viewer arranges their own view,
  // owned or shared — nothing leaks from one viewer to another.
  const persistOrder = (standalone: Account[], groups: typeof orderedWalletGroups) => {
    setDisplayOrder.mutate([
      ...groups.map((g) => g.wallet.id),
      ...standalone.map((a) => a.id),
    ])
  }
  const moveStandalone = (index: number, dir: -1 | 1) => {
    const next = [...orderedStandalone]
    const target = index + dir
    if (target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
    persistOrder(next, orderedWalletGroups)
  }
  const moveWalletGroup = (index: number, dir: -1 | 1) => {
    const next = [...orderedWalletGroups]
    const target = index + dir
    if (target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
    persistOrder(orderedStandalone, next)
  }

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
      return { pnl: null, pnlPct: null, totalInvested: null }
    }
    const latest = historyData[historyData.length - 1]
    if (!latest || !latest.accounts) return { pnl: null, pnlPct: null, totalInvested: null }

    let inv = 0
    let pnlSum = 0
    let hasObservation = false
    let hasUnknownBasis = false
    for (const a of filteredNonPockets) {
      const ap = latest.accounts[String(a.id)]
      if (ap) {
        hasObservation = true
        const accountInvested = accountInvestedAt(a, ap)
        const accountPnl = accountPnlAt(a, ap)
        if (accountInvested == null || accountPnl == null) {
          hasUnknownBasis = true
          continue
        }
        inv += accountInvested
        pnlSum += accountPnl
      }
    }
    if (!hasObservation || hasUnknownBasis) {
      return { pnl: null, pnlPct: null, totalInvested: null }
    }
    const pct = inv > 0 ? (pnlSum / inv) * 100 : null
    return { pnl: pnlSum, pnlPct: pct, totalInvested: inv }
  }, [historyData, filteredNonPockets])

  const pnlPositive = pnl !== null && pnl >= 0

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
      listPosition: null,
      listCollapsed: false,
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
            if (ap) {
              const accountPnl = accountPnlAt(a, ap)
              if (accountPnl != null) row[String(a.id)] = accountPnl
            }
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
          let hasObservation = false
          let hasUnknownBasis = false
          for (const a of members) {
            const ap = point.accounts![String(a.id)]
            if (ap) {
              hasObservation = true
              const invested = accountInvestedAt(a, ap)
              const accountPnl = accountPnlAt(a, ap)
              if (invested == null || accountPnl == null) {
                hasUnknownBasis = true
                continue
              }
              pnlSum += accountPnl
            }
          }
          if (hasObservation && !hasUnknownBasis) row[group] = pnlSum
        }
        return row
      })
  }, [historyData, accounts, nonPocketAccounts, filter])

  // With the Immobilier filter on, "add an account" almost certainly means "add a property",
  // so the primary action goes straight to the guided flow instead of the generic picker.
  const addingProperty = filter === 'REAL_ESTATE'

  function handleOpenCreate(type?: AccountType) {
    if (!type && addingProperty) {
      setShowPropertyModal(true)
      return
    }
    setCreateType(type)
    setShowCreateModal(true)
  }

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
            <Button onClick={() => handleOpenCreate()} size="sm">
              <Plus className="size-4" />
              {addingProperty ? t('property.add.action') : t('accounts.addAccount')}
            </Button>
            {filter === 'ALL' && (
              <Button
                onClick={() => setReorderMode((v) => !v)}
                size="sm"
                variant={reorderActive ? 'secondary' : 'outline'}
                aria-pressed={reorderActive}
              >
                <ArrowUpDown className="size-4" />
                {t(reorderActive ? 'accounts.doneReordering' : 'accounts.reorder')}
              </Button>
            )}
            {filter === 'RECEIVABLES' && (
              <Button
                onClick={() => handleOpenCreate('PEER_LOAN')}
                size="sm"
                variant="outline"
              >
                <Plus className="size-4" />
                {t('peerLoan.create')}
              </Button>
            )}
            {addingProperty && (
              <Button onClick={() => setShowScpiModal(true)} size="sm" variant="outline">
                <Plus className="size-4" />
                {t('scpi.add.action')}
              </Button>
            )}
            {filter === 'RECEIVABLES' && closedPeerLoanIds.size > 0 && (
              <Button
                onClick={() => setShowClosed((value) => !value)}
                size="sm"
                variant={showClosed ? 'default' : 'outline'}
                aria-pressed={showClosed}
              >
                {showClosed ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                {t(showClosed ? 'accounts.hideClosedLoans' : 'accounts.showClosedLoans')}
              </Button>
            )}
          </div>
        }
      />

      {/* Savings suggestions banner */}
      {hasSavingsSuggestions && (
        <div className="flex items-center justify-between gap-4 rounded-xl border border-border/60 bg-card px-4 py-3">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted/60 text-foreground">
              <PiggyBank className="size-4" aria-hidden="true" />
            </div>
            <p className="text-sm font-medium text-foreground">
              {t('savings.suggestionsBanner', { count: savingsSuggestions!.length })}
            </p>
          </div>
          <Button
            size="sm"
            variant="outline"
            className="shrink-0"
            onClick={() => navigate(`/accounts/${savingsSuggestions![0].accountId}?tab=config`)}
          >
            {t('savings.configureSavings')}
          </Button>
        </div>
      )}

      {hasAnyAccounts && (
        <>
          <ScrollStrip activeKey={filter}>
            <div
              role="group"
              aria-label={t('accounts.title')}
              className="flex min-w-full w-max items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
            >
            {primaryFilters.map((f) => {
              const active = filter === f
              return (
                <button
                  key={f}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setFilter(f)}
                  className={cn(
                    'inline-flex h-8 flex-auto items-center justify-center whitespace-nowrap rounded-md px-3 text-sm font-medium transition-[background-color,color]',
                    active
                      ? 'bg-primary text-primary-foreground'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {t(`accounts.filters.${f}`)}
                </button>
              )
            })}
            {overflowFilters.length > 0 && (
              <>
                <span aria-hidden="true" className="mx-0.5 hidden h-4 w-px bg-border/60 sm:block" />
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <button
                      type="button"
                      aria-pressed={isOverflowFilterActive}
                      className={cn(
                        'inline-flex h-8 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-md px-3 text-sm font-medium transition-[background-color,color]',
                        isOverflowFilterActive
                          ? 'bg-primary text-primary-foreground'
                          : 'text-muted-foreground hover:text-foreground',
                      )}
                    >
                      <span>
                        {isOverflowFilterActive
                          ? t(`accounts.filters.${filter}`)
                          : t('accounts.filters.more')}
                      </span>
                      <ChevronDown className="size-3.5 opacity-75" />
                    </button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end" className="min-w-44">
                    {overflowPopulatedFilters.map((f) => (
                      <DropdownMenuItem
                        key={f}
                        onSelect={() => setFilter(f)}
                        className="justify-between gap-2"
                      >
                        <span>{t(`accounts.filters.${f}`)}</span>
                        {filter === f && <Check className="size-4 text-primary" />}
                      </DropdownMenuItem>
                    ))}
                    {overflowPopulatedFilters.length > 0 && overflowEmptyFilters.length > 0 && (
                      <DropdownMenuSeparator />
                    )}
                    {overflowEmptyFilters.map((f) => (
                      <DropdownMenuItem
                        key={f}
                        onSelect={() => setFilter(f)}
                        className={cn(
                          'justify-between gap-2',
                          filter !== f && 'text-muted-foreground',
                        )}
                      >
                        <span>{t(`accounts.filters.${f}`)}</span>
                        {filter === f && <Check className="size-4 text-primary" />}
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuContent>
                </DropdownMenu>
              </>
            )}
            </div>
          </ScrollStrip>

          {showsReceivablesOverview && peerLoansOverview.data && (
            <PeerLoansOverviewCard data={peerLoansOverview.data} />
          )}

          {/* Summary card */}
          {filter !== 'RECEIVABLES' && (
          <Card>
            <CardContent>
              <CardTitle>{t('accounts.total')}</CardTitle>
              <CurrencyDisplay value={totalBalance} className="text-4xl font-bold" />
              {hasPnl && pnl !== null && totalInvested !== null && totalInvested > 0 && (
                <div
                  className="mt-3 flex items-center gap-2"
                  data-testid="portfolio-performance"
                  aria-label={t('dashboard.portfolioPerformance')}
                >
                  {pnlPositive
                    ? <TrendingUp className="text-emerald-500" size={18} />
                    : <TrendingDown className="text-red-500" size={18} />}
                  <span className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`}>
                    <CurrencyDisplay value={pnl} showSign />
                    {pnlPct !== null && (
                      <span className="ml-1 font-normal text-muted-foreground">
                        ({formatPercent(pnlPct, { signed: true })})
                      </span>
                    )}
                  </span>
                </div>
              )}
            </CardContent>
          </Card>
          )}

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

      {isAccountsError ? (
        <ErrorState title={t('common.error')} onRetry={() => void refetchAccounts()} />
      ) : isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full rounded-xl" />
          ))}
        </div>
      ) : filteredNonPockets.length === 0 && !showsReceivablesOverview ? (
        <Card>
          <CardContent>
            <EmptyState
              className="py-8"
              icon={<Wallet className="size-10" />}
              title={t('accounts.noAccounts')}
            />
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          {/* Wallet groups: parent account + nested pocket sub-accounts */}
          {displayWalletGroups.map(({ wallet, pockets: walletPockets }, wIndex) => (
            <div key={wallet.id} className={cn('space-y-2', reorderActive && wallet.listCollapsed && 'opacity-60')}>
              {/* Parent wallet card */}
              <div className="relative group">
                <AccountCard
                  account={wallet}
                  closed={closedPeerLoanIds.has(wallet.id)}
                  onClick={() => navigate(`/accounts/${wallet.id}`)}
                />
                {reorderActive && (
                  <div className={CARD_ACTIONS_CLASS}>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-7"
                      aria-label={t('accounts.moveUp')}
                      disabled={wIndex === 0 || setDisplayOrder.isPending}
                      onClick={(e) => {
                        e.stopPropagation()
                        moveWalletGroup(wIndex, -1)
                      }}
                    >
                      <ChevronUp className="size-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-7"
                      aria-label={t('accounts.moveDown')}
                      disabled={wIndex === displayWalletGroups.length - 1 || setDisplayOrder.isPending}
                      onClick={(e) => {
                        e.stopPropagation()
                        moveWalletGroup(wIndex, 1)
                      }}
                    >
                      <ChevronDown className="size-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-7"
                      aria-label={t(wallet.listCollapsed ? 'accounts.expandAccount' : 'accounts.collapseAccount')}
                      aria-pressed={wallet.listCollapsed}
                      disabled={setListCollapsed.isPending}
                      onClick={(e) => {
                        e.stopPropagation()
                        setListCollapsed.mutate({ id: wallet.id, collapsed: !wallet.listCollapsed })
                      }}
                    >
                      {wallet.listCollapsed ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
                    </Button>
                  </div>
                )}
              </div>

              {/* Pocket sub-accounts */}
              <div className="ml-3 pl-4 border-l-2 border-border/40 space-y-2">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {t('pockets.subAccountsCount', { count: walletPockets.length })}
                </p>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {walletPockets.map((pocket) => (
                    <PocketCard
                      key={pocket.id}
                      account={pocket}
                      onClick={() => navigate(`/accounts/${pocket.id}`)}
                    />
                  ))}
                </div>
              </div>
            </div>
          ))}

          {/* Standalone accounts — normal responsive grid */}
          {displayStandalone.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {displayStandalone.map((account, sIndex) => (
                <div key={account.id} className={cn('relative group', reorderActive && account.listCollapsed && 'opacity-60')}>
                  <AccountCard
                    account={account}
                    closed={closedPeerLoanIds.has(account.id)}
                    onClick={() => navigate(`/accounts/${account.id}`)}
                  />
                  {reorderActive && (
                    <div className={CARD_ACTIONS_CLASS}>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-7"
                        aria-label={t('accounts.moveUp')}
                        disabled={sIndex === 0 || setDisplayOrder.isPending}
                        onClick={(e) => {
                          e.stopPropagation()
                          moveStandalone(sIndex, -1)
                        }}
                      >
                        <ChevronUp className="size-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-7"
                        aria-label={t('accounts.moveDown')}
                        disabled={sIndex === displayStandalone.length - 1 || setDisplayOrder.isPending}
                        onClick={(e) => {
                          e.stopPropagation()
                          moveStandalone(sIndex, 1)
                        }}
                      >
                        <ChevronDown className="size-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-7"
                        aria-label={t(account.listCollapsed ? 'accounts.expandAccount' : 'accounts.collapseAccount')}
                        aria-pressed={account.listCollapsed}
                        disabled={setListCollapsed.isPending}
                        onClick={(e) => {
                          e.stopPropagation()
                          setListCollapsed.mutate({ id: account.id, collapsed: !account.listCollapsed })
                        }}
                      >
                        {account.listCollapsed ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
                      </Button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {/* Collapsed recap: hidden cards stay counted in every total and chart */}
          {filter === 'ALL' && !reorderActive && collapsedCount > 0 && (
            <Card>
              <CardContent className="flex flex-wrap items-center gap-x-3 gap-y-1 py-3 text-sm">
                <EyeOff className="size-4 text-muted-foreground" />
                <span className="font-medium">
                  {t('accounts.collapsedRecap', { count: collapsedCount })}
                </span>
                <CurrencyDisplay value={collapsedTotal} className="font-semibold" />
                <span className="text-muted-foreground">{t('accounts.collapsedHint')}</span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="ml-auto"
                  onClick={() => setReorderMode(true)}
                >
                  {t('accounts.manageCollapsed')}
                </Button>
              </CardContent>
            </Card>
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
      {showScpiModal && (
        <AddScpiModal open onOpenChange={setShowScpiModal} />
      )}


      <AddAccountModal
        open={showCreateModal}
        onOpenChange={setShowCreateModal}
        defaultType={createType}
        onCreated={(created) => {
          if (created.type === 'PEER_LOAN') {
            navigate(`/accounts/${created.id}`)
          }
        }}
      />
    </div>
  )
}
