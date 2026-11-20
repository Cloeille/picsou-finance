import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { useEbCallLog, useClearEbCallLog } from '@/features/admin/hooks'
import { localeFromLanguage } from '@/lib/utils'
import type { EbCallEntry } from '@/features/admin/api'

function StatusBadge({ status }: { status: number }) {
  const ok = status >= 200 && status < 300
  return (
    <span
      className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium tabular-nums ${
        ok
          ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400'
          : 'bg-destructive/10 text-destructive'
      }`}
    >
      {status}
    </span>
  )
}

function exportTxt(entries: EbCallEntry[]) {
  const lines = entries.map((e, i) => [
    `── [${i + 1}] ${new Date(e.timestamp).toISOString()} ──────────────────────────────`,
    `${e.method} ${e.url}`,
    `HTTP ${e.responseStatus}`,
    e.requestBody ? `\n>>> REQUEST BODY\n${tryPretty(e.requestBody)}` : '',
    `\n<<< RESPONSE BODY\n${tryPretty(e.responseBody)}`,
  ].filter(Boolean).join('\n'))
  const blob = new Blob([lines.join('\n\n')], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `enable-banking-calls-${Date.now()}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function tryPretty(json: string) {
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}

function EbCallLogDialog() {
  const { i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const { data, isLoading, isError, refetch } = useEbCallLog(true)
  const clear = useClearEbCallLog()

  return (
    <div className="flex flex-col gap-4">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm text-muted-foreground">
          {data ? `${data.length} ${data.length > 1 ? 'appels en mémoire' : 'appel en mémoire'}` : 'Aucun appel'}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => refetch()}>
            Rafraîchir
          </Button>
          {data && data.length > 0 && (
            <Button variant="outline" size="sm" onClick={() => exportTxt(data)}>
              <Download className=".5 size-3.5" />
              Export .txt
            </Button>
          )}
          <Button
            variant="outline"
            size="sm"
            disabled={clear.isPending || !data?.length}
            onClick={() => { clear.mutate(); setExpandedIdx(null) }}
          >
            <Trash2 className=".5 size-3.5" />
            Vider
          </Button>
        </div>
      </div>

      {isLoading && (
        <p className="py-8 text-center text-sm text-muted-foreground animate-pulse">Chargement…</p>
      )}
      {isError && (
        <p role="alert" className="text-sm text-destructive">Erreur lors du chargement.</p>
      )}
      {!isLoading && !isError && data?.length === 0 && (
        <p className="py-8 text-center text-sm text-muted-foreground">
          Aucun appel capturé pour le moment. Lancez une synchronisation Enable Banking pour afficher les échanges ici.
        </p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="overflow-x-auto">
          <div className="max-h-[60vh] overflow-y-auto">
            <table className="w-full min-w-[560px] text-sm">
              <thead className="sticky top-0 bg-background">
                <tr className="border-b text-left text-xs font-medium text-muted-foreground">
                  <th className="pb-2 pr-3 w-[140px]">Heure</th>
                  <th className="pb-2 pr-3 w-[70px]">Méthode</th>
                  <th className="pb-2 pr-3">Point de terminaison</th>
                  <th className="pb-2 w-[70px] text-center">Statut</th>
                </tr>
              </thead>
              <tbody>
                {data.map((entry, idx) => (
                  <>
                    <tr
                      key={idx}
                      className="cursor-pointer border-b last:border-0 hover:bg-muted/30"
                      onClick={() => setExpandedIdx(expandedIdx === idx ? null : idx)}
                    >
                      <td className="py-2 pr-3 text-xs text-muted-foreground whitespace-nowrap">
                        {new Date(entry.timestamp).toLocaleTimeString(locale)}
                      </td>
                      <td className="py-2 pr-3 text-xs font-mono font-medium">
                        {entry.method}
                      </td>
                      <td className="py-2 pr-3 text-xs font-mono truncate max-w-[320px]">
                        {entry.url.replace('https://api.enablebanking.com', '')}
                      </td>
                      <td className="py-2 text-center">
                        <StatusBadge status={entry.responseStatus} />
                      </td>
                    </tr>
                    {expandedIdx === idx && (
                      <tr key={`${idx}-detail`} className="border-b last:border-0 bg-muted/10">
                        <td colSpan={4} className="px-2 pb-3 pt-1 space-y-3">
                          <p className="text-xs break-all text-muted-foreground">
                            {entry.method} {entry.url}
                          </p>
                          {entry.requestBody && (
                            <div>
                              <p className="mb-1 text-xs font-medium text-muted-foreground">Corps de la requête</p>
                              <pre className="whitespace-pre-wrap break-all text-xs max-h-48 overflow-auto rounded-md bg-muted/40 p-2.5">
                                {tryPretty(entry.requestBody)}
                              </pre>
                            </div>
                          )}
                          <div>
                            <p className="mb-1 text-xs font-medium text-muted-foreground">Corps de la réponse</p>
                            <pre className="whitespace-pre-wrap break-all text-xs max-h-96 overflow-auto rounded-md bg-muted/40 p-2.5">
                              {tryPretty(entry.responseBody)}
                            </pre>
                          </div>
                        </td>
                      </tr>
                    )}
                  </>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}

export function EnableBankingDebugSection() {
  const [open, setOpen] = useState(false)

  return (
    <Card className="rounded-xl bg-card">
      <CardHeader>
        <CardTitle className="text-base font-semibold">
            Diagnostic API Enable Banking
        </CardTitle>
        <CardDescription>
          Journal des appels HTTP échangés avec Enable Banking à des fins de diagnostic.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogTrigger asChild>
            <Button variant="outline">Voir les appels</Button>
          </DialogTrigger>
          <DialogContent className="w-[95vw] sm:max-w-4xl" aria-describedby={undefined}>
            <DialogHeader>
              <DialogTitle>Appels API Enable Banking</DialogTitle>
            </DialogHeader>
            {open && <EbCallLogDialog />}
          </DialogContent>
        </Dialog>
      </CardContent>
    </Card>
  )
}
