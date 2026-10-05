# Feature: Theme persistence + theme-adaptive rendering (dark / light / system)

> Last updated: 2026-06-02

## Context

Users can choose between dark, light, and system themes in Settings. Without persistence, the selected theme was lost on every page refresh because the DOM class was only applied when `SettingsPage` was mounted — all other pages loaded with no `.dark` class on `<html>`. Beyond persistence, individual assets and UI primitives must also render correctly in both palettes — see *Theme-adaptive rendering* below.

## How it works

Theme is stored in `localStorage` under the key `'theme'` (`'light' | 'dark' | 'system'`). On every page load, the external script `frontend/public/theme-init.js` (loaded `<script src="/theme-init.js">` in `index.html`, before `main.tsx`) reads that value and adds the `dark` class to `<html>` before any CSS or JS runs (preventing flash of unstyled content). React then picks up an already-correct DOM.

### Key files

- `frontend/index.html` + `frontend/public/theme-init.js` — pre-paint script that applies the `.dark` class immediately on load
- `frontend/src/lib/theme.ts` — **the live applier**: shared helpers `getStoredTheme`, `applyTheme`, `initSystemThemeListener`
- `frontend/src/main.tsx` — calls `initSystemThemeListener()` + `applyTheme(getStoredTheme())` once at startup
- `frontend/src/pages/settings/SettingsPage.tsx` — UI toggle, reads/writes via `lib/theme`
- `frontend/src/index.css` — CSS variables under `:root` (light, `@custom-variant dark (&:is(.dark *))`) and `.dark` (dark)
- Theme-adaptive assets/components: `AppSidebar.tsx`, `MobileBottomNav.tsx`, `ConnectionGuard.tsx`, `ErrorBoundary.tsx` (logo), `frontend/src/components/ui/dropdown-menu.tsx` (popover panel)

### Flow

```text
Page load
  └─ theme-init.js (external script loaded from index.html)
       └─ reads localStorage('theme')
       └─ adds .dark to <html> if needed   ← happens before any CSS paints

React mounts
  └─ main.tsx: initSystemThemeListener()
       └─ watches matchMedia change
       └─ re-applies theme when OS preference changes (only if theme = 'system')

User changes theme (SettingsPage)
  └─ setTheme(value)
  └─ useEffect → applyTheme(theme)
       └─ toggles .dark class on document.documentElement
       └─ writes to localStorage('theme')
```

### Theme-adaptive rendering

Two classes of UI need explicit handling beyond the `.dark` token swap:

- **The Picsou logo.** The SVG assets (`frontend/src/assets/horizontal-white-picsou.svg`, `picsou_logo_white.svg`) bake in a near-white fill (`#DEDEDE`) and are loaded as `<img src=…>`, so CSS cannot recolor their paths. They are made theme-adaptive with the Tailwind filter `brightness-0 dark:invert`: `brightness(0)` collapses any source colour to pure black (light mode); `dark:invert` then flips that to pure white (dark mode). Applied to all four logo placements (sidebar, mobile bottom-nav, ConnectionGuard, ErrorBoundary).
- **Dropdown popover panel.** `DropdownMenuContent`/`DropdownMenuSubContent` must *not* hardcode a `dark` class — Radix portals the panel under `<html>`, which already carries `.dark` in dark mode, so the panel inherits the correct palette automatically.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `frontend/public/theme-init.js` loaded before `main.tsx` | Runs before CSS/JS, zero FOUC | Initialize in `main.tsx` (too late, React hasn't painted yet) |
| Shared `frontend/src/lib/theme.ts` | Single, app-wide source of truth for applyTheme logic | Keep helpers in SettingsPage (breaks on other pages); a React `ThemeProvider` context (a duplicate `theme-provider.tsx` was tried, left unwired, and removed in 1.0.0) |
| System listener in `main.tsx` | Active on all pages, not just Settings | Listener inside SettingsPage (stopped working when page unmounted) |
| CSS classes on `<html>` + Tailwind variables | Standard Tailwind dark mode approach | Zustand-stored theme driving conditional class props (complex, no FOUC protection) |
| Logo via `brightness-0 dark:invert` filter | Recolors an `<img>` SVG that CSS can't reach inside; source-colour-agnostic (true black/white) | Plain `invert` (only reaches `#212121` grey); shipping separate black/white assets; inlining the SVG |

## Gotchas / Pitfalls

- **The external script must stay in `<head>` before any stylesheet link.** Moving it to `<body>` or after stylesheets causes a brief flash in dark mode on page load.
- **`initSystemThemeListener` registers a persistent event listener** — it must only be called once (currently in `main.tsx`). Calling it inside a component would add a new listener on every mount.
- **`localStorage.getItem('theme')` can return `null`** (first visit) — `getStoredTheme` defaults to `'system'` in that case, matching the external script which also defaults to system.
- Tailwind dark mode is configured via the `dark` class on the root element (not `media` strategy) — if this changes, `theme-init.js` and `applyTheme` need to be updated.
- **Never hardcode a literal `dark` (or `light`) class in a `className`/`cn()` string.** Tailwind v4's `@custom-variant dark (&:is(.dark *))` makes any element *carrying* `dark` resolve the dark token palette regardless of the active theme. This caused the My Account dropdown to render black in light mode (`bg-popover` pinned to the near-black dark value). Use `dark:` *variants* for per-theme overrides, never the bare class. As of 1.0.0 the dropdown was the only offender; the remaining absolute colours (`bg-black/80` modal overlays, `text-white` labels over coloured chart segments, the 2FA QR's `bg-white`) are intentionally theme-agnostic.
- **The logo assets are white-filled**, so a logo dropped into a *light* surface without the `brightness-0 dark:invert` filter will be invisible. Any new logo placement must carry that filter (or be a `bg-background`/`text-foreground`-driven element).

## Tests

No automated tests — purely DOM/localStorage/CSS. Manual verification:
1. Set Dark → refresh → stays dark on any page
2. Set System → toggle OS dark mode → page reacts without reload
3. In **light** mode: the Picsou logo is black (sidebar, mobile nav, connection/error screens) and the My Account dropdown panel is light
4. In **dark** mode: the logo is white and the dropdown panel is dark
