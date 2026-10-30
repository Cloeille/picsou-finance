# Picsou — building with this design system

Picsou is a personal-finance UI kit: React components (shadcn-derived, style `radix-mira`) styled with **Tailwind v4 utilities backed by CSS variables**, in the **Geist** typeface. Build finance UI — accounts, balances, transactions, budgets, goals — from these real components.

## Setup & wrapping

- **Load the DS stylesheet once at the app root** (`styles.css`, which `@import`s `_ds_bundle.css`). It defines every token, the Geist `@font-face`, and all utility classes. Without it, components render unstyled.
- **No provider needed for most components** — they style themselves from the stylesheet.
- **Dark mode**: put `class="dark"` on an ancestor (`<html>` or a wrapper). All tokens have a `.dark` scope.
- **Context-bound components**: wrap `Tooltip` in a `TooltipProvider`; wrap `Sidebar` (and its `Sidebar*` parts) in a `SidebarProvider`. `Dialog` / `Sheet` / `DropdownMenu` manage their own open state — drive them with the `open` prop (or a trigger).

## Styling idiom — token utilities, never raw hex

Style with the semantic Tailwind utilities below (all token-backed); reach for raw colors only for data viz. Utilities accept opacity modifiers (`bg-primary/80`, `bg-destructive/10`) — that's how Picsou builds subtle intent surfaces.

| Role | Utilities |
|---|---|
| Surfaces | `bg-background` `bg-card` `bg-popover` `bg-muted` `bg-sidebar` |
| Text | `text-foreground` `text-muted-foreground` `text-primary-foreground` `text-card-foreground` |
| Brand / intent | `bg-primary` `text-primary` · `bg-secondary` · `bg-accent` · `bg-destructive` `text-destructive` |
| Borders | `border-border` `border-input` (focus rings are applied by the components themselves from `--ring`) |
| Charts | `var(--chart-1)` … `var(--chart-5)` (or a `ChartContainer` `config`) |
| Radius | `rounded-md` `rounded-lg` (scaled from `--radius`) |
| Type | Geist Variable is the default typeface (`--font-sans`) — you don't set a font. Use **`tabular-nums`** on every monetary/aligned figure. |

Green/red for gains/losses are the one idiomatic raw-color exception: `text-emerald-500` (income/positive), `text-red-500` (spend/negative).

Component variants (e.g. `Button` `variant="outline|secondary|ghost|destructive|link"` + `size`, `Badge` variants, `Item` `variant`) are enumerated in each component's `.d.ts` / `.prompt.md`.

## Where the truth lives

Before styling, read the bound `styles.css` (+ its `_ds_bundle.css` import) for the full token/utility set, and each component's **`<Name>.prompt.md`** (usage) and **`<Name>.d.ts`** (props). Picsou-specific building blocks live under the **shared** group: `AccountCard`, `CurrencyDisplay`, `AccountTypeBadge`, `GoalProgressBar`, `MerchantAvatar`, `PageHeader`, `EmptyState`, `ErrorState`, `TimeRangeSelector`, `PriceFreshnessDot`, `NumericInput` — prefer these over rebuilding finance UI from primitives.

## Idiomatic snippet

```tsx
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter, Badge, Button } from "picsou"
import { TrendingUp } from "lucide-react"

<Card className="w-80">
  <CardHeader>
    <CardTitle>Livret A</CardTitle>
    <CardDescription>Savings · Crédit Agricole</CardDescription>
    <Badge variant="secondary" className="ml-auto">SAVINGS</Badge>
  </CardHeader>
  <CardContent>
    <p className="text-2xl font-semibold tabular-nums">€12,450.00</p>
    <p className="mt-1 flex items-center gap-1 text-xs text-emerald-500">
      <TrendingUp className="size-3" /> +€320 this month
    </p>
  </CardContent>
  <CardFooter>
    <Button variant="outline" size="sm" className="w-full">View transactions</Button>
  </CardFooter>
</Card>
```

Icons come from `lucide-react`. Compose layout with the token utilities above; use library components for every control.
