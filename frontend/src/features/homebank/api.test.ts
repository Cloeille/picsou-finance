import { beforeEach, describe, expect, it, vi } from 'vitest'

const { apiPost } = vi.hoisted(() => ({ apiPost: vi.fn() }))

vi.mock('@/lib/api-client', () => ({ api: { post: apiPost } }))

const { homeBankApi } = await import('./api')

describe('homeBankApi.preview', () => {
  beforeEach(() => apiPost.mockReset().mockResolvedValue({ data: {} }))

  it('includes explicitly supplied QIF currency in multipart preview', async () => {
    const file = new File(['qif'], 'history.qif')
    await homeBankApi.preview(file, undefined, 'USD')

    const [, body] = apiPost.mock.calls[0]
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
    expect(body.get('currency')).toBe('USD')
    expect(body.get('password')).toBeNull()
  })

  it('does not send currency for iOS preview', async () => {
    const file = new File(['ios'], 'archive.hbk')
    await homeBankApi.preview(file, 'secret')

    const [, body] = apiPost.mock.calls[0]
    expect(body.get('password')).toBe('secret')
    expect(body.get('currency')).toBeNull()
  })
})
