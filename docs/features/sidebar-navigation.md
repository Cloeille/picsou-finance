# Feature: Navigation (Sidebar + Mobile Bottom Nav)

> Last updated: 2026-08-13

## Context

Navigation adapts to screen size: a vertical sidebar on desktop (>=768px) and a horizontal bottom navbar on mobile (<768px). Desktop shows the primary app navigation, including Family, and exposes settings through the bottom account area. Admin users also get a visible profile switcher there so a managed-member override can never stay hidden. Mobile keeps the compact bottom bar — five items since Analysis joined — and active state logic.

## How it works

### Desktop: `AppSidebar` (hidden on mobile via `hidden md:flex`)

The sidebar lives in `AppSidebar.tsx` with two visual sections:

1. **Primary nav items** — rendered by the `NavItem` internal component, one per route. Uses `react-router-dom`'s `NavLink` with `useLocation` for active detection. Desktop intentionally does not render a separate Settings item because the bottom account link already targets `/settings`.
2. **Bottom account area** — non-admin and demo sessions keep a bottom-pinned `NavLink` to `/settings`. Admin sessions render a `DropdownMenu` trigger in the same place. The trigger shows the currently scoped profile; the menu lets the admin switch back to their own account, switch into managed profiles from `selectSwitchableMembers()`, or open Settings.

Desktop navigation opens with the horizontal Picsou brand logo (`horizontal-white-picsou.svg`, `brightness-0 dark:invert` so the single white SVG renders black in light theme and white in dark), then the route list. The logo sits at the top of the `<nav>`, aligned on the items' `px-4` gutter with `self-start` so it doesn't stretch in the flex column.

### Admin profile switching

The admin switcher is backed by `profile-store.activeMemberId`. Selecting a managed profile calls `setActiveMember(member.id)` and invalidates TanStack Query so the current page refetches under the new `?memberId=` scope. Selecting the admin's own account clears the override with `setActiveMember(null)`.

The switcher loads family members through `useFamilyMembers({ enabled: canSwitchProfile })`, where `canSwitchProfile = !demoMode && user?.role === 'ADMIN'`. It must stay disabled for non-admin and demo sessions because `/family/members` is admin-only and a regular member would otherwise be redirected to the global 403 page.

### Mobile: `MobileBottomNav` (hidden on desktop via `md:hidden`)

A fixed bottom bar with five evenly spaced nav items:

```text
[Dashboard] [Accounts] [Analysis] [Goals] [Settings]
```

It used to centre the Picsou logo between two pairs of items and keep its **own** hardcoded copy
of the route list. Adding Analysis (2026-08-13) left no honest way to keep that symmetry, and a
private copy was one more place to forget a route, so both went: the bar now maps
`[...NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM]` from the shared registry. Settings stays in the list
because mobile has no other way to reach it — the desktop sidebar uses its bottom account row.

The bar's height is unchanged (`size-10` items + `py-3` + `bottom-4` = 80 px), so `AppLayout`'s
`pb-20` still clears it. `frontend/src/assets/picsou_logo_white.svg` is now unused.

- Same icon styling as the sidebar: `size-10 rounded-lg bg-muted text-muted-foreground`
- Active state: `ring-1 ring-border` + foreground icon color
- Safe area padding for iOS notch: `env(safe-area-inset-bottom)`
- `AppLayout` adds `pb-20 md:pb-0` on main content to avoid overlap

### Active state (shared pattern)

Active nav items keep Lucide icons stroke-only. The item gets `ring-1 ring-border` when active, and the icon box switches from `text-muted-foreground` to `text-foreground`. Do not use SVG fill for active nav states.

### Key files

- `frontend/src/components/layout/AppSidebar.tsx` — desktop sidebar with `NavItem`, admin profile switcher, and bottom-pinned account/settings access
- `frontend/src/components/layout/MobileBottomNav.tsx` — mobile bottom navbar
- `frontend/src/components/layout/AppLayout.tsx` — renders sidebar (desktop) + bottom nav (mobile)
- `frontend/src/components/ui/item.tsx` — `Item` / `ItemMedia` / `ItemContent` primitives (do not edit)
- `frontend/src/i18n/locales/{fr,en,de,es}.json` — `nav.*` keys for labels
- `frontend/src/assets/horizontal-white-picsou.svg` — horizontal wordmark logo used at the top of the desktop sidebar
- `frontend/src/assets/picsou_logo_white.svg` — icon-only logo, **no longer used** since the mobile bar went to five items

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Stroke-only Lucide icons for active state | Preserves the intended outline glyphs across Dashboard, Accounts, Goals, Settings, and Family | Filled Lucide icons (`fill="currentColor"`) — fills internal shapes and creates blob-like active icons |
| Standalone `Avatar` instead of `ItemMedia` wrapper | Avoids nested box-in-box (avatar inside icon container) | Wrapping avatar in `ItemMedia` adds redundant sizing layer |
| Separate `MobileBottomNav` component | Keeps mobile/desktop nav logic isolated; each can evolve independently | One component with responsive internals — harder to read and maintain |
| 768px breakpoint (`md:`) | Consistent with existing `useIsMobile` hook and shadcn UI sidebar pattern | Custom breakpoint — would diverge from the rest of the codebase |

## Gotchas / Pitfalls

- **Babel spread in JSX props**: `{...(condition && { prop: value })}` causes a Vite/Babel parse error (500) in this project's config. Use a ternary `prop={condition ? value : fallback}` instead.
- **Lucide icons are stroke icons**: do not pass `fill="currentColor"` to sidebar or bottom-nav icons. It fills the internal SVG geometry and makes active icons look like broken blobs.
- **File encoding**: Editing `AppSidebar.tsx` with the Edit tool introduced invisible characters that broke Babel parsing. If the file breaks after edits, rewrite it entirely with the Write tool.
- **`ItemMedia` import**: The component is imported even though the user dropdown no longer uses it — it's still used by `NavItem`. Don't remove the import.
- **Mobile bottom nav padding**: `AppLayout` adds `pb-20` on mobile to prevent content from being hidden behind the fixed bottom nav. If the bottom nav height changes, update this value.
- **Admin switcher only**: the sidebar dropdown is only for profile scope plus Settings access. Logout, language, admin, and account controls remain centralized in Settings.
- **Admin-only members query**: keep `useFamilyMembers({ enabled: isAdmin })` on the sidebar. Calling `/family/members` for non-admins causes a 403 redirect.
- **No duplicate desktop Settings item**: desktop settings access is the bottom-pinned account row. Do not add `/settings` back to `NAV_ITEMS`, or the sidebar will show two entries for the same page.
- **`hidden md:flex` on sidebar**: The sidebar nav element uses `hidden md:flex` — not `md:block` — because it needs flexbox for its internal layout. Changing to `md:block` will break the sidebar layout.
- **Brand logo regression history**: commit `716228e` (PR #29) silently dropped the sidebar logo while restructuring the `<nav>` (the asset `horizontal-white-picsou.svg` was never deleted — only its usage). Restored on 2026-07-12. If you refactor the sidebar container, keep the logo `<img>` as the first child of `<nav>`.

## Tests

- `frontend/src/components/layout/AppSidebar.test.tsx` covers the admin switcher, query disabling for non-admins, managed-profile filtering, and Query invalidation on profile switch.
- Sidebar is also covered by E2E via Playwright (`bun run test:e2e`).

## Links

- Related: `docs/features/demo-mode.md` — the dropdown shows "Demo" in demo mode
