import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { connectedAppsApi } from './api'

/** Lists the current user's own OAuth2-connected apps (the endpoint is scoped to the caller). */
export function useConnectedApps() {
  return useQuery({
    queryKey: ['connectedApps'],
    queryFn: () => connectedAppsApi.list(),
  })
}

export function useRevokeConnectedApp() {
  return useInvalidatingMutation({
    mutationFn: (id: string) => connectedAppsApi.revoke(id),
    invalidates: [['connectedApps']],
    awaitRefresh: true,
  })
}
