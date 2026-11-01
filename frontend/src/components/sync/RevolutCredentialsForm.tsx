import { useTranslation } from 'react-i18next'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { InputOTP, InputOTPGroup } from '@/components/ui/input-otp'
import { MaskedOTPSlot } from '@/components/sync/MaskedOTPSlot'
import { Loader2, Lock, Smartphone } from 'lucide-react'

export interface RevolutCredentials {
  phoneNumber: string
  passcode: string
  remember: boolean
}

interface RevolutCredentialsFormProps {
  value: RevolutCredentials
  onChange: (value: RevolutCredentials) => void
  onSubmit: (e: React.FormEvent) => void
  busy: boolean
  /** `page`: Sync tab (card, plain passcode input). `modal`: add-account wizard (masked OTP passcode). */
  variant: 'page' | 'modal'
}

export function RevolutCredentialsForm({ value, onChange, onSubmit, busy, variant }: RevolutCredentialsFormProps) {
  const { t } = useTranslation()
  const modal = variant === 'modal'

  const fields = (
    <>
      <div className="space-y-2">
        <Label htmlFor="revolut-phone">
          <Smartphone className="size-4 inline-block mr-1" />
          {t('sync.revolut.phone')}
        </Label>
        <Input
          id="revolut-phone"
          type="tel"
          value={value.phoneNumber}
          onChange={(e) => onChange({ ...value, phoneNumber: e.target.value })}
          required
          disabled={busy}
          placeholder="+33..."
          autoFocus={modal}
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="revolut-passcode">
          <Lock className="size-4 inline-block mr-1" />
          {t('sync.revolut.passcode')}
        </Label>
        {modal ? (
          <InputOTP
            maxLength={6}
            value={value.passcode}
            onChange={(passcode) => onChange({ ...value, passcode })}
            disabled={busy}
          >
            <InputOTPGroup>
              {[0, 1, 2, 3, 4, 5].map((i) => (
                <MaskedOTPSlot key={i} index={i} />
              ))}
            </InputOTPGroup>
          </InputOTP>
        ) : (
          <Input
            id="revolut-passcode"
            type="password"
            inputMode="numeric"
            maxLength={6}
            value={value.passcode}
            onChange={(e) => onChange({ ...value, passcode: e.target.value })}
            required
            disabled={busy}
          />
        )}
      </div>

      <label className="flex items-center gap-2 cursor-pointer">
        <Checkbox
          checked={value.remember}
          onCheckedChange={(checked) => onChange({ ...value, remember: checked === true })}
          disabled={busy}
        />
        <span className="text-sm text-muted-foreground">{t('sync.revolut.remember')}</span>
      </label>

      <Button type="submit" disabled={busy} className="w-full">
        {busy && <Loader2 className="size-4 animate-spin" />}
        {busy ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
      </Button>
    </>
  )

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      {modal ? fields : (
        <Card size="sm">
          <CardContent className="space-y-4 py-4">{fields}</CardContent>
        </Card>
      )}
    </form>
  )
}
