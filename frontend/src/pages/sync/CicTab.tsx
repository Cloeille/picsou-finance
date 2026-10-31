import { useCallback } from "react"
import { useSearchParams } from "react-router-dom"
import { CicPanel } from "@/components/sync/CicPanel"

/**
 * `?reconnect=1` comes from the Sync-all row of a lapsed session: the panel starts the
 * stored-credentials reconnect at once, then the flag is dropped so a reload does not
 * send another confirmation request to the phone.
 */
export function CicTab() {
  const [searchParams, setSearchParams] = useSearchParams()
  const autoReconnect = searchParams.get("reconnect") === "1"
  const dropReconnectFlag = useCallback(() => {
    setSearchParams(params => {
      params.delete("reconnect")
      return params
    }, { replace: true })
  }, [setSearchParams])
  return <CicPanel autoReconnect={autoReconnect} onAutoReconnectStarted={dropReconnectFlag} />
}
