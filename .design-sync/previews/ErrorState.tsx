import { ErrorState } from "picsou"

export function WithRetry() {
  return (
    <div className="w-96 rounded-lg border">
      <ErrorState
        message="We couldn't reach your bank. Check your connection and try again."
        onRetry={() => {}}
      />
    </div>
  )
}

export function CustomTitle() {
  return (
    <div className="w-96 rounded-lg border">
      <ErrorState
        title="Sync failed"
        message="Boursorama returned an error during the last sync."
        onRetry={() => {}}
      />
    </div>
  )
}
