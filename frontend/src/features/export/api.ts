import { api } from '@/lib/api-client'
import { filenameFromDisposition } from '@/lib/download'

export interface ExportRequest {
  /** Omitted when a `reauth_proof` cookie (minted by `/auth/reauth` or an SSO round trip) authorizes the export. */
  reAuth?: {
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

export interface ArchiveExportRequest {
  passphrase: string
  /** Omitted when a `reauth_proof` cookie, or the desktop window's gate, authorizes the export. */
  reAuth?: {
    password?: string | null
    totpCode?: string | null
  }
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

  /**
   * The encrypted archive of the whole instance (`.picsou-archive`). A refusal answers with a
   * JSON problem, but `responseType: 'blob'` hands it over as a Blob: it is read back so the
   * caller can tell the stable `code` from the status.
   */
  downloadArchive: async (req: ArchiveExportRequest): Promise<DownloadedFile> => {
    try {
      const res = await api.post<Blob>('/admin/archive', req, { responseType: 'blob' })
      return {
        blob: res.data,
        filename: filenameFromDisposition(
          res.headers['content-disposition'] as string | undefined,
          'picsou.picsou-archive'
        ),
      }
    } catch (err) {
      await unwrapBlobProblem(err)
      throw err
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

async function unwrapBlobProblem(err: unknown): Promise<void> {
  const response = (err as { response?: { data?: unknown } }).response
  if (!(response?.data instanceof Blob)) return
  try {
    response.data = JSON.parse(await response.data.text())
  } catch {
    response.data = undefined
  }
}
