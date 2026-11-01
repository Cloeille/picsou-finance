import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, renderHook, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"

const apis = vi.hoisted(() => ({
  amexApi: { getStatus: vi.fn(), initiateAuth: vi.fn(), completeAuth: vi.fn(), sync: vi.fn(), clearSession: vi.fn() },
  caisseEpargneApi: { getStatus: vi.fn(), completeAuth: vi.fn(), sync: vi.fn(), clearSession: vi.fn() },
  corumApi: { getStatus: vi.fn(), authenticate: vi.fn(), sync: vi.fn(), clearSession: vi.fn() },
  cicApi: { getStatus: vi.fn(), reconnect: vi.fn() },
  amundiApi: { getStatus: vi.fn(), clearSession: vi.fn() },
}))

vi.mock("./api", () => ({
  bankSyncApi: {},
  trApi: {},
  cryptoExchangeApi: {},
  cryptoWalletApi: {},
  finaryApi: {},
  boursoApi: {},
  bourseDirectApi: {},
  cmesApi: {},
  fortuneoApi: {},
  sofidyApi: {},
  ...apis,
}))

import { syncKeys } from "./keys"

const { useProviderInitiate, useProviderComplete, useProviderSync, useProviderClear, useProviderReconnect, useRecoverAmexHistory } =
  await import("./hooks")

const status = {
  isActive: true,
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  syncStatus: "QUEUED",
  lastSyncError: null,
}

function createHarness() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper, invalidations: vi.spyOn(client, "invalidateQueries") }
}

const invalidatedKeys = (spy: { mock: { calls: unknown[][] } }) =>
  spy.mock.calls.map(([filters]) => (filters as { queryKey: unknown }).queryKey)

describe("sidecar provider quirks", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("Amex refreshes its status after a failed sync, without writing the answer", async () => {
    apis.amexApi.sync.mockRejectedValue(new Error("boom"))
    const { client, wrapper, invalidations } = createHarness()
    const { result } = renderHook(() => useProviderSync("amex"), { wrapper })

    await act(() => result.current.mutateAsync().catch(() => undefined))

    expect(invalidatedKeys(invalidations)).toEqual([syncKeys.amex()])
    expect(client.getQueryData(syncKeys.amex())).toBeUndefined()
  })

  it("Amex sends the OTP method and an empty code when none is typed", async () => {
    apis.amexApi.initiateAuth.mockResolvedValue({ processId: "p", mfaRequired: true, mfaType: "OTP" })
    apis.amexApi.completeAuth.mockResolvedValue(status)
    const { wrapper } = createHarness()
    const initiate = renderHook(() => useProviderInitiate("amex"), { wrapper })
    const complete = renderHook(() => useProviderComplete("amex"), { wrapper })

    await act(() => initiate.result.current.mutateAsync({ login: "l", password: "p", method: "email" }))
    await act(() => complete.result.current.mutateAsync({ processId: "p" }))

    expect(apis.amexApi.initiateAuth).toHaveBeenCalledWith("l", "p", "email")
    expect(apis.amexApi.completeAuth).toHaveBeenCalledWith("p", "")
  })

  it("Amex history recovery also refreshes accounts, dashboard and history, even on failure", async () => {
    const recover = vi.fn().mockRejectedValue(new Error("boom"))
    ;(apis.amexApi as Record<string, unknown>).recoverHistory = recover
    const { wrapper, invalidations } = createHarness()
    const { result } = renderHook(() => useRecoverAmexHistory(), { wrapper })

    await act(() => result.current.mutateAsync().catch(() => undefined))

    expect(invalidatedKeys(invalidations)).toEqual([syncKeys.amex(), ["accounts"], ["dashboard"], ["history"]])
  })

  it("Amex clear refreshes only its own status", async () => {
    apis.amexApi.clearSession.mockResolvedValue(undefined)
    const { wrapper, invalidations } = createHarness()
    const { result } = renderHook(() => useProviderClear("amex"), { wrapper })

    await act(() => result.current.mutateAsync())

    expect(invalidatedKeys(invalidations)).toEqual([syncKeys.amex()])
  })

  it("Caisse d'Epargne completion never retries and does not write the cache", async () => {
    apis.caisseEpargneApi.completeAuth.mockRejectedValue(new Error("lost"))
    const client = new QueryClient({ defaultOptions: { mutations: { retry: 3 } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useProviderComplete("caisseEpargne"), { wrapper })

    await act(() => result.current.mutateAsync({ processId: "p" }).catch(() => undefined))
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(apis.caisseEpargneApi.completeAuth).toHaveBeenCalledTimes(1)

    apis.caisseEpargneApi.completeAuth.mockResolvedValue({ connected: true })
    await act(() => result.current.mutateAsync({ processId: "p" }))
    expect(client.getQueryData(syncKeys.caisseEpargne())).toBeUndefined()
  })

  it("CORUM's single login step answers as a status plus 'no second factor' and seeds the cache", async () => {
    apis.corumApi.authenticate.mockResolvedValue(status)
    const { client, wrapper, invalidations } = createHarness()
    const { result } = renderHook(() => useProviderInitiate("corum"), { wrapper })

    const answer = await act(() => result.current.mutateAsync({ login: "l", password: "p" }))

    expect(answer).toMatchObject({ ...status, processId: null, mfaRequired: false, mfaType: null })
    expect(client.getQueryData(syncKeys.corum())).toMatchObject({ mfaRequired: false })
    expect(invalidatedKeys(invalidations)).toEqual([syncKeys.corum()])
  })

  it("CORUM's second step is never reachable", async () => {
    const { wrapper } = createHarness()
    const { result } = renderHook(() => useProviderComplete("corum"), { wrapper })

    await expect(act(() => result.current.mutateAsync({ processId: "p" }))).rejects.toThrow(
      "CORUM requires no second factor",
    )
  })

  it("CIC reconnects from stored credentials and refreshes its status", async () => {
    apis.cicApi.reconnect.mockResolvedValue({ processId: "p", mfaRequired: true, mfaType: "APP_PUSH" })
    const { wrapper, invalidations } = createHarness()
    const { result } = renderHook(() => useProviderReconnect("cic"), { wrapper })

    await act(() => result.current.mutateAsync())

    expect(apis.cicApi.reconnect).toHaveBeenCalledOnce()
    expect(invalidatedKeys(invalidations)).toEqual([syncKeys.cic()])
  })

  it("a provider with no stored-credentials login rejects a reconnect", async () => {
    const { wrapper } = createHarness()
    const { result } = renderHook(() => useProviderReconnect("amundi"), { wrapper })

    await expect(act(() => result.current.mutateAsync())).rejects.toThrow(
      "This provider cannot reconnect from stored credentials",
    )
  })

  it("clearing a standard provider refreshes its status, accounts and dashboard", async () => {
    apis.amundiApi.clearSession.mockResolvedValue(undefined)
    const { wrapper, invalidations } = createHarness()
    const { result } = renderHook(() => useProviderClear("amundi"), { wrapper })

    await act(() => result.current.mutateAsync())

    expect(invalidatedKeys(invalidations)).toEqual([syncKeys.amundi(), ["accounts"], ["dashboard"]])
  })
})
