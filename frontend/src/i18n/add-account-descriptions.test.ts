import { describe, expect, it } from 'vitest'
import de from './locales/de.json'
import en from './locales/en.json'
import es from './locales/es.json'
import fr from './locales/fr.json'

const modal = Object.values(import.meta.glob('../components/shared/AddAccountModal.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>)[0]

// Every source the add-account modal lists shows a one-line description; a key missing from a
// locale renders as the raw key (`addAccount.desc.corum`), which is how CORUM and Sofidy shipped.
const descKeys = [...modal.matchAll(/descKey:\s*'addAccount\.desc\.([\w-]+)'/g)].map(([, key]) => key)

describe('add-account descriptions', () => {
  it('finds the descriptions the modal lists', () => {
    expect(descKeys).toContain('corum')
  })

  it.each(Object.entries({ fr, en, de, es }))('%s describes every listed source', (_lang, locale) => {
    const descriptions = locale.addAccount.desc as Record<string, string | undefined>
    const missing = descKeys.filter((key) => !descriptions[key]?.trim())
    expect(missing).toEqual([])
  })
})
