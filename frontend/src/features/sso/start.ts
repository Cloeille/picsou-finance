export type SsoStartParams = {
  intent: 'login' | 'link' | 'reauth' | 'activate' | 'setup'
  redirect?: string
  rememberMe?: boolean
  token?: string
  ack?: boolean
}

/** Registration id of the OIDC client (backend SsoProperties). */
const SSO_START_ACTION = '/oauth2/sso/authorization/oidc'

export function ssoStartFields(params: SsoStartParams): Array<[string, string]> {
  const fields: Array<[string, string]> = [['intent', params.intent]]
  if (params.redirect) fields.push(['redirect', params.redirect])
  if (params.rememberMe) fields.push(['rememberMe', '1'])
  if (params.token) fields.push(['token', params.token])
  if (params.ack) fields.push(['ack', '1'])
  return fields
}

/** The SSO chain refuses GET, so every start is a top-level form POST (a navigation, not an API call). */
export function submitSsoStart(params: SsoStartParams): void {
  const form = document.createElement('form')
  form.method = 'post'
  form.action = SSO_START_ACTION
  form.hidden = true
  for (const [name, value] of ssoStartFields(params)) {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = name
    input.value = value
    form.appendChild(input)
  }
  document.body.appendChild(form)
  form.requestSubmit()
}
