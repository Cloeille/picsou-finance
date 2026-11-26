import '@testing-library/jest-dom/vitest'
import { useRef, useState } from 'react'
import { chooseSelectOption, getSelectOptions } from '@/test/radix-select'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import type { AdminAiModelsRequest, AdminAiRequest, AiModelsResult } from '@/features/admin/api'
import { useAppStore } from '@/stores/app-store'
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
  listModelsFn: vi.fn<(body: AdminAiModelsRequest) => Promise<AiModelsResult>>(),
  listModelsReset: vi.fn(),
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
  useTestAi: () => {
    const [cleared, setCleared] = useState(false)
    const mutate = (body: AdminAiRequest) => {
      setCleared(false)
      state.testAiMutate(body)
    }
    const reset = () => {
      state.testAiReset()
      setCleared(true)
    }
    return {
      mutate,
      reset,
      isPending: false,
      isError: cleared ? false : state.testAiIsError,
      data: cleared ? undefined : state.testAiData,
      error: cleared ? null : state.testAiError,
    }
  },
  useListAiModels: () => {
    const [result, setResult] = useState<{
      isPending: boolean
      isError: boolean
      data: AiModelsResult | undefined
      error: Error | null
    }>({ isPending: false, isError: false, data: undefined, error: null })
    const seqRef = useRef(0)

    const mutate = (body: AdminAiModelsRequest) => {
      const seq = ++seqRef.current
      setResult({ isPending: true, isError: false, data: undefined, error: null })
      void Promise.resolve(state.listModelsFn(body)).then(
        (data) => {
          if (seq !== seqRef.current) return
          setResult({ isPending: false, isError: false, data, error: null })
        },
        (err: unknown) => {
          if (seq !== seqRef.current) return
          setResult({
            isPending: false,
            isError: true,
            data: undefined,
            error: err instanceof Error ? err : new Error(String(err)),
          })
        },
      )
    }

    const reset = () => {
      seqRef.current += 1
      state.listModelsReset()
      setResult({ isPending: false, isError: false, data: undefined, error: null })
    }

    return { ...result, mutate, reset }
  },
}))

const DEFAULT_SETTINGS = {
  provider: 'none',
  model: '',
  baseUrl: '',
  apiKeyPresent: false,
  maxConcurrency: 4,
}

function getProviderSelect() {
  return screen.getByRole('combobox', { name: 'admin.ai.provider' })
}

function getDatalistOptionValues(): string[] {
  const datalist = document.getElementById('admin-ai-model-suggestions')
  if (!datalist) return []
  return Array.from(datalist.querySelectorAll('option')).map((o) => o.getAttribute('value') ?? '')
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
    state.listModelsFn = vi.fn().mockResolvedValue({ ok: true, models: [] })
    state.listModelsReset = vi.fn()
    useAppStore.setState({ desktopMode: false })
  })

  it('shows empty state with an Add provider button when provider is none', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)

    expect(screen.getByText('admin.ai.empty')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'admin.ai.addProvider' })).toBeInTheDocument()
    expect(screen.queryByLabelText('admin.ai.model')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('admin.ai.baseUrl')).not.toBeInTheDocument()
    expect(screen.queryByText('admin.ai.test')).not.toBeInTheDocument()
  })

  it('opens the configuration form with all seven providers in order and with the new names, and can close it', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    const select = getProviderSelect()
    const options = getSelectOptions(select)
    expect(options.map((o) => o.getAttribute('data-option-value'))).toEqual([
      'anthropic',
      'openai',
      'gemini',
      'mistral',
      'openrouter',
      'ollama',
      'custom',
    ])
    expect(options.map((o) => o.textContent?.trim())).toEqual([
      'Anthropic',
      'OpenAI',
      'Google Gemini',
      'Mistral',
      'OpenRouter',
      'Ollama',
      'admin.ai.providerCustom',
    ])
    fireEvent.keyDown(options[0], { key: 'Escape' })

    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    expect(screen.queryByRole('combobox', { name: 'admin.ai.provider' })).not.toBeInTheDocument()
    expect(screen.getByText('admin.ai.empty')).toBeInTheDocument()
  })

  it('fills base URL, model and parallel requests with each provider defaults when chosen', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    const select = getProviderSelect()
    const baseUrlInput = screen.getByLabelText('admin.ai.baseUrl')
    const modelInput = screen.getByLabelText('admin.ai.model')
    const concurrencyInput = screen.getByLabelText('admin.ai.maxConcurrency')

    expect(baseUrlInput).toHaveValue('https://api.anthropic.com')
    expect(modelInput).toHaveValue('claude-haiku-4-5')
    expect(concurrencyInput).toHaveValue(8)

    const cases = [
      {
        provider: 'openai',
        baseUrl: 'https://api.openai.com',
        model: 'gpt-4o-mini',
        maxConcurrency: 8,
      },
      {
        provider: 'gemini',
        baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
        model: 'gemini-flash-latest',
        maxConcurrency: 4,
      },
      {
        provider: 'mistral',
        baseUrl: 'https://api.mistral.ai',
        model: 'mistral-small-latest',
        maxConcurrency: 4,
      },
      {
        provider: 'openrouter',
        baseUrl: 'https://openrouter.ai/api',
        model: 'anthropic/claude-3.5-haiku',
        maxConcurrency: 8,
      },
      {
        provider: 'ollama',
        baseUrl: 'http://ollama:11434',
        model: 'qwen3:0.6b',
        maxConcurrency: 4,
      },
      {
        provider: 'custom',
        baseUrl: '',
        model: '',
        maxConcurrency: 4,
      },
    ]

    for (const c of cases) {
      chooseSelectOption(select, c.provider)
      expect(baseUrlInput).toHaveValue(c.baseUrl)
      expect(modelInput).toHaveValue(c.model)
      expect(concurrencyInput).toHaveValue(c.maxConcurrency)
    }
  })

  it('pre-fills Ollama with http://localhost:11434 in desktop mode', async () => {
    useAppStore.setState({ desktopMode: true })
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    chooseSelectOption(getProviderSelect(), 'ollama')

    expect(screen.getByLabelText('admin.ai.baseUrl')).toHaveValue('http://localhost:11434')
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    expect(state.listModelsFn).toHaveBeenCalledWith({
      provider: 'ollama',
      baseUrl: 'http://localhost:11434',
      apiKey: '',
    })
  })

  it('starts each provider from its default base URL, even after the URL was edited', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    const select = getProviderSelect()
    const baseUrlInput = screen.getByLabelText('admin.ai.baseUrl')

    chooseSelectOption(select, 'openai')
    expect(baseUrlInput).toHaveValue('https://api.openai.com')

    fireEvent.change(baseUrlInput, { target: { value: 'https://proxy.example.com/v1' } })
    chooseSelectOption(select, 'mistral')
    expect(baseUrlInput).toHaveValue('https://api.mistral.ai')
  })

  it('restores the saved base URL and model when the user comes back to the saved provider', () => {
    render(
      <AiCategorizationSection
        settings={{
          provider: 'custom',
          model: 'local-model',
          baseUrl: 'http://localhost:1234/v1',
          apiKeyPresent: false,
          maxConcurrency: 2,
        }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))

    const select = getProviderSelect()
    chooseSelectOption(select, 'ollama')
    expect(screen.getByLabelText('admin.ai.baseUrl')).toHaveValue('http://ollama:11434')

    chooseSelectOption(select, 'custom')
    expect(screen.getByLabelText('admin.ai.baseUrl')).toHaveValue('http://localhost:1234/v1')
    expect(screen.getByLabelText('admin.ai.model')).toHaveValue('local-model')
  })

  it('keeps working for an install saved with openai and its own base URL, and fills defaults when saved base URL is blank', async () => {
    const { rerender } = render(
      <AiCategorizationSection
        settings={{
          provider: 'openai',
          model: 'gpt-4o-mini',
          baseUrl: 'https://gateway.example.com/v1',
          apiKeyPresent: true,
          maxConcurrency: 8,
        }}
      />,
    )

    expect(screen.getByText('OpenAI')).toBeInTheDocument()
    expect(screen.getByText('https://gateway.example.com/v1')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    expect(screen.getByLabelText('admin.ai.baseUrl')).toHaveValue('https://gateway.example.com/v1')
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))

    rerender(
      <AiCategorizationSection
        settings={{
          provider: 'openai',
          model: 'gpt-4o-mini',
          baseUrl: '',
          apiKeyPresent: true,
          maxConcurrency: 8,
        }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    expect(screen.getByLabelText('admin.ai.baseUrl')).toHaveValue('https://api.openai.com')
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })

  it('shows a clear summary card when a provider is configured with its API key saved', () => {
    render(
      <AiCategorizationSection
        settings={{
          provider: 'anthropic',
          model: 'claude-haiku-4-5',
          baseUrl: '',
          apiKeyPresent: true,
          maxConcurrency: 8,
        }}
      />,
    )

    expect(screen.getByText('Anthropic')).toBeInTheDocument()
    expect(screen.getByText('admin.ai.statusActive')).toBeInTheDocument()
    expect(screen.getByText('claude-haiku-4-5')).toBeInTheDocument()
    expect(screen.queryByText('admin.ai.apiKeySaved')).not.toBeInTheDocument()
    expect(screen.queryByText('admin.ai.parallelRequests')).not.toBeInTheDocument()
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

  it('does not render an API key input when editing an ollama provider', async () => {
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'ollama' }} />)
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    expect(screen.queryByLabelText('admin.ai.apiKey')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })

  it('shows password input and apiKeyHintPresent when editing a provider with a saved key', async () => {
    render(
      <AiCategorizationSection
        settings={{ ...DEFAULT_SETTINGS, provider: 'openai', apiKeyPresent: true }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    const apiKeyInput = screen.getByLabelText('admin.ai.apiKey')
    expect(apiKeyInput).toHaveAttribute('type', 'password')
    expect(screen.getByText('admin.ai.apiKeyHintPresent')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })

  it('loads model suggestions on open for a saved provider with a kept key and keeps a custom saved model', async () => {
    state.listModelsFn = vi.fn().mockResolvedValue({
      ok: true,
      models: ['claude-haiku-4-5', 'claude-sonnet-4-5'],
    })

    render(
      <AiCategorizationSection
        settings={{
          provider: 'anthropic',
          model: 'claude-custom-snapshot',
          baseUrl: '',
          apiKeyPresent: true,
          maxConcurrency: 8,
        }}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))

    await waitFor(() => {
      expect(getDatalistOptionValues()).toEqual(['claude-haiku-4-5', 'claude-sonnet-4-5'])
    })
    expect(state.listModelsFn).toHaveBeenCalledWith({
      provider: 'anthropic',
      baseUrl: 'https://api.anthropic.com',
      apiKey: '',
    })
    expect(screen.getByLabelText('admin.ai.model')).toHaveValue('claude-custom-snapshot')
  })

  it('loads model suggestions after leaving the API key field and shows a loading indicator while pending', async () => {
    let resolveList!: (value: AiModelsResult) => void
    state.listModelsFn = vi.fn().mockImplementation(
      () =>
        new Promise<AiModelsResult>((resolve) => {
          resolveList = resolve
        }),
    )

    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    // Leaving base URL without a key does not trigger a fetch for anthropic
    fireEvent.blur(screen.getByLabelText('admin.ai.baseUrl'))
    expect(state.listModelsFn).not.toHaveBeenCalled()

    const apiKeyInput = screen.getByLabelText('admin.ai.apiKey')
    fireEvent.change(apiKeyInput, { target: { value: 'sk-test-key' } })
    fireEvent.blur(apiKeyInput)

    expect(state.listModelsFn).toHaveBeenCalledWith({
      provider: 'anthropic',
      baseUrl: 'https://api.anthropic.com',
      apiKey: 'sk-test-key',
    })
    expect(screen.getByRole('status')).toHaveTextContent('admin.ai.modelsLoading')

    resolveList({ ok: true, models: ['claude-haiku-4-5', 'claude-sonnet-4-5'] })

    await waitFor(() => {
      expect(getDatalistOptionValues()).toEqual(['claude-haiku-4-5', 'claude-sonnet-4-5'])
    })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('loads model suggestions immediately after choosing ollama', async () => {
    state.listModelsFn = vi.fn().mockResolvedValue({
      ok: true,
      models: ['qwen3:0.6b', 'llama3.2:3b'],
    })

    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    chooseSelectOption(getProviderSelect(), 'ollama')

    await waitFor(() => {
      expect(getDatalistOptionValues()).toEqual(['qwen3:0.6b', 'llama3.2:3b'])
    })
    expect(state.listModelsFn).toHaveBeenCalledWith({
      provider: 'ollama',
      baseUrl: 'http://ollama:11434',
      apiKey: '',
    })
  })

  it('shows a muted hint and keeps the model input editable when model listing returns ok: false or rejects', async () => {
    state.listModelsFn = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, message: 'Unauthorized', models: [] })
      .mockRejectedValueOnce(new Error('Network error'))

    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    const modelInput = screen.getByLabelText('admin.ai.model')
    expect(modelInput).not.toHaveAttribute('aria-describedby')

    const apiKeyInput = screen.getByLabelText('admin.ai.apiKey')
    fireEvent.change(apiKeyInput, { target: { value: 'sk-bad-key' } })
    fireEvent.blur(apiKeyInput)

    await waitFor(() => {
      expect(screen.getByText('admin.ai.modelsLoadFailed')).toBeInTheDocument()
    })
    const failedHint = screen.getByText('admin.ai.modelsLoadFailed')
    expect(failedHint).toHaveAttribute('id', 'admin-ai-models-error')
    expect(failedHint).toHaveAttribute('aria-live', 'polite')
    expect(modelInput).toHaveAttribute('aria-describedby', 'admin-ai-models-error')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    fireEvent.change(modelInput, { target: { value: 'my-manual-model' } })
    expect(modelInput).toHaveValue('my-manual-model')

    // Reject path also shows the muted line and keeps the field editable
    fireEvent.change(apiKeyInput, { target: { value: 'sk-network-fail' } })
    fireEvent.blur(apiKeyInput)

    await waitFor(() => {
      expect(screen.getByText('admin.ai.modelsLoadFailed')).toBeInTheDocument()
    })
    expect(modelInput).toHaveValue('my-manual-model')
  })

  it('blocks save and shows apiKeyRequired error when provider changed and api key is blank', async () => {
    render(
      <AiCategorizationSection
        settings={{ provider: 'anthropic', model: '', baseUrl: '', apiKeyPresent: true, maxConcurrency: 8 }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))

    chooseSelectOption(getProviderSelect(), 'openai')

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).not.toHaveBeenCalled()
    })
    expect(screen.getByText('admin.ai.apiKeyRequired')).toBeInTheDocument()
  })

  it('requires a base URL for custom provider and allows saving custom with a blank API key', async () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    chooseSelectOption(getProviderSelect(), 'custom')

    // Cannot save custom with a blank base URL
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(screen.getByText('admin.ai.baseUrlRequired')).toBeInTheDocument()
    })
    expect(state.updateMutateAsync).not.toHaveBeenCalled()

    // Filling base URL allows saving with a blank API key (and triggers model list on blur)
    const baseUrlInput = screen.getByLabelText('admin.ai.baseUrl')
    fireEvent.change(baseUrlInput, { target: { value: 'https://vllm.example.com/v1' } })
    fireEvent.blur(baseUrlInput)
    expect(state.listModelsFn).toHaveBeenCalledWith({
      provider: 'custom',
      baseUrl: 'https://vllm.example.com/v1',
      apiKey: '',
    })

    fireEvent.change(screen.getByLabelText('admin.ai.model'), {
      target: { value: 'local-model-id' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledWith({
        provider: 'custom',
        model: 'local-model-id',
        baseUrl: 'https://vllm.example.com/v1',
        apiKey: '',
        maxConcurrency: 4,
      })
    })
  })

  it('closes the form automatically after saving', async () => {
    render(
      <AiCategorizationSection
        settings={{ provider: 'anthropic', model: 'claude-haiku-4-5', baseUrl: '', apiKeyPresent: true, maxConcurrency: 8 }}
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
        settings={{ provider: 'anthropic', model: 'claude-haiku-4-5', baseUrl: '', apiKeyPresent: true, maxConcurrency: 8 }}
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

  it('clears a typed API key when switching from Anthropic to custom or Ollama', async () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    fireEvent.change(screen.getByLabelText('admin.ai.apiKey'), { target: { value: 'sk-test-key' } })
    chooseSelectOption(getProviderSelect(), 'custom')
    expect(screen.getByLabelText('admin.ai.apiKey')).toHaveValue('')

    fireEvent.change(screen.getByLabelText('admin.ai.baseUrl'), {
      target: { value: 'https://vllm.example.com/v1' },
    })
    fireEvent.change(screen.getByLabelText('admin.ai.model'), {
      target: { value: 'local-model-id' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledWith({
        provider: 'custom',
        model: 'local-model-id',
        baseUrl: 'https://vllm.example.com/v1',
        apiKey: '',
        maxConcurrency: 4,
      })
    })

    // Reopen, type a key for Anthropic, switch to Ollama, test and save: both send blank apiKey
    state.updateMutateAsync.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))
    fireEvent.change(screen.getByLabelText('admin.ai.apiKey'), { target: { value: 'sk-test-key' } })
    chooseSelectOption(getProviderSelect(), 'ollama')

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.test' }))
    expect(state.testAiMutate).toHaveBeenCalledWith({
      provider: 'ollama',
      model: 'qwen3:0.6b',
      baseUrl: 'http://ollama:11434',
      apiKey: '',
    })

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))
    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledWith({
        provider: 'ollama',
        model: 'qwen3:0.6b',
        baseUrl: 'http://ollama:11434',
        apiKey: '',
        maxConcurrency: 4,
      })
    })
  })

  it('requires a model for custom provider and keeps the form open without unhandled rejection when save fails', async () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    chooseSelectOption(getProviderSelect(), 'custom')
    fireEvent.change(screen.getByLabelText('admin.ai.baseUrl'), {
      target: { value: 'https://vllm.example.com/v1' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('admin.ai.modelRequired')
    })
    expect(state.updateMutateAsync).not.toHaveBeenCalled()

    // Filling model clears the error; a failed save keeps the form open without throwing
    state.updateMutateAsync.mockRejectedValueOnce(new Error('Server error'))
    fireEvent.change(screen.getByLabelText('admin.ai.model'), { target: { value: 'local-model' } })
    expect(screen.queryByText('admin.ai.modelRequired')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))
    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledOnce()
    })
    expect(screen.getByLabelText('admin.ai.model')).toBeInTheDocument()
  })

  it('does not send a duplicate model-list request on blur while the same request is pending', () => {
    state.listModelsFn = vi.fn().mockImplementation(() => new Promise<AiModelsResult>(() => {}))

    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    const apiKeyInput = screen.getByLabelText('admin.ai.apiKey')
    const baseUrlInput = screen.getByLabelText('admin.ai.baseUrl')
    fireEvent.change(apiKeyInput, { target: { value: 'sk-test-key' } })
    fireEvent.blur(apiKeyInput)
    fireEvent.blur(baseUrlInput)

    expect(state.listModelsFn).toHaveBeenCalledTimes(1)
  })

  it('clears a shown connection test result after changing provider', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    state.testAiData = { ok: true, message: 'Connected.' }
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.test' }))
    expect(screen.getByText('Connected.')).toBeInTheDocument()

    chooseSelectOption(getProviderSelect(), 'openai')
    expect(screen.queryByText('Connected.')).not.toBeInTheDocument()
  })

  it('shows a range error and blocks save when parallel requests is 0', async () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))

    fireEvent.change(screen.getByLabelText('admin.ai.apiKey'), { target: { value: 'sk-test-key' } })
    fireEvent.change(screen.getByLabelText('admin.ai.maxConcurrency'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('admin.ai.maxConcurrencyRange')
    })
    expect(state.updateMutateAsync).not.toHaveBeenCalled()
  })

  it('fetches models with a kept key only when the base URL matches the saved origin', async () => {
    render(
      <AiCategorizationSection
        settings={{
          provider: 'anthropic',
          model: 'claude-haiku-4-5',
          baseUrl: '',
          apiKeyPresent: true,
          maxConcurrency: 8,
        }}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    expect(state.listModelsFn).toHaveBeenCalledTimes(1)
    expect(state.listModelsFn).toHaveBeenLastCalledWith({
      provider: 'anthropic',
      baseUrl: 'https://api.anthropic.com',
      apiKey: '',
    })

    state.listModelsFn.mockClear()
    const baseUrlInput = screen.getByLabelText('admin.ai.baseUrl')
    fireEvent.change(baseUrlInput, { target: { value: 'https://other.example.com/v1' } })
    fireEvent.blur(baseUrlInput)
    expect(state.listModelsFn).not.toHaveBeenCalled()

    // Same origin as the saved default allows using the kept key again
    fireEvent.change(baseUrlInput, { target: { value: 'https://api.anthropic.com/v1' } })
    fireEvent.blur(baseUrlInput)
    await waitFor(() => {
      expect(state.listModelsFn).toHaveBeenCalledTimes(1)
    })
    expect(state.listModelsFn).toHaveBeenCalledWith({
      provider: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      apiKey: '',
    })

    // A blank base URL means the default of the provider, as the backend reads it
    state.listModelsFn.mockClear()
    fireEvent.change(baseUrlInput, { target: { value: '   ' } })
    fireEvent.blur(baseUrlInput)
    await waitFor(() => {
      expect(state.listModelsFn).toHaveBeenCalledTimes(1)
    })
  })

  it('sends maxConcurrency 8 for a new Anthropic provider and preserves the stored value when editing', async () => {
    const { rerender } = render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.addProvider' }))
    fireEvent.change(screen.getByLabelText('admin.ai.apiKey'), { target: { value: 'sk-test-key' } })
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledWith({
        provider: 'anthropic',
        model: 'claude-haiku-4-5',
        baseUrl: 'https://api.anthropic.com',
        apiKey: 'sk-test-key',
        maxConcurrency: 8,
      })
    })

    state.updateMutateAsync.mockClear()
    rerender(
      <AiCategorizationSection
        settings={{
          provider: 'anthropic',
          model: 'claude-haiku-4-5',
          baseUrl: '',
          apiKeyPresent: true,
          maxConcurrency: 12,
        }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.edit' }))
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: 'admin.ai.save' }))

    await waitFor(() => {
      expect(state.updateMutateAsync).toHaveBeenCalledWith({
        provider: 'anthropic',
        model: 'claude-haiku-4-5',
        baseUrl: 'https://api.anthropic.com',
        apiKey: '',
        maxConcurrency: 12,
      })
    })
  })
})
