import { TimeRangeSelector } from "picsou"

export function OneMonth() {
  return (
    <div className="w-fit rounded-lg border p-1">
      <TimeRangeSelector value="1M" onChange={() => {}} />
    </div>
  )
}

export function YearToDate() {
  return (
    <div className="w-fit rounded-lg border p-1">
      <TimeRangeSelector value="YTD" onChange={() => {}} />
    </div>
  )
}
