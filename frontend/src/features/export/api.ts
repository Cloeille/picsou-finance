import { api } from '@/lib/api-client'
import { filenameFromDisposition } from '@/lib/download'

export interface ExportRequest {
  reAuth: {
    password?: string | null
    totpCode?: string | null
  }
  includeBalanceSnapshots: boolean
}

export interface AccountsExportRequest {
  accountIds: number[]
  /**
   * Column and section headings, keyed by the backend's `LabelKey` in lowerCamelCase.
   *
   * The server carries no message bundle, so the localized wording travels with the request;
   * anything omitted falls back to that key's English default. See
   * `docs/decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md`.
   */
  labels: Record<string, string>
}

interface DownloadedFile {
  blob: Blob
  filename: string
}

/**
 * Streams the GDPR data export as a ZIP. Returns a Blob so the caller can
 * trigger a browser download via an anchor click. The Axios `responseType:
 * 'blob'` is critical — without it Axios tries to parse the binary body as
 * UTF-8 text and corrupts the ZIP central directory.
 */
export const exportApi = {
  download: async (req: ExportRequest): Promise<DownloadedFile> => {
    const res = await api.post<Blob>('/me/export', req, { responseType: 'blob' })
    return {
      blob: res.data,
      filename: filenameFromDisposition(
        res.headers['content-disposition'] as string | undefined,
        'picsou-export.zip'
      ),
    }
  },

  /** One spreadsheet, one sheet per selected account. Same binary-response caveat as above. */
  downloadAccountsXlsx: async (req: AccountsExportRequest): Promise<DownloadedFile> => {
    const res = await api.post<Blob>('/accounts/export', req, { responseType: 'blob' })
    return {
      blob: res.data,
      filename: filenameFromDisposition(
        res.headers['content-disposition'] as string | undefined,
        'picsou-comptes.xlsx'
      ),
    }
  },
}
