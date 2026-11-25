import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createElement, type ReactNode } from 'react'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const { updateAi, testAi, listAiModels } = vi.hoisted(() => ({
  updateAi: vi.fn(),
  testAi: vi.fn(),
  listAiModels: vi.fn(),
}))

vi.mock('./api', () => ({
  adminApi: { updateAi, testAi, listAiModels },
}))

import { useUpdateAi, useTestAi, useListAiModels } from './hooks'

function makeWrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

beforeEach(() => {
  updateAi.mockReset()
  testAi.mockReset()
  listAiModels.mockReset()
})

describe('AI mutation hooks scrub apiKey from cached variables', () => {
  it('scrubs apiKey in useUpdateAi, useTestAi and useListAiModels on success and failure', async () => {
    const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const wrapper = makeWrapper(queryClient)

    updateAi.mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('fail'))
    testAi.mockResolvedValueOnce({ ok: true, message: 'ok' }).mockRejectedValueOnce(new Error('fail'))
    listAiModels.mockResolvedValueOnce({ ok: true, models: [] }).mockRejectedValueOnce(new Error('fail'))

    const { result: updateHook } = renderHook(() => useUpdateAi(), { wrapper })
    const { result: testHook } = renderHook(() => useTestAi(), { wrapper })
    const { result: listHook } = renderHook(() => useListAiModels(), { wrapper })

    const aiPayload = () => ({
      provider: 'anthropic',
      model: 'claude-haiku-4-5',
      baseUrl: 'https://api.anthropic.com',
      apiKey: 'sk-test-key',
      maxConcurrency: 8,
    })
    const listPayload = () => ({
      provider: 'anthropic',
      baseUrl: 'https://api.anthropic.com',
      apiKey: 'sk-test-key',
    })

    await act(async () => {
      await updateHook.current.mutateAsync(aiPayload())
      await testHook.current.mutateAsync(aiPayload())
      await listHook.current.mutateAsync(listPayload())
    })

    expect(updateAi).toHaveBeenCalledWith(aiPayload())
    expect(testAi).toHaveBeenCalledWith(aiPayload())
    expect(listAiModels).toHaveBeenCalledWith(listPayload())

    await act(async () => {
      await expect(updateHook.current.mutateAsync(aiPayload())).rejects.toThrow('fail')
      await expect(testHook.current.mutateAsync(aiPayload())).rejects.toThrow('fail')
      await expect(listHook.current.mutateAsync(listPayload())).rejects.toThrow('fail')
    })

    const cached = queryClient.getMutationCache().getAll()
    expect(cached).toHaveLength(6)
    for (const mutation of cached) {
      expect((mutation.state.variables as { apiKey?: string }).apiKey).toBe('')
    }
  })
})
