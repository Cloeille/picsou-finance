/**
 * Hands a generated file to the browser.
 *
 * The anchor click is what makes this survive a popup blocker: it is a synchronous click on an
 * element that is in the DOM, initiated inside the user's own gesture, rather than a programmatic
 * navigation. Revoking the object URL is deferred because doing it in the same tick races with
 * Safari's own handling of the click and yields an empty file.
 */
export function triggerBlobDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** Filename the server named in `Content-Disposition`, or {@code fallback}. */
export function filenameFromDisposition(header: string | undefined, fallback: string): string {
  const match = header?.match(/filename="([^"]+)"/)
  return match?.[1] ?? fallback
}
