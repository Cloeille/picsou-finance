const AVATAR_SIZE = 192

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      if (typeof reader.result === 'string') resolve(reader.result)
      else reject(new Error('LOAD_FAILED'))
    }
    reader.onerror = () => reject(new Error('LOAD_FAILED'))
    reader.readAsDataURL(file)
  })
}

/**
 * Center-crops and resizes an uploaded image file to a compact 192x192 data URL
 * suitable for crisp Retina display in avatars without bloating localStorage.
 *
 * Uses FileReader (`data:` URL) rather than `URL.createObjectURL` (`blob:` URL)
 * so image loading complies with the production CSP (`img-src 'self' data: https:`).
 */
export async function resizeProfilePhoto(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('INVALID_IMAGE')
  }

  const dataUrl = await readFileAsDataUrl(file)
  const img = await new Promise<HTMLImageElement>((resolve, reject) => {
    const el = new Image()
    el.onload = () => resolve(el)
    el.onerror = () => reject(new Error('LOAD_FAILED'))
    el.src = dataUrl
  })

  const side = Math.min(img.naturalWidth, img.naturalHeight)
  const sx = (img.naturalWidth - side) / 2
  const sy = (img.naturalHeight - side) / 2

  const canvas = document.createElement('canvas')
  canvas.width = AVATAR_SIZE
  canvas.height = AVATAR_SIZE
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('CANVAS_UNAVAILABLE')

  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(img, sx, sy, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE)

  const webp = canvas.toDataURL('image/webp', 0.85)
  if (webp.startsWith('data:image/webp')) return webp
  return canvas.toDataURL('image/jpeg', 0.85)
}
