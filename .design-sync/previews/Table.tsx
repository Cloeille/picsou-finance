import {
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableRow,
  TableHead,
  TableCell,
  Badge,
} from "picsou"

const rows = [
  { date: "May 12", merchant: "Carrefour", category: "Groceries", amount: "-€54.20", income: false },
  { date: "May 11", merchant: "Salary — ACME Corp", category: "Income", amount: "+€3,200.00", income: true },
  { date: "May 10", merchant: "Netflix", category: "Subscriptions", amount: "-€13.49", income: false },
  { date: "May 9", merchant: "SNCF", category: "Transport", amount: "-€87.00", income: false },
]

export function Transactions() {
  return (
    <Table className="w-[36rem]">
      <TableHeader>
        <TableRow>
          <TableHead>Date</TableHead>
          <TableHead>Merchant</TableHead>
          <TableHead>Category</TableHead>
          <TableHead className="text-right">Amount</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.merchant}>
            <TableCell className="text-muted-foreground">{r.date}</TableCell>
            <TableCell className="font-medium">{r.merchant}</TableCell>
            <TableCell>
              <Badge variant="outline">{r.category}</Badge>
            </TableCell>
            <TableCell
              className={`text-right tabular-nums ${r.income ? "text-emerald-500" : ""}`}
            >
              {r.amount}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
      <TableFooter>
        <TableRow>
          <TableCell colSpan={3}>Net this week</TableCell>
          <TableCell className="text-right tabular-nums">+€3,045.31</TableCell>
        </TableRow>
      </TableFooter>
    </Table>
  )
}
