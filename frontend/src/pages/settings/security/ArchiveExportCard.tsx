import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { DownloadSimple } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { ExportArchiveDialog } from './ExportArchiveDialog'

/**
 * Admin-only block of the Security tab: the encrypted archive that moves the whole instance
 * (data, settings, bank connections, encryption key) to another Picsou install. Not offered in
 * demo mode, where there is no server to build it.
 */
export function ArchiveExportCard() {
  const { t } = useTranslation()
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const demoMode = useAppStore((s) => s.demoMode)
  const [searchParams] = useSearchParams()
  // Back from the IdP re-authentication: reopen on the passphrase form, already confirmed.
  const [resumed] = useState(() => searchParams.get('resume') === 'archive')
  const [open, setOpen] = useState(resumed)

  if (!isAdmin || demoMode) return null

  return (
    <Card className="rounded-xl bg-card">
      <CardHeader>
        <CardTitle className="text-base font-semibold">{t('settings.archive.cardTitle')}</CardTitle>
        <CardDescription className="text-sm">{t('settings.archive.cardDescription')}</CardDescription>
      </CardHeader>
      <CardContent>
        <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
          <DownloadSimple className="size-4" aria-hidden="true" />
          {t('settings.archive.button')}
        </Button>
        <ExportArchiveDialog open={open} onOpenChange={setOpen} resumed={resumed} />
      </CardContent>
    </Card>
  )
}
