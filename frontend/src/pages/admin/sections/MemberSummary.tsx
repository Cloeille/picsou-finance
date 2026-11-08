import { useTranslation } from 'react-i18next'
import { ShieldCheck } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import type { FamilyMemberItem } from '@/features/family/api'

/** Who a member is, their account status and how they sign in: the leading part of a member row. */
export function MemberSummary({ member: m, isSelf }: { member: FamilyMemberItem; isSelf: boolean }) {
  const { t } = useTranslation()
  const isAdminMember = !m.managed
  const status = isAdminMember
    ? 'statusAdmin'
    : m.activated
      ? 'statusActive'
      : m.hasLogin
        ? 'statusPending'
        : 'statusManaged'

  return (
    <div className="flex items-center gap-3 min-w-0">
      <div
        className="size-9 shrink-0 rounded-full"
        style={{ backgroundColor: m.avatarColor }}
        aria-hidden
      />
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <p className="font-medium truncate">{m.displayName}</p>
          {isSelf && (
            <span className="text-sm text-muted-foreground">
              {t('admin.members.you')}
            </span>
          )}
        </div>
        <p className="text-sm text-muted-foreground truncate">
          <span className="font-mono">
            {m.loginName ?? t('admin.members.noLogin')}
          </span>
          <span className="mx-2">·</span>
          <span>{t(`admin.members.${status}`)}</span>
          {m.mfaEnabled && (
            <>
              <span className="mx-2">·</span>
              <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
                <ShieldCheck size={11} />
                {t('admin.members.mfaOn')}
              </span>
            </>
          )}
        </p>
        {(m.hasPassword || m.ssoLinked) && (
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {m.hasPassword && <Badge variant="secondary">{t('admin.members.signInPassword')}</Badge>}
            {m.ssoLinked && <Badge variant="secondary">{t('admin.members.signInSso')}</Badge>}
          </div>
        )}
      </div>
    </div>
  )
}
