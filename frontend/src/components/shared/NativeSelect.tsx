import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/** A native `<select>` drawn like `Input`, so the two share a height, border and fill in a row. */
export function NativeSelect({ className, ...props }: ComponentProps<'select'>) {
  return (
    <select
      className={cn(
        'h-10 w-full min-w-0 rounded-md border border-input bg-input/20 px-3 text-sm outline-none transition-[border-color] focus:border-ring disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30',
        className,
      )}
      {...props}
    />
  )
}
