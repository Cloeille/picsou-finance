import type { IconProps } from '@phosphor-icons/react'

/**
 * Defaults for every Phosphor icon. Unlike lucide-react, Phosphor does not hide its SVGs
 * from assistive technology, so a decorative glyph would be announced. An icon that is the
 * only label of a control still gets its name from the control's `aria-label`; an icon that
 * must be announced on its own passes `aria-hidden={false}` and `alt`.
 */
export const ICON_DEFAULTS: IconProps = { weight: 'regular', 'aria-hidden': true }
