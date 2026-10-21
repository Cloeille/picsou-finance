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
 * Date that places a line in [today, end].
 *
 * A known payment date wins, even when the ex-date is already past or the cash
 * date sits past the horizon. When paymentDate is null the ex-date rule applies.
 * Nothing here adds a lag.
 */
function horizonDate(row: UpcomingDividend): string | null {
  if (row.paymentDate != null) return row.paymentDate
  return row.exDate ?? row.expectedDate
}

/**
 * Projected payments relative to today, so the budget card always has something to show.
 *
 * The PEA line and the reinvested SCPI line carry `countsInBudget: false` because that is
 * what the backend would decide — the fixture does not compute the rule, it records it.
 * The SCPI amount mirrors the demo account (id 13, 10 shares, 12 €/year, quarterly → 30 €).
 * A payment 90 days out exists so a shorter horizon actually drops a row.
 *
 * A holding is inside the horizon when its known paymentDate is in [today, end].
 * When that date is null, the ex-date rule applies. A lag is never invented, so an
 * ex-date already past with no known payment stays out, while one already past with
 * a known payment still ahead stays in. An SCPI has a payment date only.
 * Passing `accountId` keeps the rows of that account, which is what the compte-titres
 * and PEA pages ask for. Sort stays on the ex-date (`expectedDate`), matching the API.
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
  const bnEx = daysFromNow(-4)
  const bnPay = daysFromNow(6)
  const suEx = daysFromNow(55)
  const suPay = daysFromNow(70)
  const kerEx = daysFromNow(-12)
  const riEx = daysFromNow(10)
  const riPay = daysFromNow(-3)
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
    {
      // Already ex, known payment still ahead: the window follows the payment date.
      id: `HOLDING:3:BN.PA:${bnEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'BN.PA',
      name: 'Danone',
      expectedDate: bnEx,
      exDate: bnEx,
      paymentDate: bnPay,
      paymentDateEstimated: false,
      cadence: null,
      basis: 'ANNOUNCED',
      quantity: 6,
      amountPerShare: 1.1,
      amountPerShareEur: 1.1,
      currency: 'EUR',
      amountEur: 6.6,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
    {
      // Ex-date inside 60 days, known payment past it: the payment date wins.
      id: `HOLDING:3:SU.PA:${suEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'SU.PA',
      name: 'Schneider',
      expectedDate: suEx,
      exDate: suEx,
      paymentDate: suPay,
      paymentDateEstimated: true,
      cadence: 'ANNUAL',
      basis: 'HISTORY_CADENCE',
      quantity: 3,
      amountPerShare: 3.5,
      amountPerShareEur: 3.5,
      currency: 'EUR',
      amountEur: 10.5,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
    {
      // Ex-date already past, no known payment: do not invent a lag.
      id: `HOLDING:3:KER.PA:${kerEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'KER.PA',
      name: 'Kering',
      expectedDate: kerEx,
      exDate: kerEx,
      paymentDate: null,
      paymentDateEstimated: false,
      cadence: 'ANNUAL',
      basis: 'HISTORY_CADENCE',
      quantity: 1,
      amountPerShare: 4,
      amountPerShareEur: 4,
      currency: 'EUR',
      amountEur: 4,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
    {
      // Known payment already past: the ex-date still ahead does not pull it back in.
      id: `HOLDING:3:RI.PA:${riEx}`,
      source: 'HOLDING',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      ticker: 'RI.PA',
      name: 'Pernod Ricard',
      expectedDate: riEx,
      exDate: riEx,
      paymentDate: riPay,
      paymentDateEstimated: false,
      cadence: 'ANNUAL',
      basis: 'ANNOUNCED',
      quantity: 2,
      amountPerShare: 2.3,
      amountPerShareEur: 2.3,
      currency: 'EUR',
      amountEur: 4.6,
      reinvested: false,
      accountType: 'COMPTE_TITRES',
      countsInBudget: true,
    },
  ]
  return rows
    .filter((row) => {
      const date = horizonDate(row)
      return date != null && date >= today && date <= limit
    })
    .filter((row) => accountId == null || row.accountId === accountId)
    .sort((a, b) => a.expectedDate.localeCompare(b.expectedDate)
      || a.accountName.localeCompare(b.accountName)
      || (a.ticker ?? '').localeCompare(b.ticker ?? ''))
}
