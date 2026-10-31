// Auto-generated design-system entry for the claude.ai/design sync (see .design-sync/).
// Re-exports Picsou's reusable components so esbuild can bundle them into a single
// IIFE assigning every export to window.Picsou.*. NOT imported by the app itself.
// Regenerate when the synced component set changes; keep in step with
// .design-sync/config.json componentSrcMap.

// Initialize the global i18next instance (side-effect import) so useTranslation()
// resolves real FR strings inside the design previews instead of raw keys.
import "@/i18n/index"

// ── ui/ primitives (shadcn, style "radix-mira") ──────────────────────────────
export * from "@/components/ui/avatar"
export * from "@/components/ui/badge"
export * from "@/components/ui/button"
export * from "@/components/ui/card"
export * from "@/components/ui/chart"
export * from "@/components/ui/checkbox"
export * from "@/components/ui/dialog"
export * from "@/components/ui/dropdown-menu"
export * from "@/components/ui/empty"
export * from "@/components/ui/input-otp"
export * from "@/components/ui/input"
export * from "@/components/ui/item"
export * from "@/components/ui/label"
export * from "@/components/ui/partition-bar"
export * from "@/components/ui/progress"
export * from "@/components/ui/scroll-area"
export * from "@/components/ui/separator"
export * from "@/components/ui/sheet"
export * from "@/components/ui/sidebar"
export * from "@/components/ui/skeleton"
export * from "@/components/ui/sonner"
export * from "@/components/ui/switch"
export * from "@/components/ui/table"
export * from "@/components/ui/tabs"
export * from "@/components/ui/tooltip"

// ── shared (Picsou-specific presentational compositions) ─────────────────────
export * from "@/components/shared/CurrencyDisplay"
export * from "@/components/shared/AccountTypeBadge"
export * from "@/components/shared/MerchantAvatar"
export * from "@/components/shared/PageHeader"
export * from "@/components/shared/EmptyState"
export * from "@/components/shared/ErrorState"
export * from "@/components/shared/GoalProgressBar"
export * from "@/components/shared/AccountCard"
export * from "@/components/shared/PriceFreshnessDot"
export * from "@/components/shared/NumericInput"
export * from "@/components/shared/TimeRangeSelector"
