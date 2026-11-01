import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Eye, EyeOff, Loader2 } from 'lucide-react'
import type { ExchangeType } from '@/types/api'
import { SUPPORTED_EXCHANGES, exchangeRequiresApiSecret } from '@/types/api'
import { useAddCryptoExchange } from '@/features/sync/hooks'
import { extractErrorMessage, getErrorDetail } from '@/lib/errors'
import { EXCHANGE_API_KEY_MAX_LENGTH, EXCHANGE_API_SECRET_MAX_LENGTH } from '@/lib/constants'

interface CryptoExchangeFormProps {
  onSuccess: () => void
  /** Cancel button beside Connect; only the `page` variant renders it. */
  onCancel?: () => void
  /** `page`: Connect + Cancel row, alert paragraph cleared on exchange change. `modal`: full-width Connect with spinner, dismissible banner. */
  variant: 'page' | 'modal'
}

export function CryptoExchangeForm({ onSuccess, onCancel, variant }: CryptoExchangeFormProps) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  const [exchangeType, setExchangeType] = useState<ExchangeType>('BINANCE')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const addMutation = useAddCryptoExchange()
  const requiresSecret = exchangeRequiresApiSecret(exchangeType)

  function selectExchange(type: ExchangeType) {
    setExchangeType(type)
    // Drop anything typed under the previous exchange: sending a secret to a single-key exchange
    // is a 400, and it would be entirely self-inflicted.
    setApiSecret('')
    setShowSecret(false)
    if (!modal) setError(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    addMutation.mutate(
      { type: exchangeType, apiKey, apiSecret: requiresSecret ? apiSecret : undefined },
      {
        onSuccess,
        // Without this the backend's rejections (a wrong key, a missing or stray secret) leave
        // the form sitting there with no explanation at all.
        // The fallback names only the credentials this exchange actually takes: telling a Meria
        // user to check a secret they were never asked for sends them looking for a field that
        // does not exist.
        onError: (err: unknown) => {
          const fallback = t(requiresSecret ? 'sync.exchanges.connectError' : 'sync.exchanges.connectErrorKeyOnly')
          setError(
            modal
              ? getErrorDetail(err) || (err as { message?: string })?.message || fallback
              : extractErrorMessage(err) || fallback,
          )
        },
      },
    )
  }

  return (
    <>
      {error && (modal ? (
        <AlertBanner onDismiss={() => setError(null)}>{error}</AlertBanner>
      ) : (
        <AlertBanner variant="plain" role="alert">{error}</AlertBanner>
      ))}
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <Label>{t('sync.exchanges.type')}</Label>
          {/* wrap: these buttons are wide (px-8) and there is no room for three in a row on a
              narrow viewport — without it the row stretches the whole form past the dialog. */}
          <div className="flex flex-wrap gap-2">
            {SUPPORTED_EXCHANGES.map((exchange) => (
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
            onChange={(e) => setApiKey(e.target.value)}
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
                onChange={(e) => setApiSecret(e.target.value)}
                placeholder={t('sync.exchanges.apiSecret')}
                required
                maxLength={EXCHANGE_API_SECRET_MAX_LENGTH}
                className="pr-10"
              />
              <button
                type="button"
                onClick={() => setShowSecret((p) => !p)}
                aria-label={t(showSecret ? 'sync.exchanges.hideSecret' : 'sync.exchanges.showSecret')}
                className="absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {showSecret ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
          </div>
        )}

        {!modal ? (
          <div className="flex gap-2">
            <Button type="submit" disabled={addMutation.isPending}>
              {t('sync.exchanges.connect')}
            </Button>
            <Button type="button" variant="outline" onClick={onCancel}>
              {t('common.cancel')}
            </Button>
          </div>
        ) : (
          <Button type="submit" disabled={addMutation.isPending} className="w-full">
            {addMutation.isPending && <Loader2 className="size-4 animate-spin" />}
            {t('sync.exchanges.connect')}
          </Button>
        )}
      </form>
    </>
  )
}
