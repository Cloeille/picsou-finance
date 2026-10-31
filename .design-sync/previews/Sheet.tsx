import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
  Button,
  Badge,
  Separator,
} from "picsou"

export function TransactionDetail() {
  return (
    <Sheet open>
      <SheetContent side="right" className="w-96">
        <SheetHeader>
          <SheetTitle>Carrefour Market</SheetTitle>
          <SheetDescription>May 12, 2025 · Groceries</SheetDescription>
        </SheetHeader>
        <div className="grid gap-3 px-4">
          <div className="flex items-center justify-between">
            <span className="text-sm text-muted-foreground">Amount</span>
            <span className="text-lg font-semibold tabular-nums">-€54.20</span>
          </div>
          <Separator />
          <div className="flex items-center justify-between">
            <span className="text-sm text-muted-foreground">Account</span>
            <span className="text-sm">Compte courant</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm text-muted-foreground">Category</span>
            <Badge variant="outline">Groceries</Badge>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm text-muted-foreground">Status</span>
            <Badge className="bg-emerald-500/10 text-emerald-600">Cleared</Badge>
          </div>
        </div>
        <SheetFooter>
          <Button variant="outline">Edit transaction</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
