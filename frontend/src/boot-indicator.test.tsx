import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import indexHtml from '../index.html?raw'

function mountIndexRoot() {
  const doc = new DOMParser().parseFromString(indexHtml, 'text/html')
  const root = document.importNode(doc.getElementById('root')!, true)
  document.body.append(root)
  return root
}

describe('boot indicator in index.html', () => {
  afterEach(() => {
    document.body.replaceChildren()
  })

  it('is a labelled status inside #root, so the first paint is not blank', () => {
    const root = mountIndexRoot()

    const status = root.querySelector('[role="status"]')
    expect(status?.getAttribute('aria-label')).toBeTruthy()
  })

  it('disappears when React renders the app into #root', () => {
    const root = mountIndexRoot()

    act(() => createRoot(root).render(<main>app</main>))

    expect(root.innerHTML).toBe('<main>app</main>')
  })
})
