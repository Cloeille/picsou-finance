import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { SsoProgressScreen } from './SsoProgressScreen'

const SSO_CODE_ACTION = '/oauth2/sso/code/oidc'

/**
 * The IdP's redirect target. It changes nothing itself: it re-posts what the IdP sent to the
 * backend SSO chain, which refuses GET, after taking the authorization code out of the address
 * bar and the history.
 */
export function SsoCallbackPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const formRef = useRef<HTMLFormElement>(null)
  // StrictMode runs the effect twice; a second POST would replay a single-use code.
  const handled = useRef(false)
  const params = [...searchParams.entries()]
  const hasResult = searchParams.has('code') || searchParams.has('error')

  useEffect(() => {
    if (handled.current) return
    handled.current = true
    if (!hasResult) {
      navigate('/login', { replace: true })
      return
    }
    window.history.replaceState(null, '', '/login/sso/callback')
    formRef.current?.requestSubmit()
  }, [hasResult, navigate])

  return (
    <>
      <SsoProgressScreen message={t('auth.sso.completing')} />
      {hasResult && (
        <form ref={formRef} method="post" action={SSO_CODE_ACTION} hidden>
          {params.map(([name, value], index) => (
            <input key={index} type="hidden" name={name} value={value} />
          ))}
        </form>
      )}
    </>
  )
}
