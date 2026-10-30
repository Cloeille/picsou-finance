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
 * Prefers backend suggestedMappings (external-id or name match). Falls back to a
 * unique name+type match against existing Picsou accounts. Unmatched rows stay
 * CREATE_NEW so a single new Finary account no longer resets the whole wizard.
 * A Picsou account is assigned to at most one row: the backend rejects two Finary
 * accounts mapped onto the same target.
 */
export function initialFinaryMappings(preview: FinaryPreviewResponse): FinaryAccountMapping[] {
  const claimed = new Set<number>()
  const suggestedByKey = new Map(
    (preview.suggestedMappings ?? [])
      .filter((m) => m.finaryId)
      .map((m) => [finaryKey(m), m]),
  )
  const existing = preview.existingPicsouAccounts ?? []

  return preview.accounts.map((account, i) => {
    const suggested = suggestedByKey.get(finaryKey(account))
    if (
      suggested?.action === 'MAP_EXISTING' &&
      suggested.targetAccountId != null &&
      !claimed.has(suggested.targetAccountId)
    ) {
      claimed.add(suggested.targetAccountId)
      return {
        ...suggested,
        finaryId: account.finaryId,
        finaryName: account.finaryName,
        finaryCategory: account.finaryCategory,
      }
    }

    const needle = account.finaryName.trim().toLowerCase()
    const nameHits = existing.filter(
      (acc) =>
        !claimed.has(acc.id) &&
        acc.name.trim().toLowerCase() === needle &&
        typesCompatible(account.finaryCategory, acc.type),
    )
    if (nameHits.length === 1) {
      claimed.add(nameHits[0].id)
      return {
        finaryId: account.finaryId,
        finaryName: account.finaryName,
        finaryCategory: account.finaryCategory,
        action: 'MAP_EXISTING',
        targetAccountId: nameHits[0].id,
      }
    }

    return createNewMapping(account, i)
  })
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
