import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
  Button,
  Input,
  Label,
} from "picsou"

export function AddAccount() {
  return (
    <Dialog open>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Add account</DialogTitle>
          <DialogDescription>
            Connect a new account to track its balance in your net worth.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-1.5">
            <Label htmlFor="acc-name">Account name</Label>
            <Input id="acc-name" defaultValue="Livret A" />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="acc-balance">Current balance (€)</Label>
            <Input id="acc-balance" defaultValue="12,450.00" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost">Cancel</Button>
          <Button>Add account</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
