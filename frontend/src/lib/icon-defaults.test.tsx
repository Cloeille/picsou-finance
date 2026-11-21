import '@testing-library/jest-dom'
import { render } from '@testing-library/react'
import { IconContext, TrendUp } from '@phosphor-icons/react'
import { describe, expect, it } from 'vitest'
import { ICON_DEFAULTS } from './icon-defaults'

describe('ICON_DEFAULTS', () => {
  it('hides decorative icons from assistive technology, as lucide-react did', () => {
    const { container } = render(
      <IconContext.Provider value={ICON_DEFAULTS}>
        <TrendUp />
      </IconContext.Provider>,
    )
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('lets an icon that must be announced opt back in', () => {
    const { container } = render(
      <IconContext.Provider value={ICON_DEFAULTS}>
        <TrendUp aria-hidden={false} alt="up" />
      </IconContext.Provider>,
    )
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'false')
  })
})
