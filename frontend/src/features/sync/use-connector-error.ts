import { useCallback, useState } from 'react'

interface ConnectorErrorState {
  message: string | null
  code: string | null
  reportable: boolean
}

const EMPTY: ConnectorErrorState = { message: null, code: null, reportable: false }

/** Tracks a connector error message, its typed code, and whether it originated from the connector. */
export function useConnectorError() {
  const [state, setState] = useState<ConnectorErrorState>(EMPTY)
  const setError = useCallback((message: string | null, code?: string | null) => {
    setState(message ? { message, code: code ?? null, reportable: true } : EMPTY)
  }, [])
  const setLocalError = useCallback((message: string) => {
    setState({ message, code: null, reportable: false })
  }, [])
  return {
    error: state.message,
    errorCode: state.code,
    reportable: state.reportable,
    setError,
    setLocalError,
  }
}
