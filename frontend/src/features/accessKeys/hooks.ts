import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { accessKeysApi, type CreateAccessKeyInput } from './api'

/** Lists the current member's own access-keys (the endpoint is scoped to the caller). */
export function useAccessKeys() {
  return useQuery({
    queryKey: ['accessKeys'],
    queryFn: () => accessKeysApi.list(),
  })
}

export function useCreateAccessKey() {
  return useInvalidatingMutation({
    mutationFn: (data: CreateAccessKeyInput) => accessKeysApi.create(data),
    invalidates: [['accessKeys']],
    awaitRefresh: true,
  })
}

export function useRevokeAccessKey() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => accessKeysApi.revoke(id),
    invalidates: [['accessKeys']],
    awaitRefresh: true,
  })
}
