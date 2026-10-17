import { api } from '@/lib/api-client'
import type { TelemetryConfig } from '@/types/api'

export const telemetryApi = {
  getConfig: () => api.get<TelemetryConfig>('/telemetry/config').then(r => r.data),
}
