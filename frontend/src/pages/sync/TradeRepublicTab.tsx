import { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import {
  RefreshCw,
  Upload,
  LogOut,
} from 'lucide-react'
import type { TrSessionStatus } from '@/types/api'
import { formatDateTime } from '@/lib/utils'
import { TradeRepublicLogin } from '@/components/sync/TradeRepublicLogin'

export function TradeRepublicTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const { data: sessionStatus, isLoading: statusLoading } = useQuery<TrSessionStatus>({
    queryKey: ['sync', 'tr', 'status'],
    queryFn: () => api.get<TrSessionStatus>('/tr/status').then(r => r.data),
  })

  const isConnected = sessionStatus?.isActive ?? false

  const syncMutation = useMutation({
    mutationFn: () => api.post('/tr/sync').then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'tr', 'status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const importCsvMutation = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api.post('/tr/import', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      }).then(r => r.data)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      if (fileInputRef.current) fileInputRef.current.value = ''
    },
  })

  const logoutMutation = useMutation({
    mutationFn: () => api.delete('/tr/session').then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'tr', 'status'] })
    },
  })

  function handleCsvChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) importCsvMutation.mutate(file)
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {isConnected ? (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.tr.sessionActive')}
                </Badge>
              ) : (
                <Badge variant="outline">
                  {t('sync.tr.noSession')}
                </Badge>
              )}
              {isConnected && sessionStatus?.expiresAt && (
                <span className="text-sm text-muted-foreground">
                  {t('sync.tr.expiresAt')} {formatDateTime(sessionStatus.expiresAt)}
                </span>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Connected controls */}
      {isConnected && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending}>
            <RefreshCw />
            {t('sync.tr.sync')}
          </Button>

          <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={importCsvMutation.isPending}>
            <Upload />
            {t('sync.tr.importCsv')}
          </Button>

          <Button variant="destructive" onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
            <LogOut />
            {t('sync.tr.clearSession')}
          </Button>

          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            className="hidden"
            onChange={handleCsvChange}
          />
        </div>
      )}

      {!isConnected && <TradeRepublicLogin variant="page" />}
    </div>
  )
}
