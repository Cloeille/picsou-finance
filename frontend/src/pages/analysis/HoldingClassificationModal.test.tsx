import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HoldingClassificationModal } from './HoldingClassificationModal'

/**
 * Guards the contract the user sees: saving a classification closes the dialog.
 *
 * <p>The bug it followed was subtler than a missing onClose. useClassifyHolding invalidates the
 * whole ['analysis'] namespace, which includes the per-ticker query this modal reads; the refetch
 * returns the values just saved, the form's remount key changes, React unmounts it, and TanStack
 * Query then skips the callbacks passed to mutate() because the component that called it is gone.
 * The fix was to own the mutation in the component that survives that remount.
 *
 * <p><strong>Honest limit:</strong> this test does not reproduce that race. Checked by reverting
 * the fix — it still passes, because the refetch resolves after the mutation's success dispatch
 * under jsdom, so the form is never unmounted at the moment that matters. It pins the outcome,
 * not the mechanism. The mechanism is held by where useClassifyHolding is called, which is why
 * that placement carries a comment rather than relying on this file to defend it.
 */
const view = {
  ticker: 'AAPL',
  wealthTier: null,
  sectorKey: null,
  countryKey: null,
  inferredSectorKey: 'technology',
  inferredCountryKey: 'US',
  profileLooked: true,
}

// The API layer is mocked, not the hooks: the real hooks are what invalidate ['analysis'] and
// trigger the refetch that remounts the form. Mocking them away would remove the very mechanism
// this test exists to pin.
const holdingClassification = vi.fn()
const classifyHolding = vi.fn()
vi.mock('@/features/analysis/api', () => ({
  analysisApi: {
    holdingClassification: (...args: unknown[]) => holdingClassification(...args),
    classifyHolding: (...args: unknown[]) => classifyHolding(...args),
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

function renderModal(onClose: () => void) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <HoldingClassificationModal
        open
        accountId={3}
        ticker="AAPL"
        name="Apple Inc."
        onClose={onClose}
      />
    </QueryClientProvider>,
  )
}

describe('HoldingClassificationModal', () => {
  beforeEach(() => {
    holdingClassification.mockReset()
    classifyHolding.mockReset()
  })

  it('closes once the classification is saved', async () => {
    // The save changes what the per-ticker query returns, so the refetch and the remount do
    // happen -- just not in the order that used to swallow onClose. See the note above.
    holdingClassification
      .mockResolvedValueOnce(view)
      .mockResolvedValue({ ...view, sectorKey: 'healthcare' })
    classifyHolding.mockResolvedValue({ ...view, sectorKey: 'healthcare' })

    const onClose = vi.fn()
    renderModal(onClose)

    await screen.findByText('common.save')
    fireEvent.change(screen.getByLabelText('analysis.classification.sector'), {
      target: { value: 'healthcare' },
    })
    fireEvent.click(screen.getByText('common.save'))

    await waitFor(() => expect(classifyHolding).toHaveBeenCalled())
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('sends the chosen values and treats an untouched field as no override', async () => {
    holdingClassification.mockResolvedValue(view)
    classifyHolding.mockResolvedValue(view)

    renderModal(vi.fn())

    await screen.findByText('common.save')
    fireEvent.change(screen.getByLabelText('analysis.classification.sector'), {
      target: { value: 'healthcare' },
    })
    fireEvent.click(screen.getByText('common.save'))

    // The inferred sector is shown but never pre-selected, so leaving country alone must send
    // null rather than adopting the provider's guess as a permanent override.
    await waitFor(() =>
      expect(classifyHolding).toHaveBeenCalledWith(3, 'AAPL', {
        sectorKey: 'healthcare',
        countryKey: null,
        wealthTier: null,
      }),
    )
  })
})
