import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { DiscoveredRevolutAccount } from '@/types/api'

interface RevolutSelectionCardProps {
  discovered: DiscoveredRevolutAccount[]
  onConfirm: (selectedExternalIds: string[]) => void
  confirming?: boolean
}

interface AccountNode {
  account: DiscoveredRevolutAccount
  children: DiscoveredRevolutAccount[]
}

/**
 * Post-discovery step: lets the user pick which of the harvested wallets/pockets/vaults
 * to actually import. Seeded from `alreadyImported` (unticking a previously-imported
 * account soft-deletes it on confirm; ticking a new one imports it) — a lazy `useState`
 * initializer keyed on the `discovered` prop, no populate-on-mount effect (see
 * docs/conventions/frontend.md's key-remount rule).
 */
export function RevolutSelectionCard({ discovered, onConfirm, confirming = false }: RevolutSelectionCardProps) {
  const { t } = useTranslation()

  const [checked, setChecked] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(discovered.map((d) => [d.externalId, d.alreadyImported]))
  )

  // Children whose parent isn't in this batch (e.g. a vault with no wallet returned)
  // render top-level instead of being silently dropped.
  const tree = useMemo<AccountNode[]>(() => {
    const ids = new Set(discovered.map((d) => d.externalId))
    const childrenByParent = new Map<string, DiscoveredRevolutAccount[]>()
    const roots: DiscoveredRevolutAccount[] = []
    for (const account of discovered) {
      if (account.parentExternalId && ids.has(account.parentExternalId)) {
        const siblings = childrenByParent.get(account.parentExternalId) ?? []
        siblings.push(account)
        childrenByParent.set(account.parentExternalId, siblings)
      } else {
        roots.push(account)
      }
    }
    return roots.map((account) => ({ account, children: childrenByParent.get(account.externalId) ?? [] }))
  }, [discovered])

  const allSelected = discovered.length > 0 && discovered.every((d) => checked[d.externalId])

  function toggleAll(value: boolean) {
    setChecked(Object.fromEntries(discovered.map((d) => [d.externalId, value])))
  }

  // Ticking a child also ticks its parent — a picked pocket must never end up orphaned,
  // since the wallet has to be persisted first for the pocket's parentAccountId to resolve.
  function toggleChild(parentExternalId: string, externalId: string, value: boolean) {
    setChecked((prev) => {
      const next = { ...prev, [externalId]: value }
      if (value) next[parentExternalId] = true
      return next
    })
  }

  // Unticking a parent also unticks (and, in the UI, disables) its children.
  function toggleParent(node: AccountNode, value: boolean) {
    setChecked((prev) => {
      const next = { ...prev, [node.account.externalId]: value }
      if (!value) {
        for (const child of node.children) next[child.externalId] = false
      }
      return next
    })
  }

  function handleConfirm() {
    onConfirm(discovered.filter((d) => checked[d.externalId]).map((d) => d.externalId))
  }

  return (
    <Card size="sm">
      <CardContent className="space-y-4 py-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-medium">{t('sync.revolut.selection.title')}</p>
          <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
            <Checkbox checked={allSelected} onCheckedChange={(v) => toggleAll(v === true)} />
            {t('sync.revolut.selection.selectAll')}
          </label>
        </div>

        <div className="space-y-3">
          {tree.map((node) => (
            <div key={node.account.externalId} className="space-y-2">
              <AccountRow
                account={node.account}
                checked={checked[node.account.externalId] ?? false}
                onCheckedChange={(v) => toggleParent(node, v)}
              />
              {node.children.length > 0 && (
                <div className="ml-6 space-y-2 border-l pl-3">
                  {node.children.map((child) => (
                    <AccountRow
                      key={child.externalId}
                      account={child}
                      checked={checked[child.externalId] ?? false}
                      onCheckedChange={(v) => toggleChild(node.account.externalId, child.externalId, v)}
                    />
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>

        <Button className="w-full sm:w-auto" onClick={handleConfirm} disabled={confirming}>
          {confirming && <Loader2 className="size-4 animate-spin" />}
          {confirming ? t('sync.revolut.selection.importing') : t('sync.revolut.selection.confirm')}
        </Button>
      </CardContent>
    </Card>
  )
}

function AccountRow({
  account,
  checked,
  disabled = false,
  onCheckedChange,
}: {
  account: DiscoveredRevolutAccount
  checked: boolean
  disabled?: boolean
  onCheckedChange: (value: boolean) => void
}) {
  const { t } = useTranslation()
  return (
    <label
      className={`flex items-start justify-between gap-3 rounded-lg p-2 ${
        disabled ? 'opacity-50' : 'cursor-pointer hover:bg-muted/50'
      }`}
    >
      <div className="flex min-w-0 items-start gap-3">
        <Checkbox
          checked={checked}
          disabled={disabled}
          onCheckedChange={(v) => onCheckedChange(v === true)}
          className="mt-0.5"
        />
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{account.name}</p>
          <p className="text-xs text-muted-foreground">
            {t('sync.revolut.selection.transactions', { count: account.transactionCount })}
            {account.alreadyImported && <> · {t('sync.revolut.selection.alreadyImported')}</>}
          </p>
        </div>
      </div>
      <span className="shrink-0 text-sm font-semibold tabular-nums">
        <CurrencyDisplay value={account.balance} currency={account.currency} />
      </span>
    </label>
  )
}
