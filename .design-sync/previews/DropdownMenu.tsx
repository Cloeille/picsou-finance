import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  Button,
} from "picsou"
import { Eye, Pencil, Download, Trash2 } from "lucide-react"

export function AccountActions() {
  return (
    <DropdownMenu open>
      <DropdownMenuTrigger asChild>
        <Button variant="outline">Actions</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-56">
        <DropdownMenuLabel>Livret A</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem>
          <Eye /> View transactions
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Pencil /> Rename account
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Download /> Export CSV
          <DropdownMenuShortcut>⌘E</DropdownMenuShortcut>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="text-destructive focus:text-destructive">
          <Trash2 /> Delete account
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
