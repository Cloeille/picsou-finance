import './i18n'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import { Providers } from './app/providers'
import { router } from './app/routes'
import { initSystemThemeListener, applyTheme, getStoredTheme } from './lib/theme'
import { ErrorBoundary } from './components/shared/ErrorBoundary'
import { ConnectionGuard } from './components/shared/ConnectionGuard'
import './index.css'

initSystemThemeListener()
applyTheme(getStoredTheme())

const root = document.getElementById('root')
if (!root) throw new Error('Root element not found')

createRoot(root).render(
  <StrictMode>
    <Providers>
      <ErrorBoundary>
        <ConnectionGuard>
          <RouterProvider router={router} />
        </ConnectionGuard>
      </ErrorBoundary>
    </Providers>
  </StrictMode>
)
