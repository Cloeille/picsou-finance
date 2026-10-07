import '@testing-library/jest-dom'
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MerchantAvatar } from './MerchantAvatar'

describe('MerchantAvatar', () => {
  it('derives two initials from a multi-word label', () => {
    render(<MerchantAvatar label="Boulangerie du Coin" />)
    expect(screen.getByText('BD')).toBeInTheDocument()
  })

  it('uses the first two characters of a single-word label', () => {
    render(<MerchantAvatar label="Spotify" />)
    expect(screen.getByText('SP')).toBeInTheDocument()
  })

  it('prefers an explicit monogram over the derived one', () => {
    render(<MerchantAvatar label="Carrefour" monogram="C" />)
    expect(screen.getByText('C')).toBeInTheDocument()
    expect(screen.queryByText('CA')).not.toBeInTheDocument()
  })

  it('falls back to a neutral glyph when the label is empty', () => {
    render(<MerchantAvatar label={null} />)
    expect(screen.getByText('•')).toBeInTheDocument()
  })

  it('uses the brand colour as background when provided', () => {
    render(<MerchantAvatar label="Carrefour" color="#0055ff" />)
    expect(screen.getByText('CA')).toHaveStyle({ backgroundColor: '#0055ff' })
  })

  it('picks dark text on a light brand colour for contrast', () => {
    render(<MerchantAvatar label="Snow" color="#ffffff" />)
    expect(screen.getByText('SN')).toHaveStyle({ color: '#1a1a1a' })
  })
})
