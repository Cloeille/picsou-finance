import {
  PartitionBar,
  PartitionBarSegment,
  PartitionBarSegmentTitle,
  PartitionBarSegmentValue,
} from "picsou"

export function BudgetBreakdown() {
  return (
    <PartitionBar size="md" gap={1} className="w-96">
      <PartitionBarSegment num={38} variant="default" alignment="left">
        <PartitionBarSegmentTitle>Housing</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>38%</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={24} variant="secondary" alignment="left">
        <PartitionBarSegmentTitle>Food</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>24%</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={14} variant="outline" alignment="left">
        <PartitionBarSegmentTitle>Transport</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>14%</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={24} variant="muted" alignment="left">
        <PartitionBarSegmentTitle>Savings</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>24%</PartitionBarSegmentValue>
      </PartitionBarSegment>
    </PartitionBar>
  )
}

export function PortfolioAllocation() {
  return (
    <PartitionBar size="sm" gap={0.5} className="w-96">
      <PartitionBarSegment num={52} variant="default" alignment="center">
        <PartitionBarSegmentTitle>BTC</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>52%</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={31} variant="secondary" alignment="center">
        <PartitionBarSegmentTitle>ETH</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>31%</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={17} variant="muted" alignment="center">
        <PartitionBarSegmentTitle>Others</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>17%</PartitionBarSegmentValue>
      </PartitionBarSegment>
    </PartitionBar>
  )
}

export function OverspendWarning() {
  return (
    <PartitionBar size="lg" gap={1} className="w-96">
      <PartitionBarSegment num={70} variant="default" alignment="left">
        <PartitionBarSegmentTitle>Spent</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>€1,400 of €2,000</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={20} variant="destructive" alignment="left">
        <PartitionBarSegmentTitle>Over budget</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>€400</PartitionBarSegmentValue>
      </PartitionBarSegment>
      <PartitionBarSegment num={10} variant="outline" alignment="left">
        <PartitionBarSegmentTitle>Remaining</PartitionBarSegmentTitle>
        <PartitionBarSegmentValue>€200</PartitionBarSegmentValue>
      </PartitionBarSegment>
    </PartitionBar>
  )
}
