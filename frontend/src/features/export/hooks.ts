import { useMutation } from '@tanstack/react-query'
import { exportApi, type AccountsExportRequest, type ExportRequest } from './api'
import { triggerBlobDownload } from '@/lib/download'
import { trackFeature } from '@/lib/telemetry'

export function useExportData() {
  return useMutation({
    mutationFn: async (req: ExportRequest) => {
      const { blob, filename } = await exportApi.download(req)
      triggerBlobDownload(blob, filename)
      trackFeature('export_downloaded')
      return { filename }
    },
  })
}

export function useExportAccountsXlsx() {
  return useMutation({
    mutationFn: async (req: AccountsExportRequest) => {
      const { blob, filename } = await exportApi.downloadAccountsXlsx(req)
      triggerBlobDownload(blob, filename)
      trackFeature('export_downloaded')
      return { filename }
    },
  })
}
