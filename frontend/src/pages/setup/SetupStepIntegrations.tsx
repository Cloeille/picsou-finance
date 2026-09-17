import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import type { ComponentType } from 'react'
import {
  Boxes,
  Landmark,
  LineChart,
  BriefcaseBusiness,
  PiggyBank,
  Banknote,
  Bitcoin,
  CreditCard,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { IntegrationCard } from './components/IntegrationCard'
import { useSetupFlowStore, type IntegrationKey } from '@/stores/setup-flow-store'

/**
 * Ordered catalog — consumed here and later in Settings → Integrations.
 * The order also drives the sub-step navigation: after "Continue" we route
 * to the first *selected* integration in this exact sequence, so the user
 * always gets a predictable path regardless of tick order.
 */
const CATALOG: Array<{
  key: IntegrationKey
  icon: ComponentType<{ className?: string }>
  route: string
}> = [
  { key: 'enablebanking', icon: Landmark, route: '/setup/integrations/enablebanking' },
  { key: 'boursobank', icon: Boxes, route: '/setup/integrations/boursobank' },
  { key: 'boursedirect', icon: BriefcaseBusiness, route: '/setup/integrations/boursedirect' },
  { key: 'fortuneo', icon: Banknote, route: '/setup/integrations/fortuneo' },
  { key: 'traderepublic', icon: LineChart, route: '/setup/integrations/traderepublic' },
  { key: 'revolut', icon: CreditCard, route: '/setup/integrations/revolut' },
  { key: 'finary', icon: PiggyBank, route: '/setup/integrations/finary' },
  { key: 'crypto', icon: Bitcoin, route: '/setup/integrations/crypto' },
]

export function SetupStepIntegrations() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const toggle = useSetupFlowStore((s) => s.toggleIntegration)

  const count = selected.length

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
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <h1 className="text-3xl sm:text-4xl font-semibold tracking-tight">
          {t('setup.integrations.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.integrations.subtitle')}
        </p>
      </div>

      <div className="grid gap-3 sm:gap-4">
        {CATALOG.map(({ key, icon }) => (
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

      <p
        className="text-center text-xs text-muted-foreground"
        aria-live="polite"
      >
        {t(hintKey, { count })}
      </p>

      <div className="flex flex-col gap-3 sm:flex-row sm:justify-end">
        {count === 0 ? (
          <Button
            onClick={handleContinue}
            className="w-full rounded-full"
          >
            {t('setup.integrations.skipAll')}
          </Button>
        ) : (
          <Button
            onClick={handleContinue}
            className="w-full rounded-full"
          >
            {t('setup.integrations.continue')}
          </Button>
        )}
      </div>
    </div>
  )
}
