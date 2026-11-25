import { useMemo, useState, type ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { MagnifyingGlass, X } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { IntegrationCard } from './components/IntegrationCard'
import {
  BitcoinLogo,
  BnpParibasLogo,
  BourseDirectLogo,
  BoursoBankLogo,
  EnableBankingLogo,
  FinaryLogo,
  FortuneoLogo,
  LaBanquePostaleLogo,
  RevolutLogo,
  TradeRepublicLogo,
} from './components/IntegrationLogos'
import { useSetupFlowStore, type IntegrationKey } from '@/stores/setup-flow-store'

type CategoryKey = 'all' | 'banks' | 'brokerage' | 'wealth'

interface CatalogEntry {
  key: IntegrationKey
  category: Exclude<CategoryKey, 'all'>
  icon: ComponentType<{ className?: string }>
  route: string
  keywords: string
}

/**
 * Ordered catalog — consumed here and later in Settings → Integrations.
 * The order also drives the sub-step navigation: after "Continue" we route
 * to the first *selected* integration in this exact sequence, so the user
 * always gets a predictable path regardless of tick order.
 */
const CATALOG: CatalogEntry[] = [
  {
    key: 'enablebanking',
    category: 'banks',
    icon: EnableBankingLogo,
    route: '/setup/integrations/enablebanking',
    keywords: 'psd2 banque europe open banking',
  },
  {
    key: 'bnp',
    category: 'banks',
    icon: BnpParibasLogo,
    route: '/setup/integrations/bnp',
    keywords: 'bnp paribas hello bank banque pea cto livret assurance vie pret',
  },
  {
    key: 'boursobank',
    category: 'banks',
    icon: BoursoBankLogo,
    route: '/setup/integrations/boursobank',
    keywords: 'boursorama banque pea cto livret',
  },
  {
    key: 'fortuneo',
    category: 'banks',
    icon: FortuneoLogo,
    route: '/setup/integrations/fortuneo',
    keywords: 'banque pea cto compte courant',
  },
  {
    key: 'lbp',
    category: 'banks',
    icon: LaBanquePostaleLogo,
    route: '/setup/integrations/lbp',
    keywords: 'la banque postale livret a lep ldds',
  },
  {
    key: 'revolut',
    category: 'banks',
    icon: RevolutLogo,
    route: '/setup/integrations/revolut',
    keywords: 'neobanque carte pockets vaults',
  },
  {
    key: 'boursedirect',
    category: 'brokerage',
    icon: BourseDirectLogo,
    route: '/setup/integrations/boursedirect',
    keywords: 'courtier bourse pea cto actions etf',
  },
  {
    key: 'traderepublic',
    category: 'brokerage',
    icon: TradeRepublicLogo,
    route: '/setup/integrations/traderepublic',
    keywords: 'courtier bourse etf actions epargne',
  },
  {
    key: 'finary',
    category: 'wealth',
    icon: FinaryLogo,
    route: '/setup/integrations/finary',
    keywords: 'agregateur patrimoine holdings',
  },
  {
    key: 'crypto',
    category: 'wealth',
    icon: BitcoinLogo,
    route: '/setup/integrations/crypto',
    keywords: 'binance meria okx bitcoin ethereum exchange api',
  },
]

const CATEGORIES: CategoryKey[] = ['all', 'banks', 'brokerage', 'wealth']

function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim()
}

export function SetupStepIntegrations() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const toggle = useSetupFlowStore((s) => s.toggleIntegration)

  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<CategoryKey>('all')

  const count = selected.length

  const filteredCatalog = useMemo(() => {
    const q = normalize(query)
    return CATALOG.filter((item) => {
      if (category !== 'all' && item.category !== category) return false
      if (!q) return true
      const haystack = normalize(
        `${t(`setup.integrations.cards.${item.key}.title`)} ${t(`setup.integrations.cards.${item.key}.description`)} ${item.keywords}`
      )
      return haystack.includes(q)
    })
  }, [category, query, t])

  const selectedCountByCategory = useMemo(() => {
    const counts: Record<CategoryKey, number> = {
      all: selected.length,
      banks: 0,
      brokerage: 0,
      wealth: 0,
    }
    for (const item of CATALOG) {
      if (selected.includes(item.key)) {
        counts[item.category] += 1
      }
    }
    return counts
  }, [selected])

  /**
   * Determine which screen to push to next. The `CATALOG` array is the
   * canonical ordering — we find the first selected entry in that order,
   * not in the toggle-click order. If nothing selected, we hop straight
   * to the Done screen with nothing flipped server-side.
   */
  const handleContinue = () => {
    const firstSelected = CATALOG.find((c) => selected.includes(c.key))
    if (firstSelected) {
      navigate(firstSelected.route)
    } else {
      navigate('/setup/done')
    }
  }

  const hintKey =
    count === 0
      ? 'setup.integrations.hint.none'
      : count === 1
        ? 'setup.integrations.hint.some'
        : 'setup.integrations.hint.some_other'

  return (
    <div className="space-y-6">
      <div className="space-y-2 text-center">
        <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
          {t('setup.integrations.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.integrations.subtitle')}
        </p>
      </div>

      <div className="space-y-3">
        <div className="relative">
          <MagnifyingGlass
            className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('setup.integrations.searchPlaceholder')}
            aria-label={t('setup.integrations.searchPlaceholder')}
            className="h-10 rounded-lg pl-10 pr-10"
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
          role="tablist"
          aria-label={t('setup.integrations.categoriesLabel')}
          className="flex w-full flex-wrap items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
        >
          {CATEGORIES.map((cat) => {
            const active = category === cat
            const badgeCount = cat === 'all' ? 0 : selectedCountByCategory[cat]
            return (
              <button
                key={cat}
                role="tab"
                type="button"
                aria-selected={active}
                onClick={() => setCategory(cat)}
                className={cn(
                  'inline-flex h-8 flex-auto items-center justify-center gap-1.5 rounded-md px-3 text-xs font-medium whitespace-nowrap transition-[background-color,color] sm:text-sm',
                  active
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                <span>{t(`setup.integrations.categories.${cat}`)}</span>
                {badgeCount > 0 && (
                  <span
                    className={cn(
                      'inline-flex h-5 min-w-5 items-center justify-center rounded px-1 text-xs font-semibold tabular-nums',
                      active
                        ? 'bg-primary-foreground/20 text-primary-foreground'
                        : 'bg-muted text-foreground'
                    )}
                  >
                    {badgeCount}
                  </span>
                )}
              </button>
            )
          })}
        </div>
      </div>

      {filteredCatalog.length > 0 ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {filteredCatalog.map(({ key, icon }) => (
            <IntegrationCard
              key={key}
              icon={icon}
              title={t(`setup.integrations.cards.${key}.title`)}
              description={t(`setup.integrations.cards.${key}.description`)}
              checked={selected.includes(key)}
              onToggle={() => toggle(key)}
            />
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border/70 bg-card/50 px-4 py-8 text-center">
          <p className="text-sm text-muted-foreground">
            {t('setup.integrations.empty')}
          </p>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setQuery('')
              setCategory('all')
            }}
            className="h-9 rounded-lg px-4 text-xs"
          >
            {t('setup.integrations.resetFilter')}
          </Button>
        </div>
      )}

      <div className="space-y-3 pt-1">
        <p
          className="text-center text-sm text-muted-foreground"
          aria-live="polite"
        >
          {t(hintKey, { count })}
        </p>

        <Button
          onClick={handleContinue}
          className="w-full rounded-lg"
        >
          {count === 0 ? t('setup.integrations.skipAll') : t('setup.integrations.continue')}
        </Button>
      </div>
    </div>
  )
}
