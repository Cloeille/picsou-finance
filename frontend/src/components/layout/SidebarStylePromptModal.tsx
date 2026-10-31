import { useTranslation } from 'react-i18next'
import { ChevronDown, Eye, Settings, Users } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useAppStore, type SidebarStyle } from '@/stores/app-store'
import { cn } from '@/lib/utils'
import picsouLogo from '@/assets/horizontal-white-picsou.svg'
import { NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM } from './sidebar-nav-items'

const OPTIONS: { value: SidebarStyle; labelKey: string }[] = [
  { value: 'current', labelKey: 'settings.sidebarStyleCurrent' },
  { value: 'classic', labelKey: 'settings.sidebarStyleClassic' },
]

// A sketch of AppSidebar for each style: same nav list (derived from NAV_ITEMS,
// plus the Family item AppSidebar renders by hand), same card height (full vs
// h-fit) and same member row placement, so the choice shows what it changes.
function SidebarPreview({ variant }: { variant: SidebarStyle }) {
  const isClassic = variant === 'classic'
  const navIcons = [...(isClassic ? [...NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM] : NAV_ITEMS).map((item) => item.icon), Users]

  return (
    <div className="flex h-48 w-full gap-1.5 rounded-lg bg-muted/40 p-2" aria-hidden="true">
      <div
        data-slot="preview-sidebar"
        className={cn('flex w-1/2 flex-col rounded-md bg-background p-1.5', isClassic ? 'h-fit' : 'h-full')}
      >
        <div className={cn('flex items-center justify-between', isClassic ? 'mb-3' : 'mb-2')}>
          <img src={picsouLogo} alt="" className="h-2 w-auto opacity-70 brightness-0 dark:invert" />
          <Eye className="size-2 shrink-0 text-muted-foreground" />
        </div>
        <div className="flex flex-col gap-0.5">
          {navIcons.map((Icon, i) => (
            <div
              key={i}
              className={cn('flex items-center gap-1 rounded-sm px-0.5 py-px', i === 0 && 'bg-muted ring-1 ring-border')}
            >
              <div className="flex size-3 shrink-0 items-center justify-center rounded-[3px] bg-muted">
                <Icon className="size-2 text-muted-foreground" />
              </div>
              <div className="flex flex-1 flex-col gap-0.5">
                <div className="h-1 w-3/5 rounded-full bg-foreground/20" />
                <div className="h-0.5 w-full rounded-full bg-foreground/10" />
              </div>
            </div>
          ))}
        </div>
        <div className={cn('flex items-center gap-0.5 px-0.5', isClassic ? 'mt-1.5' : 'mt-auto')}>
          <div className="size-3 shrink-0 rounded-full bg-foreground/15" />
          <div className="ml-0.5 h-1 min-w-3 flex-1 rounded-full bg-foreground/20" />
          <ChevronDown className="size-2 shrink-0 text-muted-foreground" />
          {!isClassic && <Settings className="size-2.5 shrink-0 text-muted-foreground" />}
        </div>
      </div>
      <div className="flex-1 space-y-1.5 rounded-md bg-background p-1.5">
        <div className="h-2.5 w-2/3 rounded-sm bg-muted" />
        <div className="h-8 rounded-sm bg-muted/60" />
      </div>
    </div>
  )
}

interface SidebarStylePromptModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Shown once — either right after setup or on a user's first normal login —
 * so the current/classic sidebar split (see AppSidebar.tsx) is an active
 * choice instead of a silent default. Picking an option closes the modal
 * immediately; dismissing it any other way (Escape, backdrop, close button)
 * also marks the prompt as seen, since re-showing it every session would be
 * more annoying than a silent default.
 */
export function SidebarStylePromptModal({ open, onOpenChange }: SidebarStylePromptModalProps) {
  const { t } = useTranslation()
  const sidebarStyle = useAppStore((s) => s.sidebarStyle)
  const setSidebarStyle = useAppStore((s) => s.setSidebarStyle)
  const setHasSeenSidebarStylePrompt = useAppStore((s) => s.setHasSeenSidebarStylePrompt)

  function handleOpenChange(next: boolean) {
    if (!next) setHasSeenSidebarStylePrompt(true)
    onOpenChange(next)
  }

  function choose(style: SidebarStyle) {
    setSidebarStyle(style)
    handleOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('sidebarStylePrompt.title')}</DialogTitle>
          <DialogDescription>{t('sidebarStylePrompt.description')}</DialogDescription>
        </DialogHeader>

        <div className="grid grid-cols-2 gap-3" role="radiogroup" aria-label={t('sidebarStylePrompt.title')}>
          {OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={sidebarStyle === option.value}
              onClick={() => choose(option.value)}
              className="flex flex-col gap-2 rounded-lg border border-border p-2 text-left transition-colors hover:border-primary/60 hover:bg-muted/40"
            >
              <SidebarPreview variant={option.value} />
              <span className="text-sm font-medium">{t(option.labelKey)}</span>
            </button>
          ))}
        </div>

        <p className="text-center text-xs text-muted-foreground">{t('sidebarStylePrompt.hint')}</p>
      </DialogContent>
    </Dialog>
  )
}
