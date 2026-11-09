import { useEffect, useRef } from 'react'

/** False once the component has unmounted: a dialog closed mid-request must not navigate anywhere. */
export function useMounted() {
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  return mounted
}
