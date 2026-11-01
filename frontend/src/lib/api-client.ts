import axios from 'axios'
import { createDemoAdapter } from '@/demo'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { useConnectivityStore } from '@/stores/connectivity-store'
import { useProfileStore } from '@/stores/profile-store'

declare module 'axios' {
  interface AxiosRequestConfig {
    skipGlobalErrorRedirect?: boolean
  }
}

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})

if (import.meta.env.VITE_DEMO_MODE === 'true') {
  api.defaults.adapter = createDemoAdapter()
}

// Add memberId to requests when an admin is viewing a managed profile.
// Only admins may impersonate (the backend ignores the override for non-admins and
// rejects it for activated members), so gating on role here keeps a stale persisted
// activeMemberId from ever affecting a regular member's requests.
api.interceptors.request.use((config) => {
  const { activeMemberId } = useProfileStore.getState()
  const isAdmin = useAuthStore.getState().user?.role === 'ADMIN'
  if (isAdmin && activeMemberId) {
    config.params = { ...config.params, memberId: activeMemberId }
  }
  return config
})

let isRefreshing = false
let refreshSubscribers: Array<() => void> = []

function subscribeToRefresh(cb: () => void) {
  refreshSubscribers.push(cb)
}

function notifyRefreshSubscribers() {
  refreshSubscribers.forEach(cb => cb())
  refreshSubscribers = []
}

export function isSetupRequiredResponse(status: number | undefined, data: unknown): boolean {
  if (status !== 503) return false
  if (typeof data === 'string') return data.includes('setup_required')
  if (data && typeof data === 'object') {
    const body = data as { code?: unknown; detail?: unknown }
    return body.code === 'setup_required' || body.detail === 'setup_required'
  }
  return false
}

api.interceptors.response.use(
  res => {
    // Mark as connected on any successful response (skip demo mode)
    if (!useAppStore.getState().demoMode) {
      useConnectivityStore.getState().setConnected(true)
    }
    return res
  },
  async error => {
    const originalRequest = error.config as typeof error.config & { _retry?: boolean }

    // Network error detection (no response at all, or CORS-blocked)
    if (!error.response && !error.config?.url?.includes('/auth/')) {
      if (!useAppStore.getState().demoMode) {
        useConnectivityStore.getState().setConnected(false)
      }
    }

    // Error pages render the app shell, whose own queries (members, telemetry,
    // instance status) can fail too. Redirecting from there would reload the
    // error page and refire those queries forever.
    const onErrorPage = window.location.pathname.startsWith('/error/')

    // 403: Forbidden
    if (error.response?.status === 403 && error.config?.method === 'get' && !onErrorPage) {
      window.location.href = '/error/403'
      return Promise.reject(error)
    }

    // 503 setup-required: the backend's SetupFilter signals that the
    // wizard hasn't been completed yet. Bounce to /setup instead of the
    // generic 5xx error page.
    //
    // The backend sets code: "setup_required"; the detail fallback keeps
    // compatibility with older or stringified responses.
    const setupRequiredBody = isSetupRequiredResponse(
      error.response?.status,
      error.response?.data,
    )
    if (setupRequiredBody && window.location.pathname !== '/setup') {
      window.location.href = '/setup'
      return Promise.reject(error)
    }

    // 5xx: Server errors (GET only to avoid disrupting mutations)
    if (
      error.response?.status >= 500 &&
      error.response?.status < 600 &&
      error.config?.method === 'get' &&
      !error.config?.skipGlobalErrorRedirect &&
      !onErrorPage
    ) {
      window.location.href = '/error/500?code=' + error.response.status
      return Promise.reject(error)
    }

    // 401: Unauthorized - token refresh
    if (
      error.response?.status === 401 &&
      !originalRequest._retry &&
      !originalRequest.url?.includes('/auth/')
    ) {
      if (isRefreshing) {
        return new Promise(resolve => {
          subscribeToRefresh(() => resolve(api(originalRequest!)))
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        await api.post('/auth/refresh')
        notifyRefreshSubscribers()
        return api(originalRequest!)
      } catch {
        // Refresh failed: the session is dead. Clear the JS-side auth flag
        // (sessionStorage) before redirecting, otherwise PublicOnly on /login
        // sees `isAuthenticated=true` and bounces back to "/", which fires
        // /family/members → 401 → refresh → 401 → redirect → … infinite loop.
        useAuthStore.getState().logout()
        if (window.location.pathname !== '/login') {
          window.location.href =
            '/login?redirect=' +
            encodeURIComponent(window.location.pathname + window.location.search)
        }
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)
