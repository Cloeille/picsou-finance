import "@testing-library/jest-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { ReactNode } from "react"
import type { BourseDirectSessionStatus } from "@/types/api"

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock("@/lib/api-client", () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))
vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { BourseDirectTab } = await import("./BourseDirectTab")

const inactiveStatus: BourseDirectSessionStatus = {
  isActive: false,
  expiresAt: null,
  syncStatus: "IDLE",
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

function renderTab(onConnected?: () => void) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<BourseDirectTab onConnected={onConnected} />, { wrapper: Wrapper })
}

describe("BourseDirectTab authentication flow", () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: inactiveStatus })
  })

  it("submits credentials then the six-digit OTP with the process id", async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === "/bourse-direct/auth/initiate") {
        return Promise.resolve({
          data: { processId: "process-123", mfaRequired: true, mfaType: "OTP" },
        })
      }
      if (url === "/bourse-direct/auth/complete") {
        return Promise.resolve({
          data: {
            ...inactiveStatus,
            isActive: true,
            syncStatus: "QUEUED",
          },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    fireEvent.change(await screen.findByLabelText("sync.bourseDirect.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.bourseDirect.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.connect" })
    )

    fireEvent.change(
      await screen.findByLabelText("sync.bourseDirect.otpCode"),
      { target: { value: "12a3456" } }
    )
    expect(screen.getByLabelText("sync.bourseDirect.otpCode")).toHaveValue(
      "123456"
    )
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.validate" })
    )

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith("/bourse-direct/auth/complete", {
        processId: "process-123",
        code: "123456",
      })
    )
  })

  it("closes the account wizard only after the background import succeeds", async () => {
    const onConnected = vi.fn()
    let serverStatus = inactiveStatus
    apiGet.mockImplementation(() => Promise.resolve({ data: serverStatus }))
    apiPost.mockImplementation((url: string) => {
      if (url === "/bourse-direct/auth/initiate") {
        return Promise.resolve({
          data: { processId: "process-123", mfaRequired: true, mfaType: "OTP" },
        })
      }
      if (url === "/bourse-direct/auth/complete") {
        serverStatus = {
          ...inactiveStatus,
          isActive: true,
          syncStatus: "SUCCESS",
          lastSyncCompletedAt: "2026-07-20T10:00:00Z",
        }
        return Promise.resolve({
          data: {
            ...inactiveStatus,
            isActive: true,
            syncStatus: "QUEUED",
          },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab(onConnected)
    fireEvent.change(await screen.findByLabelText("sync.bourseDirect.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.bourseDirect.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.connect" })
    )
    fireEvent.change(
      await screen.findByLabelText("sync.bourseDirect.otpCode"),
      { target: { value: "123456" } }
    )
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.validate" })
    )

    expect(onConnected).not.toHaveBeenCalled()
    await waitFor(() => expect(onConnected).toHaveBeenCalledOnce())
  })

  it("maps a coded 401 completion failure to the OTP error", async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === "/bourse-direct/auth/initiate") {
        return Promise.resolve({
          data: { processId: "process-123", mfaRequired: true, mfaType: "OTP" },
        })
      }
      if (url === "/bourse-direct/auth/complete") {
        return Promise.reject({
          response: {
            status: 401,
            data: { code: "INVALID_OTP", detail: "Authentication failed" },
          },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    fireEvent.change(await screen.findByLabelText("sync.bourseDirect.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.bourseDirect.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.connect" })
    )
    fireEvent.change(
      await screen.findByLabelText("sync.bourseDirect.otpCode"),
      { target: { value: "123456" } }
    )
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.validate" })
    )

    expect(
      await screen.findByText("sync.bourseDirect.errors.invalidCode")
    ).toBeInTheDocument()
  })

  it("shows a rate-limit error without retaining the password", async () => {
    apiPost.mockRejectedValue({
      response: { status: 429, data: { detail: "Too many attempts" } },
    })
    renderTab()
    fireEvent.change(await screen.findByLabelText("sync.bourseDirect.login"), {
      target: { value: "client-42" },
    })
    fireEvent.change(screen.getByLabelText("sync.bourseDirect.password"), {
      target: { value: "secret" },
    })
    fireEvent.click(
      screen.getByRole("button", { name: "sync.bourseDirect.connect" })
    )

    expect(
      await screen.findByText("sync.bourseDirect.errors.tooManyAttempts")
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: "common.retry" }))
    expect(screen.getByLabelText("sync.bourseDirect.password")).toHaveValue("")
  })

  it("shows progress and reports a manual sync failure", async () => {
    apiGet.mockResolvedValue({
      data: { ...inactiveStatus, isActive: true },
    })
    let rejectSync!: (reason?: unknown) => void
    apiPost.mockImplementation((url: string) => {
      if (url === "/bourse-direct/sync") {
        return new Promise((_resolve, reject) => {
          rejectSync = reject
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    fireEvent.click(
      await screen.findByRole("button", { name: "sync.bourseDirect.sync" })
    )

    expect(
      await screen.findByRole("button", { name: "sync.bourseDirect.syncing" })
    ).toBeDisabled()
    rejectSync({})
    expect(
      await screen.findByText("sync.bourseDirect.errors.serverError")
    ).toBeInTheDocument()
  })
})
