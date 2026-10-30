import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
  CardAction,
  Button,
  Badge,
} from "picsou"
import { TrendingUp, MoreHorizontal } from "lucide-react"

export function AccountSummary() {
  return (
    <Card className="w-80">
      <CardHeader>
        <CardTitle>Livret A</CardTitle>
        <CardDescription>Savings · Crédit Agricole</CardDescription>
        <CardAction>
          <Badge variant="secondary">SAVINGS</Badge>
        </CardAction>
      </CardHeader>
      <CardContent>
        <p className="text-2xl font-semibold tabular-nums">€12,450.00</p>
        <p className="mt-1 flex items-center gap-1 text-xs text-emerald-500">
          <TrendingUp className="size-3" /> +€320 this month
        </p>
      </CardContent>
      <CardFooter>
        <Button variant="outline" size="sm" className="w-full">
          View transactions
        </Button>
      </CardFooter>
    </Card>
  )
}

export function NetWorth() {
  return (
    <Card className="w-80">
      <CardHeader>
        <CardDescription>Total net worth</CardDescription>
        <CardTitle className="text-3xl tabular-nums">€184,320</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-xs text-muted-foreground">
          Across 7 accounts · updated today
        </p>
      </CardContent>
    </Card>
  )
}

export function Simple() {
  return (
    <Card className="w-80">
      <CardContent className="flex items-center justify-between p-4">
        <div>
          <p className="font-medium">Monthly budget</p>
          <p className="text-xs text-muted-foreground">€1,240 of €2,000 spent</p>
        </div>
        <Button variant="ghost" size="icon" aria-label="More">
          <MoreHorizontal />
        </Button>
      </CardContent>
    </Card>
  )
}
