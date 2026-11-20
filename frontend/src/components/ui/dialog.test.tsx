import '@testing-library/jest-dom'
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from './dialog'

describe('DialogContent layout safeguards', () => {
  it('constrains grid column width and prevents horizontal blowout while allowing vertical scroll', () => {
    render(
      <Dialog open>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Long content dialog</DialogTitle>
          </DialogHeader>
          <div>
            <span>{'A'.repeat(120)}</span>
          </div>
        </DialogContent>
      </Dialog>
    )

    const content = screen.getByRole('dialog')
    // grid-cols-1 + *:min-w-0 stops implicit CSS grid auto tracks from expanding past max-w-*
    expect(content.className).toContain('grid-cols-1')
    expect(content.className).toContain('*:min-w-0')
    // Keeps modals inside the viewport vertically without introducing a horizontal scrollbar
    expect(content.className).toContain('overflow-x-hidden')
    expect(content.className).toContain('overflow-y-auto')
    expect(content.className).toContain('max-h-[calc(100dvh-2rem)]')
  })
})
