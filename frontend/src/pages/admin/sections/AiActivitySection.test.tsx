import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AiActivitySection } from './AiActivitySection'
import type { AiCallLogPage } from '@/types/api'

// ── i18n stub ──────────────────────────────────────────────────────────────
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      if (opts) {
        return Object.entries(opts).reduce(
          (acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)),
          key,
        )
      }
      return key
    },
  }),
}))

// ── mock data ──────────────────────────────────────────────────────────────
const MOCK_PAGE_SMALL: AiCallLogPage = {
  items: [
    {
      id: 1,
      createdAt: '2026-06-26T10:00:00Z',
      memberId: 1,
      transactionId: 42,
      merchantLabel: 'LIDL',
      batchId: 'batch-001',
      provider: 'anthropic',
      model: 'claude-haiku',
      prompt: 'Categorize LIDL transaction',
      response: '{"slug":"groceries","confidence":0.97}',
      promptTokens: 38,
      completionTokens: 12,
      totalTokens: 50,
      latencyMs: 320,
      status: 'OK',
      error: null,
      chosenSlug: 'groceries',
      confidence: 0.97,
      applied: true,
    },
    {
      id: 2,
      createdAt: '2026-06-25T08:00:00Z',
      memberId: 1,
      transactionId: 37,
      merchantLabel: 'UNKNOWN',
      batchId: 'batch-001',
      provider: 'anthropic',
      model: 'claude-haiku',
      prompt: 'Categorize UNKNOWN transaction',
      response: null,
      promptTokens: 42,
      completionTokens: null,
      totalTokens: null,
      latencyMs: null,
      status: 'ERROR',
      error: 'Rate limit exceeded',
      chosenSlug: null,
      confidence: null,
      applied: false,
    },
  ],
  total: 2,
  totalTokens: 50,
}

const MOCK_PAGE_PAGINATED_0: AiCallLogPage = {
  items: [
    {
      id: 1,
      createdAt: '2026-06-26T10:00:00Z',
      memberId: 1,
      transactionId: 42,
      merchantLabel: 'LIDL',
      batchId: 'batch-001',
      provider: 'anthropic',
      model: 'claude-haiku',
      prompt: 'Categorize LIDL transaction',
      response: '{"slug":"groceries","confidence":0.97}',
      promptTokens: 38,
      completionTokens: 12,
      totalTokens: 50,
      latencyMs: 320,
      status: 'OK',
      error: null,
      chosenSlug: 'groceries',
      confidence: 0.97,
      applied: true,
    },
  ],
  total: 120,
  totalTokens: 500,
}

const MOCK_PAGE_PAGINATED_50: AiCallLogPage = {
  items: [
    {
      id: 51,
      createdAt: '2026-06-24T10:00:00Z',
      memberId: 1,
      transactionId: 43,
      merchantLabel: 'Carrefour',
      batchId: 'batch-002',
      provider: 'anthropic',
      model: 'claude-haiku',
      prompt: 'Categorize Carrefour transaction',
      response: '{"slug":"groceries","confidence":0.95}',
      promptTokens: 40,
      completionTokens: 10,
      totalTokens: 50,
      latencyMs: 300,
      status: 'OK',
      error: null,
      chosenSlug: 'groceries',
      confidence: 0.95,
      applied: true,
    },
  ],
  total: 120,
  totalTokens: 500,
}

// ── hook capture state ─────────────────────────────────────────────────────
const hookArgs = vi.hoisted(() => ({ limit: 0, offset: 0 }))
const testState = vi.hoisted(() => ({ testPagination: false }))

vi.mock('@/features/admin/hooks', () => ({
  useAiCalls: (limit: number, offset: number) => {
    hookArgs.limit = limit
    hookArgs.offset = offset

    // Return appropriate mock data based on test context and offset
    if (testState.testPagination) {
      if (offset === 0) {
        return {
          data: MOCK_PAGE_PAGINATED_0,
          isLoading: false,
          isError: false,
        }
      } else if (offset === 50) {
        return {
          data: MOCK_PAGE_PAGINATED_50,
          isLoading: false,
          isError: false,
        }
      }
    }

    // Default: small dataset for non-pagination tests
    return {
      data: MOCK_PAGE_SMALL,
      isLoading: false,
      isError: false,
    }
  },
}))

// ── helpers ────────────────────────────────────────────────────────────────
function openDialog() {
  // Click the "View AI tasks" button to open the dialog
  fireEvent.click(screen.getByText('admin.aiActivity.view'))
}

// ── tests ──────────────────────────────────────────────────────────────────
describe('AiActivitySection', () => {
  it('renders merchant and provider from mock data after opening dialog', () => {
    render(<AiActivitySection />)
    openDialog()

    expect(screen.getByText('LIDL')).toBeInTheDocument()
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument()
  })

  it('renders status badges for OK and ERROR rows', () => {
    render(<AiActivitySection />)
    openDialog()

    expect(screen.getByText('OK')).toBeInTheDocument()
    expect(screen.getByText('ERROR')).toBeInTheDocument()
  })

  it('expands a row and shows its prompt text on click', () => {
    render(<AiActivitySection />)
    openDialog()

    // The prompt text should not be visible yet
    expect(screen.queryByText('Categorize LIDL transaction')).not.toBeInTheDocument()

    // Click the first row to expand it
    const rows = screen.getAllByRole('row')
    // rows[0] is thead, rows[1] is first data row
    fireEvent.click(rows[1])

    expect(screen.getByText('Categorize LIDL transaction')).toBeInTheDocument()
  })

  it('shows pagination summary "X–Y / total"', () => {
    render(<AiActivitySection />)
    openDialog()

    // total=2, offset=0, limit=50 → "1–2 / 2"
    expect(screen.getByText('1–2 / 2')).toBeInTheDocument()
  })

  it('Next button is disabled when all results fit in one page', () => {
    render(<AiActivitySection />)
    openDialog()

    const nextBtn = screen.getByText('admin.aiActivity.next')
    expect(nextBtn).toBeDisabled()
  })

  it('Prev button is disabled on first page', () => {
    render(<AiActivitySection />)
    openDialog()

    const prevBtn = screen.getByText('admin.aiActivity.prev')
    expect(prevBtn).toBeDisabled()
  })

  it('Next button advances offset and updates pagination range', async () => {
    testState.testPagination = true
    try {
      render(<AiActivitySection />)
      openDialog()

      // Initial state: offset 0, showing "1–50 of 120"
      // Use a regex matcher to handle the en-dash character
      expect(screen.getByText(/1–50 \/ 120/)).toBeInTheDocument()
      expect(hookArgs.offset).toBe(0)

      // Click Next button
      const nextBtn = screen.getByText('admin.aiActivity.next')
      expect(nextBtn).not.toBeDisabled()
      fireEvent.click(nextBtn)

      // After click: offset should advance to 50, showing "51–100 of 120"
      await waitFor(() => {
        expect(screen.getByText(/51–100 \/ 120/)).toBeInTheDocument()
      })
      expect(hookArgs.offset).toBe(50)
    } finally {
      testState.testPagination = false
    }
  })
})
