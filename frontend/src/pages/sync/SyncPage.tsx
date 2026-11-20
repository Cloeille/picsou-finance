import { useMemo, useState, type ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useSearchParams } from 'react-router'
import {
  Building2,
  Eye,
  Search,
  X,
} from 'lucide-react'
import { PageHeader } from '@/components/shared/PageHeader'
import { ScrollStrip } from '@/components/shared/ScrollStrip'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  ActualBudgetLogo,
  AmexLogo,
  AmundiLogo,
  BanquePopulaireLogo,
  BitcoinLogo,
  BnpParibasLogo,
  BourseDirectLogo,
  BoursoBankLogo,
  CaisseEpargneLogo,
  CicLogo,
  CmesLogo,
  CorumLogo,
  DegiroLogo,
  EnableBankingLogo,
  EthereumLogo,
  FinaryLogo,
  FortuneoLogo,
  HomeBankLogo,
  IbkrLogo,
  LaBanquePostaleLogo,
  NatixisLogo,
  RevolutLogo,
  SimplefinLogo,
  SofidyLogo,
  TradeRepublicLogo,
  WiseLogo,
} from '@/pages/setup/components/IntegrationLogos'
import { BankSyncTab } from './BankSyncTab'
import { CryptoExchangeTab } from './CryptoExchangeTab'
import { CryptoWalletTab } from './CryptoWalletTab'
import { TradeRepublicTab } from './TradeRepublicTab'
import { RevolutTab } from './RevolutTab'
import { IbkrTab } from './IbkrTab'
import { SimplefinTab } from './SimplefinTab'
import { WiseTab } from './WiseTab'
import { FinaryTab } from './FinaryTab'
import { HomeBankTab } from './HomeBankTab'
import { BourseDirectTab } from './BourseDirectTab'
import { AccountsVisibilityTab } from './AccountsVisibilityTab'
import { DegiroTab } from './DegiroTab'
import { AmundiTab } from './AmundiTab'
import { CmesTab } from './CmesTab'
import { NatixisTab } from './NatixisTab'
import { CicTab } from './CicTab'
import { AmexTab } from './AmexTab'
import { CorumTab } from './CorumTab'
import { SofidyTab } from './SofidyTab'
import { LouveTab } from './LouveTab'
import { LOUVE_CALLBACK_PATH } from '@/components/sync/LouvePanel'
import { BoursoTab } from './BoursoTab'
import { CaisseEpargneTab } from './CaisseEpargneTab'
import { BanquePopulaireTab } from './BanquePopulaireTab'
import { LbpTab } from './LbpTab'
import { BnpTab } from './BnpTab'
import { FortuneoTab } from './FortuneoTab'
import { ActualBudgetTab } from './ActualBudgetTab'

type SyncCategory = 'all' | 'banks' | 'investing' | 'cryptoAndWealth' | 'imports'

interface ConnectorDef {
  id: string
  category: Exclude<SyncCategory, 'all'>
  labelKey: string
  icon: ComponentType<{ className?: string }>
  keywords: string
}

const CONNECTORS: ConnectorDef[] = [
  { id: 'banks', category: 'banks', labelKey: 'sync.banks.title', icon: EnableBankingLogo, keywords: 'enable banking psd2 open banking' },
  { id: 'bnp', category: 'banks', labelKey: 'sync.bnp.title', icon: BnpParibasLogo, keywords: 'bnp paribas hello bank' },
  { id: 'bourso', category: 'banks', labelKey: 'sync.bourso.title', icon: BoursoBankLogo, keywords: 'boursobank boursorama' },
  { id: 'fortuneo', category: 'banks', labelKey: 'sync.fortuneo.title', icon: FortuneoLogo, keywords: 'fortuneo pea' },
  { id: 'lbp', category: 'banks', labelKey: 'sync.lbp.title', icon: LaBanquePostaleLogo, keywords: 'la banque postale livret' },
  { id: 'caisse-epargne', category: 'banks', labelKey: 'sync.caisseEpargne.title', icon: CaisseEpargneLogo, keywords: 'caisse epargne' },
  { id: 'banque-populaire', category: 'banks', labelKey: 'sync.banquePopulaire.title', icon: BanquePopulaireLogo, keywords: 'banque populaire' },
  { id: 'cic', category: 'banks', labelKey: 'sync.cic.title', icon: CicLogo, keywords: 'cic credit mutuel' },
  { id: 'revolut', category: 'banks', labelKey: 'sync.revolut.title', icon: RevolutLogo, keywords: 'revolut neobanque' },
  { id: 'amex', category: 'banks', labelKey: 'sync.amex.title', icon: AmexLogo, keywords: 'american express carte' },
  { id: 'wise', category: 'banks', labelKey: 'sync.wise.title', icon: WiseLogo, keywords: 'wise jars transferwise' },
  { id: 'simplefin', category: 'banks', labelKey: 'sync.simplefin.title', icon: SimplefinLogo, keywords: 'simplefin us' },

  { id: 'tr', category: 'investing', labelKey: 'sync.tr.title', icon: TradeRepublicLogo, keywords: 'trade republic etf' },
  { id: 'bourse-direct', category: 'investing', labelKey: 'sync.bourseDirect.title', icon: BourseDirectLogo, keywords: 'bourse direct pea cto' },
  { id: 'degiro', category: 'investing', labelKey: 'sync.degiro.title', icon: DegiroLogo, keywords: 'degiro courtier' },
  { id: 'ibkr', category: 'investing', labelKey: 'sync.ibkr.title', icon: IbkrLogo, keywords: 'interactive brokers flex' },
  { id: 'amundi', category: 'investing', labelKey: 'sync.amundi.title', icon: AmundiLogo, keywords: 'amundi epargne salariale pee per' },
  { id: 'cmes', category: 'investing', labelKey: 'sync.cmes.title', icon: CmesLogo, keywords: 'cmes credit mutuel cic epargne salariale' },
  { id: 'natixis', category: 'investing', labelKey: 'sync.natixis.title', icon: NatixisLogo, keywords: 'natixis interepargne pee per' },

  { id: 'exchanges', category: 'cryptoAndWealth', labelKey: 'sync.exchanges.title', icon: BitcoinLogo, keywords: 'binance meria crypto exchange' },
  { id: 'wallets', category: 'cryptoAndWealth', labelKey: 'sync.wallets.title', icon: EthereumLogo, keywords: 'bitcoin ethereum solana evm wallet' },
  { id: 'corum', category: 'cryptoAndWealth', labelKey: 'sync.corum.title', icon: CorumLogo, keywords: 'corum scpi assurance vie' },
  { id: 'sofidy', category: 'cryptoAndWealth', labelKey: 'sync.sofidy.title', icon: SofidyLogo, keywords: 'sofidy scpi immobilier' },
  { id: 'louve', category: 'cryptoAndWealth', labelKey: 'sync.louve.title', icon: Building2, keywords: 'louve invest scpi immobilier' },
  { id: 'finary', category: 'cryptoAndWealth', labelKey: 'sync.finary.title', icon: FinaryLogo, keywords: 'finary agregateur patrimoine' },

  { id: 'actual', category: 'imports', labelKey: 'sync.actual.title', icon: ActualBudgetLogo, keywords: 'actual budget import sqlite' },
  { id: 'homebank', category: 'imports', labelKey: 'sync.homebank.title', icon: HomeBankLogo, keywords: 'homebank import xhb' },
  { id: 'visibility', category: 'imports', labelKey: 'sync.visibility.title', icon: Eye, keywords: 'visibilite masquer afficher comptes' },
]

const VALID_IDS = new Set(CONNECTORS.map((c) => c.id))
const CATEGORIES: SyncCategory[] = ['all', 'banks', 'investing', 'cryptoAndWealth', 'imports']

function normalize(value: string): string {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim()
}

export function SyncPage() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const initialTab = searchParams.get('tab')
  // Louve Invest's OAuth callback lands on its own path and opens its tab.
  const [tab, setTabState] = useState(() => {
    if (pathname === LOUVE_CALLBACK_PATH) return 'louve'
    return initialTab && VALID_IDS.has(initialTab) ? initialTab : 'banks'
  })
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<SyncCategory>('all')

  const setTab = (next: string) => {
    setTabState(next)
    setSearchParams(next === 'banks' ? {} : { tab: next }, { replace: true })
  }

  const filtered = useMemo(() => {
    const q = normalize(query)
    return CONNECTORS.filter((c) => {
      if (category !== 'all' && c.category !== category) return false
      if (!q) return true
      return normalize(`${t(c.labelKey)} ${c.keywords}`).includes(q)
    })
  }, [category, query, t])

  const renderPanel = () => {
    switch (tab) {
      case 'banks': return <BankSyncTab onOpenWiseJars={() => setTab('wise')} />
      case 'exchanges': return <CryptoExchangeTab />
      case 'wallets': return <CryptoWalletTab />
      case 'tr': return <TradeRepublicTab />
      case 'revolut': return <RevolutTab />
      case 'bourso': return <BoursoTab />
      case 'caisse-epargne': return <CaisseEpargneTab />
      case 'banque-populaire': return <BanquePopulaireTab />
      case 'lbp': return <LbpTab />
      case 'bnp': return <BnpTab />
      case 'bourse-direct': return <BourseDirectTab />
      case 'degiro': return <DegiroTab />
      case 'ibkr': return <IbkrTab />
      case 'simplefin': return <SimplefinTab />
      case 'wise': return <WiseTab />
      case 'amundi': return <AmundiTab />
      case 'cmes': return <CmesTab />
      case 'natixis': return <NatixisTab />
      case 'cic': return <CicTab />
      case 'fortuneo': return <FortuneoTab />
      case 'amex': return <AmexTab />
      case 'corum': return <CorumTab />
      case 'sofidy': return <SofidyTab />
      case 'louve': return <LouveTab />
      case 'actual': return <ActualBudgetTab />
      case 'finary': return <FinaryTab />
      case 'homebank': return <HomeBankTab />
      case 'visibility': return <AccountsVisibilityTab />
      default: return <BankSyncTab onOpenWiseJars={() => setTab('wise')} />
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t('sync.title')} />

      <div className="space-y-3">
        <div className="relative">
          <Search
            className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('sync.searchPlaceholder')}
            aria-label={t('sync.searchPlaceholder')}
            className="pl-10 pr-10"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery('')}
              aria-label={t('setup.integrations.clearSearch')}
              className="absolute right-1 top-1/2 inline-flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          )}
        </div>

        <div
          role="group"
          aria-label={t('sync.categoriesLabel')}
          className="flex w-full flex-wrap items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
        >
          {CATEGORIES.map((cat) => {
            const active = category === cat
            return (
              <button
                key={cat}
                type="button"
                aria-pressed={active}
                onClick={() => setCategory(cat)}
                className={cn(
                  'inline-flex h-8 flex-auto items-center justify-center rounded-md px-3 text-sm font-medium whitespace-nowrap transition-[background-color,color]',
                  active
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                {t(`sync.categories.${cat}`)}
              </button>
            )
          })}
        </div>
      </div>

      {/* Mobile horizontal selector */}
      <div className="lg:hidden">
        <ScrollStrip activeKey={tab}>
          <div role="tablist" aria-label={t('sync.title')} className="flex w-max gap-1.5">
            {filtered.map(({ id, labelKey, icon: Icon }) => {
              const active = tab === id
              return (
                <button
                  key={id}
                  role="tab"
                  type="button"
                  aria-selected={active}
                  onClick={() => setTab(id)}
                  className={cn(
                    'inline-flex h-9 items-center gap-2 rounded-lg border px-3 text-sm font-medium whitespace-nowrap transition-colors',
                    active
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-border/60 bg-card text-muted-foreground hover:text-foreground'
                  )}
                >
                  <Icon className="size-4 shrink-0" />
                  <span>{t(labelKey)}</span>
                </button>
              )
            })}
          </div>
        </ScrollStrip>
      </div>

      <div className="grid gap-6 lg:grid-cols-[248px_minmax(0,1fr)] lg:items-start">
        {/* Desktop left connector list */}
        <div
          role="tablist"
          aria-orientation="vertical"
          aria-label={t('sync.title')}
          className="hidden max-h-[calc(100vh-14rem)] space-y-1 overflow-y-auto rounded-xl border border-border/60 bg-card p-2 lg:block"
        >
          {filtered.length > 0 ? (
            filtered.map(({ id, labelKey, icon: Icon }) => {
              const active = tab === id
              return (
                <button
                  key={id}
                  role="tab"
                  type="button"
                  aria-selected={active}
                  onClick={() => setTab(id)}
                  className={cn(
                    'flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm transition-colors',
                    active
                      ? 'bg-muted font-semibold text-foreground'
                      : 'font-medium text-muted-foreground hover:bg-muted/60 hover:text-foreground'
                  )}
                >
                  <Icon className={cn('size-4 shrink-0', active ? 'text-foreground' : 'text-muted-foreground')} />
                  <span className="truncate">{t(labelKey)}</span>
                </button>
              )
            })
          ) : (
            <div className="space-y-2 p-4 text-center">
              <p className="text-sm text-muted-foreground">{t('setup.integrations.empty')}</p>
              <Button
                type="button"
                variant="outline"
                size="xs"
                onClick={() => {
                  setQuery('')
                  setCategory('all')
                }}
              >
                {t('setup.integrations.resetFilter')}
              </Button>
            </div>
          )}
        </div>

        {/* Active connector panel */}
        <div role="tabpanel" className="min-w-0">
          {renderPanel()}
        </div>
      </div>
    </div>
  )
}