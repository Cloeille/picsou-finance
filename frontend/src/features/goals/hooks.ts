import { useQuery } from '@tanstack/react-query'
import { useInvalidatingMutation } from '@/hooks/use-invalidating-mutation'
import { goalsApi } from './api'
import type { GoalRequest } from '@/types/api'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { trackFeature } from '@/lib/telemetry'

export function useGoals() {
  return useQuery({
    queryKey: ['goals'],
    queryFn: goalsApi.list,
    staleTime: QUERY_STALE_TIMES.goals,
  })
}

export function useGoal(id: number) {
  return useQuery({
    queryKey: ['goals', id],
    queryFn: () => goalsApi.get(id),
    staleTime: QUERY_STALE_TIMES.goals,
    enabled: !!id,
  })
}

export function useGoalHistory(id: number) {
  return useQuery({
    queryKey: ['goals', id, 'history'],
    queryFn: () => goalsApi.getHistory(id),
    staleTime: QUERY_STALE_TIMES.goals,
    enabled: !!id,
  })
}

export function useGoalMonths(id: number) {
  return useQuery({
    queryKey: ['goals', id, 'months'],
    queryFn: () => goalsApi.getMonths(id),
    enabled: !!id,
  })
}

export function useCreateGoal() {
  return useInvalidatingMutation({
    mutationFn: (data: GoalRequest) => goalsApi.create(data),
    onSuccess: () => {
      trackFeature('goal_created')
    },
    invalidates: [['goals'], ['dashboard']],
  })
}

export function useUpdateGoal() {
  return useInvalidatingMutation({
    mutationFn: ({ id, data }: { id: number; data: GoalRequest }) => goalsApi.update(id, data),
    invalidates: [['goals'], ['dashboard']],
  })
}

export function useDeleteGoal() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => goalsApi.delete(id),
    invalidates: [['goals'], ['dashboard']],
  })
}

export function useExtendGoalHistory() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => goalsApi.extendHistory(id),
    invalidates: (_, id) => [['goals', id, 'months'], ['goals', id]],
  })
}

export function useExtendGoalHistoryByMonth() {
  return useInvalidatingMutation({
    mutationFn: (id: number) => goalsApi.extendHistoryByMonth(id),
    invalidates: (_, id) => [['goals', id, 'months'], ['goals', id]],
  })
}

export function useSetMonthOverride() {
  return useInvalidatingMutation({
    mutationFn: ({ id, ym, amount }: { id: number; ym: string; amount: number }) =>
      goalsApi.setMonthOverride(id, ym, amount),
    invalidates: (_, { id }) => [['goals', id, 'months']],
  })
}

export function useDeleteMonthOverride() {
  return useInvalidatingMutation({
    mutationFn: ({ id, ym }: { id: number; ym: string }) =>
      goalsApi.deleteMonthOverride(id, ym),
    invalidates: (_, { id }) => [['goals', id, 'months']],
  })
}

export function useSetManualContribution() {
  return useInvalidatingMutation({
    mutationFn: ({ id, ym, amount }: { id: number; ym: string; amount: number }) =>
      goalsApi.setManualContribution(id, ym, amount),
    invalidates: (_, { id }) => [['goals', id, 'months']],
  })
}

export function useDeleteManualContribution() {
  return useInvalidatingMutation({
    mutationFn: ({ id, ym }: { id: number; ym: string }) =>
      goalsApi.deleteManualContribution(id, ym),
    invalidates: (_, { id }) => [['goals', id, 'months']],
  })
}
