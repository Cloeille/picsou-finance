import { useQuery } from '@tanstack/react-query'
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
    staleTime: 5 * 60_000,
    retry: false,
  })
}
