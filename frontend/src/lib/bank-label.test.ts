import { describe, expect, it } from 'vitest'
import { formatBankLabel, type LabelTranslator } from './bank-label'

const EN: Record<string, string> = {
  'budget.recurring.labels.transferFrom': 'Transfer from {{name}}',
  'budget.recurring.labels.transferIncoming': 'Incoming transfer from {{name}}',
  'budget.recurring.labels.transferOutgoing': 'Outgoing transfer to {{name}}',
}
const FR: Record<string, string> = {
  'budget.recurring.labels.transferFrom': 'Virement de {{name}}',
  'budget.recurring.labels.transferIncoming': 'Virement entrant de {{name}}',
  'budget.recurring.labels.transferOutgoing': 'Virement sortant vers {{name}}',
}
const translator = (dict: Record<string, string>): LabelTranslator =>
  (key, { name }) => (dict[key] ?? key).replace('{{name}}', name)
const tEn = translator(EN)
const tFr = translator(FR)

describe('formatBankLabel', () => {
  it('strips SEPA creditor, mandate, and reference noise from direct debits', () => {
    const res = formatBankLabel(
      'Cardif Assurance Vie Ech Id Emetteur Fr28zzz110086 Mdt Fr28zzz1100862021092305371751cdif Ref S Lib S',
      'fr',
      tFr,
    )
    expect(res).toEqual({ title: 'Cardif Assurance Vie', detail: null })
  })

  it('extracts readable motif and sender from French bank transfer labels', () => {
    const res = formatBankLabel(
      'Recu De Mme Chantal De Bailliencourt Motif Argent De Poche Ref',
      'fr',
      tFr,
    )
    expect(res).toEqual({
      title: 'Argent de poche',
      detail: 'Mme Chantal De Bailliencourt',
    })
  })

  it('keeps clean merchant names untouched', () => {
    expect(formatBankLabel('American Express', 'fr', tFr)).toEqual({
      title: 'American Express',
      detail: null,
    })
    expect(formatBankLabel('Netflix', 'fr', tFr)).toEqual({
      title: 'Netflix',
      detail: null,
    })
  })

  it('words a synthesized transfer title in the interface language, never in hardcoded French', () => {
    expect(formatBankLabel('Recu De Jean Martin', 'en', tEn).title).toBe('Transfer from Jean Martin')
    expect(formatBankLabel('Recu De Jean Martin', 'fr', tFr).title).toBe('Virement de Jean Martin')
    expect(formatBankLabel('Incoming Transfer From Alice', 'fr', tFr).title).toBe('Virement entrant de Alice')
    expect(formatBankLabel('Outgoing Transfer To Bob', 'fr', tFr).title).toBe('Virement sortant vers Bob')
  })
})
