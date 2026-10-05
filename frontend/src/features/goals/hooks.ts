import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
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
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: GoalRequest) => goalsApi.create(data),
    onSuccess: () => {
      trackFeature('goal_created')
      queryClient.invalidateQueries({ queryKey: ['goals'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useUpdateGoal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: GoalRequest }) => goalsApi.update(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['goals'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDeleteGoal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => goalsApi.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['goals'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useExtendGoalHistory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => goalsApi.extendHistory(id),
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: ['goals', id, 'months'] })
      queryClient.invalidateQueries({ queryKey: ['goals', id] })
    },
  })
}

export function useExtendGoalHistoryByMonth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => goalsApi.extendHistoryByMonth(id),
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: ['goals', id, 'months'] })
      queryClient.invalidateQueries({ queryKey: ['goals', id] })
    },
  })
}

export function useSetMonthOverride() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ym, amount }: { id: number; ym: string; amount: number }) =>
      goalsApi.setMonthOverride(id, ym, amount),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['goals', id, 'months'] })
    },
  })
}

export function useDeleteMonthOverride() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ym }: { id: number; ym: string }) =>
      goalsApi.deleteMonthOverride(id, ym),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['goals', id, 'months'] })
    },
  })
}

export function useSetManualContribution() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ym, amount }: { id: number; ym: string; amount: number }) =>
      goalsApi.setManualContribution(id, ym, amount),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['goals', id, 'months'] })
    },
  })
}

export function useDeleteManualContribution() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ym }: { id: number; ym: string }) =>
      goalsApi.deleteManualContribution(id, ym),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['goals', id, 'months'] })
    },
  })
}
