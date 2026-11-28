import { beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.hoisted(() => vi.fn())
vi.mock('@/lib/api-client', () => ({ api: { post } }))

const { exportApi } = await import('./api')

async function rejection(run: () => Promise<unknown>): Promise<unknown> {
  try {
    await run()
  } catch (error) {
    return error
  }
  throw new Error('expected the call to be refused')
}

describe('exportApi.downloadArchive', () => {
  beforeEach(() => {
    post.mockReset()
  })

  it('posts the passphrase and the proof as a blob and names the file after Content-Disposition', async () => {
    const blob = new Blob(['archive'])
    post.mockResolvedValue({
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="picsou-2026-11-28.picsou-archive"' },
    })

    const result = await exportApi.downloadArchive({ passphrase: 'a long enough passphrase', reAuth: { password: 'pw' } })

    expect(post).toHaveBeenCalledWith(
      '/admin/archive',
      { passphrase: 'a long enough passphrase', reAuth: { password: 'pw' } },
      { responseType: 'blob' },
    )
    expect(result.filename).toBe('picsou-2026-11-28.picsou-archive')
    expect(result.blob).toBe(blob)
  })

  it('falls back to a default file name without the header', async () => {
    post.mockResolvedValue({ data: new Blob(['x']), headers: {} })
    expect((await exportApi.downloadArchive({ passphrase: 'p'.repeat(12) })).filename).toBe('picsou.picsou-archive')
  })

  it('hands the stable code of a refusal back as JSON, although the body arrived as a Blob', async () => {
    const problem = { code: 'PASSPHRASE_TOO_SHORT', detail: 'too short' }
    const error = { response: { status: 400, data: new Blob([JSON.stringify(problem)]) } }
    post.mockRejectedValue(error)

    expect(await rejection(() => exportApi.downloadArchive({ passphrase: 'short' }))).toBe(error)
    expect(error.response.data).toEqual(problem)
  })

  it('drops a refusal body that is not JSON instead of throwing a parse error', async () => {
    const error = { response: { status: 502, data: new Blob(['<html>Bad gateway</html>']) } }
    post.mockRejectedValue(error)

    expect(await rejection(() => exportApi.downloadArchive({ passphrase: 'p'.repeat(12) }))).toBe(error)
    expect(error.response.data).toBeUndefined()
  })

  it('leaves a network error untouched', async () => {
    const error = { code: 'ERR_NETWORK' }
    post.mockRejectedValue(error)
    expect(await rejection(() => exportApi.downloadArchive({ passphrase: 'p'.repeat(12) }))).toBe(error)
  })
})
