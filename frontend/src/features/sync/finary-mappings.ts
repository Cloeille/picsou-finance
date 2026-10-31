import { ACCOUNT_COLORS } from '@/lib/constants'
import type { Account, AccountType, FinaryAccountMapping, FinaryPreviewResponse } from '@/types/api'

const INVESTMENT_TYPES = new Set<AccountType>(['PEA', 'COMPTE_TITRES', 'EMPLOYEE_SAVINGS'])
const SAVINGS_TYPES = new Set<AccountType>([
  'SAVINGS',
  'LIVRET_A',
  'LDDS',
  'LEP',
  'LIVRET_JEUNE',
  'PEL',
  'CEL',
])

export function typesCompatible(category: string, type: AccountType): boolean {
  const cat = category.toLowerCase().replace(/ /g, '_')
  switch (cat) {
    case 'checkings':
    case 'checking':
      return type === 'CHECKING'
    case 'savings':
    case 'fonds_euro':
    case 'fonds-euro':
      return SAVINGS_TYPES.has(type)
    case 'investments':
      return INVESTMENT_TYPES.has(type)
    case 'cryptos':
      return type === 'CRYPTO'
    case 'loans':
    case 'credits':
      return type === 'LOAN' || type === 'OTHER'
    case 'real_estates':
    case 'real-estate':
      return type === 'REAL_ESTATE'
    default:
      return type === 'OTHER'
  }
}

/** Finary ids are only unique within a category, as on the backend (`finary_{category}_{id}`). */
function finaryKey(row: { finaryCategory: string; finaryId: string }): string {
  return JSON.stringify([row.finaryCategory, row.finaryId])
}

/**
 * A backend suggestedMapping is final: its matcher knows which accounts another
 * connector owns, which the frontend cannot fully see. Only rows the backend gave
 * no suggestion fall back to a unique name+type match against manual Picsou
 * accounts. Unmatched rows stay CREATE_NEW so a single new Finary account no
 * longer resets the whole wizard. A Picsou account is assigned to at most one row:
 * the backend rejects two Finary accounts mapped onto the same target.
 */
export function initialFinaryMappings(preview: FinaryPreviewResponse): FinaryAccountMapping[] {
  const suggestedByKey = new Map(
    (preview.suggestedMappings ?? [])
      .filter((m) => m.finaryId)
      .map((m) => [finaryKey(m), m]),
  )
  const suggestions = preview.accounts.map((account) => suggestedByKey.get(finaryKey(account)))

  // Reserved before any name fallback, so an earlier unsuggested row cannot take
  // the account a later row's suggestion maps onto.
  const claimed = new Set<number>()
  const backendTargets = suggestions.map((s) => {
    if (s?.action !== 'MAP_EXISTING' || s.targetAccountId == null || claimed.has(s.targetAccountId)) {
      return undefined
    }
    claimed.add(s.targetAccountId)
    return s.targetAccountId
  })

  const existing = preview.existingPicsouAccounts ?? []
  return preview.accounts.map((account, i) => {
    const target = suggestions[i] ? backendTargets[i] : nameMatch(account, existing, claimed)
    if (target == null) return createNewMapping(account, i)
    return {
      finaryId: account.finaryId,
      finaryName: account.finaryName,
      finaryCategory: account.finaryCategory,
      action: 'MAP_EXISTING',
      targetAccountId: target,
    }
  })
}

/**
 * Claims the single unclaimed manual account whose name and type match the row.
 * Non-manual accounts are skipped: they may belong to another connector, and the
 * backend name tier refuses those (the frontend has no externalAccountId to tell).
 */
function nameMatch(
  account: FinaryPreviewResponse['accounts'][number],
  existing: Account[],
  claimed: Set<number>,
): number | undefined {
  const needle = account.finaryName.trim().toLowerCase()
  const hits = existing.filter(
    (acc) =>
      acc.isManual &&
      !claimed.has(acc.id) &&
      acc.name.trim().toLowerCase() === needle &&
      typesCompatible(account.finaryCategory, acc.type),
  )
  if (hits.length !== 1) return undefined
  claimed.add(hits[0].id)
  return hits[0].id
}

/**
 * Picsou accounts the target picker of row `rowIndex` may offer: every existing
 * account except those another row already maps onto. The row keeps its own target.
 */
export function availableTargets(
  existing: Account[],
  mappings: FinaryAccountMapping[],
  rowIndex: number,
): Account[] {
  const reserved = new Set(
    mappings
      .filter((m, i) => i !== rowIndex && m.action === 'MAP_EXISTING' && m.targetAccountId != null)
      .map((m) => m.targetAccountId),
  )
  return existing.filter((acc) => !reserved.has(acc.id))
}

function createNewMapping(
  account: FinaryPreviewResponse['accounts'][number],
  index: number,
): FinaryAccountMapping {
  return {
    finaryId: account.finaryId,
    finaryName: account.finaryName,
    finaryCategory: account.finaryCategory,
    action: 'CREATE_NEW',
    targetAccountId: undefined,
    newAccount: {
      name: account.finaryName,
      type: account.suggestedType,
      provider: account.finaryInstitution,
      currency: account.nativeCurrency,
      color: ACCOUNT_COLORS[index % ACCOUNT_COLORS.length],
    },
  }
}
