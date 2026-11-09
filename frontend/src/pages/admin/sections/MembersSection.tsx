import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Users, Link as LinkIcon, KeyRound, Trash2, Copy, Check, ShieldOff, ShieldCheck, UserPlus } from 'lucide-react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { formatApiError } from '@/lib/errors'
import { useAuthStore } from '@/stores/auth-store'
import {
  useFamilyMembers,
  useCreateUserWithLogin,
  useDeleteMember,
  useGenerateActivationLink,
  useResetMemberPassword,
} from '@/features/family/hooks'
import { useAdminForceDisableMfa } from '@/features/mfa/hooks'

type GeneratedLink = { memberId: number; url: string } | null

export function MembersSection() {
  const { t } = useTranslation()
  const currentUser = useAuthStore((s) => s.user)
  const { data: members, isLoading } = useFamilyMembers()
  const createUser = useCreateUserWithLogin()
  const deleteMember = useDeleteMember()
  const generateActivation = useGenerateActivationLink()
  const resetPassword = useResetMemberPassword()
  const forceDisableMfa = useAdminForceDisableMfa()

  const [newName, setNewName] = useState('')
  const [link, setLink] = useState<GeneratedLink>(null)
  const [copied, setCopied] = useState(false)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [resetMfaId, setResetMfaId] = useState<number | null>(null)

  function showLink(memberId: number, path: string) {
    setLink({ memberId, url: `${window.location.origin}${path}` })
    setCopied(false)
  }

  function handleCreateUser() {
    const displayName = newName.trim()
    if (!displayName) return
    createUser.mutate(
      { displayName },
      {
        onSuccess: ({ member, activationLink }) => {
          showLink(member.id, activationLink)
          setNewName('')
        },
      },
    )
  }

  async function handleCopy() {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link.url)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard may be unavailable in non-https dev contexts; user can select-copy */
    }
  }

  function handleConfirmDelete() {
    if (deletingId == null) return
    deleteMember.mutate(deletingId, {
      onSuccess: () => setDeletingId(null),
    })
  }

  function handleConfirmResetMfa() {
    if (resetMfaId == null) return
    forceDisableMfa.mutate(resetMfaId, {
      onSuccess: () => setResetMfaId(null),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Users className="size-5" />
          {t('admin.members.title')}
        </CardTitle>
        <CardDescription>{t('admin.members.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-col gap-2 sm:flex-row">
          <Input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleCreateUser() }}
            placeholder={t('admin.members.createUserPlaceholder')}
            disabled={createUser.isPending}
          />
          <Button
            onClick={handleCreateUser}
            disabled={!newName.trim() || createUser.isPending}
            className="shrink-0"
          >
            <UserPlus className="size-4" />
            {t('admin.members.createUser')}
          </Button>
        </div>

        {isLoading ? (
          <p className="text-sm text-muted-foreground">{t('admin.members.loading')}</p>
        ) : !members || members.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('admin.members.noMembers')}</p>
        ) : (
          <ul className="divide-y rounded-lg border">
            {members.map((m) => {
              const isSelf = m.id === currentUser?.memberId
              const isAdminMember = !m.managed
              const status = isAdminMember
                ? 'statusAdmin'
                : m.activated
                  ? 'statusActive'
                  : m.hasLogin
                    ? 'statusPending'
                    : 'statusManaged'

              return (
                <li
                  key={m.id}
                  className="flex flex-col gap-3 p-3 sm:flex-row sm:items-center sm:justify-between"
                >
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
                    </div>
                  </div>

                  <div className="flex flex-wrap gap-2 sm:justify-end">
                    {/* Create login OR regenerate activation link (non-activated) */}
                    {m.managed && !m.activated && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={generateActivation.isPending}
                        onClick={() => {
                          generateActivation.mutate(m.id, {
                            onSuccess: (d) => showLink(m.id, d.activationLink),
                          })
                        }}
                      >
                        <LinkIcon className="size-3.5" />
                        {m.hasLogin
                          ? t('admin.members.regenerateLink')
                          : t('admin.members.createLogin')}
                      </Button>
                    )}

                    {/* Reset password (only for active accounts that the admin doesn't own) */}
                    {m.activated && !isSelf && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={resetPassword.isPending}
                        onClick={() => {
                          resetPassword.mutate(m.id, {
                            onSuccess: (d) => showLink(m.id, d.resetLink),
                          })
                        }}
                      >
                        <KeyRound className="size-3.5" />
                        {t('admin.members.resetPassword')}
                      </Button>
                    )}

                    {/* Reset 2FA — locked-out account recovery */}
                    {m.mfaEnabled && !isSelf && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setResetMfaId(m.id)}
                      >
                        <ShieldOff className="size-3.5" />
                        {t('admin.members.resetMfa')}
                      </Button>
                    )}

                    {/* Delete (anyone but self and the seed admin) */}
                    {!isSelf && m.managed && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setDeletingId(m.id)}
                      >
                        <Trash2 className="size-3.5" />
                        {t('admin.members.delete')}
                      </Button>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        )}

        {link && (
          <div className="rounded-lg border bg-muted p-3 space-y-2">
            <p className="text-sm font-medium">{t('admin.members.linkLabel')}</p>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <code className="flex-1 break-all rounded-xl bg-background px-3 py-2 text-sm">
                {link.url}
              </code>
              <Button size="sm" variant="outline" onClick={handleCopy}>
                {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
                {copied ? t('admin.members.copied') : t('admin.members.copyLink')}
              </Button>
            </div>
          </div>
        )}
      </CardContent>

      <ConfirmDialog
        open={deletingId !== null}
        onOpenChange={(o) => { if (!o) { setDeletingId(null); deleteMember.reset() } }}
        title={t('admin.members.deleteConfirmTitle')}
        description={t('admin.members.deleteConfirmDesc')}
        onConfirm={handleConfirmDelete}
        loading={deleteMember.isPending}
        error={deleteMember.isError ? formatApiError(deleteMember.error, t) : undefined}
        variant="destructive"
        // Activated members own private data; require retyping their name.
        confirmPhrase={
          members?.find((m) => m.id === deletingId)?.activated
            ? members.find((m) => m.id === deletingId)?.displayName
            : undefined
        }
      />

      <ConfirmDialog
        open={resetMfaId !== null}
        onOpenChange={(o) => { if (!o) setResetMfaId(null) }}
        title={t('admin.members.resetMfaConfirmTitle')}
        description={t('admin.members.resetMfaConfirmDesc')}
        onConfirm={handleConfirmResetMfa}
        loading={forceDisableMfa.isPending}
        variant="destructive"
      />
    </Card>
  )
}
