import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RuleWordPicker } from './RuleWordPicker'

// Mock the hook
vi.mock('@/features/budget/hooks', () => ({
  usePreviewRule: () => ({
    mutate: vi.fn(),
    isPending: false,
    data: null,
  }),
}))

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      if (opts?.count !== undefined) return `${opts.count}`
      return key
    },
  }),
}))

// Mock CurrencyDisplay
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

// Mock utils
vi.mock('@/lib/utils', () => ({
  formatDate: (d: string) => d,
  getLocale: () => 'fr-FR',
  cn: (...args: unknown[]) => args.filter(Boolean).join(' '),
}))

const noop = () => {}

describe('RuleWordPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('tokenizes a simple label and shows tokens', () => {
    render(<RuleWordPicker label="Netflix.com France" onConfirm={noop} onClose={noop} />)
    expect(screen.getByText('Netflix')).toBeTruthy()
    expect(screen.getByText('com')).toBeTruthy()
    expect(screen.getByText('France')).toBeTruthy()
  })

  it('all non-UUID tokens selected by default', () => {
    const label = 'Netflix.com'
    render(<RuleWordPicker label={label} onConfirm={noop} onClose={noop} />)
    // Both tokens should appear highlighted (bg-primary class)
    const netflixBtn = screen.getByRole('button', { name: 'Netflix' })
    expect(netflixBtn.className).toContain('bg-primary')
  })

  it('skips UUID tokens (renders as muted, non-clickable)', () => {
    const uuid = '550e8400-e29b-41d4-a716-446655440000'
    render(<RuleWordPicker label={`To EUR MB:${uuid}`} onConfirm={noop} onClose={noop} />)
    // UUID should not be a button
    expect(screen.queryByRole('button', { name: new RegExp(uuid.slice(0, 8)) })).toBeNull()
  })

  it('toggles a token off by clicking it', () => {
    render(<RuleWordPicker label="Netflix France" onConfirm={noop} onClose={noop} />)
    const netflixBtn = screen.getByRole('button', { name: 'Netflix' })
    // Initially selected
    expect(netflixBtn.className).toContain('bg-primary')
    // Click to deselect
    fireEvent.click(netflixBtn)
    expect(netflixBtn.className).not.toContain('bg-primary')
    // Click to re-select
    fireEvent.click(netflixBtn)
    expect(netflixBtn.className).toContain('bg-primary')
  })

  it('calls onConfirm with lowercase pattern and KEYWORDS_ALL by default', () => {
    const onConfirm = vi.fn()
    render(<RuleWordPicker label="Netflix France" onConfirm={onConfirm} onClose={noop} />)
    // Click confirm
    const confirmBtn = screen.getByRole('button', { name: /budget.rule.createRule/ })
    fireEvent.click(confirmBtn)
    expect(onConfirm).toHaveBeenCalledWith(
      expect.objectContaining({
        pattern: 'netflix france',
        matchType: 'KEYWORDS_ALL',
      })
    )
  })

  it('switches to KEYWORDS_ANY when OR button clicked', () => {
    const onConfirm = vi.fn()
    render(<RuleWordPicker label="Netflix" onConfirm={onConfirm} onClose={noop} />)
    // Click "Any word (OR)" toggle — it has text "budget.rule.matchAny"
    const anyBtn = screen.getByRole('button', { name: /budget.rule.matchAny/ })
    fireEvent.click(anyBtn)
    const confirmBtn = screen.getByRole('button', { name: /budget.rule.createRule/ })
    fireEvent.click(confirmBtn)
    expect(onConfirm).toHaveBeenCalledWith(
      expect.objectContaining({ matchType: 'KEYWORDS_ANY' })
    )
  })

  it('does not call onConfirm with empty pattern', () => {
    const onConfirm = vi.fn()
    render(<RuleWordPicker label="Netflix" onConfirm={onConfirm} onClose={noop} />)
    // Deselect all
    fireEvent.click(screen.getByRole('button', { name: 'Netflix' }))
    const confirmBtn = screen.getByRole('button', { name: /budget.rule.createRule/ })
    expect(confirmBtn).toBeDisabled()
    fireEvent.click(confirmBtn)
    expect(onConfirm).not.toHaveBeenCalled()
  })
})
