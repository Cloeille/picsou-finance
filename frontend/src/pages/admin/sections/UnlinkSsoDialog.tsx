import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import type { FamilyMemberItem } from '@/features/family/api'
import { formatSsoApiError } from '@/features/sso/errors'
import { useAdminUnlinkIdentities } from '@/features/sso/hooks'

interface UnlinkSsoDialogProps {
  /** The member whose SSO identities to remove; the dialog is open while it is set. */
  member: FamilyMemberItem | null
  onClose: () => void
}

export function UnlinkSsoDialog({ member, onClose }: UnlinkSsoDialogProps) {
  const { t } = useTranslation()
  const unlink = useAdminUnlinkIdentities()
  // Keeps the name in the title while the dialog fades out after `member` is cleared.
  const [shown, setShown] = useState(member)
  if (member && member !== shown) setShown(member)
  // Read when a request settles, which can be after the dialog closed or opened for someone else.
  const openMemberId = useRef<number | null>(null)
  useEffect(() => {
    openMemberId.current = member?.id ?? null
  }, [member])

  const confirm = (target: FamilyMemberItem) => {
    unlink.mutate(target.id, {
      onSuccess: () => {
        if (openMemberId.current === target.id) onClose()
      },
      onError: err => {
        // The confirmation that would show the error has closed: report it where it stays visible.
        if (openMemberId.current === target.id) return
        toast.error(t('admin.members.unlinkSsoFailed', { name: target.displayName }), {
          description: formatSsoApiError(err, t),
        })
      },
    })
  }

  const handleOpenChange = (open: boolean) => {
    if (open) return
    onClose()
    // An unlink in flight keeps running (offline, it waits for the network): its pending state
    // keeps Confirm disabled if the dialog reopens, so a second request cannot start.
    if (!unlink.isPending) unlink.reset()
  }

  return (
    <ConfirmDialog
      open={member !== null}
      onOpenChange={handleOpenChange}
      title={t('admin.members.unlinkSsoTitle', { name: shown?.displayName ?? '' })}
      description={t('admin.members.unlinkSsoDescription')}
      confirmLabel={t('admin.members.unlinkSso')}
      onConfirm={() => {
        if (member) confirm(member)
      }}
      loading={unlink.isPending}
      error={unlink.isError && unlink.variables === member?.id ? formatSsoApiError(unlink.error, t) : undefined}
      variant="destructive"
    />
  )
}
