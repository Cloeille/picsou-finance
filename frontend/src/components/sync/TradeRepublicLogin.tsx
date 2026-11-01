import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { InputOTP, InputOTPGroup, InputOTPSlot } from '@/components/ui/input-otp'
import { MaskedOTPSlot } from '@/components/sync/MaskedOTPSlot'
import { AlertTriangle, Loader2, Lock, ShieldCheck, Smartphone } from 'lucide-react'
import { useInitiateTrAuth, useCompleteTrAuth } from '@/features/sync/hooks'
import { formatTrAuthError } from '@/lib/errors'
import { TR_VERIFICATION_CODE_LENGTH } from '@/lib/constants'

type TrAuthState = 'IDLE' | 'AWAITING_TAN'

interface TradeRepublicLoginProps {
  /** Called once the TAN is accepted. */
  onConnected?: () => void
  /** `page`: Sync tab (cards, plain inputs). `modal`: add-account wizard (masked OTP PIN, spinners). */
  variant: 'page' | 'modal'
}

export function TradeRepublicLogin({ onConnected, variant }: TradeRepublicLoginProps) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  const [authState, setAuthState] = useState<TrAuthState>('IDLE')
  const [phone, setPhone] = useState('')
  const [pin, setPin] = useState('')
  const [tan, setTan] = useState('')
  const [processId, setProcessId] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const initiateMutation = useInitiateTrAuth()
  const completeMutation = useCompleteTrAuth()

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    if (pin.length === 0) return
    initiateMutation.mutate(
      { phoneNumber: phone, pin },
      {
        onSuccess: (data) => {
          setProcessId(data.processId)
          setTan('')
          setAuthState('AWAITING_TAN')
          setErrorMsg(null)
        },
        onError: (err: unknown) => {
          setErrorMsg(formatTrAuthError(err, t))
          setProcessId(null)
          setTan('')
          setAuthState('IDLE')
        },
      },
    )
  }

  function handleTan(e: React.FormEvent) {
    e.preventDefault()
    if (!processId || tan.length !== TR_VERIFICATION_CODE_LENGTH) return
    completeMutation.mutate(
      { processId, tan },
      {
        onSuccess: () => {
          setTan('')
          setPhone('')
          setPin('')
          setProcessId(null)
          setErrorMsg(null)
          setAuthState('IDLE')
          onConnected?.()
        },
        onError: (err: unknown) => {
          setErrorMsg(formatTrAuthError(err, t))
          setTan('')
          setAuthState('AWAITING_TAN')
        },
      },
    )
  }

  // Only dismiss the banner: the mutations' onError handlers already reset
  // authState/processId appropriately per failure type. Resetting to IDLE here
  // would throw away a still-valid TAN session after a completion failure.
  function dismissError() {
    setErrorMsg(null)
  }

  const phoneField = (
    <div className="space-y-2">
      <Label htmlFor="tr-phone">
        <Smartphone className="size-4 inline-block mr-1" />
        {t('sync.tr.phone')}
      </Label>
      <Input
        id="tr-phone"
        type="tel"
        value={phone}
        onChange={(e) => setPhone(e.target.value)}
        required
        placeholder="+49..."
        autoFocus={modal}
      />
    </div>
  )

  const pinField = modal ? (
    <div className="space-y-2">
      <Label htmlFor="tr-pin">
        <ShieldCheck className="size-4 inline-block mr-1" />
        {t('sync.tr.pin')}
      </Label>
      <InputOTP maxLength={4} value={pin} onChange={setPin} autoFocus>
        <InputOTPGroup>
          {[0, 1, 2, 3].map((i) => (
            <MaskedOTPSlot key={i} index={i} />
          ))}
        </InputOTPGroup>
      </InputOTP>
    </div>
  ) : (
    <div className="space-y-2">
      <Label htmlFor="tr-pin">
        <Lock className="size-4 inline-block mr-1" />
        {t('sync.tr.pin')}
      </Label>
      <Input id="tr-pin" type="password" value={pin} onChange={(e) => setPin(e.target.value)} required />
    </div>
  )

  const tanField = modal ? (
    <div className="space-y-2">
      <Label htmlFor="tr-tan">
        <ShieldCheck className="size-4 inline-block mr-1" />
        {t('sync.tr.tan')}
      </Label>
      <InputOTP
        id="tr-tan"
        maxLength={TR_VERIFICATION_CODE_LENGTH}
        value={tan}
        onChange={(value) => setTan(value.replace(/\D/g, '').slice(0, TR_VERIFICATION_CODE_LENGTH))}
        autoFocus
      >
        <InputOTPGroup>
          {[0, 1, 2, 3].map((i) => (
            <InputOTPSlot key={i} index={i} />
          ))}
        </InputOTPGroup>
      </InputOTP>
    </div>
  ) : (
    <div className="space-y-2">
      <Label htmlFor="tr-tan">
        <ShieldCheck className="size-4 inline-block mr-1" />
        {t('sync.tr.tan')}
      </Label>
      <Input
        id="tr-tan"
        value={tan}
        onChange={(e) => setTan(e.target.value.replace(/\D/g, '').slice(0, TR_VERIFICATION_CODE_LENGTH))}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={TR_VERIFICATION_CODE_LENGTH}
        required
        autoFocus
      />
    </div>
  )

  const initiateSubmit = (
    <Button type="submit" disabled={initiateMutation.isPending} className="w-full">
      {modal && initiateMutation.isPending && <Loader2 className="size-4 animate-spin" />}
      {t('sync.tr.connect')}
    </Button>
  )
  const tanSubmit = (
    <Button
      type="submit"
      disabled={completeMutation.isPending || tan.length !== TR_VERIFICATION_CODE_LENGTH}
      className="w-full"
    >
      {modal && completeMutation.isPending && <Loader2 className="size-4 animate-spin" />}
      {t('sync.tr.connect')}
    </Button>
  )

  const errorBanner = errorMsg && (modal ? (
    <div className="flex flex-col gap-3 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
      <span className="leading-6">{errorMsg}</span>
      <Button
        variant="ghost"
        size="sm"
        className="self-end px-4 text-sm text-destructive hover:bg-destructive/10 hover:text-destructive"
        onClick={dismissError}
      >
        {t('sync.banks.retry')}
      </Button>
    </div>
  ) : (
    <Card size="sm" className="border-destructive/30">
      <CardContent className="py-4">
        <div className="flex items-center gap-3">
          <AlertTriangle className="size-5 text-destructive shrink-0" />
          <p className="text-sm text-destructive flex-1">{errorMsg}</p>
          <Button size="sm" variant="outline" onClick={dismissError}>
            {t('sync.banks.retry')}
          </Button>
        </div>
      </CardContent>
    </Card>
  ))

  const form = authState === 'AWAITING_TAN' ? (
    <form key="tan" onSubmit={handleTan} className="space-y-4">
      {modal ? (
        <>
          {tanField}
          {tanSubmit}
        </>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 py-4">
            {tanField}
            {tanSubmit}
          </CardContent>
        </Card>
      )}
    </form>
  ) : (
    <form key="initiate" onSubmit={handleInitiate} className="space-y-4">
      {modal ? (
        <>
          {phoneField}
          {pinField}
          {initiateSubmit}
        </>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 py-4">
            {phoneField}
            {pinField}
            {initiateSubmit}
          </CardContent>
        </Card>
      )}
    </form>
  )

  if (modal) {
    return (
      <div className="space-y-4">
        {errorBanner}
        {form}
      </div>
    )
  }
  return (
    <>
      {errorBanner}
      {form}
    </>
  )
}
