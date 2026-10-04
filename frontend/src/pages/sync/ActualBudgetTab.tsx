import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Loader2, Upload, X } from 'lucide-react'
import { useMoney } from '@/hooks/use-money'
import { ACCOUNT_COLORS, ACCOUNT_TYPES, SUPPORTED_CURRENCIES } from '@/lib/constants'
import { extractErrorMessage } from '@/lib/errors'
import { formatDate } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { useImportActualBudget, usePlanActualImport, usePreviewActualBudget } from '@/features/actual/hooks'
import type {
  ActualAccountMapping,
  ActualCategoryMapping,
  ActualImportPlan,
  ActualImportRequest,
  ActualImportResult,
  ActualImportWarning,
  ActualPreviewResponse,
} from '@/features/actual/types'
import type { AccountType } from '@/types/api'

/**
 * Actual holds cash ledgers; these types derive their value from positions instead. A loan is
 * stored as the positive amount owed, so Actual's negative ledger would count as wealth.
 */
const NON_LEDGER_TYPES: AccountType[] = ['PEA', 'COMPTE_TITRES', 'CRYPTO', 'ASSURANCE_VIE', 'EMPLOYEE_SAVINGS', 'REAL_ESTATE', 'SCPI', 'LOAN']
const LEDGER_ACCOUNT_TYPES = ACCOUNT_TYPES.filter(({ value }) => !NON_LEDGER_TYPES.includes(value))
const RESULT_KEYS = ['accountsCreated', 'accountsMapped', 'accountsSkipped', 'categoriesCreated', 'transactionsImported', 'transactionsSkipped', 'transactionsDeleted', 'transactionsMoved'] as const
const SELECT_CLASS = 'h-10 w-full rounded-md border border-input bg-background px-4 text-sm text-foreground outline-none [color-scheme:light] dark:[color-scheme:dark]'

function isPreview(data: unknown): data is ActualPreviewResponse {
  const value = data as Partial<ActualPreviewResponse> | null
  return !!value && typeof value.fileToken === 'string' && Array.isArray(value.accounts)
    && Array.isArray(value.categories) && Array.isArray(value.existingAccounts)
    && Array.isArray(value.existingCategories) && Array.isArray(value.sampleTransactions)
    && typeof value.totalTransactions === 'number'
}

function isPlan(data: unknown): data is ActualImportPlan {
  const value = data as Partial<ActualImportPlan> | null
  return !!value && typeof value.transactionsToAdd === 'number' && typeof value.transactionsToDelete === 'number'
    && typeof value.transactionsToMove === 'number' && Array.isArray(value.warnings)
}

function compatibleAccounts(preview: ActualPreviewResponse, currency: string) {
  return preview.existingAccounts.filter((item) => item.currency === currency && !NON_LEDGER_TYPES.includes(item.type))
}

function initialAccountMappings(preview: ActualPreviewResponse, currency: string): ActualAccountMapping[] {
  const compatible = compatibleAccounts(preview, currency)
  return preview.accounts.map((account, index) => {
    // The account an earlier import created wins over a same-name account the user made.
    const match = compatible.find((item) => item.id === account.importedAccountId)
      ?? compatible.find((item) => item.name === account.name)
    // newAccount is kept even when mapping to an existing account, so switching back to
    // "create" restores the suggested details; the request strips it for other actions.
    return {
      sourceId: account.sourceId,
      action: match ? 'MAP_EXISTING' : 'CREATE_NEW',
      targetAccountId: match?.id,
      newAccount: {
        name: account.name.slice(0, 100),
        type: account.suggestedType,
        currency,
        color: ACCOUNT_COLORS[index % ACCOUNT_COLORS.length],
      },
    }
  })
}

function initialCategoryMappings(preview: ActualPreviewResponse): ActualCategoryMapping[] {
  return preview.categories.map((category) => {
    const kind = category.income ? 'INCOME' : 'EXPENSE'
    const match = preview.existingCategories.find((item) => item.name === category.name && item.kind === kind && !item.archived)
    return match
      ? { sourceId: category.sourceId, action: 'MAP_EXISTING', targetCategoryId: match.id }
      : { sourceId: category.sourceId, action: 'CREATE_NEW', name: category.name.slice(0, 100) }
  })
}

export function ActualBudgetTab() {
  const { t } = useTranslation()
  const money = useMoney()
  const previewMutation = usePreviewActualBudget()
  const planMutation = usePlanActualImport()
  const importMutation = useImportActualBudget()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [preview, setPreview] = useState<ActualPreviewResponse | null>(null)
  const [currency, setCurrency] = useState('EUR')
  const [accountMappings, setAccountMappings] = useState<ActualAccountMapping[]>([])
  const [categoryMappings, setCategoryMappings] = useState<ActualCategoryMapping[]>([])
  const [error, setError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [plan, setPlan] = useState<ActualImportPlan | null>(null)
  const [result, setResult] = useState<ActualImportResult | null>(null)

  const busy = previewMutation.isPending || planMutation.isPending || importMutation.isPending

  function selectFile(selected: File | undefined) {
    if (!selected || busy) return
    setFile(selected)
    setError(null)
  }

  async function handlePreview() {
    if (!file || busy) return
    setError(null)
    try {
      const data = await previewMutation.mutateAsync(file)
      if (!isPreview(data)) throw new Error(t('sync.actual.errors.invalidPreview'))
      const budgetCurrency = data.currency ?? 'EUR'
      setPreview(data)
      setCurrency(budgetCurrency)
      setAccountMappings(initialAccountMappings(data, budgetCurrency))
      setCategoryMappings(initialCategoryMappings(data))
    } catch (cause) {
      setError(extractErrorMessage(cause, t('sync.actual.errors.previewFailed')))
    }
  }

  function changeCurrency(next: string) {
    setCurrency(next)
    setAccountMappings((current) => current.map((mapping) => ({
      ...mapping,
      targetAccountId: undefined,
      newAccount: mapping.newAccount && { ...mapping.newAccount, currency: next },
    })))
  }

  function updateAccount(index: number, patch: Partial<ActualAccountMapping>) {
    setAccountMappings((current) => current.map((mapping, i) => (i === index ? { ...mapping, ...patch } : mapping)))
  }

  function updateCategory(index: number, patch: Partial<ActualCategoryMapping>) {
    setCategoryMappings((current) => current.map((mapping, i) => (i === index ? { ...mapping, ...patch } : mapping)))
  }

  function buildRequest(fileToken: string): ActualImportRequest {
    return {
      fileToken,
      currency,
      accountMappings: accountMappings.map((mapping) => ({
        sourceId: mapping.sourceId,
        action: mapping.action,
        ...(mapping.action === 'MAP_EXISTING' && { targetAccountId: mapping.targetAccountId }),
        ...(mapping.action === 'CREATE_NEW' && { newAccount: mapping.newAccount }),
      })),
      categoryMappings: categoryMappings.map((mapping) => ({
        sourceId: mapping.sourceId,
        action: mapping.action,
        ...(mapping.action === 'MAP_EXISTING' && { targetCategoryId: mapping.targetCategoryId }),
        ...(mapping.action === 'CREATE_NEW' && { name: mapping.name?.trim() }),
      })),
    }
  }

  /** The dry run tells the user what a re-import deletes and moves before they confirm. */
  async function reviewImport() {
    if (!preview || busy) return
    setError(null)
    try {
      const data = await planMutation.mutateAsync(buildRequest(preview.fileToken))
      if (!isPlan(data)) throw new Error(t('sync.actual.errors.importFailed'))
      setPlan(data)
      setConfirmOpen(true)
    } catch (cause) {
      setError(extractErrorMessage(cause, t('sync.actual.errors.importFailed')))
    }
  }

  async function executeImport() {
    if (!preview || importMutation.isPending) return
    setError(null)
    try {
      setResult(await importMutation.mutateAsync(buildRequest(preview.fileToken)))
    } catch (cause) {
      setError(extractErrorMessage(cause, t('sync.actual.errors.importFailed')))
    } finally {
      setConfirmOpen(false)
    }
  }

  function warningText(warning: ActualImportWarning) {
    return t(`sync.actual.warnings.${warning.reason}`, { count: warning.count })
  }

  function reset() {
    if (busy) return
    previewMutation.reset()
    planMutation.reset()
    importMutation.reset()
    setFile(null)
    setPreview(null)
    setAccountMappings([])
    setCategoryMappings([])
    setResult(null)
    setError(null)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const hasWork = accountMappings.some((mapping) => mapping.action !== 'SKIP')
  const mappingsValid = accountMappings.every((mapping) =>
    mapping.action === 'SKIP'
    || (mapping.action === 'MAP_EXISTING' ? mapping.targetAccountId != null : !!mapping.newAccount?.name.trim()))
    && categoryMappings.every((mapping) =>
      mapping.action === 'UNCATEGORIZED'
      || (mapping.action === 'MAP_EXISTING' ? mapping.targetCategoryId != null : !!mapping.name?.trim()))
  const accountNames = new Map(preview?.accounts.map((account) => [account.sourceId, account.name]))

  return (
    <div className="space-y-6">
      {error && (
        <div role="alert" className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <X className="size-4 shrink-0" />
          <span className="flex-1">{error}</span>
        </div>
      )}

      {!preview && !result && (
        <div className="mx-auto max-w-xl space-y-4">
          <div
            className={`flex flex-col items-center justify-center gap-4 rounded-2xl border-2 border-dashed p-10 text-center ${dragOver ? 'border-primary bg-primary/5' : 'border-muted-foreground/25'}`}
            onDragOver={(event) => { event.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(event) => { event.preventDefault(); setDragOver(false); selectFile(event.dataTransfer.files[0]) }}
          >
            <Upload className="size-6 text-muted-foreground" />
            <div>
              <p className="font-medium">{t('sync.actual.upload')}</p>
              <p className="mt-1 text-sm text-muted-foreground">{t('sync.actual.uploadHint')}</p>
            </div>
            <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={busy}>
              <Upload />
              {t('sync.actual.chooseFile')}
            </Button>
            <input
              ref={fileInputRef}
              aria-label={t('sync.actual.file')}
              type="file"
              accept=".zip,.sqlite,application/zip"
              className="hidden"
              disabled={busy}
              onChange={(event) => selectFile(event.target.files?.[0])}
            />
          </div>
          {file && <p className="text-sm text-muted-foreground">{file.name}</p>}
          <Button className="w-full" onClick={handlePreview} disabled={!file || busy}>
            {previewMutation.isPending && <Loader2 className="size-4 animate-spin" />}
            {t('sync.actual.preview')}
          </Button>
        </div>
      )}

      {preview && !result && (
        <div className="space-y-5">
          <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-sm text-muted-foreground">
              {t('sync.actual.previewSummary', {
                accounts: preview.accounts.length,
                categories: preview.categories.length,
                transactions: preview.totalTransactions,
              })}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" onClick={reset} disabled={busy}>{t('sync.actual.restart')}</Button>
              <Button onClick={reviewImport} disabled={busy || !hasWork || !mappingsValid}>
                {planMutation.isPending && <Loader2 className="size-4 animate-spin" />}
                {t('sync.actual.import')}
              </Button>
            </div>
          </div>

          {preview.transferTransactions > 0 && (
            <p role="status" className="rounded-lg bg-muted px-4 py-3 text-sm">
              {t('sync.actual.transfersNeutral', { count: preview.transferTransactions })}
            </p>
          )}

          {preview.currency ? (
            <p className="text-sm">
              <span className="text-muted-foreground">{t('sync.actual.currency')} · </span>
              <span className="font-medium">{preview.currency}</span>
            </p>
          ) : (
            <div className="max-w-xs space-y-1">
              <Label htmlFor="actual-currency">{t('sync.actual.currency')}</Label>
              <select id="actual-currency" className={SELECT_CLASS} value={currency} onChange={(event) => changeCurrency(event.target.value)}>
                {SUPPORTED_CURRENCIES.map((code) => <option key={code} value={code}>{code}</option>)}
              </select>
              <p className="text-xs text-muted-foreground">{t('sync.actual.currencyHint')}</p>
            </div>
          )}

          <section className="space-y-3">
            <h3 className="font-semibold">{t('sync.actual.accounts')}</h3>
            {preview.accounts.map((account, index) => {
              const mapping = accountMappings[index]
              const compatible = compatibleAccounts(preview, currency)
              return (
                <Card key={account.sourceId} size="sm">
                  <CardContent className="space-y-3 pt-0">
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate font-medium">{account.name}</p>
                        <p className="text-sm text-muted-foreground">
                          {account.transactionCount} {t('sync.actual.transactions')}
                          {account.offBudget && ` · ${t('sync.actual.offBudget')}`}
                          {account.closed && ` · ${t('sync.actual.closed')}`}
                        </p>
                      </div>
                      <p className="shrink-0 text-sm font-medium text-foreground">{money.amount(account.balance, currency)}</p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {(['SKIP', 'MAP_EXISTING', 'CREATE_NEW'] as const).map((action) => (
                        <Button
                          key={action}
                          size="sm"
                          variant={mapping.action === action ? 'default' : 'outline'}
                          onClick={() => updateAccount(index, { action })}
                        >
                          {t(`sync.actual.${action === 'SKIP' ? 'skip' : action === 'MAP_EXISTING' ? 'mapExisting' : 'createNew'}`)}
                        </Button>
                      ))}
                    </div>
                    {mapping.action === 'MAP_EXISTING' && (
                      <select
                        aria-label={t('sync.actual.targetAccount', { name: account.name })}
                        className={SELECT_CLASS}
                        value={mapping.targetAccountId ?? ''}
                        onChange={(event) => updateAccount(index, { targetAccountId: event.target.value ? Number(event.target.value) : undefined })}
                      >
                        <option value="">{t('sync.actual.chooseAccount')}</option>
                        {compatible.map((item) => <option key={item.id} value={item.id}>{item.name} ({item.currency})</option>)}
                      </select>
                    )}
                    {mapping.action === 'MAP_EXISTING' && account.importedAccountId != null
                      && mapping.targetAccountId === account.importedAccountId && (
                      <p className="text-xs text-muted-foreground">{t('sync.actual.previouslyImported')}</p>
                    )}
                    {mapping.action === 'CREATE_NEW' && mapping.newAccount && (
                      <div className="grid gap-3 sm:grid-cols-2">
                        <div className="space-y-1">
                          <Label htmlFor={`actual-account-name-${index}`}>{t('sync.actual.accountName')}</Label>
                          <Input
                            id={`actual-account-name-${index}`}
                            maxLength={100}
                            value={mapping.newAccount.name}
                            onChange={(event) => updateAccount(index, { newAccount: { ...mapping.newAccount!, name: event.target.value } })}
                          />
                        </div>
                        <div className="space-y-1">
                          <Label htmlFor={`actual-account-type-${index}`}>{t('sync.actual.accountType')}</Label>
                          <select
                            id={`actual-account-type-${index}`}
                            className={SELECT_CLASS}
                            value={mapping.newAccount.type}
                            onChange={(event) => updateAccount(index, { newAccount: { ...mapping.newAccount!, type: event.target.value as AccountType } })}
                          >
                            {LEDGER_ACCOUNT_TYPES.map((type) => <option key={type.value} value={type.value}>{t(type.labelKey)}</option>)}
                          </select>
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </section>

          <section className="space-y-3">
            <h3 className="font-semibold">{t('sync.actual.categories')}</h3>
            {preview.categories.map((category, index) => {
              const mapping = categoryMappings[index]
              const kind = category.income ? 'INCOME' : 'EXPENSE'
              return (
                <Card key={category.sourceId} size="sm">
                  <CardContent className="space-y-3 pt-0">
                    <div>
                      <p className="font-medium">{category.name}</p>
                      <p className="text-sm text-muted-foreground">
                        {category.groupName && `${category.groupName} · `}
                        {t(category.income ? 'sync.actual.income' : 'sync.actual.expense')} · {category.transactionCount} {t('sync.actual.transactions')}
                      </p>
                    </div>
                    <div className="grid gap-2 sm:grid-cols-2">
                      <select
                        aria-label={t('sync.actual.categoryAction', { name: category.name })}
                        className={SELECT_CLASS}
                        value={mapping.action}
                        onChange={(event) => {
                          const action = event.target.value as ActualCategoryMapping['action']
                          updateCategory(index, { action, targetCategoryId: undefined, name: mapping.name ?? category.name.slice(0, 100) })
                        }}
                      >
                        <option value="CREATE_NEW">{t('sync.actual.createCategory')}</option>
                        <option value="MAP_EXISTING">{t('sync.actual.mapExistingCategory')}</option>
                        <option value="UNCATEGORIZED">{t('sync.actual.uncategorized')}</option>
                      </select>
                      {mapping.action === 'CREATE_NEW' && (
                        <Input
                          aria-label={t('sync.actual.categoryName', { name: category.name })}
                          maxLength={100}
                          value={mapping.name ?? ''}
                          onChange={(event) => updateCategory(index, { name: event.target.value })}
                        />
                      )}
                      {mapping.action === 'MAP_EXISTING' && (
                        <select
                          aria-label={t('sync.actual.targetCategory', { name: category.name })}
                          className={SELECT_CLASS}
                          value={mapping.targetCategoryId ?? ''}
                          onChange={(event) => updateCategory(index, { targetCategoryId: event.target.value ? Number(event.target.value) : undefined })}
                        >
                          <option value="">{t('sync.actual.chooseCategory')}</option>
                          {preview.existingCategories
                            .filter((item) => item.kind === kind && !item.archived)
                            .map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
                        </select>
                      )}
                    </div>
                  </CardContent>
                </Card>
              )
            })}
          </section>

          <section className="space-y-3">
            <h3 className="font-semibold">{t('sync.actual.sampleTransactions')}</h3>
            {preview.sampleTransactions.map((transaction) => (
              <Card key={transaction.sourceId} size="sm">
                <CardContent className="grid gap-1 pt-0 sm:grid-cols-[1fr_auto]">
                  <div>
                    <p className="font-medium">
                      {transaction.payee || t('sync.actual.noPayee')}
                      {transaction.kind === 'TRANSFER' && <Badge variant="secondary" className="ml-2">{t('sync.actual.transfer')}</Badge>}
                      {transaction.kind === 'STARTING_BALANCE' && <Badge variant="secondary" className="ml-2">{t('sync.actual.startingBalance')}</Badge>}
                    </p>
                    {transaction.notes && <p className="text-sm text-muted-foreground">{transaction.notes}</p>}
                    <p className="text-xs text-muted-foreground">
                      {formatDate(transaction.date)} · {accountNames.get(transaction.accountSourceId)}
                    </p>
                  </div>
                  <p className="text-sm font-medium text-foreground sm:text-right">{money.amount(transaction.amount, currency)}</p>
                </CardContent>
              </Card>
            ))}
          </section>
        </div>
      )}

      {result && (
        <div className="mx-auto max-w-lg space-y-5">
          <div className="grid grid-cols-2 gap-3">
            {RESULT_KEYS.map((key) => (
              <div key={key} className="rounded-xl bg-muted/50 p-4 text-center">
                <p className="text-2xl font-semibold">{result[key]}</p>
                <p className="mt-1 text-sm text-muted-foreground">{t(`sync.actual.${key}`)}</p>
              </div>
            ))}
          </div>
          {result.warnings.map((warning) => (
            <p key={warning.reason} role="status" className="rounded-lg bg-muted px-4 py-3 text-sm">
              {warningText(warning)}
            </p>
          ))}
          <div className="flex justify-center">
            <Button onClick={reset}>
              <CheckCircle2 />
              {t('sync.actual.done')}
            </Button>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={t('sync.actual.confirmTitle')}
        description={plan ? [
          t('sync.actual.planSummary', {
            added: plan.transactionsToAdd,
            deleted: plan.transactionsToDelete,
            moved: plan.transactionsToMove,
          }),
          ...plan.warnings.map(warningText),
          t('sync.actual.confirmDescription'),
        ].join(' ') : ''}
        confirmLabel={t('common.confirm')}
        onConfirm={executeImport}
        loading={importMutation.isPending}
        variant="default"
      />
    </div>
  )
}
