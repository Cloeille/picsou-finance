import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())
vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: () => undefined, removeEventListener: () => undefined }))
vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })

// Real English lookup: a wrong key fails the assertion instead of echoing the key.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      const value = key.split('.').reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], en)
      const str = typeof value === 'string' ? value : key
      return Object.entries(opts ?? {}).reduce((acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)), str)
    },
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { importArchive, getStatus } = vi.hoisted(() => ({ importArchive: vi.fn(), getStatus: vi.fn() }))
vi.mock('@/features/setup/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/setup/api')>()
  return { ...actual, setupApi: { ...actual.setupApi, importArchive, getStatus } }
})
// A 1.5 s poll would make every test wait.
vi.mock('@/features/setup/archive-import', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/setup/archive-import')>()
  return {
    ...actual,
    waitForArchiveImport: (options: Parameters<typeof actual.waitForArchiveImport>[0]) =>
      actual.waitForArchiveImport({ ...options, intervalMs: 5 }),
  }
})

const { ImportArchiveDialog } = await import('./ImportArchiveDialog')

const PASSPHRASE = 'correct horse battery'
const archiveFile = () => new File(['x'.repeat(2048)], 'picsou-2026-11-28.picsou-archive')

function renderDialog(props: Partial<React.ComponentProps<typeof ImportArchiveDialog>> = {}) {
  const onOpenChange = vi.fn()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/setup']}>
        <Routes>
          <Route path="/setup" element={<ImportArchiveDialog open onOpenChange={onOpenChange} desktop={false} {...props} />} />
          <Route path="/login" element={<div>login-page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { onOpenChange }
}

const dialog = () => screen.getByRole('dialog')
const submitButton = () => within(dialog()).getByRole('button', { name: 'Import archive' }) as HTMLButtonElement
const passphraseInput = () => within(dialog()).getByLabelText('Passphrase') as HTMLInputElement

function fill(passphrase = PASSPHRASE, file: File | null = archiveFile()) {
  if (file) {
    const input = dialog().querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [file] } })
  }
  fireEvent.change(passphraseInput(), { target: { value: passphrase } })
}

const statusOf = (archiveImport?: unknown, over: Record<string, unknown> = {}) => ({
  state: 'PENDING_ADMIN', needsSetup: true, integrations: {}, archiveImport, ...over,
})
const apiError = (status: number, code?: string) => ({ response: { status, data: { code } } })

beforeEach(() => {
  importArchive.mockReset()
  getStatus.mockReset()
})
afterEach(cleanup)

describe('ImportArchiveDialog', () => {
  it('explains what happens and accepts only .picsou-archive files', () => {
    renderDialog()
    expect(screen.getByText('Your data, settings and bank connections are restored.')).toBeInTheDocument()
    expect(screen.getByText('You then sign in again with the account you used before.')).toBeInTheDocument()
    expect(screen.getByText('The server restarts to apply the archive, which takes a few moments.')).toBeInTheDocument()
    expect(dialog().querySelector('input[type="file"]')).toHaveAttribute('accept', '.picsou-archive')
  })

  it('tells the desktop app restarts its window', () => {
    renderDialog({ desktop: true })
    expect(screen.getByText(/the window closes, then reopens on its own/)).toBeInTheDocument()
  })

  it('shows the chosen file name and size', () => {
    renderDialog()
    fill(PASSPHRASE)
    expect(screen.getByText('picsou-2026-11-28.picsou-archive')).toBeInTheDocument()
    expect(screen.getByText('2 KB')).toBeInTheDocument()
  })

  it('requires a file and a passphrase of at least 12 characters', () => {
    renderDialog()
    expect(submitButton()).toBeDisabled()
    fill('too short', null)
    expect(submitButton()).toBeDisabled()
    expect(screen.getByText('The passphrase has at least 12 characters.')).toBeInTheDocument()
    fill('twelve chars', archiveFile())
    expect(submitButton()).toBeEnabled()
    fill('eleven char', null)
    expect(submitButton()).toBeDisabled()
  })

  it('masks the passphrase until the toggle is pressed', () => {
    renderDialog()
    expect(passphraseInput()).toHaveAttribute('type', 'password')
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Show password' }))
    expect(passphraseInput()).toHaveAttribute('type', 'text')
  })

  it('uploads the file and the passphrase, then follows the restart through network errors to the login', async () => {
    importArchive.mockImplementation(async (_f: File, _p: string, onProgress?: (n: number) => void) => {
      onProgress?.(0.5)
      return { restarting: true, tables: 12, rows: 3400 }
    })
    getStatus
      .mockResolvedValueOnce(statusOf({ state: 'pending' }))
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockResolvedValue(statusOf({ state: 'done', tables: 12, rows: 3400 }, { state: 'COMPLETE', needsSetup: false }))
    renderDialog()
    fill()

    fireEvent.click(submitButton())

    expect(await screen.findByText('Import in progress')).toBeInTheDocument()
    expect(importArchive).toHaveBeenCalledTimes(1)
    const [file, passphrase] = importArchive.mock.calls[0]
    expect((file as File).name).toBe('picsou-2026-11-28.picsou-archive')
    expect(passphrase).toBe(PASSPHRASE)

    expect(await screen.findByText('Your data is back')).toBeInTheDocument()
    expect(screen.getByText(/3,400 rows across 12 tables were restored/)).toBeInTheDocument()
    expect(getStatus.mock.calls.length).toBeGreaterThanOrEqual(4)

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Go to sign in' }))
    await waitFor(() => expect(screen.getByText('login-page')).toBeInTheDocument())
  })

  it('shows the restarting state while the server does not answer', async () => {
    importArchive.mockResolvedValue({ restarting: true, tables: 1, rows: 1 })
    getStatus.mockRejectedValue(new Error('Network Error'))
    renderDialog()
    fill()
    fireEvent.click(submitButton())
    expect(await screen.findByText('Import in progress')).toBeInTheDocument()
    expect(await screen.findByText('The server is restarting, waiting for it to answer...')).toBeInTheDocument()
    // Nothing can be closed or sent again during the restart.
    expect(within(dialog()).queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('does not close while the import is being applied or once it is done', async () => {
    importArchive.mockResolvedValue({ restarting: true, tables: 1, rows: 1 })
    getStatus.mockResolvedValue(statusOf({ state: 'pending' }))
    const { onOpenChange } = renderDialog()
    fill()
    fireEvent.click(submitButton())
    await screen.findByText('Import in progress')
    fireEvent.keyDown(dialog(), { key: 'Escape' })
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('explains a failed import with its code, keeps the previous data and allows a retry', async () => {
    importArchive.mockResolvedValue({ restarting: true, tables: 1, rows: 1 })
    getStatus.mockResolvedValue(statusOf({ state: 'failed', code: 'KEY_OVERRIDDEN' }))
    renderDialog()
    fill()
    fireEvent.click(submitButton())

    expect(await screen.findByText('The import failed')).toBeInTheDocument()
    expect(screen.getByText('None of your current data was changed.')).toBeInTheDocument()
    expect(screen.getByText(/CRYPTO_ENCRYPTION_KEY/)).toBeInTheDocument()

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Retry' }))
    expect(submitButton()).toBeDisabled()
    expect(passphraseInput()).toHaveValue('')
  })

  it.each([
    [400, 'WRONG_PASSPHRASE', /passphrase is wrong, or the archive is damaged/],
    [400, 'NOT_AN_ARCHIVE', /not a Picsou archive/],
    [422, 'ARCHIVE_TOO_NEW', /newer version of Picsou/],
    [422, 'CORRUPT_ARCHIVE', /damaged or incomplete/],
    [409, 'SETUP_ALREADY_STARTED', /account already exists/],
    [409, 'IMPORT_UNAVAILABLE', /no data folder/],
    [413, 'ARCHIVE_TOO_LARGE', /larger than this server/],
    [429, undefined, /Too many attempts/],
  ])('keeps the form open on a refused upload (%s %s)', async (status, code, message) => {
    importArchive.mockRejectedValue(apiError(status, code))
    renderDialog()
    fill()
    fireEvent.click(submitButton())

    expect(await within(dialog()).findByRole('alert')).toHaveTextContent(message)
    expect(getStatus).not.toHaveBeenCalled()
    // The file is kept for another try; the passphrase is the thing to fix.
    expect(screen.getByText('picsou-2026-11-28.picsou-archive')).toBeInTheDocument()
  })

  it('never writes the passphrase anywhere on screen, including in errors', async () => {
    importArchive.mockRejectedValue(apiError(400, 'WRONG_PASSPHRASE'))
    renderDialog()
    fill()
    fireEvent.click(submitButton())
    await within(dialog()).findByRole('alert')
    expect(dialog().textContent).not.toContain(PASSPHRASE)
  })

  it('picks up an import the server still reports as pending after a reload', async () => {
    getStatus.mockResolvedValue(statusOf({ state: 'done', tables: 3, rows: 30 }, { state: 'COMPLETE', needsSetup: false }))
    renderDialog({ reported: { state: 'pending' } })
    expect(await screen.findByText('Your data is back')).toBeInTheDocument()
  })

  it('shows a failure reported by the server at start', async () => {
    renderDialog({ reported: { state: 'failed', code: 'TARGET_NOT_EMPTY' } })
    expect(await screen.findByText('The import failed')).toBeInTheDocument()
    expect(screen.getByText(/already holds data/)).toBeInTheDocument()
  })

  it('offers to check again when the server never comes back', async () => {
    importArchive.mockResolvedValue({ restarting: true, tables: 1, rows: 1 })
    getStatus.mockRejectedValue(new Error('Network Error'))
    const realNow = Date.now
    let offset = 0
    vi.spyOn(Date, 'now').mockImplementation(() => realNow() + offset)
    renderDialog()
    fill()
    fireEvent.click(submitButton())
    await screen.findByText('Import in progress')
    offset = 10 * 60_000
    expect(await screen.findByText('The server is not answering yet')).toBeInTheDocument()
    vi.restoreAllMocks()
  })
})
