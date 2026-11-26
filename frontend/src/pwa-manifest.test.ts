import { describe, expect, it } from "vitest"
import indexHtml from "../index.html?raw"
import manifestRaw from "../public/manifest.webmanifest?raw"

const publicAssets = new Set(
  Object.keys(import.meta.glob("../public/*.{png,svg,webmanifest}")).map((p) =>
    p.replace(/^\.\.\/public/, "")
  )
)

const publicPngDataUrls = Object.fromEntries(
  Object.entries(
    import.meta.glob<string>("../public/*.png", {
      query: "?inline",
      import: "default",
      eager: true,
    })
  ).map(([p, dataUrl]) => [p.replace(/^\.\.\/public/, ""), dataUrl])
)

interface ManifestIcon {
  src: string
  sizes: string
  type: string
  purpose?: string
}

interface WebAppManifest {
  name: string
  short_name: string
  start_url: string
  display: string
  background_color: string
  theme_color: string
  icons: ManifestIcon[]
}

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

function readPngHeader(assetPath: string): {
  width: number
  height: number
  colorType: number
} {
  const dataUrl = publicPngDataUrls[assetPath]
  expect(
    dataUrl,
    `${assetPath} must exist under frontend/public/`
  ).toBeDefined()
  const base64 = dataUrl.replace(/^data:image\/png;base64,/, "")
  const binary = atob(base64)
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
  const view = new DataView(bytes.buffer)

  for (let i = 0; i < PNG_SIGNATURE.length; i++) {
    expect(bytes[i], `${assetPath} PNG signature byte ${i}`).toBe(
      PNG_SIGNATURE[i]
    )
  }
  const chunkType = String.fromCharCode(
    bytes[12],
    bytes[13],
    bytes[14],
    bytes[15]
  )
  expect(chunkType, `${assetPath} must start with IHDR`).toBe("IHDR")

  return {
    width: view.getUint32(16, false),
    height: view.getUint32(20, false),
    colorType: bytes[25],
  }
}

describe("PWA home-screen icons and web manifest", () => {
  it("declares favicon, apple-touch-icon, web manifest, and theme metadata in index.html", () => {
    const doc = new DOMParser().parseFromString(indexHtml, "text/html")

    const favicon = doc.querySelector('link[rel="icon"]')
    expect(favicon?.getAttribute("href")).toBe("/favicon.svg")
    expect(favicon?.getAttribute("type")).toBe("image/svg+xml")
    expect(publicAssets.has("/favicon.svg")).toBe(true)

    const appleTouchIcon = doc.querySelector('link[rel="apple-touch-icon"]')
    expect(appleTouchIcon?.getAttribute("href")).toBe("/apple-touch-icon.png")
    expect(appleTouchIcon?.getAttribute("sizes")).toBe("180x180")

    const manifestLink = doc.querySelector('link[rel="manifest"]')
    expect(manifestLink?.getAttribute("href")).toBe("/manifest.webmanifest")
    expect(publicAssets.has("/manifest.webmanifest")).toBe(true)

    const appTitle = doc.querySelector(
      'meta[name="apple-mobile-web-app-title"]'
    )
    expect(appTitle?.getAttribute("content")).toBe("Picsou")

    const themeColors = Array.from(
      doc.querySelectorAll('meta[name="theme-color"]')
    ).map((el) => ({
      media: el.getAttribute("media"),
      content: el.getAttribute("content"),
    }))
    expect(themeColors).toEqual([
      { media: "(prefers-color-scheme: light)", content: "#ffffff" },
      { media: "(prefers-color-scheme: dark)", content: "#0a0a0a" },
    ])
  })

  it("ships a valid Picsou manifest.webmanifest with matching PNG icons on disk", () => {
    const manifest = JSON.parse(manifestRaw) as WebAppManifest
    expect(manifest.name).toBe("Picsou")
    expect(manifest.short_name).toBe("Picsou")
    expect(manifest.start_url).toBe("/")
    expect(manifest.display).toBe("standalone")
    expect(manifest.background_color).toBe("#ffffff")
    expect(manifest.theme_color).toBe("#ffffff")

    expect(manifest.icons).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          sizes: "192x192",
          type: "image/png",
          purpose: "any",
        }),
        expect.objectContaining({
          sizes: "512x512",
          type: "image/png",
          purpose: "any",
        }),
        expect.objectContaining({
          sizes: "512x512",
          type: "image/png",
          purpose: "maskable",
        }),
      ])
    )

    for (const icon of manifest.icons) {
      const [expectedWidth, expectedHeight] = icon.sizes.split("x").map(Number)
      const header = readPngHeader(icon.src)
      expect(header.width, `${icon.src} width`).toBe(expectedWidth)
      expect(header.height, `${icon.src} height`).toBe(expectedHeight)
      if (icon.purpose === "maskable") {
        // Color type 2 = truecolor RGB (opaque full-bleed background, no alpha channel).
        expect(
          header.colorType,
          `${icon.src} maskable icon must be opaque RGB`
        ).toBe(2)
      }
    }

    const touchHeader = readPngHeader("/apple-touch-icon.png")
    expect(touchHeader.width).toBe(180)
    expect(touchHeader.height).toBe(180)
    // iOS turns transparent pixels in apple-touch-icon black, so keep it opaque RGB.
    expect(touchHeader.colorType).toBe(2)
  })
})
