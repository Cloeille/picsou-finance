import { SquaresFour, Wallet, Triangle, Target, ChartPieSlice, GearSix, Users } from '@phosphor-icons/react'

export const NAV_ITEMS = [
  { path: '/', icon: SquaresFour, labelKey: 'nav.dashboard', descKey: 'nav.dashboard.desc' },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts', descKey: 'nav.accounts.desc' },
  { path: '/analysis', icon: Triangle, labelKey: 'nav.analysis', descKey: 'nav.analysis.desc' },
  { path: '/goals', icon: Target, labelKey: 'nav.goals', descKey: 'nav.goals.desc' },
  { path: '/budget', icon: ChartPieSlice, labelKey: 'nav.budget', descKey: 'nav.budget.desc' },
] as const

export const FAMILY_NAV_ITEM = {
  path: '/family',
  icon: Users,
  labelKey: 'nav.family',
  descKey: 'nav.family.desc',
} as const

export const SETTINGS_NAV_ITEM = {
  path: '/settings',
  icon: GearSix,
  labelKey: 'nav.settings',
  descKey: 'nav.settings.desc',
} as const
