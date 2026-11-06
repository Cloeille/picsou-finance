import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Loader2, ShieldCheck, ShieldOff, KeyRound, Download } from 'lucide-react'
import { useMfaStatus } from '@/features/mfa/hooks'
import { ssoErrorMessage } from '@/features/sso/errors'
import { useSignInMethods } from '@/features/sso/hooks'
import { MfaEnrollDialog } from './MfaEnrollDialog'
import { MfaDisableDialog } from './MfaDisableDialog'
import { RecoveryCodesDialog } from './RecoveryCodesDialog'
import { SessionsList } from './SessionsList'
import { ExportDataDialog } from './ExportDataDialog'
import type { ReauthAction } from './ReauthPanel'
import { LinkIdentityDialog, SignInMethodsCard } from './SignInMethodsCard'
import { RemovePasswordDialog } from './RemovePasswordDialog'
import { SetPasswordDialog } from './SetPasswordDialog'
import { UnlinkIdentityDialog } from './UnlinkIdentityDialog'

/** One-shot parameters the IdP round trips (re-authentication, linking) land back here with. */
const RETURN_PARAMS = ['resume', 'ssoError', 'sso'] as const

type Arrival =
  | { kind: 'none' }
  | { kind: 'ssoError'; code: string }
  | { kind: 'linked' }
  | { kind: 'resume'; action: ReauthAction }

function readArrival(params: URLSearchParams): Arrival {
  const code = params.get('ssoError')
  // A refused round trip never resumes the action it was meant to unlock.
  if (code) return { kind: 'ssoError', code }
  if (params.get('sso') === 'linked') return { kind: 'linked' }
  const action = parseResume(params.get('resume'))
  return action ? { kind: 'resume', action } : { kind: 'none' }
}

function parseResume(value: string | null): ReauthAction | null {
  switch (value) {
    case 'export':
    case 'remove-password':
    case 'set-password':
    case 'link':
      return value
  }
  const unlink = /^unlink-([1-9]\d*)$/.exec(value ?? '')
  return unlink ? `unlink-${Number(unlink[1])}` : null
}

interface OpenDialog {
  action: ReauthAction
  /** Reopened on the way back from the IdP re-authentication, so already confirmed. */
  resumed: boolean
}

/**
 * Top-level "Security" panel rendered as the contents of the SectionCard in
 * SettingsPage: sign-in methods, 2FA (password accounts only), the data
 * export and the persistent session list (Remember Me devices). Rows key
 * their layout to the card's width (container query) and stack when narrow.
 */
export function SecuritySection() {
  const { t } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()
  const [arrival] = useState(() => readArrival(searchParams))
  const [dialog, setDialog] = useState<OpenDialog | null>(() =>
    arrival.kind === 'resume' ? { action: arrival.action, resumed: true } : null,
  )
  const { data: status, isLoading } = useMfaStatus()
  const { data: methods } = useSignInMethods()
  const [enrollOpen, setEnrollOpen] = useState(false)
  const [disableOpen, setDisableOpen] = useState(false)
  const [regenerateOpen, setRegenerateOpen] = useState(false)
  const sectionRef = useRef<HTMLDivElement>(null)

  // Drop the one-shot parameters once read, so a reload cannot replay them.
  useEffect(() => {
    if (!RETURN_PARAMS.some(name => searchParams.has(name))) return
    setSearchParams(params => {
      RETURN_PARAMS.forEach(name => params.delete(name))
      return params
    }, { replace: true })
  }, [searchParams, setSearchParams])

  // The section sits below the fold: bring the outcome of the round trip into view.
  useEffect(() => {
    if (arrival.kind === 'ssoError' || arrival.kind === 'linked') {
      sectionRef.current?.scrollIntoView({ block: 'start' })
    }
  }, [arrival])

  const openDialog = (action: ReauthAction) => setDialog({ action, resumed: false })
  const closeDialog = (open: boolean) => {
    if (!open) setDialog(null)
  }
  const resumedFor = (action: ReauthAction) => dialog?.action === action && dialog.resumed
  const unlinkTarget = methods?.identities.find(identity => dialog?.action === `unlink-${identity.id}`) ?? null

  return (
    <div ref={sectionRef} className="@container space-y-6">
      {arrival.kind === 'ssoError' && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {ssoErrorMessage(t, arrival.code)}
        </p>
      )}
      {arrival.kind === 'linked' && (
        <p role="status" className="rounded-md bg-muted px-3 py-2 text-sm">
          {t('settings.signInMethods.linked')}
        </p>
      )}

      {/* ── Sign-in methods ──────────────────────────────────────────── */}
      <SignInMethodsCard onAction={openDialog} />

      {/* ── 2FA block: Picsou's TOTP only protects the password ──────── */}
      {methods?.hasPassword && (
        <div className="space-y-3 border-t pt-4 first:border-t-0 first:pt-0">
          <div className="flex flex-col gap-3 @xl:flex-row @xl:items-center @xl:justify-between">
            <div className="flex items-center gap-2">
              {status?.enabled ? (
                <ShieldCheck className="size-5 text-emerald-600 dark:text-emerald-400" />
              ) : (
                <ShieldOff className="size-5 text-muted-foreground" />
              )}
              <div>
                <p className="text-sm font-medium">{t('settings.mfaStatus')}</p>
                <p className="text-xs text-muted-foreground">
                  {isLoading
                    ? '...'
                    : status?.enabled
                      ? t('settings.mfaEnabled')
                      : t('settings.mfaDisabled')}
                  {status?.enabled && (
                    <>
                      {' · '}
                      {t('settings.mfaRecoveryCodesRemaining', {
                        count: status.remainingRecoveryCodes,
                      })}
                    </>
                  )}
                </p>
              </div>
            </div>

            {isLoading ? (
              <Loader2 size={16} className="animate-spin text-muted-foreground" />
            ) : status?.enabled ? (
              <Button variant="destructive" size="sm" onClick={() => setDisableOpen(true)}>
                {t('settings.mfaDisable')}
              </Button>
            ) : (
              <Button size="sm" onClick={() => setEnrollOpen(true)}>
                {t('settings.mfaEnable')}
              </Button>
            )}
          </div>

          {status?.enabled && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setRegenerateOpen(true)}
              className="w-full @xl:w-auto"
            >
              <KeyRound size={14} className="mr-1.5" />
              {t('settings.mfaRegenerate')}
            </Button>
          )}
        </div>
      )}

      {/* ── GDPR data export block ───────────────────────────────────── */}
      <div className="space-y-2 pt-4 border-t">
        <div className="flex flex-col gap-3 @xl:flex-row @xl:items-center @xl:justify-between">
          <div>
            <p className="text-sm font-medium">{t('settings.exportTitle')}</p>
            <p className="text-xs text-muted-foreground">
              {t('settings.exportShortDesc')}
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => openDialog('export')}
            className="w-full @xl:w-auto"
          >
            <Download size={14} className="mr-1.5" />
            {t('settings.exportButton')}
          </Button>
        </div>
      </div>

      {/* ── Persistent sessions block ────────────────────────────────── */}
      <div className="space-y-2 pt-4 border-t">
        <div>
          <p className="text-sm font-medium">{t('settings.sessions')}</p>
          <p className="text-xs text-muted-foreground">
            {t('settings.sessionsDescription')}
          </p>
        </div>
        <SessionsList />
      </div>

      <MfaEnrollDialog open={enrollOpen} onOpenChange={setEnrollOpen} />
      <MfaDisableDialog open={disableOpen} onOpenChange={setDisableOpen} />
      <RecoveryCodesDialog open={regenerateOpen} onOpenChange={setRegenerateOpen} />
      <ExportDataDialog
        open={dialog?.action === 'export'}
        onOpenChange={closeDialog}
        resumed={resumedFor('export')}
      />
      {methods && (
        <>
          <RemovePasswordDialog
            open={dialog?.action === 'remove-password'}
            onOpenChange={closeDialog}
            resumed={resumedFor('remove-password')}
            providerName={methods.providerName}
          />
          <SetPasswordDialog
            open={dialog?.action === 'set-password'}
            onOpenChange={closeDialog}
            resumed={resumedFor('set-password')}
            providerName={methods.providerName}
          />
          <UnlinkIdentityDialog
            identity={unlinkTarget}
            onOpenChange={closeDialog}
            resumed={unlinkTarget !== null && resumedFor(`unlink-${unlinkTarget.id}`)}
            providerName={methods.providerName}
          />
          <LinkIdentityDialog
            open={dialog?.action === 'link'}
            onOpenChange={closeDialog}
            resumed={resumedFor('link')}
            providerName={methods.providerName}
            mfaEnabled={status?.enabled === true}
          />
        </>
      )}
    </div>
  )
}
