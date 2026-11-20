import { describe, expect, it } from 'vitest'
import { formatBankLabel } from './bank-label'

describe('formatBankLabel', () => {
  it('strips SEPA creditor, mandate, and reference noise from direct debits', () => {
    const res = formatBankLabel(
      'Cardif Assurance Vie Ech Id Emetteur Fr28zzz110086 Mdt Fr28zzz1100862021092305371751cdif Ref S Lib S',
      'fr',
    )
    expect(res).toEqual({ title: 'Cardif Assurance Vie', detail: null })
  })

  it('extracts readable motif and sender from French bank transfer labels', () => {
    const res = formatBankLabel(
      'Recu De Mme Chantal De Bailliencourt Motif Argent De Poche Ref',
      'fr',
    )
    expect(res).toEqual({
      title: 'Argent de poche',
      detail: 'Mme Chantal De Bailliencourt',
    })
  })

  it('keeps clean merchant names untouched', () => {
    expect(formatBankLabel('American Express', 'fr')).toEqual({
      title: 'American Express',
      detail: null,
    })
    expect(formatBankLabel('Netflix', 'fr')).toEqual({
      title: 'Netflix',
      detail: null,
    })
  })
})
