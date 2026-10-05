import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDate } from '@/lib/utils'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Plus,
  RefreshCw,
  Trash2,
  Eye,
  EyeOff,
} from 'lucide-react'
import type { ExchangeType } from '@/types/api'
import { SUPPORTED_EXCHANGES, exchangeRequiresApiSecret } from '@/types/api'
import {
  useCryptoExchangeStatuses,
  useAddCryptoExchange,
  useSyncCryptoExchange,
  useRemoveCryptoExchange,
} from '@/features/sync/hooks'
import { extractErrorMessage } from '@/lib/errors'
import { EXCHANGE_API_KEY_MAX_LENGTH, EXCHANGE_API_SECRET_MAX_LENGTH } from '@/lib/constants'

export function CryptoExchangeTab() {
  const { t } = useTranslation()
  const { data: exchanges, isLoading, error, refetch } = useCryptoExchangeStatuses()
  const addMutation = useAddCryptoExchange()
  const syncMutation = useSyncCryptoExchange()
  const removeMutation = useRemoveCryptoExchange()

  const [showAddForm, setShowAddForm] = useState(false)
  const [exchangeType, setExchangeType] = useState<ExchangeType>('BINANCE')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [removingId, setRemovingId] = useState<number | null>(null)
  const [addError, setAddError] = useState<string | null>(null)

  const requiresSecret = exchangeRequiresApiSecret(exchangeType)

  function selectExchange(type: ExchangeType) {
    setExchangeType(type)
    // Drop anything typed under the previous exchange: sending a secret to a single-key exchange
    // is a 400, and it would be entirely self-inflicted.
    setApiSecret('')
    setShowSecret(false)
    setAddError(null)
  }

  function handleAdd(e: React.FormEvent) {
    e.preventDefault()
    setAddError(null)
    addMutation.mutate(
      { type: exchangeType, apiKey, apiSecret: requiresSecret ? apiSecret : undefined },
      {
        // Without this the backend's rejections (a wrong key, a missing or stray secret) leave
        // the form sitting there with no explanation at all.
        // The fallback names only the credentials this exchange actually takes: telling a Meria
        // user to check a secret they were never asked for sends them looking for a field that
        // does not exist.
        onError: (err: unknown) => setAddError(extractErrorMessage(err)
          || t(requiresSecret ? 'sync.exchanges.connectError' : 'sync.exchanges.connectErrorKeyOnly')),
        onSuccess: () => {
          setApiKey('')
          setApiSecret('')
          setShowSecret(false)
          setShowAddForm(false)
        },
      },
    )
  }

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
        <Button onClick={() => { setAddError(null); setShowAddForm(true) }}>
          <Plus />
          {t('sync.exchanges.add')}
        </Button>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 p-4">
            {addError && (
              <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
                {addError}
              </p>
            )}
            <form onSubmit={handleAdd} className="space-y-4">
              <div className="space-y-2">
                <Label>{t('sync.exchanges.type')}</Label>
                <div className="flex flex-wrap gap-2">
                  {SUPPORTED_EXCHANGES.map(exchange => (
                    <Button
                      key={exchange.type}
                      type="button"
                      variant={exchangeType === exchange.type ? 'default' : 'outline'}
                      size="sm"
                      onClick={() => selectExchange(exchange.type)}
                    >
                      {exchange.type}
                    </Button>
                  ))}
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="exchange-api-key">{t('sync.exchanges.apiKey')}</Label>
                <Input
                  id="exchange-api-key"
                  value={apiKey}
                  onChange={e => setApiKey(e.target.value)}
                  placeholder={t('sync.exchanges.apiKey')}
                  required
                  maxLength={EXCHANGE_API_KEY_MAX_LENGTH}
                />
                {!requiresSecret && (
                  <p className="text-xs text-muted-foreground">{t('sync.exchanges.apiKeyOnly')}</p>
                )}
              </div>

              {requiresSecret && (
                <div className="space-y-2">
                  <Label htmlFor="exchange-api-secret">{t('sync.exchanges.apiSecret')}</Label>
                  <div className="relative">
                    <Input
                      id="exchange-api-secret"
                      type={showSecret ? 'text' : 'password'}
                      value={apiSecret}
                      onChange={e => setApiSecret(e.target.value)}
                      placeholder={t('sync.exchanges.apiSecret')}
                      required
                      maxLength={EXCHANGE_API_SECRET_MAX_LENGTH}
                      className="pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => setShowSecret(prev => !prev)}
                      aria-label={t(showSecret ? 'sync.exchanges.hideSecret' : 'sync.exchanges.showSecret')}
                      className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    >
                      {showSecret ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                    </button>
                  </div>
                </div>
              )}

              <div className="flex gap-2">
                <Button type="submit" disabled={addMutation.isPending}>
                  {t('sync.exchanges.connect')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setShowAddForm(false)
                    setApiKey('')
                    setApiSecret('')
                    setShowSecret(false)
                    setAddError(null)
                  }}
                >
                  {t('common.cancel')}
                </Button>
              </div>
            </form>
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
                  onClick: () => { setAddError(null); setShowAddForm(true) },
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
                      {exchange.status}
                    </Badge>
                  </div>
                  {exchange.lastSyncedAt && (
                    <p className="text-xs text-muted-foreground">
                      {t('sync.exchanges.lastSync')}: {formatDate(exchange.lastSyncedAt)}
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
