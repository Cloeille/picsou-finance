import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useAddSnapshot } from '@/features/accounts/hooks'
import { extractErrorMessage } from '@/lib/errors'
import { localeFromLanguage, parseAmount } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { NumericInput } from '@/components/shared/NumericInput'
import { Label } from '@/components/ui/label'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter
} from '@/components/ui/dialog'
import { Loader2 } from 'lucide-react'
import type { BalanceSnapshot } from '@/types/api'

function getLast12Months(locale: string) {
  const months = []
  const now = new Date()
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const year = d.getFullYear()
    const month = d.getMonth() + 1
    const key = `${year}-${String(month).padStart(2, '0')}`
    const lastDay = new Date(year, month, 0).getDate()
    const date = `${key}-${String(lastDay).padStart(2, '0')}`
    const label = d.toLocaleDateString(locale, { month: 'long', year: 'numeric' })
    months.push({ key, year, month, date, label })
  }
  return months
}

function snapshotForMonth(history: BalanceSnapshot[] | undefined, year: number, month: number) {
  return history
    ?.filter(s => {
      const [y, m] = s.date.split('-').map(Number)
      return y === year && m === month
    })
    .sort((a, b) => b.date.localeCompare(a.date))[0]
}

interface MonthEndBalanceModalProps {
  open: boolean
  onClose: () => void
  accountId: number
  history: BalanceSnapshot[] | undefined
  /** A card's debt is stored negative; the user reads and types the positive amount owed. */
  amountOwed?: boolean
}

export function MonthEndBalanceModal({ open, onClose, accountId, history, amountOwed = false }: MonthEndBalanceModalProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={o => { if (!o) onClose() }}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t('accounts.monthlyHistory')}</DialogTitle>
          {amountOwed && <p className="text-sm text-muted-foreground">{t('accounts.currentDebt')}</p>}
        </DialogHeader>
        {/* Mount the form only while open so its inputs seed from `history`
            via lazy initializers — no seed-on-open effect needed. */}
        {open && (
          <MonthEndForm
            key={accountId}
            accountId={accountId}
            history={history}
            amountOwed={amountOwed}
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

interface MonthEndFormProps {
  accountId: number
  history: BalanceSnapshot[] | undefined
  amountOwed: boolean
  onClose: () => void
}

function MonthEndForm({ accountId, history, amountOwed, onClose }: MonthEndFormProps) {
  const { t, i18n } = useTranslation()
  const addSnapshot = useAddSnapshot()

  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const months = useMemo(() => getLast12Months(locale), [locale])
  const [values, setValues] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    months.forEach(({ key, year, month }) => {
      const snap = snapshotForMonth(history, year, month)
      if (snap) initial[key] = String(amountOwed ? Math.abs(snap.balance) : snap.balance)
    })
    return initial
  })
  const [modified, setModified] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleChange = (key: string, value: string) => {
    setValues(prev => ({ ...prev, [key]: value }))
    setModified(prev => new Set([...prev, key]))
  }

  const handleSave = async () => {
    setSaving(true)
    setError(null)
    const toSave = months.filter(({ key }) => modified.has(key) && values[key] !== '')
    try {
      await Promise.all(
        toSave.map(({ key, year, month, date }) => {
          const existing = snapshotForMonth(history, year, month)
          const saveDate = existing ? existing.date : date
          return addSnapshot.mutateAsync({
            id: accountId,
            balance: parseAmount(values[key]),
            date: saveDate,
          })
        })
      )
      onClose()
    } catch (err) {
      setError(extractErrorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="flex flex-col gap-0 max-h-[60vh] overflow-y-auto -mx-1 px-1">
        {months.map(({ key, label }) => {
          const isModified = modified.has(key)
          return (
            <div
              key={key}
              className="flex items-center gap-3 py-2 border-b last:border-0"
            >
              <Label
                htmlFor={`month-${key}`}
                className={`flex-1 capitalize text-sm ${isModified ? 'text-foreground font-semibold' : 'text-muted-foreground font-medium'}`}
              >
                {label}
              </Label>
              <NumericInput
                id={`month-${key}`}
                value={values[key] ?? ''}
                onChange={e => handleChange(key, e.target.value)}
                placeholder="—"
                className="w-40 text-right"
              />
            </div>
          )
        })}
      </div>

      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}

      <DialogFooter className="gap-2">
        <Button variant="outline" onClick={onClose} type="button">
          {t('common.cancel')}
        </Button>
        <Button
          onClick={handleSave}
          disabled={saving || modified.size === 0}
        >
          {saving && (
            <Loader2 size={12} className="mr-1.5 animate-spin" />
          )}
          {t('accounts.addSnapshot')}
          {modified.size > 0 && ` (${modified.size})`}
        </Button>
      </DialogFooter>
    </>
  )
}
