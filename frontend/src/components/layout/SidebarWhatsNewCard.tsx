import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronRight, ExternalLink, Megaphone, Star } from 'lucide-react'
import { WhatsNewDialog } from '@/components/shared/WhatsNewDialog'
import { useAppStore } from '@/stores/app-store'
import {
  PICSOU_GITHUB_URL,
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
    <div className="mb-2 overflow-hidden rounded-xl border border-border/60 bg-muted/25">
      <button
        type="button"
        onClick={handleOpen}
        className="group flex w-full cursor-pointer flex-col gap-0.5 px-3 py-2.5 text-left transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <div className="flex w-full items-center justify-between gap-2">
          <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-foreground">
            <Megaphone className="size-3.5 shrink-0 text-foreground" aria-hidden="true" />
            <span>{t('whatsNew.title')}</span>
            <span className="rounded bg-muted px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-muted-foreground">
              v{latest.version}
            </span>
            {hasUnread && (
              <span
                className="size-1.5 shrink-0 rounded-full bg-primary"
                aria-hidden="true"
              />
            )}
          </span>
          <ChevronRight
            className="size-3.5 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-foreground"
            aria-hidden="true"
          />
        </div>
        <p className="line-clamp-1 text-xs leading-relaxed text-muted-foreground">
          {latest.summary[locale]}
        </p>
      </button>

      <a
        href={PICSOU_GITHUB_URL}
        target="_blank"
        rel="noreferrer"
        className="group flex w-full items-center justify-between gap-2 border-t border-border/60 px-3 py-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground"
      >
        <span className="inline-flex items-center gap-1.5">
          <Star className="size-3.5 shrink-0 text-foreground" aria-hidden="true" />
          <span>{t('whatsNew.starGithub')}</span>
        </span>
        <ExternalLink
          className="size-3 shrink-0 opacity-60 transition-opacity group-hover:opacity-100"
          aria-hidden="true"
        />
      </a>

      <WhatsNewDialog open={open} onOpenChange={setOpen} />
    </div>
  )
}
