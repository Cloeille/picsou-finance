import { useTranslation } from 'react-i18next'
import { PageHeader } from '@/components/shared/PageHeader'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { useAdminSettings } from '@/features/admin/hooks'
import { SecuritySection } from './sections/SecuritySection'
import { EnableBankingSection } from './sections/EnableBankingSection'
import { AiCategorizationSection } from './sections/AiCategorizationSection'
import { IntegrationsSection } from './sections/IntegrationsSection'
import { MembersSection } from './sections/MembersSection'
import { AiActivitySection } from './sections/AiActivitySection'
import { EnableBankingDebugSection } from './sections/EnableBankingDebugSection'

export function AdminPage() {
  const { t } = useTranslation()
  const { data, isLoading } = useAdminSettings()

  if (isLoading) return <LoadingSkeleton />

  return (
    <div className="space-y-6">
      <PageHeader title={t('admin.title')} />
      {data && (
        <>
          <MembersSection />
          <SecuritySection settings={data.security} />
          <EnableBankingSection settings={data.enableBanking} />
          <AiCategorizationSection settings={data.ai} />
          <AiActivitySection />
          <EnableBankingDebugSection />
          <IntegrationsSection integrations={data.integrations} />
        </>
      )}
    </div>
  )
}
