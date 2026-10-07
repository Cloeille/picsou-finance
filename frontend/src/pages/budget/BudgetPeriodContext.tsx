import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

const SESSION_KEY = 'picsou_budget_anchor'

/**
 * Read the persisted anchor from sessionStorage. Wrapped in try/catch so it
 * degrades gracefully in SSR or when storage access is denied (e.g. private
 * browsing with strict settings).
 */
function readStorage(): string | undefined {
  try {
    return typeof window !== 'undefined'
      ? (window.sessionStorage.getItem(SESSION_KEY) ?? undefined)
      : undefined
  } catch {
    return undefined
  }
}

function writeStorage(value: string | undefined): void {
  try {
    if (typeof window === 'undefined') return
    if (value === undefined) {
      window.sessionStorage.removeItem(SESSION_KEY)
    } else {
      window.sessionStorage.setItem(SESSION_KEY, value)
    }
  } catch {
    // Ignore write failures (storage quota, private browsing, SSR).
  }
}

// ─── Context ──────────────────────────────────────────────────────────────────

interface BudgetPeriodContextValue {
  anchor: string | undefined
  setAnchor: (anchor: string | undefined) => void
}

const BudgetPeriodContext = createContext<BudgetPeriodContextValue | null>(null)

// ─── Provider ─────────────────────────────────────────────────────────────────

/**
 * Mount once in BudgetLayout (outside the keyed div that remounts on tab
 * change). The anchor survives sub-route navigation within /budget/*, and is
 * persisted to sessionStorage so it also survives leaving /budget entirely and
 * returning.
 */
export function BudgetPeriodProvider({ children }: { children: ReactNode }) {
  // Lazy initializer: reads sessionStorage once at mount, not on every render.
  const [anchor, setAnchorState] = useState<string | undefined>(() => readStorage())

  const setAnchor = useCallback((value: string | undefined) => {
    writeStorage(value)
    setAnchorState(value)
  }, [])

  const value = useMemo<BudgetPeriodContextValue>(
    () => ({ anchor, setAnchor }),
    [anchor, setAnchor],
  )

  return (
    <BudgetPeriodContext.Provider value={value}>
      {children}
    </BudgetPeriodContext.Provider>
  )
}

// ─── Hook ─────────────────────────────────────────────────────────────────────

/**
 * Access the shared budget period anchor. Must be used inside BudgetPeriodProvider.
 *
 * Co-exporting a hook alongside the provider is the standard React Context pattern.
 * react-refresh flags this as a non-component export; the suppression is intentional
 * and scoped to this one export (not a React Compiler rule — no runtime impact).
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useBudgetPeriod(): BudgetPeriodContextValue {
  const ctx = useContext(BudgetPeriodContext)
  if (!ctx) throw new Error('useBudgetPeriod must be used inside BudgetPeriodProvider')
  return ctx
}
