import type { UpcomingDividend } from '@/types/api'

function iso(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function daysFromNow(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() + n)
  return iso(d)
}

function addDays(isoDate: string, n: number): string {
  const d = new Date(`${isoDate}T00:00:00`)
  d.setDate(d.getDate() + n)
  return iso(d)
}

/**
 * Projected payments relative to today, so the budget card always has something to show.
 *
 * The PEA line and the reinvested SCPI line carry `countsInBudget: false` because that is
 * what the backend would decide — the fixture does not compute the rule, it records it.
 * The SCPI amount mirrors the demo account (id 13, 10 shares, 12 €/year, quarterly → 30 €).
 * A payment 90 days out exists so a shorter horizon actually drops a row.
 *
 * `exDate` / `paymentDate` follow the contract: a holding's ex-date is its expected date,
 * its payment date is that date plus a learned lag (or null when nothing was learned),
 * and an SCPI has a payment date only. Passing `accountId` keeps the rows of that account,
 * which is what the compte-titres and PEA pages ask for.
 */
export function mockUpcomingDividends(horizonDays: number, accountId?: number): UpcomingDividend[] {
  const today = daysFromNow(0)
  const limit = daysFromNow(horizonDays)
  const tteEx = daysFromNow(8)
  const msftEx = daysFromNow(15)
  const airEx = daysFromNow(12)
  const mcEx = daysFromNow(180)
  const scpiPay = daysFromNow(20)
  const sanEx = daysFromNow(40)
  const orEx = daysFromNow(90)
  const rows: UpcomingDividend[] = [
    {
      id: `HOLDING:3:TTE.PA:${tteEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'TTE.PA',
      name: 'TotalEnergies',
      expectedDate: tteEx,
      exDate: tteEx,
      paymentDate: addDays(tteEx, 5),
      paymentDateEstimated: true,
      cadence: 'QUARTERLY',
      basis: 'HISTORY_CADENCE',
      quantity: 40,
      amountPerShare: 0.85,
      amountPerShareEur: 0.85,
      currency: 'EUR',
      amountEur: 34,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
    {
      id: `HOLDING:3:MSFT:${msftEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'MSFT',
      name: 'Microsoft',
      expectedDate: msftEx,
      exDate: msftEx,
      // A declared Nasdaq date, used as-is: not a learned lag, so the marker stays off.
      paymentDate: addDays(msftEx, 21),
      paymentDateEstimated: false,
      cadence: 'QUARTERLY',
      basis: 'ANNOUNCED',
      quantity: 10,
      amountPerShare: 0.98,
      amountPerShareEur: 0.9,
      currency: 'USD',
      amountEur: 9,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
    {
      id: `HOLDING:2:AIR.PA:${airEx}`,
      source: 'HOLDING',
      accountId: 2,
      accountName: 'PEA BoursoBank',
      ticker: 'AIR.PA',
      name: 'Airbus',
      expectedDate: airEx,
      exDate: airEx,
      paymentDate: addDays(airEx, 3),
      paymentDateEstimated: true,
      cadence: 'ANNUAL',
      basis: 'HISTORY_CADENCE',
      quantity: 8,
      amountPerShare: 2.25,
      amountPerShareEur: 2.25,
      currency: 'EUR',
      amountEur: 18,
      reinvested: false,
      accountType: 'PEA',
      countsInBudget: false,
    },
    {
      id: `HOLDING:2:MC.PA:${mcEx}`,
      source: 'HOLDING',
      accountId: 2,
      accountName: 'PEA BoursoBank',
      ticker: 'MC.PA',
      name: 'LVMH',
      expectedDate: mcEx,
      exDate: mcEx,
      paymentDate: addDays(mcEx, 10),
      paymentDateEstimated: true,
      cadence: 'ANNUAL',
      basis: 'HISTORY_CADENCE',
      quantity: 4,
      amountPerShare: 13,
      amountPerShareEur: 13,
      currency: 'EUR',
      amountEur: 52,
      reinvested: false,
      accountType: 'PEA',
      countsInBudget: false,
    },
    {
      id: `SCPI:13:-:${scpiPay}`,
      source: 'SCPI',
      accountId: 13,
      accountName: 'Pierre-papier exemple',
      ticker: null,
      name: 'Pierre-papier exemple',
      expectedDate: scpiPay,
      exDate: null,
      paymentDate: scpiPay,
      paymentDateEstimated: true,
      cadence: 'QUARTERLY',
      basis: 'SCPI_DECLARED',
      quantity: 10,
      amountPerShare: 3,
      amountPerShareEur: 3,
      currency: 'EUR',
      amountEur: 30,
      reinvested: true,
      accountType: 'SCPI',
      countsInBudget: false,
    },
    {
      id: `HOLDING:3:SAN.PA:${sanEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'SAN.PA',
      name: 'Sanofi',
      expectedDate: sanEx,
      exDate: sanEx,
      paymentDate: null,
      paymentDateEstimated: false,
      cadence: 'ANNUAL',
      basis: 'HISTORY_CADENCE',
      quantity: 5,
      amountPerShare: 1.2,
      amountPerShareEur: 1.2,
      currency: 'EUR',
      amountEur: 6,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
    {
      id: `HOLDING:3:OR.PA:${orEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'OR.PA',
      name: "L'Oréal",
      expectedDate: orEx,
      exDate: orEx,
      paymentDate: addDays(orEx, 7),
      paymentDateEstimated: true,
      cadence: 'ANNUAL',
      basis: 'HISTORY_CADENCE',
      quantity: 2,
      amountPerShare: 6.6,
      amountPerShareEur: 6.6,
      currency: 'EUR',
      amountEur: 13.2,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
  ]
  return rows
    .filter((row) => row.expectedDate >= today && row.expectedDate <= limit)
    .filter((row) => accountId == null || row.accountId === accountId)
    .sort((a, b) => a.expectedDate.localeCompare(b.expectedDate)
      || a.accountName.localeCompare(b.accountName)
      || (a.ticker ?? '').localeCompare(b.ticker ?? ''))
}
