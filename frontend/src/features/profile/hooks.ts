import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { profileApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type { MemberProfileRequest } from '@/types/api'

export function useMemberProfile() {
  return useQuery({
    queryKey: ['me', 'profile'],
    queryFn: profileApi.get,
    staleTime: QUERY_STALE_TIMES.analysis,
  })
}

export function useSaveMemberProfile() {
  return useInvalidatingMutation({
    mutationFn: (body: MemberProfileRequest) => profileApi.save(body),
    // The goals page reads the income from here for its savings rate, so it has to re-render
    // when the figure changes -- setting a salary and seeing the rate stay blank would read as
    // the feature being broken.
    invalidates: [['me', 'profile']],
    awaitRefresh: true,
  })
}
