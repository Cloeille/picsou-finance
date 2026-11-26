import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Triangle, SlidersHorizontal } from '@phosphor-icons/react'
import { PageHeader } from '@/components/shared/PageHeader'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { useAllocationTargets, useDiversification, useWealthPyramid } from '@/features/analysis/hooks'
import { PyramidSection } from './PyramidSection'
import { DiversificationSection } from './DiversificationSection'
import { ProjectionSection } from './ProjectionSection'
import {
  AllocationTargetsModal,
  type AllocationTargetsFocusField,
} from './AllocationTargetsModal'

type AnalysisTab = 'pyramid' | 'diversification' | 'projection'

export function AnalysisPage() {
  const { t } = useTranslation()
  const pyramid = useWealthPyramid()
  const targets = useAllocationTargets()
  const diversification = useDiversification()
  const [editing, setEditing] = useState(false)
  const [targetsInitialFocus, setTargetsInitialFocus] = useState<
    AllocationTargetsFocusField | undefined
  >(undefined)
  const [activeTab, setActiveTab] = useState<AnalysisTab>('pyramid')

  function openTargets(initialFocus?: AllocationTargetsFocusField) {
    setTargetsInitialFocus(initialFocus)
    setEditing(true)
  }

  function handleTargetsOpenChange(open: boolean) {
    setEditing(open)
    if (!open) setTargetsInitialFocus(undefined)
  }

  const hasWealth = (pyramid.data?.totalAssetsEur ?? 0) !== 0

  const tabs: Array<{ id: AnalysisTab; label: string }> = [
    { id: 'pyramid', label: t('analysis.tabs.pyramid') },
    { id: 'diversification', label: t('analysis.tabs.diversification') },
    { id: 'projection', label: t('analysis.tabs.projection') },
  ]

  return (
    <div className="flex min-h-0 flex-1 flex-col space-y-6">
      <PageHeader
        title={t('analysis.title')}
        actions={
          <Button variant="outline" onClick={() => openTargets()} disabled={!targets.data}>
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
        <Card>
          <CardContent>
            <EmptyState
              className="py-8"
              icon={<Triangle className="size-10" />}
              title={t('analysis.empty.title')}
              description={t('analysis.empty.description')}
            />
          </CardContent>
        </Card>
      )}

      {pyramid.data && hasWealth && (
        <div className="space-y-6">
          <div
            role="tablist"
            aria-label={t('analysis.title')}
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
                    'inline-flex h-8 flex-auto items-center justify-center rounded-md px-3.5 text-sm font-medium whitespace-nowrap transition-[background-color,color]',
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

          {activeTab === 'pyramid' && (
            <PyramidSection
              pyramid={pyramid.data}
              onConfigureExpenses={targets.data ? () => openTargets('expenses') : undefined}
            />
          )}

          {activeTab === 'diversification' && (
            diversification.isError ? (
              <ErrorState title={t('analysis.error')} onRetry={() => void diversification.refetch()} />
            ) : diversification.isLoading ? (
              <Skeleton className="h-72 w-full" />
            ) : diversification.data ? (
              <DiversificationSection data={diversification.data} />
            ) : null
          )}

          {activeTab === 'projection' && <ProjectionSection />}
        </div>
      )}

      <AllocationTargetsModal
        open={editing}
        onOpenChange={handleTargetsOpenChange}
        targets={targets.data}
        initialFocus={targetsInitialFocus}
      />
    </div>
  )
}
