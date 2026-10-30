import { useTranslation } from 'react-i18next'
import { useAllAccounts, useToggleAccountVisibility, useAccountTree } from '@/features/accounts/hooks'
import { Card, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { Account } from '@/types/api'

function VisibilityRow({ account, indent = false }: { account: Account; indent?: boolean }) {
  const toggle = useToggleAccountVisibility()
  return (
    <div className={`flex items-center justify-between gap-3 py-2 ${indent ? 'pl-6' : ''}`}>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{account.name}</p>
        <CurrencyDisplay value={account.currentBalanceEur} className="text-xs text-muted-foreground" />
      </div>
      <Switch
        checked={!account.hidden}
        disabled={toggle.isPending}
        onCheckedChange={(visible) => toggle.mutate({ id: account.id, hidden: !visible })}
        aria-label={account.name}
      />
    </div>
  )
}

export function AccountsVisibilityTab() {
  const { t } = useTranslation()
  const { data: accounts, isLoading } = useAllAccounts()
  const { walletGroups, standaloneAccounts } = useAccountTree(accounts)

  // Group standalone accounts and wallets by provider so the tree reads
  // provider -> wallet -> pockets, per the approved design.
  const byProvider = new Map<string, Account[]>()
  for (const a of standaloneAccounts) {
    const key = a.provider ?? t('sync.visibility.manualGroup')
    if (!byProvider.has(key)) byProvider.set(key, [])
    byProvider.get(key)!.push(a)
  }
  const walletsByProvider = new Map<string, typeof walletGroups>()
  for (const g of walletGroups) {
    const key = g.wallet.provider ?? t('sync.visibility.manualGroup')
    if (!walletsByProvider.has(key)) walletsByProvider.set(key, [])
    walletsByProvider.get(key)!.push(g)
  }
  const providers = [...new Set([...byProvider.keys(), ...walletsByProvider.keys()])].sort()

  if (isLoading) {
    return (
      <div className="space-y-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-14 w-full rounded-xl" />
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">{t('sync.visibility.description')}</p>
      {providers.map((provider) => (
        <Card key={provider}>
          <CardContent className="divide-y">
            <p className="pb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {provider}
            </p>
            {(walletsByProvider.get(provider) ?? []).map(({ wallet, pockets }) => (
              <div key={wallet.id}>
                <VisibilityRow account={wallet} />
                {pockets.map((pocket) => (
                  <VisibilityRow key={pocket.id} account={pocket} indent />
                ))}
              </div>
            ))}
            {(byProvider.get(provider) ?? []).map((account) => (
              <VisibilityRow key={account.id} account={account} />
            ))}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
