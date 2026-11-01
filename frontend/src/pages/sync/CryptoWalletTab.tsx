import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  useCryptoWallets,
  useRemoveCryptoWallet,
  useSyncCryptoWallet,
} from '@/features/sync/hooks'
import { formatDate } from '@/lib/utils'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { CryptoWalletForm } from '@/components/sync/CryptoWalletForm'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-react'
import type { ChainType } from '@/types/api'
import { extractErrorMessage, formatApiError } from '@/lib/errors'

const CHAIN_COLORS: Record<ChainType, string> = {
  BITCOIN: 'bg-orange-500/15 text-orange-600 dark:text-orange-400',
  EVM: 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
  SOLANA: 'bg-purple-500/15 text-purple-600 dark:text-purple-400',
}

function truncateAddress(address: string): string {
  if (address.length <= 12) return address
  return `${address.slice(0, 8)}...${address.slice(-4)}`
}

export function CryptoWalletTab() {
  const { t } = useTranslation()
  const { data: wallets, isLoading, error, refetch } = useCryptoWallets()
  const syncMutation = useSyncCryptoWallet()
  const removeMutation = useRemoveCryptoWallet()

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
                <Skeleton className="h-4 w-36" />
                <Skeleton className="h-3 w-52" />
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
      <ErrorState messageOnly message={extractErrorMessage(error)} onRetry={() => refetch()} />
    )
  }

  return (
    <div className="space-y-4">
      {/* Add wallet */}
      {!showAddForm ? (
        <Button onClick={() => setShowAddForm(true)}>
          <Plus />
          {t('sync.wallets.add')}
        </Button>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 p-4">
            <CryptoWalletForm
              variant="page"
              onSuccess={() => setShowAddForm(false)}
              onCancel={() => setShowAddForm(false)}
            />
          </CardContent>
        </Card>
      )}

      {/* Wallet list */}
      {!wallets || wallets.length === 0 ? (
        <EmptyState
          title={t('sync.wallets.noWallets')}
          action={
            showAddForm
              ? undefined
              : {
                  label: t('sync.wallets.add'),
                  onClick: () => setShowAddForm(true),
                }
          }
        />
      ) : (
        <div className="space-y-3">
          {wallets.map(wallet => (
            <Card key={wallet.id} size="sm">
              <CardContent className="space-y-2 p-4">
                <div className="flex items-center justify-between">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      {wallet.label && (
                        <span className="font-medium">{wallet.label}</span>
                      )}
                      <Badge className={CHAIN_COLORS[wallet.chain]}>
                        {wallet.chain}
                      </Badge>
                    </div>
                    <p className="font-mono text-xs text-muted-foreground">
                      {truncateAddress(wallet.address)}
                    </p>
                    {wallet.lastSyncedAt && (
                      <p className="text-xs text-muted-foreground">
                        {t('common.labelColon', { label: t('sync.wallets.lastSync') })} {formatDate(wallet.lastSyncedAt)}
                      </p>
                    )}
                  </div>

                  <div className="flex gap-1">
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => syncMutation.mutate(wallet.id)}
                      // Disables EVERY row while any sync is in flight, deliberately. One
                      // shared mutation drives all rows, so starting a second sync before
                      // the first settles overwrites `variables` -- and the error block
                      // below keys on it, so wallet A's failure would surface under wallet
                      // B. Scoping this per-row reads tidier and reintroduces that bug.
                      disabled={syncMutation.isPending}
                    >
                      <RefreshCw />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => setRemovingId(wallet.id)}
                    >
                      <Trash2 />
                    </Button>
                  </div>
                </div>

                {/* Both conditions are required: `variables` holds the last-synced id and
                    survives until reset, so isError alone would mark every row. */}
                {syncMutation.isError && syncMutation.variables === wallet.id && (
                  <p role="alert" className="text-sm text-destructive">
                    {formatApiError(syncMutation.error, t, 'sync.wallets.syncError')}
                  </p>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Remove confirmation */}
      <ConfirmDialog
        open={removingId != null}
        // ConfirmDialog renders `error` and stays open; per its contract the parent owns
        // clearing it, so reset on close or a stale failure greets the next deletion.
        onOpenChange={open => {
          if (!open) {
            setRemovingId(null)
            removeMutation.reset()
          }
        }}
        title={t('sync.wallets.remove')}
        description={t('sync.wallets.removeConfirm')}
        onConfirm={handleRemove}
        loading={removeMutation.isPending}
        error={
          removeMutation.isError
            ? formatApiError(removeMutation.error, t, 'sync.wallets.removeError')
            : undefined
        }
      />
    </div>
  )
}
