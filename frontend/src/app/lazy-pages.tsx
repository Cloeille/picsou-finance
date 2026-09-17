import { lazy, Suspense } from 'react'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'

// Lazy-loaded page components, split out of routes.tsx so that routes.tsx can
// stay a pure route table (a single non-component `router` export). Keeping the
// component declarations here — a file that exports only components — satisfies
// react-refresh/only-export-components for both files.

export const LoginPage = lazy(() =>
  import('@/pages/login/LoginPage').then((m) => ({ default: m.LoginPage }))
)
export const MfaChallengePage = lazy(() =>
  import('@/pages/login/MfaChallengePage').then((m) => ({ default: m.MfaChallengePage }))
)
export const DashboardPage = lazy(() =>
  import('@/pages/dashboard/DashboardPage').then((m) => ({
    default: m.DashboardPage,
  }))
)
export const AccountsPage = lazy(() =>
  import('@/pages/accounts/AccountsPage').then((m) => ({
    default: m.AccountsPage,
  }))
)
export const AccountDetailPage = lazy(() =>
  import('@/pages/accounts/AccountDetailPage').then((m) => ({
    default: m.AccountDetailPage,
  }))
)
export const GoalsPage = lazy(() =>
  import('@/pages/goals/GoalsPage').then((m) => ({ default: m.GoalsPage }))
)
export const GoalCalendarPage = lazy(() =>
  import('@/pages/goals/GoalCalendarPage').then((m) => ({ default: m.GoalCalendarPage }))
)
export const BudgetLayout = lazy(() =>
  import('@/pages/budget/BudgetLayout').then((m) => ({ default: m.BudgetLayout }))
)
export const BudgetOverviewPage = lazy(() =>
  import('@/pages/budget/BudgetOverviewPage').then((m) => ({ default: m.BudgetOverviewPage }))
)
export const SpendingPage = lazy(() =>
  import('@/pages/budget/SpendingPage').then((m) => ({ default: m.SpendingPage }))
)
export const CategoryDetailPage = lazy(() =>
  import('@/pages/budget/CategoryDetailPage').then((m) => ({ default: m.CategoryDetailPage }))
)
export const SubscriptionsPage = lazy(() =>
  import('@/pages/budget/SubscriptionsPage').then((m) => ({ default: m.SubscriptionsPage }))
)
export const EnvelopesPage = lazy(() =>
  import('@/pages/budget/EnvelopesPage').then((m) => ({ default: m.EnvelopesPage }))
)
export const ReviewPage = lazy(() =>
  import('@/pages/budget/ReviewPage').then((m) => ({ default: m.ReviewPage }))
)
export const BudgetSettingsPage = lazy(() =>
  import('@/pages/budget/BudgetSettingsPage').then((m) => ({ default: m.BudgetSettingsPage }))
)
export const BudgetTransactionsPage = lazy(() =>
  import('@/pages/budget/BudgetTransactionsPage').then((m) => ({ default: m.BudgetTransactionsPage }))
)
export const SyncPage = lazy(() =>
  import('@/pages/sync/SyncPage').then((m) => ({ default: m.SyncPage }))
)
export const SettingsPage = lazy(() =>
  import('@/pages/settings/SettingsPage').then((m) => ({
    default: m.SettingsPage,
  }))
)

export const ActivationPage = lazy(() =>
  import('@/pages/activation/ActivationPage').then((m) => ({ default: m.ActivationPage }))
)
export const FamilyDashboardPage = lazy(() =>
  import('@/pages/family/FamilyDashboardPage').then((m) => ({ default: m.FamilyDashboardPage }))
)
export const FamilySettingsPage = lazy(() =>
  import('@/pages/settings/FamilySettingsPage').then((m) => ({ default: m.FamilySettingsPage }))
)
export const AdminPage = lazy(() =>
  import('@/pages/admin/AdminPage').then((m) => ({ default: m.AdminPage }))
)

export const SetupLayout = lazy(() =>
  import('@/pages/setup/SetupLayout').then((m) => ({ default: m.SetupLayout }))
)
export const SetupStepIntro = lazy(() =>
  import('@/pages/setup/SetupStepIntro').then((m) => ({ default: m.SetupStepIntro }))
)
export const SetupStepAdmin = lazy(() =>
  import('@/pages/setup/SetupStepAdmin').then((m) => ({ default: m.SetupStepAdmin }))
)
export const SetupStepSecurity = lazy(() =>
  import('@/pages/setup/SetupStepSecurity').then((m) => ({ default: m.SetupStepSecurity }))
)
export const SetupStepIntegrations = lazy(() =>
  import('@/pages/setup/SetupStepIntegrations').then((m) => ({ default: m.SetupStepIntegrations }))
)
export const SetupStepComplete = lazy(() =>
  import('@/pages/setup/SetupStepComplete').then((m) => ({ default: m.SetupStepComplete }))
)
export const SetupStepEnableBanking = lazy(() =>
  import('@/pages/setup/integrations/SetupStepEnableBanking').then((m) => ({
    default: m.SetupStepEnableBanking,
  }))
)
export const SetupStepBoursoBank = lazy(() =>
  import('@/pages/setup/integrations/SetupStepBoursoBank').then((m) => ({
    default: m.SetupStepBoursoBank,
  }))
)
export const SetupStepBourseDirect = lazy(() =>
  import('@/pages/setup/integrations/SetupStepBourseDirect').then((m) => ({
    default: m.SetupStepBourseDirect,
  }))
)
export const SetupStepTradeRepublic = lazy(() =>
  import('@/pages/setup/integrations/SetupStepTradeRepublic').then((m) => ({
    default: m.SetupStepTradeRepublic,
  }))
)
export const SetupStepRevolut = lazy(() =>
  import('@/pages/setup/integrations/SetupStepRevolut').then((m) => ({
    default: m.SetupStepRevolut,
  }))
)
export const SetupStepFinary = lazy(() =>
  import('@/pages/setup/integrations/SetupStepFinary').then((m) => ({
    default: m.SetupStepFinary,
  }))
)
export const SetupStepCrypto = lazy(() =>
  import('@/pages/setup/integrations/SetupStepCrypto').then((m) => ({
    default: m.SetupStepCrypto,
  }))
)

export const AnalysisPage = lazy(() =>
  import('@/pages/analysis/AnalysisPage').then((m) => ({ default: m.AnalysisPage }))
)

export const ConsentPage = lazy(() =>
  import('@/pages/oauth/ConsentPage').then((m) => ({ default: m.ConsentPage }))
)

export const NotFoundPage = lazy(() =>
  import('@/pages/error/NotFoundPage').then((m) => ({ default: m.NotFoundPage }))
)
export const ForbiddenPage = lazy(() =>
  import('@/pages/error/ForbiddenPage').then((m) => ({ default: m.ForbiddenPage }))
)
export const ServerErrorPage = lazy(() =>
  import('@/pages/error/ServerErrorPage').then((m) => ({ default: m.ServerErrorPage }))
)

export function SuspensePage({
  children,
  fallback = <LoadingSkeleton />,
}: {
  children: React.ReactNode
  fallback?: React.ReactNode
}) {
  return <Suspense fallback={fallback}>{children}</Suspense>
}
