import { PageHeader, Button } from "picsou"
import { Plus } from "lucide-react"

export function WithAction() {
  return (
    <div className="w-[32rem]">
      <PageHeader
        surtitle="Accounts"
        title="Net worth"
        actions={
          <Button size="sm">
            <Plus /> Add account
          </Button>
        }
      />
    </div>
  )
}

export function TitleOnly() {
  return (
    <div className="w-[32rem]">
      <PageHeader title="Settings" />
    </div>
  )
}
