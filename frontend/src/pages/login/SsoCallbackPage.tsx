import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { SsoProgressScreen } from './SsoProgressScreen'

const SSO_CODE_ACTION = '/oauth2/sso/code/oidc'
// The authorization response (RFC 6749 §4.1.2 and §4.1.2.1, RFC 9207's iss). Any other query
// parameter stays out of the form, so a crafted link cannot add fields to the POST.
const RESPONSE_PARAMS = new Set(['code', 'state', 'iss', 'error', 'error_description', 'error_uri'])

/**
 * The IdP's redirect target. It changes nothing itself: it re-posts the IdP's authorization
 * response to the backend SSO chain, which refuses GET, after taking the authorization code out of
 * the address bar and the history.
 */
export function SsoCallbackPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const formRef = useRef<HTMLFormElement>(null)
  // StrictMode runs the effect twice; a second POST would replay a single-use code.
  const handled = useRef(false)
  const params = [...searchParams.entries()].filter(([name]) => RESPONSE_PARAMS.has(name))
  const hasResult = searchParams.has('code') || searchParams.has('error')

  useEffect(() => {
    if (handled.current) return
    handled.current = true
    if (!hasResult) {
      navigate('/login', { replace: true })
      return
    }
    window.history.replaceState(null, '', '/login/sso/callback')
    // Called through the prototype, so no input name can ever shadow the method.
    if (formRef.current) HTMLFormElement.prototype.requestSubmit.call(formRef.current)
  }, [hasResult, navigate])

  // Back from the IdP's answer restores this page from the back/forward cache, spinner included,
  // and `handled` blocks a second POST of the single-use code: start over from the login page.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) navigate('/login', { replace: true })
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [navigate])

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
