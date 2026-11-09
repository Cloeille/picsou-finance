import { afterEach, describe, expect, it } from 'vitest'
import { ssoStartFields, submitSsoStart } from './start'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ssoStartFields', () => {
  it('orders intent, redirect, rememberMe, token, ack and keeps the redirect verbatim', () => {
    expect(
      ssoStartFields({
        intent: 'activate',
        redirect: '/settings?a=1&b=2',
        rememberMe: true,
        token: 'tok',
        ack: true,
      }),
    ).toEqual([
      ['intent', 'activate'],
      ['redirect', '/settings?a=1&b=2'],
      ['rememberMe', '1'],
      ['token', 'tok'],
      ['ack', '1'],
    ])
  })

  it('omits every optional field that is absent or false', () => {
    expect(ssoStartFields({ intent: 'login', rememberMe: false, ack: false })).toEqual([['intent', 'login']])
  })
})

describe('submitSsoStart', () => {
  it('submits a hidden POST form to the SSO authorization endpoint with the fields', () => {
    let action: string | null = null
    let method: string | null = null
    let captured: FormData | null = null
    document.addEventListener('submit', (e) => {
      e.preventDefault()
      const form = e.target as HTMLFormElement
      action = form.getAttribute('action')
      method = form.getAttribute('method')
      captured = new FormData(form)
    }, { once: true })

    submitSsoStart({ intent: 'link', redirect: '/settings?a=1&b=2', rememberMe: true })

    expect(action).toBe('/oauth2/sso/authorization/oidc')
    expect(method).toBe('post')
    expect(captured).not.toBeNull()
    expect(captured!.get('intent')).toBe('link')
    expect(captured!.get('redirect')).toBe('/settings?a=1&b=2')
    expect(captured!.get('rememberMe')).toBe('1')
    expect(captured!.has('token')).toBe(false)
  })
})
