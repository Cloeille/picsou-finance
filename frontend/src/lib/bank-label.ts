import { capitalizeFirst } from './utils'

/** The keys {@link formatBankLabel} words a synthesized title with, so it follows the interface language. */
export type BankLabelKey =
  | 'budget.recurring.labels.transferFrom'
  | 'budget.recurring.labels.transferIncoming'
  | 'budget.recurring.labels.transferOutgoing'

export type LabelTranslator = (key: BankLabelKey, options: { name: string }) => string

export interface FormattedBankLabel {
  title: string
  detail: string | null
}

/**
 * Strip SEPA mandate identifiers, creditor IDs, UUIDs, and parenthesized IBANs from raw bank
 * recurring labels so users see readable names instead of raw banking strings.
 */
export function formatBankLabel(raw: string, locale: string, t: LabelTranslator): FormattedBankLabel {
  const trimmed = raw.trim()
  if (!trimmed) return { title: raw, detail: null }

  // Strip embedded UUIDs and parenthesized IBANs first.
  let cleaned = trimmed
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '')
    .replace(/\(\s*[a-z]{2}\d{2}[a-z0-9]{10,30}\s*\)/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim()

  // Strip trailing SEPA direct-debit / transfer metadata (Ech Id Emetteur..., Mdt..., Ref..., Lib...).
  cleaned = cleaned
    .replace(/\s+\b(?:ech\s+)?(?:id\s+emetteur|emetteur|mdt|mandat|ref|lib)\b.*$/i, '')
    .replace(/\s{2,}/g, ' ')
    .trim()

  // Handle French "Recu De <Sender> Motif <Motif>" / "Virement De <Sender> Motif <Motif>" labels.
  const transferWithMotif = cleaned.match(
    /^(?:recu|reçu|virement(?:\s+sepa)?(?:\s+recu|\s+reçu)?)\s+de\s+(.+?)\s+motif\s+(.+)$/i,
  )
  if (transferWithMotif) {
    const sender = transferWithMotif[1]!.trim()
    const motif = transferWithMotif[2]!.trim()
    if (motif) {
      return {
        title: capitalizeFirst(motif.toLowerCase(), locale),
        detail: sender || null,
      }
    }
  }

  // Handle "Recu De <Sender>" without a Motif.
  const transferOnly = cleaned.match(/^(?:recu|reçu)\s+de\s+(.+)$/i)
  if (transferOnly && transferOnly[1]!.trim()) {
    return {
      title: t('budget.recurring.labels.transferFrom', { name: transferOnly[1]!.trim() }),
      detail: null,
    }
  }

  // Handle English Trade Republic boilerplate when UI is in French.
  if (locale.toLowerCase().startsWith('fr')) {
    const incoming = cleaned.match(/^Incoming\s+Transfer\s+From\s+(.+)$/i)
    const outgoing = cleaned.match(/^Outgoing\s+Transfer\s+(?:To|For)\s+(.+)$/i)
    if (incoming) {
      return { title: t('budget.recurring.labels.transferIncoming', { name: incoming[1]!.trim() }), detail: null }
    }
    if (outgoing) {
      return { title: t('budget.recurring.labels.transferOutgoing', { name: outgoing[1]!.trim() }), detail: null }
    }
  }

  return {
    title: cleaned || trimmed,
    detail: null,
  }
}
