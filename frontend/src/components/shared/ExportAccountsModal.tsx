import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Download, Loader2 } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { useExportAccountsXlsx } from '@/features/export/hooks'
import { sheetLabels } from '@/features/export/labels'
import { extractErrorMessage } from '@/lib/errors'
import type { Account } from '@/types/api'

interface ExportAccountsModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  accounts: Account[]
}

export function ExportAccountsModal({ open, onOpenChange, accounts }: ExportAccountsModalProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('accounts.export.title')}</DialogTitle>
          <DialogDescription>{t('accounts.export.description')}</DialogDescription>
        </DialogHeader>
        {/* Mounted only while open, so the selection starts fresh each time from a lazy
            initializer rather than from a reset-on-open effect. */}
        {open && <ExportPicker accounts={accounts} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function ExportPicker({ accounts, onDone }: { accounts: Account[]; onDone: () => void }) {
  const { t } = useTranslation()
  const exportAccounts = useExportAccountsXlsx()

  // Everything selected on open: exporting the lot is the common intent, and unticking the two
  // accounts you do not want beats ticking the eight you do.
  const [selected, setSelected] = useState<Set<number>>(() => new Set(accounts.map(a => a.id)))

  const allSelected = accounts.length > 0 && selected.size === accounts.length
  const sorted = useMemo(
    () => [...accounts].sort((a, b) => a.name.localeCompare(b.name)),
    [accounts]
  )

  function toggle(id: number) {
    setSelected(prev => {
      const next = new Set(prev)
      if (!next.delete(id)) next.add(id)
      return next
    })
  }

  function toggleAll() {
    setSelected(allSelected ? new Set() : new Set(accounts.map(a => a.id)))
  }

  function submit() {
    exportAccounts.mutate(
      { accountIds: [...selected], labels: sheetLabels(t) },
      {
        onSuccess: ({ filename }) => {
          toast.success(t('accounts.export.success', { filename }))
          onDone()
        },
        onError: error => toast.error(extractErrorMessage(error)),
      }
    )
  }

  if (accounts.length === 0) {
    return <EmptyState icon={<Download className="size-12" />} title={t('accounts.export.empty')} />
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <Button variant="ghost" size="sm" onClick={toggleAll}>
          {allSelected ? t('accounts.export.selectNone') : t('accounts.export.selectAll')}
        </Button>
        <span className="text-sm text-muted-foreground">
          {t('accounts.export.selected', { count: selected.size })}
        </span>
      </div>

      <div className="max-h-80 overflow-y-auto rounded-lg border">
        <ul className="divide-y">
          {sorted.map(account => (
            <li key={account.id}>
              <label className="flex cursor-pointer items-center gap-3 px-4 py-3 hover:bg-muted">
                <Checkbox
                  checked={selected.has(account.id)}
                  onCheckedChange={() => toggle(account.id)}
                />
                <span className="min-w-0 flex-1 truncate text-sm font-medium">{account.name}</span>
                <AccountTypeBadge type={account.type} />
                <CurrencyDisplay
                  value={account.currentBalanceEur}
                  className="text-sm tabular-nums text-muted-foreground"
                />
              </label>
            </li>
          ))}
        </ul>
      </div>

      <DialogFooter>
        <Button onClick={submit} disabled={selected.size === 0 || exportAccounts.isPending}>
          {exportAccounts.isPending
            ? <Loader2 className="size-4 animate-spin" />
            : <Download className="size-4" />}
          {t('accounts.export.submit')}
        </Button>
      </DialogFooter>
    </div>
  )
}
