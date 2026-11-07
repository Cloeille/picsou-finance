import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { ssoApi } from './api'

export const ssoKeys = {
  info: ['sso', 'info'],
  methods: ['sso', 'sign-in-methods'],
} as const

export function useSsoInfo(enabled = true) {
  return useQuery({
    queryKey: ssoKeys.info,
    queryFn: ssoApi.getInfo,
    staleTime: QUERY_STALE_TIMES.ssoInfo,
    retry: false,
    enabled,
  })
}

export function useSignInMethods() {
  return useQuery({ queryKey: ssoKeys.methods, queryFn: ssoApi.getSignInMethods })
}

function useMethodsMutation<TVars>(mutationFn: (vars: TVars) => Promise<unknown>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () => qc.invalidateQueries({ queryKey: ssoKeys.methods }),
  })
}

export const useReauth = () => useMethodsMutation(ssoApi.reauth)
export const useRemovePassword = () => useMethodsMutation(ssoApi.removePassword)
export const useSetPassword = () => useMethodsMutation(ssoApi.setPassword)
export const useUnlinkIdentity = () => useMethodsMutation(ssoApi.unlinkIdentity)

export function useAdminUnlinkIdentities() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ssoApi.adminUnlinkIdentities,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['family', 'members'] }),
  })
}
