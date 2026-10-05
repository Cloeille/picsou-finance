import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { extractErrorMessage } from '@/lib/errors'
import { Loader2, Upload, CheckCircle2, AlertCircle } from 'lucide-react'
import type {
  ColumnMappingDto,
  CsvDialectDto,
  TransactionImportPreviewResponse,
  TransactionImportResultResponse,
} from '@/types/api'
import { usePreviewImport, useExecuteImport } from '@/features/accounts/hooks'

interface ImportTransactionsModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  accountId: number
}

type MappingField = keyof ColumnMappingDto
const MAPPING_FIELDS: { key: MappingField; optional: boolean }[] = [
  { key: 'date', optional: false },
  { key: 'side', optional: true },
  { key: 'tickerOrIsin', optional: false },
  { key: 'name', optional: true },
  { key: 'quantity', optional: false },
  { key: 'unitPrice', optional: true },
  { key: 'fees', optional: true },
  { key: 'currency', optional: true },
  { key: 'amount', optional: true },
]

const SELECT_CLASS =
  'flex h-9 w-full rounded-md border border-input bg-background text-foreground px-3 py-1 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring [color-scheme:light] dark:[color-scheme:dark]'

export function ImportTransactionsModal({ open, onOpenChange, accountId }: ImportTransactionsModalProps) {
  const { t } = useTranslation()

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{t('import.title')}</DialogTitle>
        </DialogHeader>
        {open && <ImportWizard accountId={accountId} onOpenChange={onOpenChange} />}
      </DialogContent>
    </Dialog>
  )
}

function ImportWizard({ accountId, onOpenChange }: { accountId: number; onOpenChange: (o: boolean) => void }) {
  const { t } = useTranslation()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragOver, setDragOver] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [preview, setPreview] = useState<TransactionImportPreviewResponse | null>(null)
  const [mapping, setMapping] = useState<ColumnMappingDto | null>(null)
  const [dialect, setDialect] = useState<CsvDialectDto | null>(null)
  const [hasHeaderRow, setHasHeaderRow] = useState(true)
  const [feesIncluded, setFeesIncluded] = useState(false)
  const [result, setResult] = useState<TransactionImportResultResponse | null>(null)

  const previewMutation = usePreviewImport(accountId)
  const executeMutation = useExecuteImport(accountId)

  async function handleFile(file: File) {
    setError(null)
    try {
      const res = await previewMutation.mutateAsync(file)
      setPreview(res)
      setMapping(res.suggestedMapping)
      setDialect(res.dialect)
      setHasHeaderRow(res.hasHeaderRow)
    } catch (err) {
      setError(extractErrorMessage(err, t('common.error')))
    }
  }

  async function handleImport() {
    if (!preview || !mapping || !dialect) return
    setError(null)
    try {
      const res = await executeMutation.mutateAsync({
        fileToken: preview.fileToken,
        mapping,
        dialect,
        hasHeaderRow,
        feesIncludedInAmount: feesIncluded,
      })
      setResult(res)
    } catch (err) {
      setError(extractErrorMessage(err, t('common.error')))
    }
  }

  // --- Step 3: result ---
  if (result) {
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-2 text-emerald-500">
          <CheckCircle2 className="size-5" />
          <span className="font-medium">{t('import.rowsImported', { count: result.imported })}</span>
        </div>
        {result.skipped > 0 && (
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-red-500">
              <AlertCircle className="size-5" />
              <span className="font-medium">{t('import.rowsSkipped', { count: result.skipped })}</span>
            </div>
            <ul className="max-h-40 space-y-1 overflow-y-auto rounded-md border border-border bg-muted/40 p-2 text-sm">
              {result.errors.map((e, i) => (
                <li key={i} className="text-muted-foreground">
                  {t('import.rowLabel', { row: e.rowNumber })}: {e.message}
                </li>
              ))}
            </ul>
          </div>
        )}
        <DialogFooter>
          <Button onClick={() => onOpenChange(false)}>{t('common.done')}</Button>
        </DialogFooter>
      </div>
    )
  }

  // --- Step 1: upload ---
  if (!preview) {
    return (
      <div className="space-y-4">
        <div
          className={cn(
            'flex flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-8 text-center transition-colors',
            dragOver ? 'border-primary bg-primary/5' : 'border-border',
          )}
          onDragOver={e => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={e => {
            e.preventDefault()
            setDragOver(false)
            const file = e.dataTransfer.files?.[0]
            if (file) handleFile(file)
          }}
        >
          <Upload className="size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">{t('import.uploadHint')}</p>
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={e => {
              const file = e.target.files?.[0]
              if (file) handleFile(file)
            }}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={previewMutation.isPending}
            onClick={() => fileInputRef.current?.click()}
          >
            {previewMutation.isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
            {t('import.chooseFile')}
          </Button>
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t('common.cancel')}</Button>
        </DialogFooter>
      </div>
    )
  }

  // --- Step 2: map columns & review ---
  const columns = preview.detectedColumns
  const setField = (key: MappingField, value: number | null) =>
    setMapping(m => (m ? { ...m, [key]: value } : m))

  return (
    <div className="space-y-4">
      {/* Dialect controls */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="space-y-1">
          <Label>{t('import.delimiter')}</Label>
          <select
            className={SELECT_CLASS}
            value={dialect?.delimiter ?? ','}
            onChange={e => setDialect(d => (d ? { ...d, delimiter: e.target.value } : d))}
          >
            <option value=",">{t('import.delimiterComma')}</option>
            <option value=";">{t('import.delimiterSemicolon')}</option>
            <option value={'\t'}>{t('import.delimiterTab')}</option>
          </select>
        </div>
        <div className="space-y-1">
          <Label>{t('import.decimal')}</Label>
          <select
            className={SELECT_CLASS}
            value={dialect?.decimal ?? 'DOT'}
            onChange={e => setDialect(d => (d ? { ...d, decimal: e.target.value as 'DOT' | 'COMMA' } : d))}
          >
            <option value="DOT">1.23</option>
            <option value="COMMA">1,23</option>
          </select>
        </div>
        <div className="space-y-1">
          <Label>{t('import.dateFormat')}</Label>
          <select
            className={SELECT_CLASS}
            value={dialect?.dateFormat ?? 'yyyy-MM-dd'}
            onChange={e => setDialect(d => (d ? { ...d, dateFormat: e.target.value } : d))}
          >
            {['yyyy-MM-dd', 'dd/MM/yyyy', 'MM/dd/yyyy', 'dd.MM.yyyy', 'dd-MM-yyyy'].map(f => (
              <option key={f} value={f}>{f}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Column mapping */}
      <div>
        <p className="mb-2 text-sm font-medium">{t('import.mapColumns')}</p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {MAPPING_FIELDS.map(({ key, optional }) => (
            <div key={key} className="space-y-1">
              <Label className="text-xs">
                {t(`import.field.${key}`)}
                {optional && <span className="text-muted-foreground"> ({t('common.optional')})</span>}
              </Label>
              <select
                className={SELECT_CLASS}
                value={mapping?.[key] ?? ''}
                onChange={e => setField(key, e.target.value === '' ? null : Number(e.target.value))}
              >
                <option value="">{t('import.none')}</option>
                {columns.map((c, i) => (
                  <option key={i} value={i}>{c}</option>
                ))}
              </select>
            </div>
          ))}
        </div>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={feesIncluded} onChange={e => setFeesIncluded(e.target.checked)} />
        {t('import.feesIncluded')}
      </label>

      {/* Preview table */}
      <div>
        <p className="mb-2 text-sm font-medium">{t('import.preview')} ({t('import.rowsTotal', { count: preview.totalRows })})</p>
        <div className="max-h-48 overflow-auto rounded-md border border-border">
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-muted">
              <tr>{columns.map((c, i) => <th key={i} className="px-2 py-1 text-left font-medium">{c}</th>)}</tr>
            </thead>
            <tbody>
              {preview.sampleRows.map((row, ri) => (
                <tr key={ri} className="border-t border-border">
                  {columns.map((_, ci) => <td key={ci} className="px-2 py-1 text-muted-foreground">{row[ci] ?? ''}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => setPreview(null)}>{t('import.back')}</Button>
        <Button type="button" onClick={handleImport} disabled={executeMutation.isPending}>
          {executeMutation.isPending && <Loader2 className="mr-2 size-4 animate-spin" />}
          {t('import.import')}
        </Button>
      </DialogFooter>
    </div>
  )
}
