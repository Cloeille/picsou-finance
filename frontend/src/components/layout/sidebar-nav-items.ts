import { LayoutDashboard, Wallet, Pyramid, Target, PieChart, Settings, Users } from 'lucide-react'

export const NAV_ITEMS = [
  { path: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard', descKey: 'nav.dashboard.desc' },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts', descKey: 'nav.accounts.desc' },
  { path: '/analysis', icon: Pyramid, labelKey: 'nav.analysis', descKey: 'nav.analysis.desc' },
  { path: '/goals', icon: Target, labelKey: 'nav.goals', descKey: 'nav.goals.desc' },
  { path: '/budget', icon: PieChart, labelKey: 'nav.budget', descKey: 'nav.budget.desc' },
] as const

export const FAMILY_NAV_ITEM = {
  path: '/family',
  icon: Users,
  labelKey: 'nav.family',
  descKey: 'nav.family.desc',
} as const

export const SETTINGS_NAV_ITEM = {
  path: '/settings',
  icon: Settings,
  labelKey: 'nav.settings',
  descKey: 'nav.settings.desc',
} as const
