import { api } from '@/lib/api-client'
import type { ActualImportRequest, ActualImportResult, ActualPreviewResponse } from './types'

export const actualBudgetApi = {
  preview(file: File): Promise<ActualPreviewResponse> {
    const form = new FormData()
    form.append('file', file)
    return api.post<ActualPreviewResponse>('/actual/import/preview', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((response) => response.data)
  },
  execute(request: ActualImportRequest): Promise<ActualImportResult> {
    return api.post<ActualImportResult>('/actual/import', request).then((response) => response.data)
  },
}
