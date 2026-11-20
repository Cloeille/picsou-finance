import type { ComponentType } from 'react'
import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'

interface IntegrationCardProps {
  icon: ComponentType<{ className?: string }>
  title: string
  description: string
  checked: boolean
  onToggle: () => void
  disabled?: boolean
}

/**
 * Selection card used on the integration picker step. Designed to
 * double as a read-only "status card" in the post-install Settings →
 * Integrations page — pass {@code disabled} to lock the toggle there.
 *
 * The whole card is the hit target (not just the checkbox) so fat
 * fingers on mobile work. We still render a semantic {@code button}
 * with {@code role="switch"} for keyboard + screen-reader users.
 */
export function IntegrationCard({
  icon: Icon,
  title,
  description,
  checked,
  onToggle,
  disabled = false,
}: IntegrationCardProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-disabled={disabled}
      disabled={disabled}
      onClick={onToggle}
      className={cn(
        'flex h-full w-full flex-col rounded-xl border p-3.5 text-left transition-[border-color,background-color,box-shadow,opacity]',
        checked
          ? 'border-primary bg-primary/5 ring-1 ring-primary/20'
          : 'border-border/70 bg-card hover:border-primary/40 hover:bg-muted/20',
        disabled && 'cursor-not-allowed opacity-60'
      )}
    >
      <div className="flex w-full items-center gap-3">
        <span
          className={cn(
            'inline-flex size-9 shrink-0 items-center justify-center rounded-lg transition-colors',
            checked ? 'text-foreground' : 'text-foreground/75'
          )}
          aria-hidden="true"
        >
          <Icon className="size-5" />
        </span>
        <span className="min-w-0 flex-1 truncate text-sm font-semibold leading-tight text-foreground">
          {title}
        </span>
        <span
          className={cn(
            'inline-flex size-5 shrink-0 items-center justify-center rounded-md border transition-colors',
            checked
              ? 'border-primary bg-primary text-primary-foreground'
              : 'border-muted-foreground/35 bg-background'
          )}
          aria-hidden="true"
        >
          {checked && <Check className="size-3.5 stroke-[2.5]" />}
        </span>
      </div>
      <p className="mt-2.5 text-sm leading-relaxed text-muted-foreground">
        {description}
      </p>
    </button>
  )
}
