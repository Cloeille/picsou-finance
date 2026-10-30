import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pyramid, SlidersHorizontal } from 'lucide-react'
import { PageHeader } from '@/components/shared/PageHeader'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useAllocationTargets, useDiversification, useWealthPyramid } from '@/features/analysis/hooks'
import { PyramidSection } from './PyramidSection'
import { DiversificationSection } from './DiversificationSection'
import { ProjectionSection } from './ProjectionSection'
import { AllocationTargetsModal } from './AllocationTargetsModal'

export function AnalysisPage() {
  const { t } = useTranslation()
  const pyramid = useWealthPyramid()
  const targets = useAllocationTargets()
  const diversification = useDiversification()
  const [editing, setEditing] = useState(false)

  const hasWealth = (pyramid.data?.totalAssetsEur ?? 0) !== 0

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <PageHeader
        title={t('analysis.title')}
        actions={
          <Button variant="outline" onClick={() => setEditing(true)} disabled={!targets.data}>
            <SlidersHorizontal className="size-4" aria-hidden="true" />
            {t('analysis.editTargets')}
          </Button>
        }
      />

      {pyramid.isLoading && (
        <div className="grid gap-4 lg:grid-cols-3">
          <Skeleton className="h-64 lg:col-span-1" />
          <Skeleton className="h-64 lg:col-span-2" />
        </div>
      )}

      {pyramid.isError && (
        <ErrorState
          title={t('analysis.error')}
          onRetry={() => void pyramid.refetch()}
        />
      )}

      {pyramid.data && !hasWealth && (
        <EmptyState
          className="flex-1"
          icon={<Pyramid className="size-12" />}
          title={t('analysis.empty.title')}
          description={t('analysis.empty.description')}
        />
      )}

      {pyramid.data && hasWealth && (
        <div className="space-y-4">
          <PyramidSection pyramid={pyramid.data} />
          {diversification.data && <DiversificationSection data={diversification.data} />}
          <ProjectionSection />
        </div>
      )}

      <AllocationTargetsModal
        open={editing}
        onOpenChange={setEditing}
        targets={targets.data}
      />
    </div>
  )
}
