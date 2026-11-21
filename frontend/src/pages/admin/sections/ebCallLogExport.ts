import type { EbCallEntry } from '@/features/admin/api'

/**
 * Leads the exported file. The export is made to be attached to a bug report, often a public
 * one, and it holds the provider's raw answers: IBANs, holder names, balances, transactions.
 * English, because that file is read by maintainers.
 */
export const RAW_DATA_WARNING =
  'WARNING: raw Enable Banking responses: IBANs, account holder names, balances and transactions. ' +
  'Review and remove personal data before sharing this file publicly.'

export function tryPretty(json: string) {
  try { return JSON.stringify(JSON.parse(json), null, 2) } catch { return json }
}

/** The text of the `.txt` export: the warning, then one block per call, newest first. */
export function buildEbCallLogExport(entries: EbCallEntry[]): string {
  const blocks = entries.map((e, i) => [
    `── [${i + 1}] ${new Date(e.timestamp).toISOString()} ──────────────────────────────`,
    `${e.method} ${e.url}`,
    `HTTP ${e.responseStatus}`,
    e.requestBody ? `\n>>> REQUEST BODY\n${tryPretty(e.requestBody)}` : '',
    `\n<<< RESPONSE BODY\n${tryPretty(e.responseBody)}`,
  ].filter(Boolean).join('\n'))
  return [RAW_DATA_WARNING, ...blocks].join('\n\n')
}
