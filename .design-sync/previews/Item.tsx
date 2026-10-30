import {
  Item,
  ItemMedia,
  ItemContent,
  ItemTitle,
  ItemDescription,
  ItemActions,
  ItemGroup,
  ItemSeparator,
} from "picsou"
import { Landmark, TrendingUp, TrendingDown } from "lucide-react"

export function TransactionRow() {
  return (
    <Item variant="outline" className="w-96">
      <ItemMedia variant="icon">
        <Landmark />
      </ItemMedia>
      <ItemContent>
        <ItemTitle>Carrefour Market</ItemTitle>
        <ItemDescription>Groceries · 2 Jul</ItemDescription>
      </ItemContent>
      <ItemActions>
        <span className="text-sm font-medium tabular-nums">-€64.20</span>
      </ItemActions>
    </Item>
  )
}

export function HoldingRow() {
  return (
    <Item variant="muted" className="w-96">
      <ItemMedia variant="icon">
        <TrendingUp className="text-emerald-500" />
      </ItemMedia>
      <ItemContent>
        <ItemTitle>Bitcoin</ItemTitle>
        <ItemDescription className="uppercase tracking-wider">BTC · 0.042</ItemDescription>
      </ItemContent>
      <ItemActions>
        <div className="text-right">
          <p className="text-sm font-medium tabular-nums">€2,840.12</p>
          <p className="text-xs text-emerald-500 tabular-nums">+3.2%</p>
        </div>
      </ItemActions>
    </Item>
  )
}

export function AccountList() {
  return (
    <ItemGroup className="w-96">
      <Item variant="default">
        <ItemMedia variant="icon">
          <Landmark />
        </ItemMedia>
        <ItemContent>
          <ItemTitle>Livret A</ItemTitle>
          <ItemDescription>Crédit Agricole</ItemDescription>
        </ItemContent>
        <ItemActions>
          <span className="text-sm font-medium tabular-nums">€12,450.00</span>
        </ItemActions>
      </Item>
      <ItemSeparator />
      <Item variant="default">
        <ItemMedia variant="icon">
          <TrendingDown className="text-destructive" />
        </ItemMedia>
        <ItemContent>
          <ItemTitle>Compte courant</ItemTitle>
          <ItemDescription>Boursorama</ItemDescription>
        </ItemContent>
        <ItemActions>
          <span className="text-sm font-medium tabular-nums">€1,204.55</span>
        </ItemActions>
      </Item>
    </ItemGroup>
  )
}
