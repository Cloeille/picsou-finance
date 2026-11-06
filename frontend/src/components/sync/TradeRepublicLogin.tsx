import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { InputOTP, InputOTPGroup, InputOTPSlot } from '@/components/ui/input-otp'
import { AlertTriangle, Loader2, Lock, ShieldCheck, Smartphone } from 'lucide-react'
import { useInitiateTrAuth, useCompleteTrAuth, useReconnectTrAuth } from '@/features/sync/hooks'
import { TrAppConfirmation } from '@/components/sync/TrAppConfirmation'
import { formatTrAuthError, isTrAttemptOverError, isTrInvalidSessionStateError } from '@/lib/errors'
import { TR_AUTHENTICATOR_CODE_LENGTH, TR_PIN_MAX_LENGTH, TR_VERIFICATION_CODE_LENGTH } from '@/lib/constants'
import type { TrAuthInitResponse } from '@/types/api'

/** `AWAITING_APP` has no form: `auth/complete` stays open until the member confirms in the app. */
type TrAuthState = 'IDLE' | 'AWAITING_APP' | 'AWAITING_TAN'

interface TradeRepublicLoginProps {
  /** Called once the login is confirmed (in the app or with the code). */
  onConnected?: () => void
  /** `page`: Sync tab (cards, plain inputs). `modal`: add-account wizard / dialogs (spinners). */
  variant: 'page' | 'modal'
  /**
   * Masked phone stored with an expired session. When set, the form asks for the PIN or password
   * only and reconnects through `auth/reconnect`; it falls back to the full form when the
   * backend has no phone to reuse or the member picks another number.
   */
  maskedPhone?: string | null
}

export function TradeRepublicLogin({ onConnected, variant, maskedPhone = null }: TradeRepublicLoginProps) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  const [authState, setAuthState] = useState<TrAuthState>('IDLE')
  const [phone, setPhone] = useState('')
  const [pin, setPin] = useState('')
  const [tan, setTan] = useState('')
  const [processId, setProcessId] = useState<string | null>(null)
  const [codeLength, setCodeLength] = useState(TR_VERIFICATION_CODE_LENGTH)
  const [authenticator, setAuthenticator] = useState(false)
  const [reconnectDisabled, setReconnectDisabled] = useState(false)
  const [countdown, setCountdown] = useState(0)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const initiateMutation = useInitiateTrAuth()
  const reconnectMutation = useReconnectTrAuth()
  const completeMutation = useCompleteTrAuth()
  const reconnecting = maskedPhone !== null && !reconnectDisabled

  function finishLogin() {
    setTan('')
    setPhone('')
    setPin('')
    setProcessId(null)
    setErrorMsg(null)
    setAuthState('IDLE')
    onConnected?.()
  }

  function completeLogin(id: string, code?: string) {
    completeMutation.mutate(
      { processId: id, tan: code },
      {
        onSuccess: finishLogin,
        onError: (err: unknown) => {
          setErrorMsg(formatTrAuthError(err, t))
          setTan('')
          // A dead attempt (refused, timed out, ...) cannot be completed any more: start over.
          // A mistyped code leaves the attempt alive, so the code field stays.
          if (code === undefined || isTrAttemptOverError(err)) {
            setProcessId(null)
            setAuthState('IDLE')
            return
          }
          setAuthState('AWAITING_TAN')
        },
      },
    )
  }

  function startAuthStep(data: TrAuthInitResponse) {
    setProcessId(data.processId)
    setTan('')
    setErrorMsg(null)
    if (data.flow === 'confirmInApp') {
      // Nothing to type: hold the completion request open while the member confirms in the app.
      setCountdown(data.countdownInSeconds)
      setAuthState('AWAITING_APP')
      completeLogin(data.processId)
      return
    }
    const isAuthenticator = data.flow === 'authenticator'
    setAuthenticator(isAuthenticator)
    setCodeLength(isAuthenticator ? TR_AUTHENTICATOR_CODE_LENGTH : TR_VERIFICATION_CODE_LENGTH)
    setAuthState('AWAITING_TAN')
  }

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    if (pin.length === 0) return
    const callbacks = {
      onSuccess: startAuthStep,
      onError: (err: unknown) => {
        if (reconnecting && isTrInvalidSessionStateError(err)) {
          // No phone stored server-side: ask for it with the full form.
          setReconnectDisabled(true)
          setPin('')
          setErrorMsg(null)
        } else {
          setErrorMsg(formatTrAuthError(err, t))
        }
        setProcessId(null)
        setTan('')
        setAuthState('IDLE')
      },
    }
    if (reconnecting) reconnectMutation.mutate({ pin }, callbacks)
    else initiateMutation.mutate({ phoneNumber: phone, pin }, callbacks)
  }

  function handleTan(e: React.FormEvent) {
    e.preventDefault()
    if (!processId || tan.length !== codeLength) return
    completeLogin(processId, tan)
  }

  // Only dismiss the banner: the mutations' onError handlers already reset
  // authState/processId appropriately per failure type. Resetting to IDLE here
  // would throw away a still-valid TAN session after a completion failure.
  function dismissError() {
    setErrorMsg(null)
  }

  const phoneField = reconnecting ? (
    <div className="space-y-1">
      <p className="text-sm text-muted-foreground">
        <Smartphone className="size-4 inline-block mr-1" />
        {t('sync.tr.reconnectPhone')}
      </p>
      <p className="font-mono text-sm" data-testid="tr-masked-phone">{maskedPhone}</p>
    </div>
  ) : (
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

  const pinField = (
    <div className="space-y-2">
      <Label htmlFor="tr-pin">
        {modal ? <ShieldCheck className="size-4 inline-block mr-1" /> : <Lock className="size-4 inline-block mr-1" />}
        {t('sync.tr.pin')}
      </Label>
      <Input
        id="tr-pin"
        type="password"
        value={pin}
        onChange={(e) => setPin(e.target.value)}
        maxLength={TR_PIN_MAX_LENGTH}
        autoComplete="current-password"
        required
        autoFocus={modal && reconnecting}
      />
    </div>
  )

  const tanField = modal ? (
    <div className="space-y-2">
      <Label htmlFor="tr-tan">
        <ShieldCheck className="size-4 inline-block mr-1" />
        {authenticator ? t('sync.tr.authenticatorCode') : t('sync.tr.tan')}
      </Label>
      {authenticator && <p className="text-sm text-muted-foreground">{t('sync.tr.authenticatorPrompt')}</p>}
      <InputOTP
        id="tr-tan"
        maxLength={codeLength}
        value={tan}
        onChange={(value) => setTan(value.replace(/\D/g, '').slice(0, codeLength))}
        autoFocus
      >
        <InputOTPGroup>
          {Array.from({ length: codeLength }, (_, i) => i).map((i) => (
            <InputOTPSlot key={i} index={i} />
          ))}
        </InputOTPGroup>
      </InputOTP>
    </div>
  ) : (
    <div className="space-y-2">
      <Label htmlFor="tr-tan">
        <ShieldCheck className="size-4 inline-block mr-1" />
        {authenticator ? t('sync.tr.authenticatorCode') : t('sync.tr.tan')}
      </Label>
      {authenticator && <p className="text-sm text-muted-foreground">{t('sync.tr.authenticatorPrompt')}</p>}
      <Input
        id="tr-tan"
        value={tan}
        onChange={(e) => setTan(e.target.value.replace(/\D/g, '').slice(0, codeLength))}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={codeLength}
        required
        autoFocus
      />
    </div>
  )

  const initiatePending = initiateMutation.isPending || reconnectMutation.isPending
  const initiateSubmit = (
    <Button type="submit" disabled={initiatePending} className="w-full">
      {modal && initiatePending && <Loader2 className="size-4 animate-spin" />}
      {t('sync.tr.connect')}
    </Button>
  )
  const tanSubmit = (
    <Button
      type="submit"
      disabled={completeMutation.isPending || tan.length !== codeLength}
      className="w-full"
    >
      {modal && completeMutation.isPending && <Loader2 className="size-4 animate-spin" />}
      {t('sync.tr.connect')}
    </Button>
  )

  const anotherNumber = reconnecting && (
    <Button
      type="button"
      variant="link"
      size="sm"
      className="w-full"
      onClick={() => {
        setReconnectDisabled(true)
        setPin('')
        setErrorMsg(null)
      }}
    >
      {t('sync.tr.useAnotherNumber')}
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

  const form = authState === 'AWAITING_APP' ? (
    modal ? (
      <TrAppConfirmation countdownInSeconds={countdown} />
    ) : (
      <Card size="sm">
        <CardContent className="py-4">
          <TrAppConfirmation countdownInSeconds={countdown} />
        </CardContent>
      </Card>
    )
  ) : authState === 'AWAITING_TAN' ? (
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
          {anotherNumber}
        </>
      ) : (
        <Card size="sm">
          <CardContent className="space-y-4 py-4">
            {phoneField}
            {pinField}
            {initiateSubmit}
            {anotherNumber}
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
