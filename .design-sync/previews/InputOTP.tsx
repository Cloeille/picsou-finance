import { InputOTP, InputOTPGroup, InputOTPSlot, InputOTPSeparator, Label } from "picsou"

export function PinEntry() {
  return (
    <div className="flex w-fit flex-col gap-1.5">
      <Label htmlFor="otp-pin">Code PIN</Label>
      <InputOTP id="otp-pin" maxLength={4} autoFocus>
        <InputOTPGroup>
          {[0, 1, 2, 3].map((i) => (
            <InputOTPSlot key={i} index={i} />
          ))}
        </InputOTPGroup>
      </InputOTP>
    </div>
  )
}

export function VerificationCode() {
  return (
    <div className="flex w-fit flex-col gap-1.5">
      <Label htmlFor="otp-verify">Code de vérification</Label>
      <InputOTP id="otp-verify" maxLength={6} value="482913" onChange={() => {}}>
        <InputOTPGroup>
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <InputOTPSlot key={i} index={i} />
          ))}
        </InputOTPGroup>
      </InputOTP>
    </div>
  )
}

export function WithSeparator() {
  return (
    <InputOTP maxLength={6} value="482" onChange={() => {}}>
      <InputOTPGroup>
        {[0, 1, 2].map((i) => (
          <InputOTPSlot key={i} index={i} />
        ))}
      </InputOTPGroup>
      <InputOTPSeparator />
      <InputOTPGroup>
        {[3, 4, 5].map((i) => (
          <InputOTPSlot key={i} index={i} />
        ))}
      </InputOTPGroup>
    </InputOTP>
  )
}

export function Invalid() {
  return (
    <div className="flex w-fit flex-col gap-1.5">
      <Label htmlFor="otp-invalid">Code de vérification</Label>
      <InputOTP id="otp-invalid" maxLength={6} value="000000" onChange={() => {}}>
        <InputOTPGroup>
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <InputOTPSlot key={i} index={i} aria-invalid />
          ))}
        </InputOTPGroup>
      </InputOTP>
      <p className="text-xs text-destructive">Code incorrect, réessayez.</p>
    </div>
  )
}
