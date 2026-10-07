import { Fragment, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ScrollText } from 'lucide-react'
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
import { useAiCalls } from '@/features/admin/hooks'

const LIMIT = 50

function StatusBadge({ status }: { status: string }) {
  if (status === 'ERROR') {
    return (
      <span className="inline-flex items-center rounded-full bg-destructive/10 px-2 py-0.5 text-[11px] font-medium text-destructive">
        {status}
      </span>
    )
  }
  if (status === 'EMPTY') {
    return (
      <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
        {status}
      </span>
    )
  }
  return (
    <span className="inline-flex items-center rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-medium text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400">
      {status}
    </span>
  )
}

function TokenCell({ value }: { value?: number | null }) {
  if (value == null) return <span className="text-muted-foreground">—</span>
  return <>{value}</>
}

function AiCallsDialog() {
  const { t } = useTranslation()
  const [offset, setOffset] = useState(0)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const { data, isLoading, isError } = useAiCalls(LIMIT, offset)

  const total = data?.total ?? 0
  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + LIMIT, total)

  return (
    <div className="flex flex-col gap-4">
      {/* Summary */}
      <p className="text-sm text-muted-foreground">
        {t('admin.aiActivity.summary', {
          calls: data?.total ?? 0,
          tokens: data?.totalTokens ?? 0,
        })}
      </p>

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center py-8 text-sm text-muted-foreground">
          <span className="animate-pulse">{t('admin.aiActivity.loading')}</span>
        </div>
      )}

      {/* Error */}
      {isError && (
        <p role="alert" className="text-sm text-destructive">
          {t('error.serverError')}
        </p>
      )}

      {/* Empty */}
      {!isLoading && !isError && data?.items.length === 0 && (
        <p className="py-6 text-center text-sm text-muted-foreground">
          {t('admin.aiActivity.empty')}
        </p>
      )}

      {/* Table */}
      {!isLoading && !isError && data && data.items.length > 0 && (
        <div className="overflow-x-auto">
          <div className="max-h-[60vh] overflow-y-auto">
            <table className="w-full min-w-[600px] text-sm">
              <thead className="sticky top-0 bg-background">
                <tr className="border-b text-left text-xs font-medium text-muted-foreground">
                  <th className="pb-2 pr-3">{t('admin.aiActivity.colTime')}</th>
                  <th className="pb-2 pr-3">{t('admin.aiActivity.colMerchant')}</th>
                  <th className="pb-2 pr-3">{t('admin.aiActivity.colModel')}</th>
                  <th className="pb-2 pr-3 text-right">{t('admin.aiActivity.colTokens')}</th>
                  <th className="pb-2">{t('admin.aiActivity.colStatus')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((call) => (
                  <Fragment key={call.id}>
                    <tr
                      className="cursor-pointer border-b last:border-0 hover:bg-muted/30"
                      onClick={() =>
                        setExpandedId((prev) => (prev === call.id ? null : call.id))
                      }
                    >
                      <td className="py-2 pr-3 text-xs text-muted-foreground whitespace-nowrap">
                        {new Date(call.createdAt).toLocaleString()}
                      </td>
                      <td className="py-2 pr-3 max-w-[140px] truncate">
                        {call.merchantLabel ?? <span className="text-muted-foreground">—</span>}
                      </td>
                      <td className="py-2 pr-3 text-xs text-muted-foreground whitespace-nowrap">
                        {call.model ? `${call.provider} / ${call.model}` : call.provider}
                      </td>
                      <td className="py-2 pr-3 text-right text-xs tabular-nums">
                        <TokenCell value={call.promptTokens} />
                        {' / '}
                        <TokenCell value={call.completionTokens} />
                        {' / '}
                        <TokenCell value={call.totalTokens} />
                      </td>
                      <td className="py-2">
                        <StatusBadge status={call.status} />
                      </td>
                    </tr>
                    {expandedId === call.id && (
                      <tr key={`${call.id}-detail`} className="border-b last:border-0 bg-muted/10">
                        <td colSpan={5} className="px-2 pb-3 pt-1">
                          {call.error && (
                            <p className="mb-2 text-xs text-destructive">
                              <strong>Error:</strong> {call.error}
                            </p>
                          )}
                          {call.prompt && (
                            <div className="mb-2">
                              <p className="mb-1 text-[11px] font-medium text-muted-foreground uppercase tracking-wide">
                                {t('admin.aiActivity.prompt')}
                              </p>
                              <pre className="whitespace-pre-wrap break-all text-[11px] max-h-64 overflow-auto rounded bg-muted/40 p-2">
                                {call.prompt}
                              </pre>
                            </div>
                          )}
                          {call.response && (
                            <div>
                              <p className="mb-1 text-[11px] font-medium text-muted-foreground uppercase tracking-wide">
                                {t('admin.aiActivity.response')}
                              </p>
                              <pre className="whitespace-pre-wrap break-all text-[11px] max-h-64 overflow-auto rounded bg-muted/40 p-2">
                                {call.response}
                              </pre>
                            </div>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Pagination */}
      {!isLoading && !isError && data && total > 0 && (
        <div className="flex items-center justify-between gap-2 text-sm">
          <span className="text-muted-foreground text-xs">
            {from}–{to} / {total}
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={offset === 0}
              onClick={() => {
                setOffset((o) => Math.max(0, o - LIMIT))
                setExpandedId(null)
              }}
            >
              {t('admin.aiActivity.prev')}
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={offset + LIMIT >= total}
              onClick={() => {
                setOffset((o) => o + LIMIT)
                setExpandedId(null)
              }}
            >
              {t('admin.aiActivity.next')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

export function AiActivitySection() {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  return (
    <Card className="rounded-4xl bg-card shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <ScrollText className="size-5 text-muted-foreground" />
          {t('admin.aiActivity.title')}
        </CardTitle>
        <CardDescription>{t('admin.aiActivity.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogTrigger asChild>
            <Button variant="outline">{t('admin.aiActivity.view')}</Button>
          </DialogTrigger>
          <DialogContent className="w-[95vw] sm:max-w-3xl">
            <DialogHeader>
              <DialogTitle>{t('admin.aiActivity.title')}</DialogTitle>
            </DialogHeader>
            {open && <AiCallsDialog />}
          </DialogContent>
        </Dialog>
      </CardContent>
    </Card>
  )
}
