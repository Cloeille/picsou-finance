import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '@/stores/app-store'
import type { MemberProfile } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key}:${Object.values(options).join(',')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

let profile: Partial<MemberProfile> | undefined
vi.mock('@/features/profile/hooks', () => ({
  useMemberProfile: () => ({ data: profile }),
}))

const { useProjectionDateLabel } = await import('./hooks')

/**
 * Unmounts before returning: the hook subscribes to the app store, and leaving an instance
 * mounted while a later case calls setState re-renders it outside act() and fills the run with
 * warnings the next reader has to triage.
 */
function label(date: string) {
  const { result, unmount } = renderHook(() => useProjectionDateLabel())
  const rendered = result.current(date)
  unmount()
  return rendered
}

beforeEach(() => {
  profile = undefined
  useAppStore.setState({ dateFormat: 'locale' })
})

describe('useProjectionDateLabel', () => {
  // The bug: the tooltip printed the payload's raw yyyy-MM-dd, so someone who had chosen a
  // format in Settings saw one shape here and another everywhere else in the app.
  it('writes the date in the format chosen in settings', () => {
    expect(label('2042-08-31')).toBe('31/08/2042')

    useAppStore.setState({ dateFormat: 'iso' })
    expect(label('2042-08-31')).toBe('31-08-2042')
  })

  it('adds the age the member will be on that date', () => {
    profile = { birthDate: '1998-09-20' }

    // 31 August is before the September birthday, so 43 rather than 44 — which is the whole
    // reason this is computed per point instead of reusing the profile's current age.
    expect(label('2042-08-31')).toBe('31/08/2042 · analysis.projection.atAge:43')
    expect(label('2042-09-20')).toBe('20/09/2042 · analysis.projection.atAge:44')
  })

  it('shows the date alone when no birth date is stated', () => {
    profile = { birthDate: null }
    expect(label('2042-08-31')).toBe('31/08/2042')
  })

  it('shows the date alone before the profile has loaded', () => {
    profile = undefined
    expect(label('2042-08-31')).toBe('31/08/2042')
  })
})
