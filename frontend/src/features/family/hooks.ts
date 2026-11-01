import { useMutation, useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { familyApi } from './api'

/**
 * Lists family members. The underlying `/family/members` endpoint is admin-only
 * (`requireAdmin()` → 403 for non-admins), so callers rendered for every user
 * (e.g. the sidebar) MUST pass `enabled: isAdmin` to avoid a spurious 403 that
 * the global interceptor would turn into an `/error/403` redirect.
 */
export function useFamilyMembers({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['family', 'members'],
    queryFn: () => familyApi.listMembers(),
    enabled,
  })
}

export function useFamilyDashboard() {
  return useQuery({
    queryKey: ['family', 'dashboard'],
    queryFn: () => familyApi.getDashboard(),
  })
}

export function useSharingSettings(resourceType: string) {
  return useQuery({
    queryKey: ['family', 'sharing', resourceType],
    queryFn: () => familyApi.getSharingSettings(resourceType),
  })
}

export function useCreateMember() {
  return useInvalidatingMutation({
    mutationFn: (data: { displayName: string; avatarColor?: string }) =>
      familyApi.createMember(data),
    invalidates: [['family', 'members']],
    awaitRefresh: true,
  })
}

/**
 * Creates a managed profile AND provisions its login in one step, returning the
 * activation link to share. Chains the two existing endpoints so the caller gets
 * a single loading/error state.
 */
export function useCreateUserWithLogin() {
  return useInvalidatingMutation({
    mutationFn: async (data: { displayName: string; avatarColor?: string }) => {
      const member = await familyApi.createMember(data)
      const { activationLink } = await familyApi.generateActivationLink(member.id)
      return { member, activationLink }
    },
    invalidates: [['family', 'members']],
    awaitRefresh: true,
  })
}

export function useUpdateMember() {
  return useInvalidatingMutation({
    mutationFn: ({ id, data }: { id: number; data: { displayName: string } }) =>
      familyApi.updateMember(id, data),
    invalidates: [['family', 'members']],
    awaitRefresh: true,
  })
}

export function useDeleteMember() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => familyApi.deleteMember(id),
    invalidates: [['family', 'members']],
    awaitRefresh: true,
  })
}

export function useGenerateActivationLink() {
  return useMutation({
    mutationFn: (id: number) => familyApi.generateActivationLink(id),
  })
}

export function useResetMemberPassword() {
  return useMutation({
    mutationFn: (id: number) => familyApi.resetMemberPassword(id),
  })
}

export function useUpdateSharingSettings() {
  return useInvalidatingMutation({
    mutationFn: (data: { resourceType: string; sharingLevel: string; sharedResourceIds?: number[] }) =>
      familyApi.updateSharingSettings(data),
    invalidates: (_, variables) => [['family', 'sharing', variables.resourceType]],
    awaitRefresh: true,
  })
}

export function useGoalContributions(goalId: number) {
  return useQuery({
    queryKey: ['family', 'goals', goalId, 'contributions'],
    queryFn: () => familyApi.getGoalContributions(goalId),
    enabled: !!goalId,
  })
}
