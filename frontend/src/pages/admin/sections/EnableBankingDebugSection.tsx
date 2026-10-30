import { useState } from 'react'
import { Download, Network, Trash2 } from 'lucide-react'
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
import type { EbCallEntry } from '@/features/admin/api'

function StatusBadge({ status }: { status: number }) {
  const ok = status >= 200 && status < 300
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium tabular-nums ${
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
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const { data, isLoading, isError, refetch } = useEbCallLog(true)
  const clear = useClearEbCallLog()

  return (
    <div className="flex flex-col gap-4">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm text-muted-foreground">
          {data ? `${data.length} appel(s) en mémoire` : '—'}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => refetch()}>
            Rafraîchir
          </Button>
          {data && data.length > 0 && (
            <Button variant="outline" size="sm" onClick={() => exportTxt(data)}>
              <Download className="mr-1.5 size-3.5" />
              Export .txt
            </Button>
          )}
          <Button
            variant="outline"
            size="sm"
            disabled={clear.isPending || !data?.length}
            onClick={() => { clear.mutate(); setExpandedIdx(null) }}
          >
            <Trash2 className="mr-1.5 size-3.5" />
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
          Aucun appel capturé — lance une sync Revolut pour en générer.
        </p>
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <div className="overflow-x-auto">
          <div className="max-h-[60vh] overflow-y-auto">
            <table className="w-full min-w-[560px] text-sm">
              <thead className="sticky top-0 bg-background">
                <tr className="border-b text-left text-xs font-medium text-muted-foreground">
                  <th className="pb-2 pr-3 w-[140px]">Heure</th>
                  <th className="pb-2 pr-3 w-[50px]">Méth.</th>
                  <th className="pb-2 pr-3">Endpoint</th>
                  <th className="pb-2 w-[60px] text-center">Status</th>
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
                        {new Date(entry.timestamp).toLocaleTimeString()}
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
                          <p className="font-mono text-[11px] break-all text-muted-foreground">
                            {entry.method} {entry.url}
                          </p>
                          {entry.requestBody && (
                            <div>
                              <p className="mb-1 text-[11px] font-medium text-muted-foreground uppercase tracking-wide">Request body</p>
                              <pre className="whitespace-pre-wrap break-all text-[11px] max-h-48 overflow-auto rounded bg-muted/40 p-2 font-mono">
                                {tryPretty(entry.requestBody)}
                              </pre>
                            </div>
                          )}
                          <div>
                            <p className="mb-1 text-[11px] font-medium text-muted-foreground uppercase tracking-wide">Response body</p>
                            <pre className="whitespace-pre-wrap break-all text-[11px] max-h-96 overflow-auto rounded bg-muted/40 p-2 font-mono">
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
    <Card className="rounded-4xl bg-card shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Network className="size-5 text-muted-foreground" />
          Enable Banking — Debug API
        </CardTitle>
        <CardDescription>
          Capture des appels HTTP bruts (endpoint, request, response) vers Enable Banking pour diagnostic.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogTrigger asChild>
            <Button variant="outline">Voir les appels</Button>
          </DialogTrigger>
          <DialogContent className="w-[95vw] sm:max-w-4xl">
            <DialogHeader>
              <DialogTitle>Enable Banking — Appels API bruts</DialogTitle>
            </DialogHeader>
            {open && <EbCallLogDialog />}
          </DialogContent>
        </Dialog>
      </CardContent>
    </Card>
  )
}
