import { useAuthStore } from '@/stores/auth-store'

const REVALIDATE_KEY = 'picsou_sso_revalidate'
const REMEMBER_KEY = 'picsou_sso_remember'

export function markSsoRevalidate(): void {
  try {
    sessionStorage.setItem(REVALIDATE_KEY, '1')
  } catch {
    // Without the flag /login shows its form, and its SSO button still signs in.
  }
}

/** True at most once per mark: the flag is cleared on read. */
export function consumeSsoRevalidate(): boolean {
  const marked = sessionStorage.getItem(REVALIDATE_KEY) === '1'
  sessionStorage.removeItem(REVALIDATE_KEY)
  return marked
}

export function rememberSsoPreference(rememberMe: boolean): void {
  try {
    // Removing needs no room, so an unchecked choice always replaces an older "remember me".
    if (rememberMe) localStorage.setItem(REMEMBER_KEY, '1')
    else localStorage.removeItem(REMEMBER_KEY)
  } catch {
    // Storage full or blocked: the preference is optional and the sign-in must still start.
  }
}

export function ssoRememberPreference(): boolean {
  return localStorage.getItem(REMEMBER_KEY) === '1'
}

/** Sends the session back through the IdP: /login restarts the round trip, then returns to `redirect`. */
export function restartSsoRevalidation(redirect: string): void {
  markSsoRevalidate()
  // A stale "signed in" flag would make /login bounce to "/" instead of restarting SSO.
  useAuthStore.getState().logout()
  window.location.assign('/login?redirect=' + encodeURIComponent(redirect))
}
