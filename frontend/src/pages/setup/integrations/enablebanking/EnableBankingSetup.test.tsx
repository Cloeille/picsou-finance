import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AxiosError } from 'axios'
import { createInstance } from 'i18next'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { ReactNode } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { setupApi } from '@/features/setup/api'
import en from '@/i18n/locales/en.json'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { SetupStepRevolut } from '../SetupStepRevolut'
import { EBStep1Explain } from './EBStep1Explain'
import { EBStep2Credentials } from './EBStep2Credentials'
import { EBStep3Keypair } from './EBStep3Keypair'
import { EBStep4Redirect } from './EBStep4Redirect'
import { EBStep5Test } from './EBStep5Test'

const i18n = createInstance()
const eb = en.setup.enablebanking
const publicKeyPem = 'synthetic-public-key'
const privatePem = 'synthetic-private-key'
const applicationId = '00000000-0000-4000-8000-000000000000'

function renderStep(step: ReactNode) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/setup/integrations/enablebanking']}>
          <Routes>
            <Route path="/setup/integrations/enablebanking" element={step} />
            <Route path="/setup/integrations/revolut" element={<SetupStepRevolut />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </I18nextProvider>,
  )
}

beforeAll(async () => {
  await i18n.init({ lng: 'en', resources: { en: { translation: en } }, interpolation: { escapeValue: false } })
})

beforeEach(() => {
  useSetupFlowStore.getState().reset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('Enable Banking setup', () => {
  it('explains email-link account creation after opening sign-in', () => {
    vi.spyOn(window, 'open').mockReturnValue(null)
    renderStep(<EBStep1Explain onNext={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: eb.explain.needAccount }))
    expect(window.open).toHaveBeenCalledWith('https://enablebanking.com/sign-in/', '_blank', 'noopener,noreferrer')
    expect(screen.getByText(eb.explain.magicLinkHint)).toBeVisible()
  })

  it('shows registration fields and activation before credentials, and copies the callback URL', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    try {
      renderStep(<EBStep4Redirect onNext={vi.fn()} onBack={vi.fn()} />)
      for (const text of [eb.redirect.environment, eb.redirect.description, eb.redirect.email,
        eb.redirect.policyUrls, eb.redirect.keyMethod, eb.redirect.activationBody, eb.redirect.restrictedMode]) {
        expect(screen.getByText(text)).toBeVisible()
      }
      expect(screen.getByRole('link', { name: 'SECURITY.md' })).toHaveAttribute('href',
        'https://github.com/Cloeille/picsou-finance/blob/main/SECURITY.md')
      expect(screen.getByRole('link', { name: 'LICENSE' })).toHaveAttribute('href',
        'https://github.com/Cloeille/picsou-finance/blob/main/LICENSE')
      fireEvent.click(screen.getByRole('button', { name: eb.redirect.copy }))
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/sync/callback`))
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('imports the downloaded key by default, clears the private PEM, and restores the import on back navigation', async () => {
    const generate = vi.spyOn(setupApi, 'generateEnableBankingKeyPair')
    const importKey = vi.spyOn(setupApi, 'importEnableBankingPrivateKey')
      .mockResolvedValue({ publicKeyPem, regenerated: false })
    const onNext = vi.fn()
    const view = renderStep(<EBStep3Keypair onNext={onNext} onBack={vi.fn()} />)

    expect(screen.getByRole('button', { name: eb.keypair.modeImport })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: eb.continue })).toBeDisabled()
    fireEvent.change(screen.getByLabelText(eb.keypair.privateKeyLabel), { target: { value: privatePem } })
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.importCta }))
    await screen.findByText(eb.keypair.importSuccess)
    expect(importKey).toHaveBeenCalledWith({ privatePem })
    expect(generate).not.toHaveBeenCalled()
    expect(JSON.stringify(useSetupFlowStore.getState().ebDraft)).not.toContain(privatePem)
    fireEvent.click(screen.getByRole('button', { name: eb.continue }))
    expect(onNext).toHaveBeenCalledOnce()

    view.unmount()
    renderStep(<EBStep3Keypair onNext={onNext} onBack={vi.fn()} />)
    expect(screen.getByText(eb.keypair.importSuccess)).toBeVisible()
    expect(screen.getByRole('button', { name: eb.continue })).toBeEnabled()
  })

  it('generates only on explicit request, including after switching modes, and never presents a generated key as imported', async () => {
    const generate = vi.spyOn(setupApi, 'generateEnableBankingKeyPair')
      .mockResolvedValue({ publicKeyPem, regenerated: false })
    renderStep(<EBStep3Keypair onNext={vi.fn()} onBack={vi.fn()} />)
    expect(generate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.modeGenerate }))
    expect(generate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.generateCta }))
    await screen.findByText(publicKeyPem)
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.modeImport }))
    expect(screen.queryByText(eb.keypair.importSuccess)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: eb.continue })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.modeGenerate }))
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.generateCta }))
    await waitFor(() => expect(generate).toHaveBeenCalledTimes(2))
    await screen.findByText(publicKeyPem)
  })

  it('requires key setup again for an old draft without key provenance', async () => {
    sessionStorage.setItem('picsou_setup_flow', JSON.stringify({ state: {
      ebDraft: { applicationId, redirectUri: '', publicKeyPem, tested: false },
    }, version: 0 }))
    await useSetupFlowStore.persist.rehydrate()
    renderStep(<EBStep3Keypair onNext={vi.fn()} onBack={vi.fn()} />)
    expect(screen.getByRole('button', { name: eb.keypair.modeImport })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: eb.continue })).toBeDisabled()
    expect(screen.queryByText(eb.keypair.importSuccess)).not.toBeInTheDocument()
  })

  it('prevents mode changes and back/continue navigation while importing', async () => {
    let finishImport: (value: Awaited<ReturnType<typeof setupApi.importEnableBankingPrivateKey>>) => void = () => {
      throw new Error('Import has not started')
    }
    vi.spyOn(setupApi, 'importEnableBankingPrivateKey').mockImplementation(() => new Promise((resolve) => {
      finishImport = resolve
    }))
    renderStep(<EBStep3Keypair onNext={vi.fn()} onBack={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(eb.keypair.privateKeyLabel), { target: { value: privatePem } })
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.importCta }))
    await waitFor(() => expect(screen.getByRole('button', { name: eb.keypair.modeGenerate })).toBeDisabled())
    expect(screen.getByRole('button', { name: eb.back })).toBeDisabled()
    expect(screen.getByRole('button', { name: eb.continue })).toBeDisabled()
    await act(async () => finishImport({ publicKeyPem, regenerated: false }))
    await screen.findByText(eb.keypair.importSuccess)
  })

  it('reads a downloaded PEM file and imports its contents', async () => {
    const importKey = vi.spyOn(setupApi, 'importEnableBankingPrivateKey')
      .mockResolvedValue({ publicKeyPem, regenerated: false })
    renderStep(<EBStep3Keypair onNext={vi.fn()} onBack={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(eb.keypair.chooseFile), {
      target: { files: [new File([privatePem], `${applicationId}.pem`, { type: 'text/plain' })] },
    })
    await waitFor(() => expect(screen.getByLabelText(eb.keypair.privateKeyLabel)).toHaveValue(privatePem))
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.importCta }))
    await screen.findByText(eb.keypair.importSuccess)
    expect(importKey).toHaveBeenCalledWith({ privatePem })
  })

  it('keeps a failed import retryable without exposing Axios text', async () => {
    vi.spyOn(setupApi, 'importEnableBankingPrivateKey').mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
    renderStep(<EBStep3Keypair onNext={vi.fn()} onBack={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(eb.keypair.privateKeyLabel), { target: { value: privatePem } })
    fireEvent.click(screen.getByRole('button', { name: eb.keypair.importCta }))
    expect(await screen.findByRole('alert')).toHaveTextContent(en.common.errors.network)
    expect(screen.getByRole('button', { name: eb.keypair.importCta })).toBeEnabled()
    expect(screen.getByRole('button', { name: eb.continue })).toBeDisabled()
  })

  it('shows a friendly Picsou network error when saving credentials fails', async () => {
    vi.spyOn(setupApi, 'writeEnableBankingConfig').mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
    const onNext = vi.fn()
    renderStep(<EBStep2Credentials onNext={onNext} onBack={vi.fn()} />)
    const input = screen.getByLabelText(eb.creds.applicationId)
    fireEvent.change(input, { target: { value: applicationId } })
    fireEvent.blur(input)
    fireEvent.click(screen.getByRole('checkbox'))
    const submit = screen.getByRole('button', { name: eb.continue })
    await waitFor(() => expect(submit).toBeEnabled())
    fireEvent.click(submit)
    expect(await screen.findByRole('alert')).toHaveTextContent(en.common.errors.network)
    expect(onNext).not.toHaveBeenCalled()
  })

  it('keeps Enable Banking confirmation visible until Continue, then opens Revolut', async () => {
    vi.spyOn(setupApi, 'testEnableBanking').mockResolvedValue({ ok: true, code: '', hint: '' })
    useSetupFlowStore.getState().setIntegrations(['enablebanking', 'revolut'])
    renderStep(<EBStep5Test onBack={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: eb.test.run }))
    expect(await screen.findByRole('status')).toHaveTextContent(eb.test.success)
    expect(screen.getByText(eb.test.successBody)).toBeVisible()
    expect(screen.queryByText(en.setup.revolut.title)).not.toBeInTheDocument()
    expect(useSetupFlowStore.getState().completedIntegrations).toContain('enablebanking')
    fireEvent.click(screen.getByRole('button', { name: eb.continue }))
    expect(await screen.findByText(en.setup.revolut.title)).toBeVisible()
  })

  it('keeps a failed test retryable and reports the unreachable Picsou server', async () => {
    vi.spyOn(setupApi, 'testEnableBanking').mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
    renderStep(<EBStep5Test onBack={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: eb.test.run }))
    expect(await screen.findByRole('alert')).toHaveTextContent(en.common.errors.network)
    expect(screen.getByRole('button', { name: eb.test.retry })).toBeEnabled()
    expect(useSetupFlowStore.getState().completedIntegrations).not.toContain('enablebanking')
  })
})
