/**
 * In-memory-only holder for the freshly-created admin credentials so the
 * Done screen can auto-login without a round-trip to /login.
 *
 * Deliberately NOT using Zustand persist — the password must never hit
 * sessionStorage or localStorage. If the user refreshes mid-wizard, we
 * simply lose the auto-login capability and the Done screen falls back
 * to the "Go to login" CTA. Worse UX, but strictly safer than any form
 * of persisted password.
 */
let cachedCredentials: { username: string; password: string } | null = null

export function stashSetupCredentials(username: string, password: string): void {
  cachedCredentials = { username, password }
}

export function consumeSetupCredentials(): { username: string; password: string } | null {
  const out = cachedCredentials
  cachedCredentials = null
  return out
}
