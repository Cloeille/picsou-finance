import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2, Wallet } from 'lucide-react'
import type { ChainType } from '@/types/api'
import { SUPPORTED_CHAINS } from '@/types/api'
import { useAddCryptoWallet } from '@/features/sync/hooks'
import { formatApiError, getErrorDetail } from '@/lib/errors'

interface CryptoWalletFormProps {
  onSuccess: () => void
  /** Cancel button beside Track; only the `page` variant renders it. */
  onCancel?: () => void
  /** `page`: Track + Cancel row, inline alert read from the mutation. `modal`: full-width Track with spinner, dismissible banner. */
  variant: 'page' | 'modal'
}

export function CryptoWalletForm({ onSuccess, onCancel, variant }: CryptoWalletFormProps) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  const [chain, setChain] = useState<ChainType>('EVM')
  const [address, setAddress] = useState('')
  const [label, setLabel] = useState('')
  const [error, setError] = useState<string | null>(null)

  const addMutation = useAddCryptoWallet()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    addMutation.mutate(
      { chain, address, label: label || undefined },
      {
        onSuccess,
        onError: (err: unknown) => {
          setError(getErrorDetail(err) || (err as { message?: string })?.message || t('sync.wallets.connectError'))
        },
      },
    )
  }

  return (
    <>
      {modal && error && (
        <AlertBanner onDismiss={() => setError(null)}>{error}</AlertBanner>
      )}
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label>{t('sync.wallets.chain')}</Label>
          <div className="flex gap-2">
            {SUPPORTED_CHAINS.map((c) => (
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
            onChange={(e) => setAddress(e.target.value)}
            placeholder={t('sync.wallets.address')}
            required
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="wallet-label">{t('sync.wallets.label')}</Label>
          <Input
            id="wallet-label"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder={t('sync.wallets.label')}
          />
        </div>

        {!modal && addMutation.isError && (
          <p role="alert" className="text-sm text-destructive">
            {formatApiError(addMutation.error, t, 'sync.wallets.connectError')}
          </p>
        )}

        {modal ? (
          <Button type="submit" disabled={addMutation.isPending} className="w-full">
            {addMutation.isPending && <Loader2 className="size-4 animate-spin" />}
            <Wallet className="size-4" />
            {t('sync.wallets.track')}
          </Button>
        ) : (
          <div className="flex gap-2">
            <Button type="submit" disabled={addMutation.isPending}>
              <Wallet />
              {t('sync.wallets.track')}
            </Button>
            <Button type="button" variant="outline" onClick={onCancel}>
              {t('common.cancel')}
            </Button>
          </div>
        )}
      </form>
    </>
  )
}
