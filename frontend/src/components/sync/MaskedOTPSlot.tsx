import { useContext } from 'react'
import { OTPInputContext } from 'input-otp'

/**
 * Masked variant of InputOTPSlot — replaces the typed character with a bullet
 * so the slot reads like a password field. We can't edit components/ui/input-otp
 * (shadcn primitives), so we re-derive slot state from OTPInputContext here.
 */
export function MaskedOTPSlot({ index }: { index: number }) {
  const ctx = useContext(OTPInputContext)
  const slot = ctx?.slots[index]
  const isActive = slot?.isActive
  const hasFakeCaret = slot?.hasFakeCaret
  const hasChar = slot?.char != null && slot.char !== ''
  return (
    <div
      data-active={isActive}
      className="relative flex size-10 items-center justify-center border-y border-r border-input bg-input/20 text-sm transition-[border-color,box-shadow,background-color] outline-none first:rounded-l-xl first:border-l last:rounded-r-xl aria-invalid:border-destructive data-[active=true]:z-10 data-[active=true]:border-ring data-[active=true]:ring-2 data-[active=true]:ring-ring/30 dark:bg-input/30"
    >
      {hasChar ? '•' : null}
      {hasFakeCaret && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="h-4 w-px animate-caret-blink bg-foreground duration-1000" />
        </div>
      )}
    </div>
  )
}
