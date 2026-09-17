import { useMutation, useQuery } from '@tanstack/react-query'
import { accountDeletionApi } from './api'

const DELETION_KEYS = {
  impact: ['account-deletion', 'impact'] as const,
}

/**
 * Fetches the deletion impact for the current user.
 *
 * Only fires when {@link enabled} is true (= dialog is open) so we don't
 * hit the endpoint on every settings render.
 */
export function useAccountDeletionImpact(enabled: boolean) {
  return useQuery({
    queryKey: DELETION_KEYS.impact,
    queryFn: accountDeletionApi.getDeletionImpact,
    enabled,
    staleTime: 0,
  })
}

/**
 * Mutation to delete (or reset) the current user's account.
 */
export function useDeleteMyAccount() {
  return useMutation({
    mutationFn: accountDeletionApi.deleteMyAccount,
  })
}
