import '@testing-library/jest-dom'
import { chooseSelectOption, getSelectOptionValues } from '@/test/radix-select'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AiCategorizationSection } from './AiCategorizationSection'

// Mutable state the mocked hooks read, so each test can vary returned data.
const state = vi.hoisted(() => ({
  testAiData: undefined as { ok: boolean; message: string } | undefined,
  testAiIsError: false,
  testAiError: null as Error | null,
  testAiMutate: vi.fn(),
  testAiReset: vi.fn(),
  updateMutateAsync: vi.fn(),
  updateIsSuccess: false,
  updateError: null as Error | null,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/features/admin/hooks', () => ({
  useUpdateAi: () => ({
    mutateAsync: state.updateMutateAsync,
    isPending: false,
    isError: !!state.updateError,
    isSuccess: state.updateIsSuccess,
    error: state.updateError,
  }),
  useTestAi: () => ({
    mutate: state.testAiMutate,
    reset: state.testAiReset,
    isPending: false,
    isError: state.testAiIsError,
    data: state.testAiData,
    error: state.testAiError,
  }),
}))

const DEFAULT_SETTINGS = {
  provider: 'none',
  model: '',
  baseUrl: '',
  apiKeyPresent: false,
  maxConcurrency: 4,
}

describe('AiCategorizationSection', () => {
  beforeEach(() => {
    state.testAiData = undefined
    state.testAiIsError = false
    state.testAiError = null
    state.testAiMutate = vi.fn()
    state.testAiReset = vi.fn()
    state.updateMutateAsync = vi.fn().mockResolvedValue(undefined)
    state.updateIsSuccess = false
    state.updateError = null
  })

  it('shows empty state with an Add provider button when provider is none', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)

    expect(screen.getByText('admin.ai.empty')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'admin.ai.addProvider' })).toBeInTheDocument()
    expect(screen.queryByLabelText('admin.ai.model')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('admin.ai.baseUrl')).not.toBeInTheDocument()
    expect(screen.queryByText('admin.ai.test')).not.toBeInTheDocument()
  })

  it('opens the configuration form with all four providers when Add provider is clicked and can close it', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    const select = screen.getByRole('combobox')
    const values = getSelectOptionValues(select)
    expect(values).toEqual(['anthropic', 'openrouter', 'openai', 'ollama'])

    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.getByText('admin.ai.empty')).toBeInTheDocument()
  })

  it('shows a clear summary card when a provider is configured with its API key saved', () => {
    render(
      <AiCategorizationSection
        settings={{
          provider: 'anthropic',
          model: 'claude-haiku-4-5',
          baseUrl: '',
          apiKeyPresent: true,
          maxConcurrency: 4,
        }}
      />,
    )

    expect(screen.getByText('Anthropic (Claude)')).toBeInTheDocument()
    expect(screen.getByText('admin.ai.statusActive')).toBeInTheDocument()
    expect(screen.getByText('claude-haiku-4-5')).toBeInTheDocument()
    expect(screen.queryByText('admin.ai.apiKeySaved')).not.toBeInTheDocument()
    expect(screen.queryByText('admin.ai.parallelRequests')).not.toBeInTheDocument()
    // Form stays closed until Edit is clicked
    expect(screen.queryByLabelText('admin.ai.apiKey')).not.toBeInTheDocument()
  })

  it('clicking Test on the summary card calls testAi.mutate with saved values', () => {
    render(
      <AiCategorizationSection
        settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic', model: 'claude-haiku-4-5', apiKeyPresent: true }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.test' }))
    expect(state.testAiMutate).toHaveBeenCalledWith({
      provider: 'anthropic',
      model: 'claude-haiku-4-5',
      baseUrl: '',
      apiKey: '',
    })
  })

  it('renders the success message when testAi returns ok: true', () => {
    state.testAiData = { ok: true, message: 'Connected.' }
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic' }} />)
    expect(screen.getByText('Connected.')).toBeInTheDocument()
  })

  it('renders an error alert when testAi returns ok: false', () => {
    state.testAiData = { ok: false, message: 'Invalid API key.' }
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic' }} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Invalid API key.')
  })

  it('does not render an API key input when editing an ollama provider', () => {
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'ollama' }} />)
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    expect(screen.queryByLabelText('admin.ai.apiKey')).not.toBeInTheDocument()
  })

  it('shows password input and apiKeyHintPresent when editing a provider with a saved key', () => {
    render(
      <AiCategorizationSection
        settings={{ ...DEFAULT_SETTINGS, provider: 'openai', apiKeyPresent: true }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    const apiKeyInput = screen.getByLabelText('admin.ai.apiKey')
    expect(apiKeyInput).toHaveAttribute('type', 'password')
    expect(screen.getByText('admin.ai.apiKeyHintPresent')).toBeInTheDocument()
  })

  it('blocks save and shows apiKeyRequired error when provider changed and api key is blank', async () => {
    render(
      <AiCategorizationSection
        settings={{ provider: 'anthropic', model: '', baseUrl: '', apiKeyPresent: true, maxConcurrency: 4 }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))

    const select = screen.getByRole('combobox')
    chooseSelectOption(select, 'openai')

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).not.toHaveBeenCalled()
    })
    expect(screen.getByText('admin.ai.apiKeyRequired')).toBeInTheDocument()
  })

  it('closes the form automatically after saving', async () => {
    render(
      <AiCategorizationSection
        settings={{ provider: 'anthropic', model: 'claude-haiku-4-5', baseUrl: '', apiKeyPresent: true, maxConcurrency: 4 }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    expect(screen.getByLabelText('admin.ai.model')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledOnce()
    })
    expect(screen.queryByLabelText('admin.ai.model')).not.toBeInTheDocument()
  })

  it('removes the configured provider after confirmation', async () => {
    render(
      <AiCategorizationSection
        settings={{ provider: 'anthropic', model: 'claude-haiku-4-5', baseUrl: '', apiKeyPresent: true, maxConcurrency: 4 }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.removeProvider' }))
    fireEvent.click(screen.getByRole('button', { name: 'common.delete' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledWith({
        provider: 'none',
        model: '',
        baseUrl: '',
        apiKey: '',
        maxConcurrency: 4,
      })
    })
  })
})
