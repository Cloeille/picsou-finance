import type { IntegrationKey } from '@/stores/setup-flow-store'

/**
 * Canonical sub-step order. Kept in sync with `CATALOG` in
 * `SetupStepIntegrations.tsx` — both lists must match or users could
 * land on a substep they didn't pick, or skip one they did.
 */
export const INTEGRATION_SUBSTEP_ORDER: Array<{
  key: IntegrationKey
  route: string
}> = [
  { key: 'enablebanking', route: '/setup/integrations/enablebanking' },
  { key: 'boursobank', route: '/setup/integrations/boursobank' },
  { key: 'boursedirect', route: '/setup/integrations/boursedirect' },
  { key: 'fortuneo', route: '/setup/integrations/fortuneo' },
  { key: 'traderepublic', route: '/setup/integrations/traderepublic' },
  { key: 'revolut', route: '/setup/integrations/revolut' },
  { key: 'finary', route: '/setup/integrations/finary' },
  { key: 'crypto', route: '/setup/integrations/crypto' },
]

/**
 * Resolve the next sub-step the user should see after finishing
 * (or skipping) the current integration. Walks the catalog from the
 * position AFTER the current one and returns the first route whose
 * key is still in `selected`. If none remain, returns the Done screen.
 */
export function nextIntegrationRoute(
  current: IntegrationKey,
  selected: IntegrationKey[]
): string {
  const currentIdx = INTEGRATION_SUBSTEP_ORDER.findIndex((s) => s.key === current)
  for (let i = currentIdx + 1; i < INTEGRATION_SUBSTEP_ORDER.length; i++) {
    const candidate = INTEGRATION_SUBSTEP_ORDER[i]
    if (selected.includes(candidate.key)) return candidate.route
  }
  return '/setup/done'
}
