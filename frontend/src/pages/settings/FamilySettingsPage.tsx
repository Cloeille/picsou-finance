import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { PageHeader } from '@/components/shared/PageHeader'
import { useAuthStore } from '@/stores/auth-store'
import {
  useFamilyMembers,
  useCreateMember,
  useDeleteMember,
  useGenerateActivationLink,
  useSharingSettings,
  useUpdateSharingSettings,
} from '@/features/family/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { useGoals } from '@/features/goals/hooks'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { formatApiError } from '@/lib/errors'
import { Plus, Trash2, Link, Shield } from 'lucide-react'

export function FamilySettingsPage() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const isAdmin = user?.role === 'ADMIN'

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('family.settings.title')}
      />

      {isAdmin && <MemberManagement />}
      <SharingSection />
    </div>
  )
}

function MemberManagement() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const { data: members, isLoading } = useFamilyMembers()
  const createMember = useCreateMember()
  const deleteMember = useDeleteMember()
  const generateLink = useGenerateActivationLink()
  const [newName, setNewName] = useState('')
  const [activationLink, setActivationLink] = useState<string | null>(null)
  // Independent members own private data; deletion is irreversible, so we require
  // the admin to retype the display name before the destructive call is enabled.
  const [memberToDelete, setMemberToDelete] = useState<
    { id: number; displayName: string; independent: boolean } | null
  >(null)

  if (isLoading) return <div>{t('common.loading')}</div>

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Shield className="size-5" />
          {t('family.settings.members')}
        </CardTitle>
        <CardDescription>
          {t('family.settings.membersDesc')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Existing members list */}
        <div className="space-y-2">
          {members?.map((member) => {
            const isOwnProfile = member.id === user?.memberId
            const isIndependent = member.managed && member.hasLogin && member.activated

            return (
              <div key={member.id} className="flex items-center justify-between rounded-lg border p-3">
                <div>
                  <div className="flex items-center gap-2">
                    <p className="font-medium">{member.displayName}</p>
                    {isOwnProfile && (
                      <span className="text-xs text-muted-foreground">({t('family.settings.you')})</span>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {isIndependent
                      ? t('family.settings.memberIndependent')
                      : member.managed
                        ? member.hasLogin
                          ? t('family.settings.memberPending')
                          : t('family.settings.memberManaged')
                        : t('family.settings.memberAdmin')}
                  </p>
                </div>
                <div className="flex gap-2">
                  {!isIndependent && member.managed && !member.hasLogin && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        generateLink.mutate(member.id, {
                          onSuccess: (data) => setActivationLink(data.activationLink),
                        })
                      }}
                    >
                      <Link className="mr-1 size-3" />
                      {t('family.settings.createLogin')}
                    </Button>
                  )}
                  {!isIndependent && !member.activated && member.hasLogin && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        generateLink.mutate(member.id, {
                          onSuccess: (data) => setActivationLink(data.activationLink),
                        })
                      }}
                    >
                      <Link className="mr-1 size-3" />
                      {t('family.settings.regenerateLink')}
                    </Button>
                  )}
                  {!isOwnProfile && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        setMemberToDelete({
                          id: member.id,
                          displayName: member.displayName,
                          independent: isIndependent,
                        })
                      }
                    >
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  )}
                </div>
              </div>
            )
          })}
        </div>

        {/* Activation link display */}
        {activationLink && (
          <div className="rounded-lg border bg-muted p-3">
            <p className="text-sm font-medium mb-1">{t('family.settings.activationLink')}</p>
            <code className="text-xs break-all">{window.location.origin}{activationLink}</code>
          </div>
        )}

        {/* Add member form */}
        <div className="flex gap-2">
          <Input
            placeholder={t('family.settings.memberName')}
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && newName.trim()) {
                createMember.mutate({ displayName: newName.trim() })
                setNewName('')
              }
            }}
          />
          <Button
            onClick={() => {
              if (newName.trim()) {
                createMember.mutate({ displayName: newName.trim() })
                setNewName('')
              }
            }}
            disabled={!newName.trim()}
          >
            <Plus className="mr-1 size-4" />
            {t('common.add')}
          </Button>
        </div>

        <ConfirmDialog
          open={memberToDelete !== null}
          onOpenChange={(open) => { if (!open) { setMemberToDelete(null); deleteMember.reset() } }}
          title={t('family.settings.deleteMemberTitle')}
          description={t('family.settings.deleteMemberWarning', { name: memberToDelete?.displayName })}
          onConfirm={() => {
            if (memberToDelete) {
              // Close only on success — on error the dialog stays open and shows why.
              deleteMember.mutate(memberToDelete.id, {
                onSuccess: () => setMemberToDelete(null),
              })
            }
          }}
          loading={deleteMember.isPending}
          error={deleteMember.isError ? formatApiError(deleteMember.error, t) : undefined}
          variant="destructive"
          // Independent members own private data; require retyping their name.
          confirmPhrase={memberToDelete?.independent ? memberToDelete.displayName : undefined}
        />
      </CardContent>
    </Card>
  )
}

function SharingSection() {
  const { t } = useTranslation()

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <SharingCard resourceType="ACCOUNT" title={t('family.settings.shareAccounts')} />
      <SharingCard resourceType="GOAL" title={t('family.settings.shareGoals')} />
    </div>
  )
}

function SharingCard({ resourceType, title }: { resourceType: string; title: string }) {
  const { t } = useTranslation()
  const { data: settings, isLoading } = useSharingSettings(resourceType)
  const updateSettings = useUpdateSharingSettings()
  const { data: accounts } = useAccounts()
  const { data: goals } = useGoals()

  if (isLoading) return null

  const items = resourceType === 'ACCOUNT' ? accounts : goals
  const currentLevel = settings?.sharingLevel || 'NONE'

  function handleLevelChange(level: string) {
    updateSettings.mutate({
      resourceType,
      sharingLevel: level,
      sharedResourceIds: level === 'MANUAL' ? settings?.sharedResourceIds?.filter(id => id !== -1) : undefined,
    })
  }

  function toggleResource(id: number) {
    const currentIds = (settings?.sharedResourceIds || []).filter(i => i !== -1)
    const newIds = currentIds.includes(id)
      ? currentIds.filter(i => i !== id)
      : [...currentIds, id]
    updateSettings.mutate({ resourceType, sharingLevel: 'MANUAL', sharedResourceIds: newIds })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap gap-2">
          {(['NONE', 'ALL', 'MANUAL'] as const).map((level) => (
            <Button
              key={level}
              variant={currentLevel === level ? 'default' : 'outline'}
              size="sm"
              className="flex-1 basis-0 px-4 sm:flex-none sm:basis-auto sm:px-8"
              onClick={() => handleLevelChange(level)}
            >
              {level === 'NONE' ? t('family.settings.shareNone') :
               level === 'ALL' ? t('family.settings.shareAll') :
               t('family.settings.shareManual')}
            </Button>
          ))}
        </div>

        {currentLevel === 'MANUAL' && items && (
          <div className="space-y-1 max-h-48 overflow-y-auto">
            {items.map((item: { id: number; name: string }) => (
              <label key={item.id} className="flex items-center gap-2 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  checked={(settings?.sharedResourceIds || []).includes(item.id)}
                  onChange={() => toggleResource(item.id)}
                />
                {item.name}
              </label>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
