import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Copy, ArrowSquareOut } from '@phosphor-icons/react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useInstanceStatus } from '@/features/admin/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import {
  PICSOU_UPDATE_COMMAND,
  WHATS_NEW_RELEASES,
  resolveWhatsNewLocale,
} from '@/lib/whats-new'
import { formatDate, getLocale } from '@/lib/utils'

interface WhatsNewDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function WhatsNewDialog({ open, onOpenChange }: WhatsNewDialogProps) {
  const { t, i18n } = useTranslation()
  const locale = resolveWhatsNewLocale(i18n.resolvedLanguage ?? i18n.language)
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const demoMode = useAppStore((s) => s.demoMode)
  const desktopMode = useAppStore((s) => s.desktopMode)
  const { data: instanceStatus } = useInstanceStatus(open && isAdmin && !demoMode)
  const [copied, setCopied] = useState(false)

  async function handleCopyUpdate() {
    try {
      await navigator.clipboard.writeText(PICSOU_UPDATE_COMMAND)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard API unavailable (non-HTTPS or blocked)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('whatsNew.title')}</DialogTitle>
          <DialogDescription>{t('whatsNew.subtitle')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-5">
          {instanceStatus?.update && (
            <div className="space-y-2.5 rounded-xl border border-primary/25 bg-primary/5 p-3.5 text-sm">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-semibold text-foreground">
                  {t('whatsNew.updateAvailable', { version: `v${instanceStatus.update.latestVersion}` })}
                </span>
                <a
                  href={instanceStatus.update.releaseUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
                >
                  <span>{t('whatsNew.viewReleaseNotes')}</span>
                  <ArrowSquareOut className="size-3" aria-hidden="true" />
                </a>
              </div>
              {!desktopMode && (
                <>
                  <p className="text-sm text-muted-foreground">{t('whatsNew.updateInstructions')}</p>
                  <div className="flex items-center justify-between gap-2 rounded-lg border border-border/60 bg-background px-3 py-2">
                    <code className="min-w-0 truncate font-mono text-xs text-foreground">
                      {PICSOU_UPDATE_COMMAND}
                    </code>
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      aria-label={copied ? t('whatsNew.copied') : t('whatsNew.copy')}
                      title={copied ? t('whatsNew.copied') : t('whatsNew.copy')}
                      onClick={() => void handleCopyUpdate()}
                    >
                      {copied
                        ? <Check className="size-3.5 text-emerald-600" aria-hidden="true" />
                        : <Copy className="size-3.5" aria-hidden="true" />}
                    </Button>
                  </div>
                </>
              )}
            </div>
          )}

          <div className="divide-y divide-border/60">
            {WHATS_NEW_RELEASES.map((release, idx) => (
              <section key={release.version} className="py-5 first:pt-0 last:pb-0">
                <div className="mb-3 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <Badge variant={idx === 0 ? 'default' : 'secondary'} className="tabular-nums">
                      v{release.version}
                    </Badge>
                    <span className="text-xs tabular-nums text-muted-foreground">
                      {formatDate(release.date, getLocale())}
                    </span>
                  </div>
                </div>
                <ul className="space-y-2 text-sm text-foreground/90">
                  {release.highlights[locale].map((item, itemIdx) => (
                    <li key={itemIdx} className="flex items-start gap-2.5">
                      <span
                        className="mt-2 size-1.5 shrink-0 rounded-full bg-primary/70"
                        aria-hidden="true"
                      />
                      <span>{item}</span>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
