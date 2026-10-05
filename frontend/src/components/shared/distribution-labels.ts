import type { AccountType } from '@/types/api'

export interface DistributionItem {
  accountId: number
  name: string
  color: string
  balanceEur: number
  percentage: number
  accountType: AccountType
}

function normalizedName(name: string): string {
  return name.trim().replace(/\s+/g, ' ').toLocaleLowerCase()
}

/**
 * Keep broker-provided account names intact unless they are ambiguous in the
 * same chart. Account type is the natural discriminator for PEA/CTO accounts;
 * the stable account id is only used as a final fallback for true duplicates.
 */
export function disambiguateDistributionNames(
  data: DistributionItem[],
  typeLabel: (type: AccountType) => string,
): DistributionItem[] {
  const nameCounts = new Map<string, number>()
  for (const item of data) {
    const key = normalizedName(item.name)
    nameCounts.set(key, (nameCounts.get(key) ?? 0) + 1)
  }

  const typedNames = data.map(item => {
    if ((nameCounts.get(normalizedName(item.name)) ?? 0) < 2) return item.name
    return `${item.name} · ${typeLabel(item.accountType)}`
  })
  const typedNameCounts = new Map<string, number>()
  for (const name of typedNames) {
    const key = normalizedName(name)
    typedNameCounts.set(key, (typedNameCounts.get(key) ?? 0) + 1)
  }

  return data.map((item, index) => {
    const name = typedNames[index]
    if (name === item.name) return item
    const uniqueName = (typedNameCounts.get(normalizedName(name)) ?? 0) > 1
      ? `${name} · #${item.accountId}`
      : name
    return { ...item, name: uniqueName }
  })
}
