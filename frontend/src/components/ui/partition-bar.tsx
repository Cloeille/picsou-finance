import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Horizontal partition bar — segments whose widths are proportional to their
 * `num` relative to siblings. Adapted from ui.8starlabs.com/components/partition-bar
 * to the project's design tokens. Segments shrink gracefully on narrow screens
 * (text truncates), so it stays mobile-friendly.
 */

const barVariants = cva("flex w-full", {
  variants: {
    size: {
      sm: "min-h-9",
      md: "min-h-12",
      lg: "min-h-16",
    },
  },
  defaultVariants: { size: "md" },
})

function PartitionBar({
  className,
  size,
  gap = 1,
  style,
  ...props
}: React.ComponentProps<"div"> &
  VariantProps<typeof barVariants> & { gap?: number }) {
  return (
    <div
      data-slot="partition-bar"
      className={cn(barVariants({ size }), className)}
      style={{ gap: `${gap * 0.25}rem`, ...style }}
      {...props}
    />
  )
}

const segmentVariants = cva(
  "flex min-w-0 flex-col justify-center overflow-hidden rounded-md px-2 py-1.5",
  {
    variants: {
      variant: {
        default: "bg-primary/15 text-foreground",
        secondary: "bg-sky-500/15 text-foreground",
        destructive: "bg-destructive/15 text-destructive",
        outline: "border border-border bg-input/20 text-foreground",
        muted: "bg-muted text-muted-foreground",
      },
      alignment: {
        left: "items-start text-left",
        center: "items-center text-center",
        right: "items-end text-right",
      },
    },
    defaultVariants: { variant: "default", alignment: "center" },
  }
)

function PartitionBarSegment({
  className,
  variant,
  alignment,
  num = 0,
  style,
  ...props
}: React.ComponentProps<"div"> &
  VariantProps<typeof segmentVariants> & { num?: number }) {
  return (
    <div
      data-slot="partition-bar-segment"
      className={cn(segmentVariants({ variant, alignment }), className)}
      style={{ flexGrow: num, flexBasis: 0, ...style }}
      {...props}
    />
  )
}

function PartitionBarSegmentTitle({
  className,
  ...props
}: React.ComponentProps<"span">) {
  return (
    <span
      data-slot="partition-bar-segment-title"
      className={cn("w-full truncate text-xs font-medium leading-tight", className)}
      {...props}
    />
  )
}

function PartitionBarSegmentValue({
  className,
  ...props
}: React.ComponentProps<"span">) {
  return (
    <span
      data-slot="partition-bar-segment-value"
      className={cn(
        "w-full truncate text-xs leading-tight tabular-nums text-muted-foreground",
        className
      )}
      {...props}
    />
  )
}

export default PartitionBar
export {
  PartitionBar,
  PartitionBarSegment,
  PartitionBarSegmentTitle,
  PartitionBarSegmentValue,
}
