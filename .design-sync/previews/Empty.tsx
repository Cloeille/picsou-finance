import {
  Empty,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  EmptyDescription,
  EmptyContent,
  Button,
} from "picsou"
import { Receipt, PiggyBank, Search } from "lucide-react"

export function NoTransactions() {
  return (
    <Empty className="w-96 border">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Receipt />
        </EmptyMedia>
        <EmptyTitle>No transactions yet</EmptyTitle>
        <EmptyDescription>
          Connect a bank account to start tracking your spending.
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button size="sm">Connect a bank</Button>
      </EmptyContent>
    </Empty>
  )
}

export function NoGoals() {
  return (
    <Empty className="w-96 border">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <PiggyBank />
        </EmptyMedia>
        <EmptyTitle>No savings goals</EmptyTitle>
        <EmptyDescription>
          Set a target — a new car, an emergency fund — and track progress here.
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button size="sm" variant="outline">
          Create a goal
        </Button>
      </EmptyContent>
    </Empty>
  )
}

export function NoSearchResults() {
  return (
    <Empty className="w-96 border">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Search />
        </EmptyMedia>
        <EmptyTitle>No results for "Netflix"</EmptyTitle>
        <EmptyDescription>
          Try a different merchant name or clear your filters.
        </EmptyDescription>
      </EmptyHeader>
    </Empty>
  )
}
