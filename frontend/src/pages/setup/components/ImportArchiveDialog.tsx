import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle, CircleNotch, File as FileIcon, UploadSimple } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Progress } from '@/components/ui/progress'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { SecretInput } from '@/components/shared/SecretInput'
import type { ArchiveImportStatus } from '@/features/setup/api'
import {
  ARCHIVE_EXTENSION,
  ARCHIVE_MIN_PASSPHRASE_CHARS,
  archiveCodeKey,
  archiveUploadErrorKey,
  passphraseLength,
} from '@/features/setup/archive-import'
import { SETUP_STATUS_KEY, useArchiveImport } from '@/features/setup/hooks'
import { formatFileSize } from '@/lib/file-size'

interface ImportArchiveDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The desktop app relaunches its window to apply the archive; Docker restarts in-process. */
  desktop: boolean
  /** An import this page did not start (a reload during the restart, a failed import): shown as is. */
  reported?: ArchiveImportStatus
}

/**
 * Restores an encrypted archive from the first setup screen, on Docker and on desktop alike.
 * The server stages the archive and restarts to apply it, so the dialog follows the restart
 * (the server is unreachable for a while) until the new process reports `done` or `failed`.
 * The passphrase lives in this component's state only: it is never displayed back, stored or
 * put in a message.
 */
export function ImportArchiveDialog({ open, onOpenChange, desktop, reported }: ImportArchiveDialogProps) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { phase, start, resume, adopt, reset } = useArchiveImport()
  const fileInput = useRef<HTMLInputElement>(null)
  const fileId = useId()
  const passphraseId = useId()
  const hintId = useId()
  const [file, setFile] = useState<File | null>(null)
  const [passphrase, setPassphrase] = useState('')
  const [error, setError] = useState<string | null>(null)

  const length = passphraseLength(passphrase)
  const tooShort = length > 0 && length < ARCHIVE_MIN_PASSPHRASE_CHARS
  const canSubmit = file !== null && length >= ARCHIVE_MIN_PASSPHRASE_CHARS
  const outcome = phase.name === 'finished' ? phase.outcome : null
  // Closing mid-upload or mid-restart would leave the user without the outcome; a finished
  // import only leaves through its own button, since the first screen is stale once imported.
  const locked = phase.name === 'uploading' || phase.name === 'restarting' || outcome?.state === 'done'

  // Primitives, so a refetch of the status that says the same thing does not adopt it again.
  const reportedState = reported?.state
  const reportedCode = reported?.state === 'failed' ? reported.code : ''
  useEffect(() => {
    if (!open) return
    if (reportedState === 'pending') adopt({ state: 'pending' })
    else if (reportedState === 'failed') adopt({ state: 'failed', code: reportedCode })
  }, [open, reportedState, reportedCode, adopt])

  const clearForm = () => {
    setFile(null)
    setPassphrase('')
    setError(null)
    if (fileInput.current) fileInput.current.value = ''
  }

  const handleOpenChange = (next: boolean) => {
    if (!next && locked) return
    if (!next) {
      reset()
      clearForm()
    }
    onOpenChange(next)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!file || !canSubmit) return
    setError(null)
    try {
      await start(file, passphrase)
      setPassphrase('')
    } catch (err: unknown) {
      setError(t(archiveUploadErrorKey(err)))
    }
  }

  const retry = () => {
    reset()
    clearForm()
  }

  const goToLogin = () => {
    navigate('/login', { replace: true })
    // The restarted server holds the imported instance: what the wizard cached is stale.
    void queryClient.invalidateQueries({ queryKey: SETUP_STATUS_KEY })
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-md" showCloseButton={!locked}>
        {phase.name === 'idle' && (
          <form onSubmit={handleSubmit} className="grid gap-5">
            <DialogHeader>
              <DialogTitle>{t('setup.archive.title')}</DialogTitle>
              <DialogDescription>{t('setup.archive.description')}</DialogDescription>
            </DialogHeader>

            <ul className="list-disc space-y-1.5 rounded-xl bg-muted/50 py-3 pl-8 pr-4 text-sm leading-relaxed text-muted-foreground ring-1 ring-foreground/10">
              <li>{t('setup.archive.willRestore')}</li>
              <li>{t('setup.archive.willSignIn')}</li>
              <li>{desktop ? t('setup.archive.willRestartDesktop') : t('setup.archive.willRestart')}</li>
            </ul>

            <div className="space-y-2">
              <Label htmlFor={fileId} className="text-sm font-semibold">
                {t('setup.archive.fileLabel')}
              </Label>
              <input
                ref={fileInput}
                id={fileId}
                type="file"
                accept={ARCHIVE_EXTENSION}
                className="sr-only"
                onChange={e => {
                  setFile(e.target.files?.[0] ?? null)
                  setError(null)
                }}
              />
              <div className="flex items-center gap-3 rounded-lg border border-border/60 px-3 py-2">
                <FileIcon className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
                <div className="min-w-0 flex-1">
                  {file ? (
                    <>
                      <p className="truncate text-sm font-medium text-foreground">{file.name}</p>
                      <p className="text-sm text-muted-foreground">
                        {formatFileSize(file.size, t, i18n.language)}
                      </p>
                    </>
                  ) : (
                    <p className="truncate text-sm text-muted-foreground">{t('setup.archive.noFile')}</p>
                  )}
                </div>
                <Button type="button" variant="outline" size="sm" onClick={() => fileInput.current?.click()}>
                  {file ? t('setup.archive.changeFile') : t('setup.archive.chooseFile')}
                </Button>
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor={passphraseId} className="text-sm font-semibold">
                {t('setup.archive.passphraseLabel')}
              </Label>
              <SecretInput
                id={passphraseId}
                value={passphrase}
                onChange={e => setPassphrase(e.target.value)}
                autoComplete="off"
                aria-invalid={tooShort}
                aria-describedby={hintId}
              />
              <p id={hintId} className={tooShort ? 'text-sm text-destructive' : 'text-sm text-muted-foreground'}>
                {tooShort ? t('setup.archive.passphraseTooShort') : t('setup.archive.passphraseHint')}
              </p>
            </div>

            {error && (
              <AlertBanner variant="prominent" role="alert">
                {error}
              </AlertBanner>
            )}

            <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
              <Button type="button" variant="ghost" onClick={() => handleOpenChange(false)}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" disabled={!canSubmit}>
                <UploadSimple className="size-4" aria-hidden="true" />
                {t('setup.archive.submit')}
              </Button>
            </DialogFooter>
          </form>
        )}

        {phase.name === 'uploading' && (
          <div className="grid gap-5" role="status">
            <DialogHeader>
              <DialogTitle>{t('setup.archive.uploading.title')}</DialogTitle>
              <DialogDescription>
                {phase.progress >= 1
                  ? t('setup.archive.uploading.verifying')
                  : t('setup.archive.uploading.progress', { percent: Math.round(phase.progress * 100) })}
              </DialogDescription>
            </DialogHeader>
            <Progress
              value={Math.round(phase.progress * 100)}
              aria-label={t('setup.archive.uploading.title')}
            />
          </div>
        )}

        {phase.name === 'restarting' && (
          <div className="grid gap-5" role="status">
            <DialogHeader>
              <DialogTitle>{t('setup.archive.restarting.title')}</DialogTitle>
              <DialogDescription>
                {desktop ? t('setup.archive.restarting.bodyDesktop') : t('setup.archive.restarting.body')}
              </DialogDescription>
            </DialogHeader>
            <div className="flex items-center gap-3 rounded-xl bg-muted/50 px-4 py-3 ring-1 ring-foreground/10">
              <CircleNotch className="size-5 shrink-0 animate-spin text-muted-foreground" aria-hidden="true" />
              <p className="text-sm text-muted-foreground">
                {phase.reachable ? t('setup.archive.restarting.applying') : t('setup.archive.restarting.waiting')}
              </p>
            </div>
          </div>
        )}

        {outcome?.state === 'done' && (
          <div className="grid gap-5">
            <DialogHeader>
              <div className="mb-1 inline-flex size-10 items-center justify-center rounded-lg bg-muted text-foreground">
                <CheckCircle className="size-5" aria-hidden="true" />
              </div>
              <DialogTitle>{t('setup.archive.done.title')}</DialogTitle>
              <DialogDescription>
                {outcome.tables > 0
                  ? t('setup.archive.done.summary', {
                      tables: outcome.tables.toLocaleString(i18n.language),
                      rows: outcome.rows.toLocaleString(i18n.language),
                    })
                  : t('setup.archive.done.restored')}{' '}
                {t('setup.archive.done.next')}
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button type="button" onClick={goToLogin}>
                {t('setup.archive.done.cta')}
              </Button>
            </DialogFooter>
          </div>
        )}

        {outcome?.state === 'failed' && (
          <div className="grid gap-5">
            <DialogHeader>
              <DialogTitle>{t('setup.archive.failed.title')}</DialogTitle>
              <DialogDescription>{t('setup.archive.failed.untouched')}</DialogDescription>
            </DialogHeader>
            <AlertBanner variant="prominent" role="alert">
              {t(archiveCodeKey(outcome.code))}
            </AlertBanner>
            <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
              <Button type="button" variant="ghost" onClick={() => handleOpenChange(false)}>
                {t('common.close')}
              </Button>
              <Button type="button" onClick={retry}>
                {t('common.retry')}
              </Button>
            </DialogFooter>
          </div>
        )}

        {outcome?.state === 'timeout' && (
          <div className="grid gap-5">
            <DialogHeader>
              <DialogTitle>{t('setup.archive.timeout.title')}</DialogTitle>
              <DialogDescription>
                {desktop ? t('setup.archive.timeout.bodyDesktop') : t('setup.archive.timeout.body')}
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button type="button" onClick={() => void resume()}>
                {t('setup.archive.timeout.cta')}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
