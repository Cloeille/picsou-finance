import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { NumericInput } from '@/components/shared/NumericInput'
import { useOwnership, useUpdateOwnership } from '@/features/accounts/hooks'
import { useFamilyMembers } from '@/features/family/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { formatApiError } from '@/lib/errors'
import { parseAmount } from '@/lib/utils'
import type { Ownership } from '@/types/api'

interface OwnershipEditorProps {
  accountId: number
  /** Only the account's owner may change the split. */
  canEdit: boolean
}

interface Row {
  id: number
  displayName: string
  avatarColor: string
}

/**
 * Splits a property (or its mortgage) between family members.
 *
 * <p>The total may legitimately be under 100%: the remainder is held by someone outside
 * Picsou — a sibling in an indivision, an SCI — and belongs in nobody's net worth. It is
 * shown rather than quietly folded into the owner's share, which would invent money.
 */
export function OwnershipEditor({ accountId, canEdit }: OwnershipEditorProps) {
  const { data, isLoading } = useOwnership(accountId)

  // `/family/members` is admin-only and answers 403 otherwise, which the global interceptor
  // turns into an /error/403 redirect. So the roster is only fetched for admins; a non-admin
  // owner still adjusts the members already in the split, they just cannot add a new one.
  const isAdmin = useAuthStore(s => s.user?.role) === 'ADMIN'
  const { data: roster = [] } = useFamilyMembers({ enabled: isAdmin })

  if (isLoading) {
    return (
      <Card>
        <CardContent className="pt-6"><Skeleton className="h-24 w-full" /></CardContent>
      </Card>
    )
  }
  if (!data) return null

  // Admins pick from the whole family; everyone else edits the holders already recorded.
  const rows: Row[] = isAdmin && roster.length > 0
    ? roster.map(m => ({ id: m.id, displayName: m.displayName, avatarColor: m.avatarColor }))
    : data.shares.map(s => ({ id: s.memberId, displayName: s.displayName, avatarColor: s.avatarColor }))

  // Remounting on the loaded split seeds the inputs from it, instead of syncing server data
  // into local state through an effect (docs/conventions/frontend.md).
  const seed = data.shares.map(s => `${s.memberId}:${s.sharePercent}`).join(',')

  return (
    <OwnershipForm
      key={`${accountId}-${seed}`}
      accountId={accountId}
      ownership={data}
      rows={rows}
      canEdit={canEdit}
    />
  )
}

function OwnershipForm({ accountId, ownership, rows, canEdit }: {
  accountId: number
  ownership: Ownership
  rows: Row[]
  canEdit: boolean
}) {
  const { t } = useTranslation()
  const update = useUpdateOwnership()

  const [shares, setShares] = useState<Record<number, string>>(() =>
    Object.fromEntries(ownership.shares.map(s => [s.memberId, String(s.sharePercent)])))

  const total = Object.values(shares)
    .map(v => (v === '' ? 0 : parseAmount(v) || 0))
    .reduce((a, b) => a + b, 0)
  const unassigned = 100 - total
  const overAllocated = total > 100

  const save = () => {
    update.mutate({
      id: accountId,
      data: {
        shares: Object.entries(shares)
          .map(([memberId, value]) => ({
            memberId: Number(memberId),
            sharePercent: value === '' ? 0 : parseAmount(value) || 0,
          }))
          // A zero share means "not a holder", which is expressed by absence, not by a row.
          .filter(s => s.sharePercent > 0),
      },
    })
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{t('property.ownership.title')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">{t('property.ownership.hint')}</p>

        <div className="space-y-2">
          {rows.map(member => (
            <div key={member.id} className="flex items-center gap-3">
              <span
                className="size-3 shrink-0 rounded-full"
                style={{ backgroundColor: member.avatarColor }}
                aria-hidden
              />
              <Label htmlFor={`share-${member.id}`} className="flex-1">
                {member.displayName}
              </Label>
              <div className="flex w-28 items-center gap-1">
                <NumericInput
                  id={`share-${member.id}`}
                  value={shares[member.id] ?? ''}
                  disabled={!canEdit}
                  onChange={e => setShares(prev => ({ ...prev, [member.id]: e.target.value }))}
                />
                <span className="text-sm text-muted-foreground">%</span>
              </div>
            </div>
          ))}
        </div>

        <div className="flex items-center justify-between border-t pt-3 text-sm">
          <span className="text-muted-foreground">{t('property.ownership.total')}</span>
          <span className={`font-medium tabular-nums ${overAllocated ? 'text-destructive' : ''}`}>
            {total.toFixed(1)} %
          </span>
        </div>

        {overAllocated && (
          <p role="alert" className="flex items-center gap-2 text-sm text-destructive">
            <AlertTriangle className="size-4 shrink-0" />
            {t('property.ownership.overAllocated')}
          </p>
        )}

        {!overAllocated && unassigned > 0.01 && (
          <p className="flex items-start gap-2 rounded-md bg-muted/50 p-3 text-xs text-muted-foreground">
            <AlertTriangle className="mt-0.5 size-4 shrink-0" />
            {t('property.ownership.unassigned', { percent: unassigned.toFixed(1) })}
          </p>
        )}

        {update.isError && (
          <p role="alert" className="text-sm text-destructive">
            {formatApiError(update.error, t, 'property.ownership.saveError')}
          </p>
        )}

        {canEdit && (
          <div className="flex justify-end">
            <Button size="sm" onClick={save} disabled={update.isPending || overAllocated}>
              {update.isPending ? t('common.loading') : t('common.save')}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
