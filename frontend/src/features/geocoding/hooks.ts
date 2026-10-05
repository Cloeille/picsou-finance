import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { geocodingApi } from './api'

/** Shorter than this and the suggestions are noise rather than help. */
const MIN_QUERY_LENGTH = 3
const DEBOUNCE_MS = 300

/**
 * Debounced address lookup.
 *
 * The debounce is a courtesy, not a control — the backend enforces the real per-member cap,
 * because a stuck key or a scripted client would otherwise spend the whole instance's
 * budget with the upstream service.
 */
export function useAddressSearch(query: string, enabled = true) {
  const [debounced, setDebounced] = useState(query)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(query), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [query])

  const trimmed = debounced.trim()
  return useQuery({
    queryKey: ['geocode', trimmed],
    queryFn: () => geocodingApi.search(trimmed),
    enabled: enabled && trimmed.length >= MIN_QUERY_LENGTH,
    // Addresses do not move; refetching the same string wastes the shared rate limit.
    staleTime: 5 * 60 * 1000,
  })
}
