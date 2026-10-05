import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  useAddCryptoWallet,
  useCryptoWallets,
  useRemoveCryptoWallet,
  useSyncCryptoWallet,
} from '@/features/sync/hooks'
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
  Wallet,
} from 'lucide-react'
import type { ChainType } from '@/types/api'
import { SUPPORTED_CHAINS } from '@/types/api'
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
  const addMutation = useAddCryptoWallet()
  const syncMutation = useSyncCryptoWallet()
  const removeMutation = useRemoveCryptoWallet()

  const [showAddForm, setShowAddForm] = useState(false)
  const [chain, setChain] = useState<ChainType>('EVM')
  const [address, setAddress] = useState('')
  const [label, setLabel] = useState('')
  const [removingId, setRemovingId] = useState<number | null>(null)

  // addMutation's error survives until the next mutate() settles, so every path that
  // opens or clears the form must reset it too -- otherwise a stale failure renders over
  // a fresh, empty form and is re-announced by role="alert".
  function openAddForm() {
    addMutation.reset()
    setShowAddForm(true)
  }

  function closeAddForm() {
    setShowAddForm(false)
    setAddress('')
    setLabel('')
    addMutation.reset()
  }

  function handleAdd(e: React.FormEvent) {
    e.preventDefault()
    addMutation.mutate(
      { chain, address, label: label || undefined },
      {
        onSuccess: () => {
          setAddress('')
          setLabel('')
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
      {/* Add wallet */}
      {!showAddForm ? (
        <Button onClick={openAddForm}>
          <Plus />
          {t('sync.wallets.add')}
        </Button>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 p-4">
            <form onSubmit={handleAdd} className="space-y-4">
              <div className="space-y-2">
                <Label>{t('sync.wallets.chain')}</Label>
                <div className="flex gap-2">
                  {SUPPORTED_CHAINS.map(c => (
                    <Button
                      key={c}
                      type="button"
                      variant={chain === c ? 'default' : 'outline'}
                      size="sm"
                      onClick={() => setChain(c)}
                    >
                      {c}
                    </Button>
                  ))}
                </div>
                {chain === 'EVM' && (
                  <p className="text-xs text-muted-foreground">{t('sync.wallets.evmHint')}</p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="wallet-address">{t('sync.wallets.address')}</Label>
                <Input
                  id="wallet-address"
                  value={address}
                  onChange={e => setAddress(e.target.value)}
                  placeholder={t('sync.wallets.address')}
                  required
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="wallet-label">{t('sync.wallets.label')}</Label>
                <Input
                  id="wallet-label"
                  value={label}
                  onChange={e => setLabel(e.target.value)}
                  placeholder={t('sync.wallets.label')}
                />
              </div>

              {addMutation.isError && (
                <p role="alert" className="text-sm text-destructive">
                  {formatApiError(addMutation.error, t, 'sync.wallets.connectError')}
                </p>
              )}

              <div className="flex gap-2">
                <Button type="submit" disabled={addMutation.isPending}>
                  <Wallet />
                  {t('sync.wallets.track')}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={closeAddForm}
                >
                  {t('common.cancel')}
                </Button>
              </div>
            </form>
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
                  onClick: openAddForm,
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
                        {t('sync.wallets.lastSync')}: {formatDate(wallet.lastSyncedAt)}
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
