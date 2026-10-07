import { Tabs, TabsList, TabsTrigger, TabsContent } from "picsou"

export function AccountsOverview() {
  return (
    <Tabs defaultValue="overview" className="w-96">
      <TabsList>
        <TabsTrigger value="overview">Overview</TabsTrigger>
        <TabsTrigger value="transactions">Transactions</TabsTrigger>
        <TabsTrigger value="insights">Insights</TabsTrigger>
      </TabsList>
      <TabsContent value="overview" className="mt-4 space-y-1">
        <p className="text-sm font-medium">Total balance</p>
        <p className="text-2xl font-semibold tabular-nums">€24,180.42</p>
        <p className="text-xs text-muted-foreground">Across 4 accounts</p>
      </TabsContent>
      <TabsContent value="transactions" className="mt-4">
        <p className="text-xs text-muted-foreground">12 transactions since Monday</p>
      </TabsContent>
      <TabsContent value="insights" className="mt-4">
        <p className="text-xs text-muted-foreground">Spending is down 8% vs last month</p>
      </TabsContent>
    </Tabs>
  )
}

export function PeriodToggle() {
  return (
    <Tabs defaultValue="CYCLE" className="w-fit">
      <TabsList>
        <TabsTrigger value="CYCLE">This cycle</TabsTrigger>
        <TabsTrigger value="YTD">Year to date</TabsTrigger>
      </TabsList>
    </Tabs>
  )
}

export function SyncSourcesLine() {
  return (
    <Tabs defaultValue="banks" className="w-96">
      <TabsList variant="line">
        <TabsTrigger value="banks">Banks</TabsTrigger>
        <TabsTrigger value="crypto">Crypto</TabsTrigger>
        <TabsTrigger value="wallets">Wallets</TabsTrigger>
      </TabsList>
      <TabsContent value="banks" className="mt-4">
        <p className="text-xs text-muted-foreground">3 banks connected · last sync 2h ago</p>
      </TabsContent>
      <TabsContent value="crypto" className="mt-4">
        <p className="text-xs text-muted-foreground">Kraken · Ledger · 2 wallets</p>
      </TabsContent>
      <TabsContent value="wallets" className="mt-4">
        <p className="text-xs text-muted-foreground">No manual wallets yet</p>
      </TabsContent>
    </Tabs>
  )
}
