import { ACCOUNT_COLORS } from '@/lib/constants'
import type { AccountType, FinaryAccountMapping, FinaryPreviewResponse } from '@/types/api'

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

/**
 * Prefers backend suggestedMappings (external-id or name match). Falls back to a
 * unique name+type match against existing Picsou accounts. Unmatched rows stay
 * CREATE_NEW so a single new Finary account no longer resets the whole wizard.
 */
export function initialFinaryMappings(preview: FinaryPreviewResponse): FinaryAccountMapping[] {
  const claimed = new Set<number>()
  const suggestedById = new Map(
    (preview.suggestedMappings ?? [])
      .filter((m) => m.finaryId)
      .map((m) => [m.finaryId, m]),
  )
  const existing = preview.existingPicsouAccounts ?? []

  return preview.accounts.map((account, i) => {
    const suggested = suggestedById.get(account.finaryId)
    if (suggested?.action === 'MAP_EXISTING' && suggested.targetAccountId != null) {
      claimed.add(suggested.targetAccountId)
      return { ...suggested }
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
