---
name: Picsou
description: Self-hosted personal-finance dashboard — credible, calm, private.
colors:
  vault-light: "oklch(1 0 0)"
  vault-dark: "oklch(0.145 0 0)"
  card-light: "oklch(1 0 0)"
  card-dark: "oklch(0.205 0 0)"
  coffre-blue: "oklch(0.488 0.243 264.376)"
  coffre-blue-dark: "oklch(0.424 0.199 265.638)"
  coffre-blue-foreground: "oklch(0.97 0.014 254.604)"
  ledger-ink: "oklch(0.145 0 0)"
  ledger-ink-dark: "oklch(0.985 0 0)"
  quiet-slate: "oklch(0.45 0 0)"
  quiet-slate-dark: "oklch(0.708 0 0)"
  soft-secondary: "oklch(0.967 0.001 286.375)"
  soft-secondary-dark: "oklch(0.274 0.006 286.033)"
  muted-surface: "oklch(0.97 0 0)"
  muted-surface-dark: "oklch(0.269 0 0)"
  alert-red: "oklch(0.577 0.245 27.325)"
  alert-red-dark: "oklch(0.704 0.191 22.216)"
  hairline-border: "oklch(0.922 0 0)"
  growth-green-1: "oklch(0.845 0.143 164.978)"
  growth-green-2: "oklch(0.696 0.17 162.48)"
  growth-green-3: "oklch(0.596 0.145 163.225)"
  growth-green-4: "oklch(0.508 0.118 165.612)"
  growth-green-5: "oklch(0.432 0.095 166.913)"
  focus-ring: "oklch(0.708 0 0)"
typography:
  display:
    fontFamily: "'Geist Variable', sans-serif"
    fontWeight: 700
  body:
    fontFamily: "'Geist Variable', sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "'Geist Variable', sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
  card-title:
    fontFamily: "'Geist Variable', sans-serif"
    fontSize: "0.875rem"
    fontWeight: 700
rounded:
  sm: "0.375rem"
  md: "0.5rem"
  lg: "0.625rem"
  xl: "0.875rem"
  2xl: "1.125rem"
  3xl: "1.375rem"
  4xl: "1.625rem"
spacing:
  card-pad: "1rem"
  card-pad-sm: "0.75rem"
  btn-h: "2.5rem"
  btn-h-lg: "3rem"
---

# Design System: Picsou

## 1. Overview

**Creative North Star: "Le Coffre Lumineux"**

Picsou is a strongbox that does not need to look austere. The interface carries real financial data — balances, holdings, net worth — and treats it with the calm seriousness of a private ledger, lit from within rather than bolted shut. The light is the point: the user opens the app to *see where they stand*, and every surface is arranged so the answer arrives in one glance, without flash, without noise.

The system rejects the four traps named in PRODUCT.md. It is not generic fintech flash — no violet gradients, no neon, no flat-design mascots. It is not a cold technical dashboard — the human behind the numbers stays legible. It is not hollow marketing — the numbers are always real and always present. And it is not a traditional banking app — the user's patrimony, not the bank's product catalog, owns the screen. What sits between these rejections is the register: credible without being cold, warm without being childish, composed enough that the data breathes.

Density is earned, never default. A surface earns its whitespace; a figure earns its presence. The palette is restrained — one confident blue carries the brand, neutrals carry everything else, and a green family reserved for growth-positive charts. Components are flat by default: depth is conveyed by tonal layering and a hairline ring, never by drop shadows. Motion is present where it carries feedback (a press, a sync, a value change) and silent everywhere else.

**Key Characteristics:** restrained palette, flat surfaces with hairline rings, single-sans typographic hierarchy, calm motion, WCAG AA with AAA on every figure the user came to read.

## 2. Colors: The Vault Palette

One confident blue, honest neutrals, and a reserved green family for growth. The palette never raises its voice.

### Primary
- **Coffre Blue** (`oklch(0.488 0.243 264.376)`): the brand anchor. A saturated blue-violet that carries identity without aggression. Used on primary actions, the active nav state, links, and focusable emphasis. Its rarity is the point — it appears on the small fraction of the screen that *does* something.
- **Coffre Blue (dark)** (`oklch(0.424 0.199 265.638)`): the dark-mode counterpart, slightly pulled toward black so it reads as the same confident blue against a dark vault rather than glowing.

### Neutral
- **Vault Light / Ledger Ink** (`oklch(1 0 0)` background, `oklch(0.145 0 0)` ink): the canvas and its text. True white and near-black, achromatic — no warm drift, no cream. The clarity is deliberate: financial figures read against pure contrast.
- **Card Light** (`oklch(1 0 0)`, dark: `oklch(0.205 0 0)`): the surface for grouped content. Identical to the canvas in light mode (cards read via their hairline ring, not a tint shift), one step lifted in dark mode.
- **Quiet Slate** (`oklch(0.45 0 0)`, dark: `oklch(0.708 0 0)`): secondary text. Held at 0.45 lightness specifically so it clears WCAG AA (4.5:1) even on a muted surface — the value was tuned down from the shadcn default for exactly this reason. Critical figures (amounts, balances) use Ledger Ink, never Quiet Slate.
- **Muted Surface** (`oklch(0.97 0 0)`, dark: `oklch(0.269 0 0)`): the resting state for inactive nav items, ghost-button hovers, and muted containers. Achromatic, one breath off the canvas.
- **Soft Secondary** (`oklch(0.967 0.001 286.375)`): a near-neutral with the faintest violet whisper, used for secondary buttons and badges. Close enough to read as neutral, tinted enough to distinguish from Muted.
- **Hairline Border** (`oklch(0.922 0 0)`): the structural line. Borders and inputs share this value; in dark mode it becomes `oklch(1 0 0 / 10%)` — a hair of white, not a gray.

### Tertiary
- **Alert Red** (`oklch(0.577 0.245 27.325)`): destruction and danger. Used sparingly and only where the user must feel a stop signal — delete confirmations, invalid states, loss indicators. Never decorative.
- **Growth Green family** (`chart-1` through `chart-5`, `oklch(0.845→0.432 0.143→0.095 ~165)`): a five-step green ramp reserved for charts and positive-value visualization. Lightened for area fills, deepened for primary series. Hue is held near-constant so the family reads as one gesture; lightness does the differentiation, keeping the palette accessible beyond hue alone.

### Named Rules
**The One Voice Rule.** Coffre Blue is the only saturated color permitted on interactive chrome. It appears on ≤10% of any given screen. Its rarity is what makes it read as confidence rather than decoration.

**The Green Is For Growth Rule.** The Growth Green family never appears on buttons, text, or chrome. It is reserved for charts and positive financial movement. Mixing it into the UI vocabulary dilutes both the brand voice and the semantic.

**The Critical Ink Rule.** Amounts, balances, and alerts — any figure the user came to read — render in Ledger Ink (`oklch(0.145 0 0)`), never in Quiet Slate. Quiet Slate is for supporting prose only. This is how the system meets AAA on critical content.

## 3. Typography

**Display Font:** Geist Variable (`'Geist Variable', sans-serif`)
**Body Font:** Geist Variable (`'Geist Variable', sans-serif`)
**Label/Mono Font:** Geist Variable — no second family is used.

**Character:** A single geometric-grotesque variable face carries the entire hierarchy. Geist's even color and open counters keep dense financial tables legible at small sizes, while its tighter terminals read as deliberate at heading scale. The system does not pair fonts; it pairs weights. The discipline of one family reinforces the "calm instrument" register — nothing competes for attention.

### Hierarchy
- **Display** (700, page scale via clamp, 1.1): reserved for the single most prominent figure on a screen — a net-worth headline, a dashboard total. Never more than one per view.
- **Headline** (700, ~1.5–1.875rem, 1.2): page and section titles via `PageHeader`. Sets the frame for what the user is looking at.
- **Card Title** (700, 0.875rem, tight): the `cn-card-title` utility — small, bold, confident. Card headers do not shout; they label.
- **Body** (400, 0.875rem, 1.6 / `text-sm/relaxed`): the default reading size. Tables, descriptions, transaction rows. Line height relaxed enough for scanning.
- **Label** (500, 0.875rem): button text, nav labels, form labels. Medium weight to read as an affordance without bulk.

### Named Rules
**The One Family Rule.** No second typeface. Hierarchy is built from weight and size, never from a contrasting face. A serif or display face would break the instrument register; the calm comes from consistency.

**The Small-Bold Rule.** Card titles are 0.875rem and 700 weight — small *and* bold. Resist the urge to enlarge them. The grid of confident small labels is what makes the dashboard scannable; oversized titles fragment the rhythm.

## 4. Elevation

Picsou is flat by default. Depth is conveyed by **tonal layering** and a **hairline ring**, not by drop shadows. This is the doctrine: shadows are reserved for elements that physically detach from the surface — floating dialogs, sheets, dropdown menus, popovers — and even there they stay soft and diffuse. A card at rest has no shadow; it has a 1px ring (`ring-1 ring-foreground/10`) that defines its edge against the canvas.

In dark mode the same logic holds: the card surface lifts one tonal step (`oklch(0.205)` against `oklch(0.145)` background) and the ring becomes a hair of white at 8–10% opacity.

### Named Rules
**The Flat-By-Default Rule.** Surfaces are flat at rest. A ring or a tonal step separates them from the canvas. Shadows appear only on elements that float above the page (dialogs, sheets, popovers, tooltips) — never on cards, inputs, or buttons in their resting state.

**The No-Glow Rule.** No colored glows, no neon drop shadows, no `box-shadow` used as decoration. A shadow that exists is structural: it tells the user "this floats." If it does not tell them that, it does not exist.

## 5. Components

### Buttons
- **Shape:** rounded-md (0.5rem), `bg-clip-padding` to keep backgrounds crisp on the radius.
- **Primary:** Coffre Blue (`bg-primary`) with `primary-foreground` text; hover drops to 80% opacity (`hover:bg-primary/80`) for a settled, not flashy, response. Default height 2.5rem (h-10), padding px-8.
- **Active feedback:** `active:not-aria-[haspopup]:scale-[0.96]` — a subtle 4% press scale, the "posé et vivant" gesture. Buttons with popovers (dropdowns) do not scale, since the click opens rather than commits.
- **Secondary / Outline / Ghost:** secondary uses Soft Secondary surface; outline uses Hairline Border with input-tinted background; ghost is transparent until hover, where it picks up Muted Surface. All share the same height, radius, and motion as Primary.
- **Destructive:** a tinted red (`bg-destructive/10`), not a solid red — the danger is legible without shouting, and darkens on hover.
- **Focus:** the global focus-visible ring (2px solid `--ring`, 4px `color-mix` halo at 20%).
- **Icon buttons:** square (`size-10`), same radius, icon scaled to 1rem.

### Badges
- **Shape:** rounded-full, height 1.25rem (h-5), 0.625rem text — the smallest typographic element in the system.
- **Variants:** default (Coffre Blue), secondary (Soft Secondary), destructive (Alert Red at 10% tint), outline (bordered, input-tinted). Same tonal logic as buttons, miniaturized.

### Cards
- **Corner:** rounded-4xl (1.625rem) — the most generous radius in the system. Cards are the soft containers of the dashboard.
- **Background:** Card Light surface; `ring-1 ring-foreground/10` defines the edge. No shadow at rest.
- **Padding:** 1rem (px-4, py-4) default; `size="sm"` tightens to 0.75rem. Header and footer pin to the card's rounded corners via `rounded-t-4xl` / `rounded-b-4xl`.
- **Title:** `cn-card-title` (0.875rem, 700) — small and confident per the Small-Bold Rule.

### Inputs
- **Shape:** rounded-md (0.5rem), height 2.5rem (h-10), Hairline Border, background `bg-input/20` (a faint tint of the border color).
- **Focus:** border and box-shadow transition together; invalid state rings Alert Red at 20% (40% in dark).
- **Placeholder:** `placeholder:text-muted-foreground/80` — slightly faded, but Quiet Slate is already tuned for contrast, so even at 80% it stays legible.

### Navigation
- **Sidebar (desktop):** Muted-dark surface (`oklch(0.985)` light / `oklch(0.16)` dark), 1px border-right. Active item: Coffre Blue text or tinted background. Inactive items use Quiet Slate, settling to Ledger Ink on hover.
- **Mobile bottom nav:** the same items compressed to the bottom bar on small screens; icons primary, labels in Label style.
- **Account menu:** dropdown (Radix), floating with the soft shadow reserved for popovers.

### Charts
- **Series colors:** the Growth Green family, lightened for fills, deepened for primary lines.
- **Grid:** hairline, low-contrast — the data owns the chart, the grid only orients.
- **Tooltips:** popover surface, Card Light, hairline ring, no decorative shadow.

## 6. Do's and Don'ts

### Do:
- **Do** keep Coffre Blue to ≤10% of any screen. It marks interactive identity and nothing else.
- **Do** render every amount, balance, and alert in Ledger Ink (`oklch(0.145 0 0)`). Critical figures meet AAA this way.
- **Do** use the Growth Green family *only* in charts and for positive financial movement.
- **Do** keep cards flat at rest — a hairline ring (`ring-1 ring-foreground/10`) defines the edge, no shadow.
- **Do** reserve shadows for elements that physically float (dialogs, sheets, popovers, tooltips), and keep them soft and diffuse.
- **Do** use a single typeface (Geist Variable) and build hierarchy from weight and size alone.
- **Do** respect `prefers-reduced-motion` — it is enforced globally in the base stylesheet, and feature motion must work inside that net.
- **Do** make charts distinguishable beyond hue: the Growth Green ramp steps lightness, never relying on hue alone.

### Don't:
- **Don't** use violet gradients, neon accents, or flat-design illustrations — Picsou is not generic fintech flash.
- **Don't** make the interface look like Datadog, Grafana, or a generic analytics tool — cold technical dashboards forget the human behind the numbers.
- **Don't** ship big animated numbers, testimonials, or reassurance without real data — Picsou is not hollow fintech marketing.
- **Don't** prioritize a product catalog over the user's patrimony — Picsou is not a traditional banking app.
- **Don't** use colored drop shadows or glows as decoration. A shadow that does not say "this floats" does not exist.
- **Don't** use `border-left` or `border-right` greater than 1px as a colored stripe accent. Full borders or background tints only.
- **Don't** apply `background-clip: text` with a gradient. Emphasis comes from weight or size, never from gradient text.
- **Don't** pair Geist with a similar geometric sans to "add variety." One family, weights and sizes — that is the hierarchy.
- **Don't** enlarge card titles past 0.875rem. The grid of confident small labels is what makes the dashboard scannable.
- **Don't** put Quiet Slate on critical figures. It is for supporting prose only.
