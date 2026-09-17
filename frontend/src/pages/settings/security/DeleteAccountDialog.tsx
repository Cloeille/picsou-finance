import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2, RotateCcw, Trash2, TriangleAlert } from 'lucide-react'
import { useMfaStatus } from '@/features/mfa/hooks'
import {
  useAccountDeletionImpact,
  useDeleteMyAccount,
} from '@/features/account-deletion/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { resetClientState } from '@/lib/reset-client-state'
import { formatApiError, getErrorCode } from '@/lib/errors'

function dialogCopyKeys(isReady: boolean, isReset: boolean) {
  if (!isReady) {
    return {
      title: 'settings.deleteAccountReviewTitle',
      description: 'settings.deleteAccountReviewDesc',
    }
  }
  if (isReset) {
    return {
      title: 'settings.deleteAccountResetTitle',
      description: 'settings.deleteAccountResetDesc',
    }
  }
  return {
    title: 'settings.deleteAccountTitle',
    description: 'settings.deleteAccountDeleteDesc',
  }
}

export function DeleteAccountDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const user = useAuthStore(s => s.user)
  const username = user?.username ?? ''
  const mfaStatus = useMfaStatus({ fresh: true })
  const deletionImpact = useAccountDeletionImpact(open)
  const deleteMutation = useDeleteMyAccount()

  const [password, setPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [confirmText, setConfirmText] = useState('')
  const [error, setError] = useState<string | null>(null)

  const isPending = deleteMutation.isPending
  const isLoading = mfaStatus.isPending || mfaStatus.isFetching
    || deletionImpact.isPending || deletionImpact.isFetching
  const hasLoadError = mfaStatus.isError || deletionImpact.isError
  const isReady = mfaStatus.isSuccess && deletionImpact.isSuccess && !isLoading
  const mode = deletionImpact.data?.mode
  const isReset = mode === 'RESET_LAST_ADMIN'
  const phraseOk = username !== '' && confirmText === username
  const credentialOk = mfaStatus.data?.enabled
    ? /^\d{6}$/.test(totpCode)
    : password.trim().length > 0
  const canSubmit = isReady && phraseOk && credentialOk && !isPending
  const copyKeys = dialogCopyKeys(isReady, isReset)

  const requestClose = (nextOpen: boolean) => {
    if (!nextOpen && isPending) return
    onOpenChange(nextOpen)
  }

  const retryQueries = () => {
    if (mfaStatus.isError) void mfaStatus.refetch()
    if (deletionImpact.isError) void deletionImpact.refetch()
  }

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)

    try {
      const result = await deleteMutation.mutateAsync({
        reAuth: mfaStatus.data.enabled ? { totpCode } : { password },
      })
      useAuthStore.getState().logout()
      resetClientState(queryClient)
      toast.success(t(
        result.mode === 'RESET_LAST_ADMIN'
          ? 'settings.deleteAccountResetSuccess'
          : 'settings.deleteAccountDeletedSuccess',
      ))
      navigate('/login', { replace: true })
    } catch (caught: unknown) {
      const code = getErrorCode(caught)
      if (code === 'REAUTH_FAILED') {
        setError(t('settings.deleteAccountInvalidCredentials'))
      } else if (code === 'ACCOUNT_DELETION_RATE_LIMITED') {
        setError(t('settings.deleteAccountRateLimited'))
      } else if (code === 'LAST_ADMIN') {
        setError(t('settings.deleteAccountLastAdmin'))
      } else {
        setError(formatApiError(caught, t))
      }
    }
  }

  return (
    <Dialog open={open} onOpenChange={requestClose}>
      <DialogContent
        className="sm:max-w-md max-h-[90vh] overflow-y-auto"
        showCloseButton={!isPending}
        aria-busy={isPending}
        onEscapeKeyDown={event => {
          if (isPending) event.preventDefault()
        }}
        onPointerDownOutside={event => {
          if (isPending) event.preventDefault()
        }}
        onInteractOutside={event => {
          if (isPending) event.preventDefault()
        }}
      >
        <DialogHeader>
          <DialogTitle>{t(copyKeys.title)}</DialogTitle>
          <DialogDescription>{t(copyKeys.description)}</DialogDescription>
        </DialogHeader>

        {isLoading && (
          <div role="status" className="flex items-center gap-2 py-4 text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            {t('settings.deleteAccountLoading')}
          </div>
        )}

        {hasLoadError && !isLoading && (
          <div className="space-y-3">
            <p role="alert" className="text-sm text-destructive">
              {t('settings.deleteAccountLoadError')}
            </p>
            <Button type="button" variant="outline" onClick={retryQueries}>
              <RotateCcw className="mr-1.5 size-4" />
              {t('settings.deleteAccountRetry')}
            </Button>
          </div>
        )}

        {isReady && (
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="flex gap-2 rounded-xl bg-destructive/10 p-3 text-sm text-destructive">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" />
              <p>{t('settings.deleteAccountSharedWarning')}</p>
            </div>

            {user?.role === 'ADMIN' && !isReset && (
              <p className="text-xs text-muted-foreground">
                {t('settings.deleteAccountAdminRaceNote')}
              </p>
            )}

            {mfaStatus.data.enabled ? (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="delete-totp">{t('auth.mfaCodeLabel')}</Label>
                <Input
                  id="delete-totp"
                  type="text"
                  inputMode="numeric"
                  pattern="\d{6}"
                  maxLength={6}
                  value={totpCode}
                  onChange={event => setTotpCode(event.target.value)}
                  autoComplete="one-time-code"
                  required
                  placeholder="123456"
                  autoFocus
                  disabled={isPending}
                  className="text-center text-lg tracking-widest font-mono"
                />
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="delete-pw">{t('settings.currentPassword')}</Label>
                <Input
                  id="delete-pw"
                  type="password"
                  value={password}
                  onChange={event => setPassword(event.target.value)}
                  autoComplete="current-password"
                  required
                  autoFocus
                  disabled={isPending}
                />
              </div>
            )}

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="delete-confirm" className="break-words">
                {t('common.confirmTypePrompt', { phrase: username })}
              </Label>
              <Input
                id="delete-confirm"
                type="text"
                value={confirmText}
                onChange={event => setConfirmText(event.target.value)}
                placeholder={username}
                autoComplete="off"
                required
                disabled={isPending}
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}

            <DialogFooter className="flex-col-reverse sm:flex-col-reverse gap-2">
              <Button
                type="button"
                variant="ghost"
                onClick={() => requestClose(false)}
                disabled={isPending}
              >
                {t('settings.mfaCancel')}
              </Button>
              <Button
                type="submit"
                variant="destructive"
                className="h-auto min-h-10 whitespace-normal py-2"
                disabled={!canSubmit}
              >
                {isPending ? (
                  <Loader2 size={14} className="mr-1.5 animate-spin" />
                ) : (
                  <Trash2 size={14} className="mr-1.5" />
                )}
                {isReset
                  ? t('settings.deleteAccountResetSubmit')
                  : t('settings.deleteAccountSubmit')}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
