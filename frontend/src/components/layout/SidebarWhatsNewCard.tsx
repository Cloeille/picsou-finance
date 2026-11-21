import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowUpRight } from '@phosphor-icons/react'
import { WhatsNewDialog } from '@/components/shared/WhatsNewDialog'
import { useAppStore } from '@/stores/app-store'
import {
  getLatestWhatsNew,
  resolveWhatsNewLocale,
} from '@/lib/whats-new'

export function SidebarWhatsNewCard() {
  const { t, i18n } = useTranslation()
  const [open, setOpen] = useState(false)
  const lastSeen = useAppStore((s) => s.lastSeenWhatsNewVersion)
  const markSeen = useAppStore((s) => s.markWhatsNewSeen)

  const latest = getLatestWhatsNew()
  const locale = resolveWhatsNewLocale(i18n.resolvedLanguage ?? i18n.language)
  const hasUnread = lastSeen !== latest.version

  function handleOpen() {
    markSeen(latest.version)
    setOpen(true)
  }

  return (
    <div className="mb-2">
      <button
        type="button"
        onClick={handleOpen}
        className="group flex w-full cursor-pointer flex-col gap-2 rounded-xl border border-border/60 bg-muted/50 px-3 py-3 text-left transition-colors hover:bg-muted/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <div className="flex w-full items-center justify-between gap-2">
          <span className="inline-flex items-center gap-1.5">
            <span className="rounded-md border border-border/60 bg-background/80 px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-muted-foreground">
              v{latest.version}
            </span>
            {hasUnread && (
              <span
                className="size-1.5 shrink-0 rounded-full bg-primary"
                aria-hidden="true"
              />
            )}
          </span>
          <ArrowUpRight
            className="size-3.5 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-foreground"
            aria-hidden="true"
          />
        </div>
        <div className="space-y-0.5">
          <p className="text-sm font-semibold leading-snug text-foreground">
            {t('whatsNew.cardTitle', { version: latest.version })}
          </p>
          <p className="line-clamp-1 text-xs leading-relaxed text-muted-foreground">
            {latest.summary[locale]}
          </p>
        </div>
      </button>

      <WhatsNewDialog open={open} onOpenChange={setOpen} />
    </div>
  )
}
