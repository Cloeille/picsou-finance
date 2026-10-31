import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton'

// Most chunks and session checks settle well under this, so fast loads paint nothing at all
// instead of flashing a placeholder that the real page immediately replaces.
const REVEAL_DELAY_MS = 200

function useRevealAfterDelay() {
  const [revealed, setRevealed] = useState(false)
  useEffect(() => {
    const id = setTimeout(() => setRevealed(true), REVEAL_DELAY_MS)
    return () => clearTimeout(id)
  }, [])
  return revealed
}

/** Suspense fallback for a page inside the app shell: a page-header bar and one content block. */
export function RouteFallback() {
  const { t } = useTranslation()
  if (!useRevealAfterDelay()) return null
  return (
    <div role="status" aria-label={t('common.loading')}>
      <div className="mb-6">
        <Skeleton className="mb-1 h-5 w-32" />
        <Skeleton className="h-[34px] w-56" />
      </div>
      <Skeleton className="h-64 w-full rounded-4xl" />
    </div>
  )
}

/** Full-screen fallback for routes rendered outside the app shell (guards, setup wizard). */
export function RouteSpinner() {
  const { t } = useTranslation()
  if (!useRevealAfterDelay()) return null
  return (
    <div role="status" aria-label={t('common.loading')} className="flex min-h-dvh items-center justify-center bg-background">
      <Loader2 className="size-6 animate-spin text-muted-foreground" />
    </div>
  )
}
