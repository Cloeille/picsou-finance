import { QueryClientProvider } from '@tanstack/react-query'
import { IconContext } from '@phosphor-icons/react'
import { useState, type ReactNode } from 'react'
import { createQueryClient } from '@/lib/query-client'
import { TooltipProvider } from '@/components/ui/tooltip'
import { Toaster } from '@/components/shared/Toaster'
import { ICON_DEFAULTS } from '@/lib/icon-defaults'

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => createQueryClient())

  return (
    <QueryClientProvider client={queryClient}>
      <IconContext.Provider value={ICON_DEFAULTS}>
        <TooltipProvider>
          {children}
          <Toaster />
        </TooltipProvider>
      </IconContext.Provider>
    </QueryClientProvider>
  )
}
