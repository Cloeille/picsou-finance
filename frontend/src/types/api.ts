export type AccountType =
  | 'LEP' | 'LIVRET_A' | 'LDDS' | 'LIVRET_JEUNE' | 'PEL' | 'CEL'
  | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS'
  | 'REAL_ESTATE' | 'LOAN' | 'EMPLOYEE_SAVINGS' | 'OTHER'

export type PropertyKind = 'HOUSE' | 'APARTMENT' | 'BUILDING' | 'LAND' | 'PARKING' | 'COMMERCIAL'

export type PropertyCategory =
  | 'PRIMARY_RESIDENCE' | 'SECONDARY_RESIDENCE' | 'RENTAL' | 'LAND' | 'OTHER'

/** Only houses and apartments have a reliable price per m² in the open data. */
export const ESTIMABLE_PROPERTY_KINDS: PropertyKind[] = ['HOUSE', 'APARTMENT']

export type ValuationMode = 'ESTIMATED' | 'MANUAL'

export type ValuationConfidence = 'HIGH' | 'MEDIUM' | 'LOW'

export type ValuationStatus =
  | 'OK'
  | 'UNSUPPORTED_AREA'
  | 'NOT_ESTIMABLE'
  | 'INCOMPLETE_DATA'
  | 'GEOCODING_FAILED'
  | 'NO_COMPARABLE_DATA'
  | 'PROVIDER_UNAVAILABLE'

export interface RealEstateMetadata {
  purchasePrice: number
  purchaseDate: string | null
  agencyFees: number | null
  notaryFees: number | null
  worksCost: number | null
  /** Purchase price plus every acquisition fee — what gain/loss is measured against. */
  costBasis: number
  propertyType: string | null
  /**
   * `propertyType` normalised by the backend's lenient `PropertyKind.parse`, or null when the
   * free-text column holds something it does not recognise. Branch on this, not on the raw
   * string — old rows predate the enum and may hold French labels.
   */
  propertyKind: PropertyKind | null
  category: PropertyCategory | null
  description: string | null
  address: string | null
  postalCode: string | null
  city: string | null
  country: string | null
  /** Present once geocoded; its absence is why a valuation cannot run. */
  inseeCode: string | null
  latitude: number | null
  longitude: number | null
  geocodeScore: number | null
  geocodedAt: string | null
  surfaceArea: number | null
  landArea: number | null
  constructionYear: number | null
  rooms: number | null
  bedrooms: number | null
  bathrooms: number | null
  floorNumber: number | null
  floorsTotal: number | null
  hasElevator: boolean | null
  garageCount: number | null
  parkingCount: number | null
  hasGarden: boolean | null
  hasTerrace: boolean | null
  hasBalcony: boolean | null
  energyClass: string | null
  valuationMode: ValuationMode
  /** Date of the newest valuation (`YYYY-MM-DD`), or null if the property was never valued. */
  lastValuedAt: string | null
  rentalIncome: number | null
}

export interface PropertyAdjustment {
  code: string
  factor: number | null
  sqm: number | null
  amount: number | null
}

export interface PropertyValuation {
  status: ValuationStatus
  mode: ValuationMode
  appliedToBalance: boolean
  estimatedValue: number | null
  lowValue: number | null
  highValue: number | null
  pricePerSqm: number | null
  sampleSize: number | null
  confidence: ValuationConfidence | null
  sourceYear: number | null
  provider: string | null
  scale: string | null
  valuedAt: string | null
  reindexRatio: number | null
  adjustments: PropertyAdjustment[]
}

export interface PropertyValuationHistoryEntry {
  valuedAt: string
  estimatedValue: number
  lowValue: number | null
  highValue: number | null
  pricePerSqm: number | null
  provider: string
  confidence: ValuationConfidence | null
  sampleSize: number | null
  sourceYear: number | null
}

export interface MemberShare {
  memberId: number
  displayName: string
  avatarColor: string
  sharePercent: number
  isOwner: boolean
}

export interface Ownership {
  shares: MemberShare[]
  totalAssigned: number
  /** 100 − totalAssigned: held outside Picsou, so counted in nobody's net worth. */
  unassigned: number
}

export interface OwnershipRequest {
  shares: { memberId: number; sharePercent: number }[]
}

export interface LinkedLoan {
  accountId: number
  name: string
  lenderName: string | null
  outstandingBalance: number
  sharePercent: number
  monthlyPayment: number | null
  endDate: string | null
}

export interface RealEstatePropertyLine {
  accountId: number
  name: string
  color: string
  propertyType: string | null
  category: string | null
  city: string | null
  sharePercent: number
  grossValue: number
  outstandingDebt: number
  netValue: number
  costBasis: number
  unrealizedGain: number
  surfaceArea: number | null
  rentalIncome: number | null
  valuationMode: ValuationMode
  lastValuedAt: string | null
  lastConfidence: ValuationConfidence | null
  loans: LinkedLoan[]
}

export interface RealEstateSummary {
  grossValue: number
  outstandingDebt: number
  netValue: number
  costBasis: number
  unrealizedGain: number
  unrealizedGainPercent: number | null
  loanToValue: number | null
  monthlyRentalIncome: number
  properties: RealEstatePropertyLine[]
}

export interface GeocodeSuggestion {
  label: string
  score: number | null
  postcode: string | null
  city: string | null
  inseeCode: string | null
  latitude: number | null
  longitude: number | null
}

export interface DebtInfo {
  linkedAccountId: number | null
  linkedAccountName: string | null
  borrowedAmount: number
  interestRate: number | null
  monthlyPayment: number | null
  lenderName: string | null
  startDate: string | null
  endDate: string | null
  insuranceMonthly: number | null
  fileFees: number | null
}

export interface Account {
  id: number
  name: string
  type: AccountType
  provider: string | null
  currency: string
  currentBalance: number
  currentBalanceEur: number
  cashBalance?: number | null
  lastSyncedAt: string | null
  isManual: boolean
  color: string
  ticker: string | null
  logoUrl: string | null
  /** Key of a bundled frontend asset (`lib/provider-logos.ts`); null for accounts with no choice made. */
  logoKey: string | null
  createdAt: string
  realEstate?: RealEstateMetadata
  debt?: DebtInfo
  /** Set only when the member owns less than all of it — the co-ownership badge signal. */
  sharePercent?: number | null
  /** Whether the viewer administers the account. Holding a share does not grant write access. */
  isOwner?: boolean | null
}

export interface AccountRequest {
  name: string
  type: AccountType
  provider?: string
  currency: string
  currentBalance?: number
  isManual: boolean
  color?: string
  ticker?: string
  /** Omitted leaves the stored key untouched — the backend only overwrites it when set. */
  logoKey?: string
  /**
   * The bank picked in the account form, as the catalog's own id. Consumed server-side to
   * resolve the logo (never sent as a URL — see `docs/features/bank-logos.md`) and not stored.
   */
  institutionId?: string
}

export interface RealEstateMetadataRequest {
  purchasePrice: number
  purchaseDate?: string | null
  agencyFees?: number | null
  notaryFees?: number | null
  worksCost?: number | null
  propertyType?: string | null
  category?: PropertyCategory | null
  description?: string | null
  address?: string | null
  postalCode?: string | null
  city?: string | null
  country?: string | null
  surfaceArea?: number | null
  landArea?: number | null
  constructionYear?: number | null
  rooms?: number | null
  bedrooms?: number | null
  bathrooms?: number | null
  floorNumber?: number | null
  floorsTotal?: number | null
  hasElevator?: boolean | null
  garageCount?: number | null
  parkingCount?: number | null
  hasGarden?: boolean | null
  hasTerrace?: boolean | null
  hasBalcony?: boolean | null
  energyClass?: string | null
  valuationMode?: ValuationMode
  rentalIncome?: number | null
}

export interface DebtRequest {
  linkedAccountId?: number | null
  borrowedAmount: number
  interestRate?: number
  monthlyPayment?: number
  lenderName?: string
  startDate?: string
  endDate?: string
  insuranceMonthly?: number
  fileFees?: number
}

export interface LoanInstallment {
  number: number
  date: string
  capital: number
  interest: number
  insurance: number
  totalPayment: number
  remainingBalance: number
}

export interface LoanSummary {
  totalInstallments: number
  paidInstallments: number
  remainingInstallments: number
  endDate: string | null
  monthlyPayment: number
  monthlyCapital: number
  monthlyInterest: number
  monthlyInsurance: number
  totalCost: number
  totalCapitalCost: number
  totalInterestCost: number
  totalInsuranceCost: number
  fileFees: number
  totalRepaid: number
  capitalRepaid: number
  interestRepaid: number
  insuranceRepaid: number
  remainingBalance: number
  capitalRepaidPct: number
}

export interface LoanScheduleResponse {
  summary: LoanSummary
  schedule: LoanInstallment[]
}

export interface BalanceSnapshot {
  id: number
  date: string
  balance: number
  investedAmount?: number
  createdAt?: string
}

export interface GoalProgress {
  id: number
  name: string
  targetAmount: number
  deadline: string
  createdAt: string
  historyStartMonth: string | null
  accounts: Account[]
  currentTotal: number
  percentComplete: number
  monthsLeft: number
  monthlyNeeded: number
  avgMonthlyContribution: number | null
  isOnTrack: boolean
  surplus: number
}

export interface GoalRequest {
  name: string
  targetAmount: number
  deadline: string
  accountIds: number[]
}

export interface GoalMonthEntry {
  yearMonth: string
  objective: number
  actual: number | null
  manualActual: number | null
  override: number | null
  effective: number | null
}

export interface DashboardData {
  totalNetWorth: number
  totalLiabilities: number
  netWorthHistory: { date: string; total: number; invested: number; pnl: number }[]
  distribution: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: AccountType
    hasHoldings: boolean
  }[]
  liabilities: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: AccountType
    hasHoldings: boolean
  }[]
  goalSummaries: GoalProgress[]
}

export interface Institution {
  /** Opaque round-trip token ("Swan::FR::business") — pass back to /sync/initiate verbatim. */
  id: string
  name: string
  bic: string | null
  logoUrl: string | null
  country: string
  /** 'personal' | 'business' — kept as a string so an unknown provider value degrades to no badge. */
  psuType: string
}

export interface HoldingResponse {
  ticker: string
  name: string | null
  quantity: number
  averageBuyIn: number | null
  currentPrice: number | null
  quoteCurrency?: string | null
  currentValueEur: number | null
  costBasisEur: number | null
  pnlEur: number | null
  pnlPercent: number | null
  priceUpdatedAt: string | null
  // The day the EUR price is for, and whether it is a recorded price rather than a live quote.
  // Set by the backend when the price provider could not be reached; the value is still shown,
  // marked, instead of leaving the line blank.
  priceAsOf: string | null
  priceStale: boolean
}

// --- Security insight (asset type + ETF composition) ---
export type AssetType = 'ETF' | 'STOCK' | 'CRYPTO' | 'UNKNOWN'

export interface WeightedSlice {
  label: string
  percent: number
}

export interface EtfComposition {
  companies: WeightedSlice[]
  countries: WeightedSlice[]
  sectors: WeightedSlice[]
  source: string | null
  asOf: string | null
}

export interface SecurityInsight {
  ticker: string
  assetType: AssetType
  composition: EtfComposition | null
}

/**
 * Crypto exchanges, in the order the pickers show them.
 *
 * Mirrors the backend `com.picsou.model.ExchangeType` enum *and* its `CryptoExchangePort` beans —
 * there is no codegen between them, so adding an exchange means editing both sides in the same
 * change. `requiresApiSecret` mirrors `CryptoExchangePort.requiresApiSecret()`: Meria authenticates
 * with a single read-only API key, and `CryptoExchangeSyncService` returns 400 both for a missing
 * secret where one is needed and for a stray secret where none is — so getting this wrong is a
 * loud error, not a silent bug.
 *
 * KRAKEN is listed but has no backend adapter yet: picking it returns 422 "This exchange isn't
 * supported yet."
 */
export const SUPPORTED_EXCHANGES = [
  { type: 'BINANCE', requiresApiSecret: true },
  { type: 'KRAKEN', requiresApiSecret: true },
  { type: 'MERIA', requiresApiSecret: false },
] as const

export type ExchangeType = (typeof SUPPORTED_EXCHANGES)[number]['type']

/** Whether the exchange needs an API secret on top of its API key. */
export function exchangeRequiresApiSecret(type: ExchangeType): boolean {
  return SUPPORTED_EXCHANGES.find(exchange => exchange.type === type)?.requiresApiSecret ?? true
}
/**
 * On-chain wallet chains, in the order the pickers show them.
 *
 * Mirrors the backend `com.picsou.model.Chain` enum — there is no codegen between the two, so
 * adding a chain means editing both in the same change. The backend side fails fast if you
 * forget the adapter (`WalletSyncService.verifyAdapterCoverage`); on this side a missing entry
 * shows up as a chain that never appears in the picker.
 */
export const SUPPORTED_CHAINS = ['BITCOIN', 'EVM', 'SOLANA'] as const

export type ChainType = (typeof SUPPORTED_CHAINS)[number]
export type FinaryMappingAction = 'SKIP' | 'MAP_EXISTING' | 'CREATE_NEW'

/** One line of a crypto exchange account's per-product breakdown. */
export interface ExchangePositionResponse {
  product: 'SPOT' | 'STAKING' | 'LENDING'
  ticker: string
  quantity: number
  /** Capital part of `quantity`; null when the exchange doesn't split it. */
  principal: number | null
  /** Yield *already included* in `quantity` — a decomposition, never an addition. */
  interest: number | null
  /** Unit cost basis, shared by every line of the same asset (cost is tracked per asset). */
  averageBuyIn: number | null
  currentPriceEur: number | null
  currentValueEur: number | null
  costBasisEur: number | null
  pnlEur: number | null
  pnlPercent: number | null
  /** The day `currentPriceEur` is for; null when no price could be resolved. */
  priceAsOf: string | null
  /** True when the price is the last one recorded rather than a live quote — shown, but marked. */
  priceStale: boolean
}

export interface ExchangeStatus {
  id: number
  exchangeType: ExchangeType
  status: string
  lastSyncedAt: string | null
}

export interface WalletStatus {
  id: number
  chain: ChainType
  address: string
  label: string | null
  lastSyncedAt: string | null
}

export interface TrSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

/**
 * What deleting an account costs beyond the account itself. `connectionLabel` names the
 * connection that goes with it — null when nothing else is removed.
 */
export interface AccountDeletionImpact {
  removesConnection: boolean
  connectionLabel: string | null
}

export interface IbkrConnectionStatus {
  connected: boolean
  connectionId: number | null
  status: string | null
  lastSyncedAt: string | null
  maskedToken: string | null
}

interface BoursoSessionStatusBase {
  isActive: boolean
  lastSyncStartedAt: string | null
  lastSyncCompletedAt: string | null
}

export type BoursoSessionStatus =
  | (BoursoSessionStatusBase & {
      syncStatus: 'FAILED'
      lastSyncError: BoursoErrorCode
    })
  | (BoursoSessionStatusBase & {
      syncStatus: 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS'
      lastSyncError: null
    })

/**
 * No `INVALID_OTP`: BoursoBank's app validation is the only second factor the
 * connector drives, so there is never a code to reject. An SMS or e-mail prompt
 * surfaces as `MFA_TYPE_UNSUPPORTED` instead. `FRAUD_ACK_REQUIRED` means the
 * login was parked on the bank's fraud-education notice: the credentials work,
 * the holder must tick the notice on the bank's website and retry.
 */
export type BoursoErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'FRAUD_ACK_REQUIRED'
  | 'MFA_TYPE_UNSUPPORTED'
  | 'APP_VALIDATION_TIMEOUT'
  | 'AUTH_ATTEMPT_EXPIRED'
  | 'SESSION_EXPIRED'
  | 'PORTFOLIO_INCOMPLETE'
  | 'UPSTREAM_FORMAT_CHANGED'
  | 'UPSTREAM_UNAVAILABLE'
  | 'INVALID_DATA'
  | 'INTERNAL_ERROR'

/** `mfaType` is always `APP_PUSH` when a second factor is required. */
export interface BoursoAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: 'APP_PUSH' | null
}

export type DegiroSessionStatusValue = 'ACTIVE' | 'REAUTH_REQUIRED' | 'FAILED'

export interface DegiroSessionStatus {
  isActive: boolean
  /** `null` when no session has ever been stored for this member. */
  status: DegiroSessionStatusValue | null
  lastSyncedAt: string | null
}

/**
 * A discriminated union rather than `{ processId: string | null; totpRequired: boolean }`:
 * the /complete endpoint cannot work without a processId, so the TOTP branch must not
 * type-check with a null one. The no-TOTP branch keeps it nullable — the backend has
 * nothing useful to send there and the client never reads it.
 */
export type DegiroAuthInitResponse =
  | { totpRequired: true; processId: string }
  | { totpRequired: false; processId: string | null }

interface BourseDirectSessionStatusBase {
  isActive: boolean
  expiresAt: string | null
  lastSyncStartedAt: string | null
  lastSyncCompletedAt: string | null
}

export type BourseDirectSessionStatus =
  | (BourseDirectSessionStatusBase & {
      syncStatus: 'FAILED'
      lastSyncError: BourseDirectErrorCode
    })
  | (BourseDirectSessionStatusBase & {
      syncStatus: 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS'
      lastSyncError: null
    })

export type BourseDirectErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'INVALID_OTP'
  | 'AUTH_ATTEMPT_EXPIRED'
  | 'SESSION_EXPIRED'
  | 'PORTFOLIO_INCOMPLETE'
  | 'UPSTREAM_FORMAT_CHANGED'
  | 'UPSTREAM_UNAVAILABLE'
  | 'INVALID_DATA'
  | 'INTERNAL_ERROR'

export interface BourseDirectAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: string | null
}

interface AmundiSessionStatusBase {
  isActive: boolean
  lastSyncStartedAt: string | null
  lastSyncCompletedAt: string | null
}

export type AmundiSessionStatus =
  | (AmundiSessionStatusBase & {
      syncStatus: 'FAILED'
      lastSyncError: AmundiErrorCode
    })
  | (AmundiSessionStatusBase & {
      syncStatus: 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS'
      lastSyncError: null
    })

export type AmundiErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'CAPTCHA_BLOCKED'
  | 'INVALID_OTP'
  | 'APP_VALIDATION_TIMEOUT'
  | 'AUTH_ATTEMPT_EXPIRED'
  | 'SESSION_EXPIRED'
  | 'PORTFOLIO_INCOMPLETE'
  | 'UPSTREAM_FORMAT_CHANGED'
  | 'UPSTREAM_UNAVAILABLE'
  | 'INVALID_DATA'
  | 'INTERNAL_ERROR'

/** `mfaType` is `APP_PUSH` when the user must approve in the Mon Épargne app, `SMS` otherwise. */
export interface AmundiAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: 'APP_PUSH' | 'SMS' | null
}

export interface FinaryAccountPreview {
  finaryId: string
  finaryName: string
  finaryInstitution: string
  finaryCategory: string
  suggestedType: AccountType
  currentBalance: number
  nativeCurrency: string
  transactionCount: number
}

export interface FinaryPreviewResponse {
  accounts: FinaryAccountPreview[]
  existingPicsouAccounts: Account[]
  totalTransactionCount: number
  fileToken: string
  autoMapped?: boolean
  suggestedMappings?: FinaryAccountMapping[]
}

export interface FinaryConnectionStatus {
  connected: boolean
  sessionId: number | null
  status: string | null
  lastSyncedAt: string | null
  maskedEmail: string | null
}

export interface NewAccountDetails {
  name: string
  type: AccountType
  provider?: string
  currency: string
  color?: string
}

export interface FinaryAccountMapping {
  finaryId: string
  finaryName: string
  finaryCategory: string
  action: FinaryMappingAction
  targetAccountId?: number
  newAccount?: NewAccountDetails
}

export interface FinaryImportRequest {
  mappings: FinaryAccountMapping[]
  fileToken: string
}

export interface ImportedAccountSummary {
  id: number
  name: string
  type: AccountType
  currentBalance: number
  color: string
}

export interface FinaryImportResultResponse {
  accountsCreated: number
  accountsMapped: number
  accountsSkipped: number
  snapshotsCreated: number
  transactionsImported: number
  importedAccounts: ImportedAccountSummary[]
}

export interface FinaryAutoSyncResponse {
  status: 'OK' | 'NEEDS_MAPPING' | 'TOTP_REQUIRED' | 'NOT_CONNECTED'
  accountsSynced: number
  newAccountCount: number
}

export interface Transaction {
  id: number
  date: string
  description: string
  amount: number
  type: string | null
  category: string | null
  nativeCurrency: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker: string | null
  name: string | null
  quantity: number | null
  pricePerUnit: number | null
  fees: number | null
}

export interface TransactionRequest {
  date: string          // ISO date "YYYY-MM-DD"
  description: string
  amount: number        // signed: positive=deposit, negative=withdrawal
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker?: string
  name?: string
  quantity?: number
  pricePerUnit?: number
  currency?: string
  fees?: number         // per-trade fees, folded into the PMP cost basis
}

// --- CSV transaction import (two-phase wizard) ---

export interface CsvDialectDto {
  delimiter: string
  decimal: 'DOT' | 'COMMA'
  dateFormat: string
}

export interface ColumnMappingDto {
  date: number | null
  side: number | null
  tickerOrIsin: number | null
  name: number | null
  quantity: number | null
  unitPrice: number | null
  fees: number | null
  currency: number | null
  amount: number | null
}

export interface TransactionImportPreviewResponse {
  fileToken: string
  detectedColumns: string[]
  sampleRows: string[][]
  totalRows: number
  hasHeaderRow: boolean
  dialect: CsvDialectDto
  suggestedMapping: ColumnMappingDto
}

export interface TransactionImportRequest {
  fileToken: string
  mapping: ColumnMappingDto
  dialect: CsvDialectDto
  hasHeaderRow: boolean
  feesIncludedInAmount: boolean
  sideValueMap?: Record<string, string>
}

export interface ImportRowError {
  rowNumber: number
  message: string
}

export interface TransactionImportResultResponse {
  imported: number
  skipped: number
  errors: ImportRowError[]
}

// --- Realized P&L (closed positions) ---

export interface RealizedLot {
  ticker: string
  name: string | null
  date: string
  quantity: number
  avgCost: number
  proceeds: number
  realized: number
}

export interface TickerRealized {
  ticker: string
  name: string | null
  realized: number
  quantitySold: number
  proceeds: number
  costBasis: number
  warning: boolean
}

export interface RealizedPnlResponse {
  currency: string
  realizedTotal: number
  byTicker: TickerRealized[]
  lots: RealizedLot[]
}
