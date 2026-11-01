import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * Destructive banner styles. `inline` and `roomy` are
 * flex rows (optional icon, message, optional action); `bordered`, `plain` and `prominent` are
 * single paragraphs.
 */
const VARIANTS = {
  inline: 'flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive',
  roomy: 'flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive',
  bordered: 'rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive',
  plain: 'rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive',
  prominent: 'rounded-xl bg-destructive/10 px-4 py-3 text-sm font-medium text-destructive',
} as const

type AlertBannerVariant = keyof typeof VARIANTS

interface AlertBannerProps {
  children: React.ReactNode
  variant?: AlertBannerVariant
  role?: 'alert'
  /** Flex variants only: leading icon. */
  icon?: React.ReactNode
  /** Flex variants only: renders the standard "x" ghost button. */
  onDismiss?: () => void
  /** Flex variants only: custom trailing control, for banners whose dismiss button is not the standard one. */
  action?: React.ReactNode
  className?: string
}

export function AlertBanner({
  children,
  variant = 'inline',
  role,
  icon,
  onDismiss,
  action,
  className,
}: AlertBannerProps) {
  const classes = cn(VARIANTS[variant], className)
  if (variant === 'bordered' || variant === 'plain' || variant === 'prominent') {
    return (
      <p role={role} className={classes}>
        {children}
      </p>
    )
  }
  return (
    <div role={role} className={classes}>
      {icon}
      <span className="flex-1">{children}</span>
      {onDismiss && (
        <Button variant="ghost" size="sm" onClick={onDismiss}>
          x
        </Button>
      )}
      {action}
    </div>
  )
}
