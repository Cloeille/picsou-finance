import { useQuery } from '@tanstack/react-query'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { telemetryApi } from './api'

export const telemetryKeys = {
  all: ['telemetry'] as const,
  config: () => [...telemetryKeys.all, 'config'] as const,
}

export function useTelemetryConfig(enabled = true) {
  return useQuery({
    queryKey: telemetryKeys.config(),
    queryFn: telemetryApi.getConfig,
    enabled,
    staleTime: QUERY_STALE_TIMES.dashboard,
    retry: false,
  })
}
