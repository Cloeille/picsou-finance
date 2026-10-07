import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { connectedAppsApi } from './api'

/** Lists the current user's own OAuth2-connected apps (the endpoint is scoped to the caller). */
export function useConnectedApps() {
  return useQuery({
    queryKey: ['connectedApps'],
    queryFn: () => connectedAppsApi.list(),
  })
}

export function useRevokeConnectedApp() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => connectedAppsApi.revoke(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['connectedApps'] }),
  })
}
