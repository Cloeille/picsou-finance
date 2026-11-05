import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/features/auth/api'
import { useAuthStore } from '@/stores/auth-store'
import { resetClientState } from '@/lib/reset-client-state'
import { isOAuthAuthorizeRedirect, safeRedirect } from '@/lib/utils'
import { SsoProgressScreen } from './SsoProgressScreen'

/**
 * Where the backend lands a successful SSO sign-in. The SSO chain only sets the session cookies,
 * so the page reads the signed-in user through the session refresh before entering the app.
 */
export function SsoCompletePage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const login = useAuthStore(s => s.login)
  const started = useRef(false)
  const target = safeRedirect(searchParams.get('redirect'))

  useEffect(() => {
    if (started.current) return
    started.current = true
    resetClientState(queryClient)
    authApi.refresh().then(
      (user) => {
        login(user)
        // replace(): Back must not return to this spent progress screen.
        if (isOAuthAuthorizeRedirect(target)) window.location.replace(target)
        else navigate(target, { replace: true })
      },
      () => {
        // A stale "signed in" flag would make /login bounce to "/" and hide the error.
        useAuthStore.getState().logout()
        const keepTarget = target === '/' ? '' : `&redirect=${encodeURIComponent(target)}`
        navigate(`/login?ssoError=SESSION_EXPIRED${keepTarget}`, { replace: true })
      },
    )
  }, [queryClient, login, navigate, target])

  return <SsoProgressScreen message={t('auth.sso.completing')} />
}
