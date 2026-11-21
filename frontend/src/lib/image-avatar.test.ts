import { describe, it, expect, vi, afterEach } from 'vitest'
import { resizeProfilePhoto } from './image-avatar'

describe('resizeProfilePhoto', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('rejects non-image files', async () => {
    const file = new File(['hello'], 'notes.txt', { type: 'text/plain' })
    await expect(resizeProfilePhoto(file)).rejects.toThrow('INVALID_IMAGE')
  })

  it('loads images via a data URL rather than a CSP-blocked blob URL', async () => {
    const createObjectUrlSpy = vi.spyOn(URL, 'createObjectURL')
    let loadedSrc = ''

    class FakeImage {
      naturalWidth = 300
      naturalHeight = 200
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      set src(value: string) {
        loadedSrc = value
        queueMicrotask(() => this.onload?.())
      }
    }
    vi.stubGlobal('Image', FakeImage)

    const drawImage = vi.fn()
    const fakeCanvas = {
      width: 0,
      height: 0,
      getContext: () => ({
        imageSmoothingEnabled: false,
        imageSmoothingQuality: 'low',
        drawImage,
      }),
      toDataURL: (mime: string) => `${mime === 'image/webp' ? 'data:image/webp' : 'data:image/jpeg'};base64,AAA`,
    }
    const origCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
      if (tag === 'canvas') return fakeCanvas as unknown as HTMLCanvasElement
      return origCreate(tag)
    }) as typeof document.createElement)

    const png = new File([new Uint8Array([137, 80, 78, 71])], 'avatar.png', { type: 'image/png' })
    const result = await resizeProfilePhoto(png)

    expect(createObjectUrlSpy).not.toHaveBeenCalled()
    expect(loadedSrc).toMatch(/^data:image\/png;base64,/)
    expect(drawImage).toHaveBeenCalledWith(expect.any(FakeImage), 50, 0, 200, 200, 0, 0, 192, 192)
    expect(result).toBe('data:image/webp;base64,AAA')
  })
})
