import {
  ScrollArea,
  Item,
  ItemMedia,
  ItemContent,
  ItemTitle,
  ItemDescription,
  ItemActions,
  ItemGroup,
} from "picsou"
import { ShoppingCart, Fuel, Coffee, Home, Wifi } from "lucide-react"

const transactions = [
  { icon: ShoppingCart, title: "Carrefour Market", desc: "Groceries · 2 Jul", amount: "-€64.20" },
  { icon: Fuel, title: "Total Energies", desc: "Transport · 1 Jul", amount: "-€52.00" },
  { icon: Coffee, title: "Starbucks", desc: "Dining · 30 Jun", amount: "-€5.40" },
  { icon: Home, title: "Loyer", desc: "Housing · 28 Jun", amount: "-€890.00" },
  { icon: Wifi, title: "Free Mobile", desc: "Utilities · 27 Jun", amount: "-€19.99" },
  { icon: ShoppingCart, title: "Monoprix", desc: "Groceries · 25 Jun", amount: "-€38.15" },
]

export function TransactionsFeed() {
  return (
    <ScrollArea className="h-64 w-96 rounded-lg border">
      <ItemGroup className="p-2">
        {transactions.map((row) => (
          <Item key={row.title} size="sm">
            <ItemMedia variant="icon">
              <row.icon />
            </ItemMedia>
            <ItemContent>
              <ItemTitle>{row.title}</ItemTitle>
              <ItemDescription>{row.desc}</ItemDescription>
            </ItemContent>
            <ItemActions>
              <span className="text-sm font-medium tabular-nums">{row.amount}</span>
            </ItemActions>
          </Item>
        ))}
      </ItemGroup>
    </ScrollArea>
  )
}

const activity = [
  "Sync completed for Boursorama — 4 new transactions",
  'Goal "Emergency fund" reached 62%',
  "Budget alert: Dining over by €18 this month",
  "New device signed in from Paris, FR",
  "Revolut sync completed — 12 new transactions",
  "Recurring payment detected: Spotify €9.99/mo",
]

export function ActivityLog() {
  return (
    <ScrollArea className="h-48 w-96 rounded-lg border p-3">
      <ul className="space-y-2">
        {activity.map((entry) => (
          <li
            key={entry}
            className="border-b border-border/50 pb-2 text-xs text-muted-foreground last:border-0 last:pb-0"
          >
            {entry}
          </li>
        ))}
      </ul>
    </ScrollArea>
  )
}
