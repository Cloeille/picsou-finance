import { api } from '@/lib/api-client'
import type { HomeBankImportRequest, HomeBankImportResult, HomeBankPreviewResponse } from './types'

export const homeBankApi = {
  preview(file: File, password?: string): Promise<HomeBankPreviewResponse> {
    const form = new FormData()
    form.append('file', file)
    if (password) form.append('password', password)
    return api.post<HomeBankPreviewResponse>('/homebank/import/preview', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(response => response.data)
  },
  execute(request: HomeBankImportRequest): Promise<HomeBankImportResult> {
    return api.post<HomeBankImportResult>('/homebank/import', request).then(response => response.data)
  },
}
