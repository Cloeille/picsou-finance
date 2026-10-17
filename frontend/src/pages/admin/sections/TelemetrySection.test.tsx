import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TelemetrySettings } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

const api = vi.hoisted(() => ({ updateTelemetry: vi.fn() }))
vi.mock('@/features/admin/api', () => ({ adminApi: api }))
const telemetry = vi.hoisted(() => ({ shutdownTelemetry: vi.fn() }))
vi.mock('@/lib/telemetry', () => telemetry)

const { TelemetrySection } = await import('./TelemetrySection')

function renderSection(settings: TelemetrySettings) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <TelemetrySection settings={settings} />
    </QueryClientProvider>,
  )
}

describe('TelemetrySection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.updateTelemetry.mockResolvedValue(undefined)
  })

  it('is disabled with an "unavailable" notice when no DSN is configured', () => {
    renderSection({ available: false, consent: 'UNSET' })
    expect(screen.getByRole('switch')).toBeDisabled()
    expect(screen.getByText('telemetry.section.unavailable')).toBeInTheDocument()
    expect(screen.getByText('telemetry.section.privacy')).toBeInTheDocument()
  })

  it('reflects the consent state', () => {
    renderSection({ available: true, consent: 'ENABLED' })
    expect(screen.getByRole('switch')).toBeChecked()
    expect(screen.queryByText('telemetry.section.unavailable')).not.toBeInTheDocument()
  })

  it('turning on PUTs enabled=true and does not shut the SDK down', async () => {
    renderSection({ available: true, consent: 'DISABLED' })
    fireEvent.click(screen.getByRole('switch'))
    await waitFor(() => expect(api.updateTelemetry).toHaveBeenCalledWith(true))
    expect(telemetry.shutdownTelemetry).not.toHaveBeenCalled()
  })

  it('turning off PUTs enabled=false then shuts the SDK down immediately', async () => {
    renderSection({ available: true, consent: 'ENABLED' })
    fireEvent.click(screen.getByRole('switch'))
    await waitFor(() => expect(api.updateTelemetry).toHaveBeenCalledWith(false))
    await waitFor(() => expect(telemetry.shutdownTelemetry).toHaveBeenCalledTimes(1))
  })
})
