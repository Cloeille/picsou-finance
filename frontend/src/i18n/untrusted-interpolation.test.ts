import { expect, it } from 'vitest'

const { default: i18n, i18nReady } = await import('./index')

// Bank-provided text (account names, labels) reaches t() as interpolation values. i18next before
// 26.4.4 ran its nesting regexp on the interpolated result with exponential backtracking, so a
// value holding an unclosed "$t(" froze the UI: about 30 s at this length, doubling per character.
it('renders an interpolated value with an unclosed $t( literally and without stalling', async () => {
  await i18nReady
  const name = '$t(' + 'a'.repeat(32)

  const started = performance.now()
  const text = i18n.t('budget.flow.node.withdrawalAccount', { name })
  const elapsedMs = performance.now() - started

  expect(text).toContain(name)
  expect(elapsedMs).toBeLessThan(1000)
})
