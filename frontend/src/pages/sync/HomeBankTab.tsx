import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import { ACCOUNT_COLORS, ACCOUNT_TYPES } from '@/lib/constants'
import { formatDate } from '@/lib/utils'
import { extractErrorMessage } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { CheckCircle2, Loader2, Upload, X } from 'lucide-react'
import { useImportHomeBank, usePreviewHomeBank } from '@/features/homebank/hooks'
import type { HomeBankAccountMapping, HomeBankCategoryMapping, HomeBankPreviewResponse } from '@/features/homebank/types'
import type { AccountType } from '@/types/api'

const allowedAccountTypes = ACCOUNT_TYPES.filter(({ value }) =>
  !(['PEA', 'COMPTE_TITRES', 'CRYPTO', 'ASSURANCE_VIE', 'EMPLOYEE_SAVINGS', 'REAL_ESTATE', 'SCPI'] as AccountType[]).includes(value),
)

export function HomeBankTab() {
  const { t } = useTranslation()
  const money = useMoney()
  const previewMutation = usePreviewHomeBank()
  const importMutation = useImportHomeBank()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const previewInFlightRef = useRef(false)
  const [file, setFile] = useState<File | null>(null)
  const [password, setPassword] = useState('')
  const [preview, setPreview] = useState<HomeBankPreviewResponse | null>(null)
  const [accountMappings, setAccountMappings] = useState<HomeBankAccountMapping[]>([])
  const [categoryMappings, setCategoryMappings] = useState<HomeBankCategoryMapping[]>([])
  const [error, setError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [result, setResult] = useState<Awaited<ReturnType<typeof importMutation.mutateAsync>> | null>(null)
  const [dragOver, setDragOver] = useState(false)

  async function handlePreview() {
    if (!file || previewInFlightRef.current || previewMutation.isPending) return
    previewInFlightRef.current = true
    setError(null)
    try {
      const data = await previewMutation.mutateAsync({ file, password: password || undefined })
      if (!data || typeof data.fileToken !== 'string' || !Array.isArray(data.accounts) || !Array.isArray(data.categories)
        || !Array.isArray(data.existingAccounts) || !Array.isArray(data.existingCategories) || !Array.isArray(data.sampleTransactions)
        || typeof data.totalTransactions !== 'number' || typeof data.forecastTransactions !== 'number') {
        throw new Error(t('sync.homebank.errors.invalidPreview'))
      }
      setPreview(data)
      setPassword('')
      setAccountMappings(data.accounts.map((account, index) => {
        const exact = data.existingAccounts.find(item => item.name === account.name && item.currency === account.currency && isLedgerType(item.type))
        const safeType = allowedAccountTypes.some(item => item.value === account.suggestedType) ? account.suggestedType : 'OTHER'
        return exact
          ? { sourceId: account.sourceId, action: 'MAP_EXISTING', targetAccountId: exact.id }
          : { sourceId: account.sourceId, action: 'CREATE_NEW', newAccount: { name: account.name, type: safeType, provider: account.institution || undefined, currency: account.currency, color: ACCOUNT_COLORS[index % ACCOUNT_COLORS.length] } }
      }))
      setCategoryMappings(data.categories.map(category => {
        const kind = category.income ? 'INCOME' : 'EXPENSE'
        const parent = data.categories.find(item => item.sourceId === category.parentSourceId)
        const parentKindMismatch = !!parent && parent.income !== category.income
        const exact = data.existingCategories.find(item => item.name === category.name && item.kind === kind && !item.archived)
        return exact && !parentKindMismatch
          ? { sourceId: category.sourceId, action: 'MAP_EXISTING', targetCategoryId: exact.id }
          : { sourceId: category.sourceId, action: 'CREATE_NEW', name: category.name }
      }))
    } catch (cause) {
      setError(extractErrorMessage(cause, t('sync.homebank.errors.previewFailed')))
    } finally {
      previewInFlightRef.current = false
    }
  }

  function isLedgerType(type: AccountType) {
    return !(['PEA', 'COMPTE_TITRES', 'CRYPTO', 'ASSURANCE_VIE', 'EMPLOYEE_SAVINGS', 'REAL_ESTATE', 'SCPI'] as AccountType[]).includes(type)
  }

  function updateAccount(index: number, patch: Partial<HomeBankAccountMapping>) {
    setAccountMappings(current => current.map((mapping, itemIndex) => itemIndex === index ? { ...mapping, ...patch } : mapping))
  }

  function updateCategory(index: number, patch: Partial<HomeBankCategoryMapping>) {
    setCategoryMappings(current => current.map((mapping, itemIndex) => itemIndex === index ? { ...mapping, ...patch } : mapping))
  }

  function hasParentKindMismatch(sourceId: string) {
    const category = preview?.categories.find(item => item.sourceId === sourceId)
    const parent = preview?.categories.find(item => item.sourceId === category?.parentSourceId)
    return !!category && !!parent && category.income !== parent.income
  }

  async function executeImport() {
    if (!preview || importMutation.isPending) return
    setError(null)
    try {
      const imported = await importMutation.mutateAsync({ fileToken: preview.fileToken, accountMappings, categoryMappings })
      setResult(imported)
      setConfirmOpen(false)
    } catch (cause) {
      setError(extractErrorMessage(cause, t('sync.homebank.errors.importFailed')))
      setConfirmOpen(false)
    }
  }

  function resetWizard() {
    if (previewInFlightRef.current || importMutation.isPending) return
    previewMutation.reset()
    importMutation.reset()
    setFile(null)
    setPassword('')
    setPreview(null)
    setAccountMappings([])
    setCategoryMappings([])
    setResult(null)
    setError(null)
    setConfirmOpen(false)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function selectFile(selected: File | undefined) {
    if (!selected || previewInFlightRef.current || importMutation.isPending) return
    previewMutation.reset()
    importMutation.reset()
    setFile(selected)
    setPassword('')
    setPreview(null)
    setResult(null)
    setError(null)
  }

  const busy = previewMutation.isPending || importMutation.isPending
  const hasWork = accountMappings.some(mapping => mapping.action !== 'SKIP')
  const mappingsValid = accountMappings.every(mapping => mapping.action === 'SKIP' || (mapping.action === 'MAP_EXISTING' ? mapping.targetAccountId != null : !!mapping.newAccount?.name.trim()))
    && categoryMappings.every(mapping => mapping.action === 'UNCATEGORIZED' || (mapping.action === 'MAP_EXISTING' ? mapping.targetCategoryId != null : !!mapping.name?.trim() && !hasParentKindMismatch(mapping.sourceId)))

  return (
    <div className="space-y-6">
      {error && <div role="alert" className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive"><X className="size-4 shrink-0" /><span className="flex-1">{error}</span></div>}

      {!preview && !result && <div className="mx-auto max-w-xl space-y-4">
        <div className={`flex flex-col items-center justify-center gap-4 rounded-2xl border-2 border-dashed p-10 text-center ${dragOver ? 'border-primary bg-primary/5' : 'border-muted-foreground/25'}`}
          onDragOver={event => { event.preventDefault(); setDragOver(true) }} onDragLeave={() => setDragOver(false)}
          onDrop={event => { event.preventDefault(); setDragOver(false); if (!busy) selectFile(event.dataTransfer.files[0]) }}>
          <Upload className="size-6 text-muted-foreground" /><div><p className="font-medium">{t('sync.homebank.upload')}</p><p className="mt-1 text-sm text-muted-foreground">{t('sync.homebank.uploadHint')}</p></div>
          <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={busy}><Upload />{t('sync.homebank.chooseFile')}</Button>
          <input ref={fileInputRef} aria-label={t('sync.homebank.file')} type="file" accept=".hbk,.hbexport" className="hidden" disabled={busy} onChange={event => selectFile(event.target.files?.[0])} />
        </div>
        {file && <p className="text-sm text-muted-foreground">{file.name}</p>}
        <div className="space-y-2"><Label htmlFor="homebank-password">{t('sync.homebank.password')}</Label><Input id="homebank-password" type="password" autoComplete="new-password" value={password} onChange={event => setPassword(event.target.value)} /></div>
        <Button className="w-full" onClick={handlePreview} disabled={!file || busy}>{previewMutation.isPending && <Loader2 className="size-4 animate-spin" />}{t('sync.homebank.preview')}</Button>
      </div>}

      {preview && !result && <div className="space-y-5">
        <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between"><p className="text-sm text-muted-foreground">{t('sync.homebank.previewSummary', { accounts: preview.accounts.length, categories: preview.categories.length, transactions: preview.totalTransactions })}</p><div className="flex flex-wrap gap-2"><Button variant="outline" onClick={resetWizard} disabled={busy}>{t('sync.homebank.restart')}</Button><Button onClick={() => setConfirmOpen(true)} disabled={busy || !hasWork || !mappingsValid}>{t('sync.homebank.import')}</Button></div></div>
        {preview.forecastTransactions > 0 && <p role="status" className="rounded-lg bg-muted px-4 py-3 text-sm">{t('sync.homebank.forecastsSkipped', { count: preview.forecastTransactions })}</p>}
        <section className="space-y-3"><h3 className="font-semibold">{t('sync.homebank.accounts')}</h3>
          {preview.accounts.map((account, index) => {
            const mapping = accountMappings[index]
            const compatible = preview.existingAccounts.filter(item => item.currency === account.currency && isLedgerType(item.type))
            return <Card key={account.sourceId} size="sm"><CardContent className="space-y-3 pt-0">
              <div className="flex items-center justify-between gap-3"><div className="min-w-0"><p className="truncate font-medium">{account.name}</p><p className="text-sm text-muted-foreground">{account.institution} · {account.currency} · {account.transactionCount} {t('sync.homebank.transactions')}</p></div><div className="shrink-0 text-right"><p className="text-sm">{money.amount(account.balance, account.currency)}</p><p className="text-xs text-muted-foreground">{t('sync.homebank.initialBalance')}: {money.amount(account.initialBalance, account.currency)}</p></div></div>
              <div className="flex flex-wrap gap-2">{(['SKIP', 'MAP_EXISTING', 'CREATE_NEW'] as const).map(action => <Button key={action} size="sm" variant={mapping.action === action ? 'default' : 'outline'} onClick={() => updateAccount(index, action === 'SKIP' ? { action, targetAccountId: undefined, newAccount: undefined } : action === 'MAP_EXISTING' ? { action, newAccount: undefined } : { action, targetAccountId: undefined, newAccount: mapping.newAccount ?? { name: account.name, type: allowedAccountTypes.some(type => type.value === account.suggestedType) ? account.suggestedType : 'OTHER', provider: account.institution || undefined, currency: account.currency } })}>{t(`sync.homebank.${action === 'SKIP' ? 'skip' : action === 'MAP_EXISTING' ? 'mapExisting' : 'createNew'}`)}</Button>)}</div>
              {mapping.action === 'MAP_EXISTING' && <select aria-label={t('sync.homebank.targetAccount', { name: account.name })} className="h-10 w-full rounded-md border border-input bg-background px-4 text-sm" value={mapping.targetAccountId ?? ''} onChange={event => updateAccount(index, { targetAccountId: event.target.value ? Number(event.target.value) : undefined })}><option value="">{t('sync.homebank.chooseAccount')}</option>{compatible.map(item => <option key={item.id} value={item.id}>{item.name} ({item.currency})</option>)}</select>}
              {mapping.action === 'CREATE_NEW' && mapping.newAccount && <div className="grid gap-3 sm:grid-cols-2"><div className="space-y-1"><Label>{t('sync.homebank.accountName')}</Label><Input value={mapping.newAccount.name} onChange={event => updateAccount(index, { newAccount: { ...mapping.newAccount!, name: event.target.value } })} /></div><div className="space-y-1"><Label>{t('sync.homebank.accountType')}</Label><select className="h-10 w-full rounded-md border border-input bg-background px-4 text-sm" value={mapping.newAccount.type} onChange={event => updateAccount(index, { newAccount: { ...mapping.newAccount!, type: event.target.value as AccountType } })}>{allowedAccountTypes.map(type => <option key={type.value} value={type.value}>{t(type.labelKey)}</option>)}</select></div></div>}
            </CardContent></Card>
          })}
        </section>
        <section className="space-y-3"><h3 className="font-semibold">{t('sync.homebank.categories')}</h3>
          {preview.categories.map((category, index) => <Card key={category.sourceId} size="sm"><CardContent className="space-y-3 pt-0">
            <div className="flex items-center justify-between gap-3"><div><p className="font-medium">{category.name}</p><p className="text-sm text-muted-foreground">{t(category.income ? 'sync.homebank.income' : 'sync.homebank.expense')} · {category.transactionCount} {t('sync.homebank.transactions')}</p></div>{categoryMappings[index].action === 'CREATE_NEW' && <Input aria-label={t('sync.homebank.categoryName', { name: category.name })} value={categoryMappings[index].name ?? category.name} onChange={event => updateCategory(index, { name: event.target.value })} />}</div>
            {hasParentKindMismatch(category.sourceId) && <p role="alert" className="rounded-lg bg-amber-500/10 px-3 py-2 text-sm text-amber-900 dark:text-amber-200">{t('sync.homebank.categoryParentKindMismatch', { parent: preview.categories.find(item => item.sourceId === category.parentSourceId)?.name })}</p>}
            <div className="grid gap-2 sm:grid-cols-2"><select aria-label={t('sync.homebank.categoryAction', { name: category.name })} className="h-10 w-full rounded-md border border-input bg-background px-4 text-sm" value={categoryMappings[index].action} onChange={event => updateCategory(index, { action: event.target.value as HomeBankCategoryMapping['action'], targetCategoryId: undefined, name: event.target.value === 'CREATE_NEW' ? categoryMappings[index].name ?? category.name : undefined })}><option value="CREATE_NEW">{t('sync.homebank.createCategory')}</option><option value="MAP_EXISTING">{t('sync.homebank.mapExistingCategory')}</option><option value="UNCATEGORIZED">{t('sync.homebank.uncategorized')}</option></select>
              {categoryMappings[index].action === 'MAP_EXISTING' && <select aria-label={t('sync.homebank.targetCategory', { name: category.name })} className="h-10 w-full rounded-md border border-input bg-background px-4 text-sm" value={categoryMappings[index].targetCategoryId ?? ''} onChange={event => updateCategory(index, { targetCategoryId: event.target.value ? Number(event.target.value) : undefined })}><option value="">{t('sync.homebank.chooseCategory')}</option>{preview.existingCategories.filter(item => item.kind === (category.income ? 'INCOME' : 'EXPENSE') && !item.archived).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select>}
            </div>
          </CardContent></Card>)}
        </section>
        <section className="space-y-3"><h3 className="font-semibold">{t('sync.homebank.sampleTransactions')}</h3>{preview.sampleTransactions.map(transaction => <Card key={transaction.sourceId} size="sm"><CardContent className="grid gap-1 pt-0 sm:grid-cols-[1fr_auto]"><div><p className="font-medium">{transaction.payee || t('sync.homebank.noPayee')}</p>{transaction.notes && <p className="text-sm text-muted-foreground">{transaction.notes}</p>}<p className="text-xs text-muted-foreground">{formatDate(transaction.date)} · {transaction.currency}{transaction.transfer ? ` · ${t('sync.homebank.transfer')}` : ''}</p></div><p className="text-sm sm:text-right">{money.amount(transaction.amount, transaction.currency)}</p></CardContent></Card>)}</section>
      </div>}

      {result && <div className="mx-auto max-w-lg space-y-5"><div className="grid grid-cols-2 gap-3">{(['accountsCreated', 'accountsMapped', 'accountsSkipped', 'categoriesCreated', 'transactionsImported', 'transactionsSkipped'] as const).map(key => <div key={key} className="rounded-xl bg-muted/50 p-4 text-center"><p className="text-2xl font-semibold">{result[key]}</p><p className="mt-1 text-sm text-muted-foreground">{t(`sync.homebank.${key}`)}</p></div>)}</div><div className="flex justify-center"><Button onClick={resetWizard}><CheckCircle2 />{t('sync.homebank.done')}</Button></div></div>}

      <ConfirmDialog open={confirmOpen} onOpenChange={setConfirmOpen} title={t('sync.homebank.confirmTitle')} description={t('sync.homebank.confirmDescription')} confirmLabel={t('common.confirm')} onConfirm={executeImport} loading={importMutation.isPending} variant="default" />
    </div>
  )
}
