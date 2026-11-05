import { useState } from 'react'
import { useTranslation } from 'react-i18next'
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
        if (member) unlink.mutate(member.id, { onSuccess: onClose })
      }}
      loading={unlink.isPending}
      error={unlink.isError && unlink.variables === member?.id ? formatSsoApiError(unlink.error, t) : undefined}
      variant="destructive"
    />
  )
}
