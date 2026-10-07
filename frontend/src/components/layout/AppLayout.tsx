import { Outlet } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AppSidebar } from './AppSidebar'
import { MobileBottomNav } from './MobileBottomNav'
import { SidebarStylePromptModal } from './SidebarStylePromptModal'
import { DegradedModeBanner } from '@/components/shared/DegradedModeBanner'
import { useAppStore } from '@/stores/app-store'

export function AppLayout() {
  const { t } = useTranslation()
  const hasSeenSidebarStylePrompt = useAppStore((s) => s.hasSeenSidebarStylePrompt)
  return (
    <div className="flex h-screen md:p-4 md:gap-4">
      {/* Keyboard skip-link: first focusable element, jumps past the nav straight to <main>. */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-background focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:shadow-md focus:outline-none focus:ring-2 focus:ring-ring"
      >
        {t('common.skipToContent')}
      </a>
      <AppSidebar />
      <main id="main-content" tabIndex={-1}
        className="flex min-w-0 flex-1 flex-col overflow-hidden pb-20 outline-none md:pb-0">
        <DegradedModeBanner />
        <div className="flex-1 overflow-auto px-1 pb-1">
          <Outlet />
        </div>
      </main>
      <MobileBottomNav />
      <SidebarStylePromptModal open={!hasSeenSidebarStylePrompt} onOpenChange={() => {}} />
    </div>
  )
}
