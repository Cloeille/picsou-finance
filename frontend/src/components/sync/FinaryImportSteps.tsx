import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ArrowLeft, ArrowRight, CheckCircle2, Upload } from 'lucide-react'
import { ACCOUNT_COLORS, ACCOUNT_TYPES } from '@/lib/constants'
import { availableTargets } from '@/features/sync/finary-mappings'
import type { FinaryStep, NewAccountField } from '@/features/sync/use-finary-import'
import type {
  FinaryAccountMapping,
  FinaryImportResultResponse,
  FinaryMappingAction,
  FinaryPreviewResponse,
} from '@/types/api'

/** `page` is the Sync tab (roomy, cards); `modal` is the compact add-account wizard. */
type FinaryVariant = 'page' | 'modal'

const SELECT_CLASS =
  'h-10 w-full rounded-xl border border-input bg-background text-foreground px-4 text-sm outline-none [color-scheme:light] dark:[color-scheme:dark]'

export function FinaryStepIndicator({ step, variant }: { step: FinaryStep; variant: FinaryVariant }) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  return (
    <div className={`flex items-center justify-center gap-2${modal ? ' mb-4' : ''}`}>
      {([1, 2, 3] as const).map((s) => (
        <div key={s} className="flex items-center gap-2">
          <div
            className={`flex ${modal ? 'size-7 text-xs' : 'size-8 text-sm'} items-center justify-center rounded-full font-medium transition-colors ${
              step >= s ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
            }`}
          >
            {step > s ? <CheckCircle2 className={modal ? 'size-3.5' : 'size-4'} /> : s}
          </div>
          <span className={`${modal ? 'text-xs' : 'text-sm'} text-muted-foreground`}>{t(`sync.finary.step${s}`)}</span>
          {s < 3 && <div className={`mx-1 h-px ${modal ? 'w-6' : 'w-8'} ${step > s ? 'bg-primary' : 'bg-muted'}`} />}
        </div>
      ))}
    </div>
  )
}

/** The "ou" divider and the .xlsx drop zone under the login / sync block of step 1. */
export function FinaryUploadZone({
  onFile,
  disabled,
  variant,
}: {
  onFile: (file: File) => void
  disabled: boolean
  variant: FinaryVariant
}) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragOver, setDragOver] = useState(false)

  function onFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) onFile(file)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(false)
    const file = e.dataTransfer.files?.[0]
    if (file) onFile(file)
  }

  return (
    <>
      <div className="flex items-center gap-3">
        <div className="h-px flex-1 bg-border" />
        <span className="text-xs text-muted-foreground">ou</span>
        <div className="h-px flex-1 bg-border" />
      </div>

      <div
        className={`flex flex-col items-center justify-center ${
          modal ? 'gap-3 rounded-xl p-8' : 'gap-4 rounded-2xl p-12'
        } border-2 border-dashed text-center transition-colors ${
          dragOver ? 'border-primary bg-primary/5' : 'border-muted-foreground/25 hover:border-muted-foreground/50'
        }`}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
      >
        <Upload className={`${modal ? 'size-5' : 'size-6'} text-muted-foreground`} />
        <div>
          <p className={modal ? 'text-sm font-medium' : 'font-medium'}>{t('sync.finary.uploadFile')}</p>
          <p className={`${modal ? 'text-xs' : 'mt-1 text-sm'} text-muted-foreground`}>{t('sync.finary.uploadHint')}</p>
        </div>
        <Button
          variant="outline"
          size={modal ? 'sm' : undefined}
          onClick={() => fileInputRef.current?.click()}
          disabled={disabled}
        >
          <Upload className={modal ? 'size-4' : undefined} />
          {t('sync.finary.uploadFile')}
        </Button>
        <input ref={fileInputRef} type="file" accept=".xlsx" className="hidden" onChange={onFileSelected} />
      </div>
    </>
  )
}

interface FinaryMappingStepProps {
  preview: FinaryPreviewResponse
  mappings: FinaryAccountMapping[]
  loading: boolean
  hasSkipAll: boolean
  onBack: () => void
  onImport: () => void
  onActionChange: (index: number, action: FinaryMappingAction) => void
  onTargetChange: (index: number, id: number) => void
  onNewAccountField: (index: number, field: NewAccountField, value: string) => void
  variant: FinaryVariant
}

export function FinaryMappingStep({
  preview,
  mappings,
  loading,
  hasSkipAll,
  onBack,
  onImport,
  onActionChange,
  onTargetChange,
  onNewAccountField,
  variant,
}: FinaryMappingStepProps) {
  const { t } = useTranslation()
  const modal = variant === 'modal'
  const iconClass = modal ? 'size-4' : undefined

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <Button variant="ghost" size="sm" onClick={onBack}>
          <ArrowLeft className={iconClass} />
          {t('sync.finary.back')}
        </Button>
        <Button onClick={onImport} disabled={loading || hasSkipAll} size={modal ? 'sm' : undefined}>
          {loading ? t('common.loading') : t('sync.finary.import')}
          <ArrowRight className={iconClass} />
        </Button>
      </div>

      <div className={modal ? 'space-y-3 max-h-80 overflow-y-auto' : 'space-y-3'}>
        {preview.accounts.map((account, index) => {
          const mapping = mappings[index]
          const body = (
            <>
              <div className="flex items-center justify-between">
                <div className="min-w-0">
                  <p className={modal ? 'truncate text-sm font-medium' : 'truncate font-medium'}>{account.finaryName}</p>
                  <p className={modal ? 'text-xs text-muted-foreground' : 'text-sm text-muted-foreground'}>
                    {account.finaryInstitution} &middot; {account.finaryCategory}
                  </p>
                </div>
                <div className={`text-right shrink-0 ${modal ? 'ml-2' : 'ml-3'}`}>
                  <CurrencyDisplay value={account.currentBalance} currency={account.nativeCurrency} />
                  <p className="text-xs text-muted-foreground">{account.transactionCount} tx</p>
                </div>
              </div>

              <div className="flex gap-1.5">
                {(['SKIP', 'MAP_EXISTING', 'CREATE_NEW'] as const).map((action) => (
                  <Button
                    key={action}
                    variant={mapping?.action === action ? 'default' : 'outline'}
                    size="xs"
                    onClick={() => onActionChange(index, action)}
                  >
                    {t(`sync.finary.${action === 'SKIP' ? 'skip' : action === 'MAP_EXISTING' ? 'mapExisting' : 'createNew'}`)}
                  </Button>
                ))}
              </div>

              {mapping?.action === 'MAP_EXISTING' && (
                <select
                  className={SELECT_CLASS}
                  value={mapping.targetAccountId ?? ''}
                  onChange={(e) => {
                    const val = e.target.value
                    if (val) onTargetChange(index, Number(val))
                  }}
                >
                  <option value="" disabled>{t('sync.finary.mapExisting')}...</option>
                  {availableTargets(preview.existingPicsouAccounts, mappings, index).map((acc) => (
                    <option key={acc.id} value={acc.id}>
                      {modal
                        ? `${acc.name} (${acc.type})`
                        : <>{acc.name} ({acc.type}) &mdash; <CurrencyDisplay value={acc.currentBalance} /></>}
                    </option>
                  ))}
                </select>
              )}

              {mapping?.action === 'CREATE_NEW' && mapping.newAccount && (
                <div className={`grid ${modal ? 'gap-2' : 'gap-3'} sm:grid-cols-2`}>
                  <div className="space-y-1">
                    <Label>{t('accounts.accountName')}</Label>
                    <Input
                      value={mapping.newAccount.name}
                      onChange={(e) => onNewAccountField(index, 'name', e.target.value)}
                      placeholder={modal ? undefined : account.finaryName}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label>{t('sync.exchanges.type')}</Label>
                    <select
                      className={SELECT_CLASS}
                      value={mapping.newAccount.type}
                      onChange={(e) => onNewAccountField(index, 'type', e.target.value)}
                    >
                      {/* The shared list, not a local copy: deriving a label key from the
                          type name only held while every key was the lowercased value, and
                          broke the moment a type needed its own (livretA, employeeSavings). */}
                      {ACCOUNT_TYPES.map((at) => (
                        <option key={at.value} value={at.value}>{t(at.labelKey)}</option>
                      ))}
                    </select>
                  </div>
                  <div className="space-y-1">
                    <Label>{t('accounts.provider')}</Label>
                    <Input
                      value={mapping.newAccount.provider ?? ''}
                      onChange={(e) => onNewAccountField(index, 'provider', e.target.value)}
                    />
                  </div>
                  <div className="space-y-1">
                    <Label>{t('common.currency')}</Label>
                    <Input
                      value={mapping.newAccount.currency}
                      onChange={(e) => onNewAccountField(index, 'currency', e.target.value)}
                      maxLength={3}
                    />
                  </div>
                  <div className="space-y-1 sm:col-span-2">
                    <Label>{t('accounts.color')}</Label>
                    <div className="flex flex-wrap gap-2">
                      {ACCOUNT_COLORS.map((color) => (
                        <button
                          key={color}
                          type="button"
                          className={`size-8 rounded-full border-2 transition-[border-color,box-shadow] ${
                            mapping.newAccount?.color === color
                              ? 'border-background ring-2 ring-foreground'
                              : 'border-transparent'
                          }`}
                          style={{ backgroundColor: color }}
                          onClick={() => onNewAccountField(index, 'color', color)}
                        />
                      ))}
                    </div>
                  </div>
                </div>
              )}
            </>
          )
          const key = account.finaryCategory + account.finaryId
          return modal ? (
            <div key={key} className="rounded-lg border p-3 space-y-2">{body}</div>
          ) : (
            <Card key={key} size="sm">
              <CardContent className="space-y-3 pt-0">{body}</CardContent>
            </Card>
          )
        })}
      </div>
    </div>
  )
}

function ResultStat({ label, value, color, modal }: { label: string; value: number; color: string; modal: boolean }) {
  return (
    <div className={`rounded-xl bg-muted/50 ${modal ? 'p-3' : 'p-4'} text-center`}>
      <p className={`${modal ? 'text-xl' : 'text-2xl'} font-semibold ${color}`}>{value}</p>
      <p className={`${modal ? '' : 'mt-1 '}text-xs text-muted-foreground`}>{label}</p>
    </div>
  )
}

export function FinaryResultStep({
  result,
  onDone,
  variant,
}: {
  result: FinaryImportResultResponse
  onDone: () => void
  variant: FinaryVariant
}) {
  const { t } = useTranslation()
  const modal = variant === 'modal'

  const rows = result.importedAccounts.map((account) => (
    <div
      key={account.id}
      className={`flex items-center gap-3 ${modal ? 'rounded-lg border' : 'rounded-lg'} px-3 py-2`}
    >
      <div className="size-3 shrink-0 rounded-full" style={{ backgroundColor: account.color }} />
      <div className="flex-1 min-w-0">
        <p className="truncate text-sm font-medium">{account.name}</p>
        {modal
          ? <span className="text-xs text-muted-foreground">{account.type}</span>
          : <Badge variant="secondary">{account.type}</Badge>}
      </div>
      <CurrencyDisplay value={account.currentBalance} />
    </div>
  ))

  return (
    <div className={modal ? 'space-y-4' : 'mx-auto max-w-lg space-y-6'}>
      <div className={`grid grid-cols-2 ${modal ? 'gap-3' : 'gap-4'}`}>
        <ResultStat modal={modal} label={t('sync.finary.accountsCreated')} value={result.accountsCreated} color="text-emerald-600" />
        <ResultStat modal={modal} label={t('sync.finary.accountsMapped')} value={result.accountsMapped} color="text-blue-600" />
        <ResultStat modal={modal} label={t('sync.finary.accountsSkipped')} value={result.accountsSkipped} color="text-muted-foreground" />
        <ResultStat modal={modal} label={t('sync.finary.transactionsImported')} value={result.transactionsImported} color="text-violet-600" />
      </div>

      {result.importedAccounts.length > 0 && (
        modal ? (
          <div className="space-y-2">{rows}</div>
        ) : (
          <Card size="sm">
            <CardContent className="space-y-2 pt-0">{rows}</CardContent>
          </Card>
        )
      )}

      {modal ? (
        <Button onClick={onDone} className="w-full">
          <CheckCircle2 className="size-4" />
          {t('sync.finary.done')}
        </Button>
      ) : (
        <div className="flex justify-center">
          <Button onClick={onDone}>
            <CheckCircle2 />
            {t('sync.finary.done')}
          </Button>
        </div>
      )}
    </div>
  )
}
