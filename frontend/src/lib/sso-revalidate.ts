const REVALIDATE_KEY = 'picsou_sso_revalidate'
const REMEMBER_KEY = 'picsou_sso_remember'

export function markSsoRevalidate(): void {
  sessionStorage.setItem(REVALIDATE_KEY, '1')
}

/** True at most once per mark: the flag is cleared on read. */
export function consumeSsoRevalidate(): boolean {
  const marked = sessionStorage.getItem(REVALIDATE_KEY) === '1'
  sessionStorage.removeItem(REVALIDATE_KEY)
  return marked
}

export function rememberSsoPreference(rememberMe: boolean): void {
  localStorage.setItem(REMEMBER_KEY, rememberMe ? '1' : '0')
}

export function ssoRememberPreference(): boolean {
  return localStorage.getItem(REMEMBER_KEY) === '1'
}
