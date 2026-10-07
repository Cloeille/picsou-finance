import { Component, type ErrorInfo, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { captureException } from '@/lib/telemetry'
import picsouLogo from '@/assets/horizontal-white-picsou.svg'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
}

function CrashFallback() {
  const { t } = useTranslation()
  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm mx-4">
        <img src={picsouLogo} alt="Picsou" className="h-8 w-auto opacity-90 brightness-0 dark:invert" />
        <AlertTriangle className="size-10 text-destructive" />
        <h1 className="text-lg font-semibold">{t('error.crashTitle')}</h1>
        <p className="text-sm text-muted-foreground">{t('error.crashDesc')}</p>
        <Button onClick={() => window.location.reload()}>
          <RefreshCw className="size-4 mr-2" />
          {t('error.reload')}
        </Button>
      </div>
    </div>
  )
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false }
  }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo)
    // No-op unless the instance opted in; the scrubber strips the message before it leaves.
    captureException(error)
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback || <CrashFallback />
    }
    return this.props.children
  }
}
