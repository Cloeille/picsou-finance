import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDate } from '@/lib/utils'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { CryptoExchangeForm } from '@/components/sync/CryptoExchangeForm'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import {
  useCryptoExchangeStatuses,
  useSyncCryptoExchange,
  useRemoveCryptoExchange,
} from '@/features/sync/hooks'
import { extractErrorMessage } from '@/lib/errors'

export function CryptoExchangeTab() {
  const { t } = useTranslation()
  const { data: exchanges, isLoading, error, refetch } = useCryptoExchangeStatuses()
  const syncMutation = useSyncCryptoExchange()
  const removeMutation = useRemoveCryptoExchange()

  const [showAddForm, setShowAddForm] = useState(false)
  const [removingId, setRemovingId] = useState<number | null>(null)

  function handleRemove() {
    if (removingId == null) return
    removeMutation.mutate(removingId, {
      onSuccess: () => setRemovingId(null),
    })
  }

  if (isLoading) {
    return (
      <div className="space-y-4">
        {Array.from({ length: 2 }).map((_, i) => (
          <Card key={i} size="sm">
            <CardContent className="flex items-center justify-between p-4">
              <div className="space-y-2">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-3 w-40" />
              </div>
              <div className="flex gap-2">
                <Skeleton className="size-8" />
                <Skeleton className="size-8" />
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <p className="text-sm text-muted-foreground">{extractErrorMessage(error)}</p>
        <Button variant="outline" onClick={() => refetch()} className="mt-4">
          {t('common.retry')}
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Add exchange */}
      {!showAddForm ? (
        <Button onClick={() => setShowAddForm(true)}>
          <Plus />
          {t('sync.exchanges.add')}
        </Button>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 p-4">
            <CryptoExchangeForm
              variant="page"
              onSuccess={() => setShowAddForm(false)}
              onCancel={() => setShowAddForm(false)}
            />
          </CardContent>
        </Card>
      )}

      {/* Exchange list */}
      {!exchanges || exchanges.length === 0 ? (
        <EmptyState
          title={t('sync.exchanges.noExchanges')}
          action={
            showAddForm
              ? undefined
              : {
                  label: t('sync.exchanges.add'),
                  onClick: () => setShowAddForm(true),
                }
          }
        />
      ) : (
        <div className="space-y-3">
          {exchanges.map(exchange => (
            <Card key={exchange.id} size="sm">
              <CardContent className="flex items-center justify-between p-4">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{exchange.exchangeType}</span>
                    <Badge variant="secondary">{exchange.exchangeType}</Badge>
                    <Badge
                      variant={exchange.status === 'CONNECTED' ? 'default' : 'destructive'}
                    >
                      {t(`sync.connectionStatus.${exchange.status}`, { defaultValue: exchange.status })}
                    </Badge>
                  </div>
                  {exchange.lastSyncedAt && (
                    <p className="text-xs text-muted-foreground">
                      {t('common.labelColon', { label: t('sync.exchanges.lastSync') })} {formatDate(exchange.lastSyncedAt)}
                    </p>
                  )}
                </div>

                <div className="flex gap-1">
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => syncMutation.mutate(exchange.id)}
                    disabled={syncMutation.isPending}
                  >
                    <RefreshCw />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setRemovingId(exchange.id)}
                  >
                    <Trash2 />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Remove confirmation */}
      <ConfirmDialog
        open={removingId != null}
        onOpenChange={open => !open && setRemovingId(null)}
        title={t('sync.exchanges.remove')}
        description={t('sync.exchanges.removeConfirm')}
        onConfirm={handleRemove}
        loading={removeMutation.isPending}
      />
    </div>
  )
}
