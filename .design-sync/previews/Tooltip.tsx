import {
  TooltipProvider,
  Tooltip,
  TooltipTrigger,
  TooltipContent,
  Button,
} from "picsou"
import { Info } from "lucide-react"

export function Hint() {
  return (
    <TooltipProvider>
      <Tooltip open>
        <TooltipTrigger asChild>
          <Button variant="ghost" size="icon" aria-label="What is net worth?">
            <Info />
          </Button>
        </TooltipTrigger>
        <TooltipContent>Net worth = assets − liabilities</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
