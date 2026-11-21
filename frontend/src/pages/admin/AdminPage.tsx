import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { PageHeader } from '@/components/shared/PageHeader'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { ErrorState } from '@/components/shared/ErrorState'
import { useAdminSettings } from '@/features/admin/hooks'
import { cn } from '@/lib/utils'
import { SecuritySection } from './sections/SecuritySection'
import { EnableBankingSection } from './sections/EnableBankingSection'
import { AiCategorizationSection } from './sections/AiCategorizationSection'
import { IntegrationsSection } from './sections/IntegrationsSection'
import { MembersSection } from './sections/MembersSection'
import { AiActivitySection } from './sections/AiActivitySection'
import { TelemetrySection } from './sections/TelemetrySection'
import { EnableBankingDebugSection } from './sections/EnableBankingDebugSection'

type AdminTab = 'members' | 'integrations' | 'ai' | 'system'

const VALID_TABS: AdminTab[] = ['members', 'integrations', 'ai', 'system']

function resolveTab(raw: string | null): AdminTab {
  return raw && VALID_TABS.includes(raw as AdminTab) ? (raw as AdminTab) : 'members'
}

export function AdminPage() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = resolveTab(searchParams.get('tab'))
  const { data, isLoading, isError, refetch } = useAdminSettings()

  const setActiveTab = (tab: AdminTab) => {
    setSearchParams(tab === 'members' ? {} : { tab }, { replace: true })
  }

  const tabs: Array<{ id: AdminTab; label: string }> = [
    { id: 'members', label: t('admin.tabs.members') },
    { id: 'integrations', label: t('admin.tabs.integrations') },
    { id: 'ai', label: t('admin.tabs.ai') },
    { id: 'system', label: t('admin.tabs.system') },
  ]

  if (isLoading) return <LoadingSkeleton />

  return (
    <div className="space-y-6">
      <PageHeader title={t('admin.title')} />

      {isError && (
        <ErrorState title={t('common.error')} onRetry={() => void refetch()} />
      )}

      {data && (
        <>
          <div
            role="tablist"
            aria-label={t('admin.title')}
            className="flex w-full flex-wrap items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
          >
            {tabs.map(({ id, label }) => {
              const active = activeTab === id
              return (
                <button
                  key={id}
                  role="tab"
                  type="button"
                  aria-selected={active}
                  onClick={() => setActiveTab(id)}
                  className={cn(
                    'inline-flex h-8 flex-auto items-center justify-center gap-2 rounded-md px-3.5 text-sm font-medium whitespace-nowrap transition-[background-color,color]',
                    active
                      ? 'bg-primary text-primary-foreground'
                      : 'text-muted-foreground hover:text-foreground'
                  )}
                >
                  <span>{label}</span>
                </button>
              )
            })}
          </div>

          {activeTab === 'members' && (
            <div className="space-y-6">
              <MembersSection />
              <SecuritySection settings={data.security} />
            </div>
          )}

          {activeTab === 'integrations' && (
            <div className="space-y-6">
              <IntegrationsSection integrations={data.integrations} />
              <EnableBankingSection settings={data.enableBanking} />
            </div>
          )}

          {activeTab === 'ai' && (
            <div className="space-y-6">
              <AiCategorizationSection settings={data.ai} />
              <AiActivitySection />
            </div>
          )}

          {activeTab === 'system' && (
            <div className="space-y-6">
              <EnableBankingDebugSection />
              <TelemetrySection settings={data.telemetry} />
            </div>
          )}
        </>
      )}
    </div>
  )
}
