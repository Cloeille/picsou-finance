import { describe, it, expect, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'

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

const { api } = await import('@/lib/api-client')
const { finaryApi } = await import('./api')

describe('finaryApi.previewFile', () => {
  it('sends the file as multipart, not as JSON', async () => {
    let sent: InternalAxiosRequestConfig | undefined
    api.defaults.adapter = async (config) => {
      sent = config
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
    }
    const file = new File(['{}'], 'finary.json', { type: 'application/json' })

    await finaryApi.previewFile(file)

    expect(sent?.url).toBe('/finary/preview')
    expect(sent?.data).toBeInstanceOf(FormData)
    expect((sent?.data as FormData).get('file')).toBeInstanceOf(File)
  })
})
