export type AccountType =
  | 'LEP' | 'LIVRET_A' | 'LDDS' | 'LIVRET_JEUNE' | 'PEL' | 'CEL'
  | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS'
  | 'REAL_ESTATE' | 'SCPI' | 'LOAN' | 'EMPLOYEE_SAVINGS' | 'ASSURANCE_VIE' | 'OTHER'

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
  /**
   * When the member says the wrapper was opened — a PEA's fifth anniversary and an
   * assurance-vie's eighth turn on it. Distinct from `createdAt`, which dates the Picsou row.
   * Omitted by the API when never stated.
   */
  openedAt?: string | null
  realEstate?: RealEstateMetadata
  debt?: DebtInfo
  /** Set for Revolut pocket sub-accounts: the id of the parent Revolut wallet.
   *  Null / absent for regular top-level accounts. */
  parentAccountId?: number | null
  /** Stable external identifier (e.g. Revolut pocket UUID from "To EUR MB:<uuid>").
   *  Null / absent for regular accounts. */
  externalAccountId?: string | null
  savingsConfig?: SavingsConfig | null
  /** Display-only visibility flag; hidden account still syncs normally. */
  hidden: boolean
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
  /**
   * ISO date. **Omitting it leaves the stored value alone** — the backend cannot tell an absent
   * field from a cleared one, and treating null as "clear" would let any client that predates
   * the field wipe it. The form can change the date but not blank it.
   */
  openedAt?: string | null
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

// ─── Savings livrets ─────────────────────────────────────────────────────────

export type SavingsProduct = 'LIVRET_A' | 'LDDS' | 'LEP' | 'COMMERCIAL'
export type RateBasis = 'GROSS' | 'NET'

export interface SavingsConfig {
  product: SavingsProduct
  annualRate: number
  rateBasis: RateBasis
  taxRatePct: number | null
  ceiling: number | null
}

export interface SavingsConfigRequest {
  product: SavingsProduct
  annualRate: number
  rateBasis: RateBasis
  taxRatePct: number | null
  ceiling: number | null
}

export interface SavingsInterestProjection {
  estimatedInterestYtd: number
  projectedInterestFullYear: number
  nextCapitalizationDate: string
  annualRatePct: number
  basis: RateBasis
  netOfTax: boolean
}

export interface SavingsSuggestion {
  accountId: number
  accountName: string
  suggestedProduct: SavingsProduct
  defaultAnnualRate: number | null
  uncertain: boolean
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

export type GoalType = 'SAVINGS_TARGET' | 'RECURRING_INVESTMENT'

/** One line of a recurring plan's monthly split. */
export interface GoalAllocation {
  ticker: string
  /** The holding's name in the funded account; the ticker is what identifies the line. */
  name: string | null
  monthlyAmount: number
}

export interface GoalProgress {
  id: number
  name: string
  type: GoalType
  createdAt: string
  historyStartMonth: string | null
  accounts: Account[]
  currentTotal: number

  /**
   * The target machinery. All null for a RECURRING_INVESTMENT — discriminate on `type`, which is
   * always present, rather than on which of these happens to be missing.
   */
  targetAmount: number | null
  deadline: string | null
  percentComplete: number | null
  monthlyNeeded: number | null
  avgMonthlyContribution: number | null
  surplus: number | null

  /**
   * Primitives on the backend, so they cannot be dropped from the JSON: a recurring plan reports
   * 0 and true. Meaningless for it — never render them without checking `type` first.
   */
  monthsLeft: number
  isOnTrack: boolean

  /** RECURRING_INVESTMENT only. */
  monthlyAmount: number | null
  expectedReturn: number | null
  startDate: string | null
  endDate: string | null

  /**
   * Where the monthly amount goes. **Always an array** — empty for a savings target and for a
   * plan nobody has detailed, never omitted. The backend sends `[]` on purpose here, against its
   * own `non_null` rule, precisely so this can be mapped over without a guard.
   */
  allocations: GoalAllocation[]
}

export interface GoalRequest {
  name: string
  type: GoalType
  targetAmount: number | null
  deadline: string | null
  monthlyAmount: number | null
  expectedReturn: number | null
  startDate: string | null
  endDate: string | null
  /** Only tickers the funded account already holds; the backend answers 400 for any other. */
  allocations: { ticker: string; monthlyAmount: number }[]
  accountIds: number[]
}

// --- Analysis: wealth projection ---

export interface ProjectionPoint {
  date: string
  valueEur: number
  /** Capital in — the base plus everything paid in since, so the chart can shade the gain. */
  contributedEur: number
}

export interface ProjectionScenario {
  key: 'PESSIMISTIC' | 'CAUTIOUS' | 'REFERENCE' | 'OPTIMISTIC'
  /**
   * The effective blended rate this scenario works out to, given where the money sits and where
   * each plan sends it. Not a headline applied to everything — the same "optimistic" curve is
   * 10 % for someone fully invested and 3 % for someone whose plans mostly feed a passbook.
   */
  annualPercent: number
  /** Points added to risky assets to obtain this scenario. Cash does not have a good year. */
  riskyDelta: number
  points: ProjectionPoint[]
}

/** The mix at one horizon, under the reference scenario, beside the member's own targets. */
export interface AllocationPoint {
  date: string
  tiers: AllocationTierShare[]
}

export interface AllocationTierShare {
  tier: WealthTier
  valueEur: number
  percent: number
  targetPercent: number | null
}

export interface Projection {
  /** Investable only: no property, no loans, no alternative assets. */
  baseValueEur: number
  monthlyInflowEur: number
  years: number
  scenarios: ProjectionScenario[]
  /** Where the mix is heading — the question the pyramid asks and a total could never answer. */
  allocation: AllocationPoint[]
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
  totalMonthlyPayment: number | null
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
    monthlyPayment: number | null
    percentPaid: number | null
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

export interface RevolutSessionStatus {
  connected: boolean
  remembered: boolean
  lastSyncedAt: string | null
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
 * surfaces as `MFA_TYPE_UNSUPPORTED` instead.
 */
export type BoursoErrorCode =
  | 'INVALID_CREDENTIALS'
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
  categoryId?: number | null
  nativeCurrency: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker: string | null
  name: string | null
  quantity: number | null
  pricePerUnit: number | null
  /** Clean merchant name derived offline from the raw bank fields (null until enriched). */
  merchantLabel?: string | null
  /** Matched brand id from the offline knowledge base, or null. */
  merchantBrandId?: number | null
  /** Account the transaction belongs to (populated by cross-account endpoints). */
  accountId?: number | null
  accountName?: string | null
  /** Per-trade broker fees folded into the PMP cost basis (null when none recorded). */
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
  categoryId?: number
  fees?: number         // per-trade fees, folded into the PMP cost basis
}

// ─── Budget & Cashflow module (mirrors com.picsou.dto.*) ─────────────────────

/** Drives cashflow/envelope/allocation behaviour. Transfers feed only allocation. */
export type CategoryKind = 'INCOME' | 'EXPENSE' | 'TRANSFER'
export type RuleMatchType = 'COUNTERPARTY' | 'KEYWORD' | 'KEYWORDS_ALL' | 'KEYWORDS_ANY'
export type RuleSource = 'USER' | 'AUTO'
export type RecurringCadence = 'WEEKLY' | 'BIWEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY'
export type RecurringStatus = 'SUGGESTED' | 'CONFIRMED' | 'IGNORED'
/** Computed (never stored) urgency of a series' next due date — drives the late / due-soon badges. */
export type RecurringRuntimeStatus = 'STALE' | 'LATE' | 'DUE_SOON' | 'SCHEDULED'
/** The kind of change surfaced in the recurring "what changed" activity feed. */
export type RecurringActivityType = 'AUTO_CONFIRMED' | 'PRICE_CHANGE'
export type AssetClass = 'CURRENT' | 'SAVINGS' | 'INVESTMENT' | 'OTHER'
export type CashflowPeriod = 'CYCLE' | 'YTD'

export interface Category {
  id: number
  name: string
  kind: CategoryKind
  color: string | null
  icon: string | null
  isDefault: boolean
  archived: boolean
  sortOrder: number
  /** Parent category id, or null for a top-level category (one level of nesting only). */
  parentId: number | null
}

export interface CategoryRequest {
  name: string
  kind: CategoryKind
  color?: string
  icon?: string
  sortOrder?: number
  /** Attach as a sub-category of this parent (must share kind, be a root). null/omit = top-level. */
  parentId?: number | null
}

export interface CategorizationRule {
  id: number
  matchType: RuleMatchType
  pattern: string
  categoryId: number
  categoryName: string
  priority: number
  source: RuleSource
}

export interface CategorizationRuleRequest {
  matchType: RuleMatchType
  pattern: string
  categoryId: number
  priority?: number
}

/** Assign a category to a transaction, optionally learning a rule from it. */
export interface CategorizeRequest {
  categoryId: number
  createRule: boolean
  /** Explicit rule pattern from RuleWordPicker (KEYWORDS_ALL/KEYWORDS_ANY). When set, ruleMatchType must also be set. */
  rulePattern?: string
  /** Match type for the explicit rule pattern. */
  ruleMatchType?: RuleMatchType
  /** Cherry-pick: if non-empty, retro-apply the rule only to these transaction ids. */
  applyToTransactionIds?: number[]
}

export interface RulePreviewRequest {
  matchType: RuleMatchType
  pattern: string
}

export interface RulePreviewTransaction {
  id: number
  date: string
  label: string
  amount: number
  currentCategoryName: string | null
}

export interface RulePreviewResponse {
  matchCount: number
  transactions: RulePreviewTransaction[]
}

/** A transaction still missing a managed category (the "to categorize" inbox). */
export interface UncategorizedTransaction {
  id: number
  date: string
  description: string
  amount: number
  type: string | null
  category: string | null
  nativeCurrency: string
  createdAt: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker: string | null
  quantity: number | null
  pricePerUnit: number | null
  categoryId: number | null
  categoryName: string | null
  counterparty: string | null
  /** Clean merchant name derived offline from the raw bank fields (null until enriched). */
  merchantLabel: string | null
  /** Matched brand id from the offline knowledge base, or null. */
  merchantBrandId: number | null
  /** AI-proposed category id pending the member's confirmation, or null when there is no suggestion. */
  aiSuggestedCategoryId: number | null
  /** Self-reported confidence (0–100) attached to the AI suggestion, or null. */
  aiConfidence: number | null
}

/** A monthly envelope with its current-cycle progress (computed on read). */
export interface Budget {
  id: number
  categoryId: number
  categoryName: string
  categoryKind: CategoryKind
  categoryColor: string | null
  categoryIcon: string | null
  monthlyLimit: number
  spent: number
  remaining: number
  percent: number
  overBudget: boolean
  /** True when the category is a parent — `spent` then covers its whole subtree. */
  rollup: boolean
  cycleStart: string
  cycleEnd: string
}

export interface BudgetRequest {
  categoryId: number
  monthlyLimit: number
}

/** How an AI category suggestion is applied. Mirrors the backend AiCategorizationMode enum. */
export type AiCategorizationMode = 'SUGGEST' | 'AUTO_HIGH_CONFIDENCE' | 'AUTO_ALL'

export interface BudgetSettings {
  cycleStartDay: number
  logoFetchEnabled: boolean
  /** Master opt-in for the optional AI categorizer (OFF by default). */
  aiCategorizationEnabled: boolean
  /** How an AI suggestion is applied: suggest-only / auto on high confidence / auto-all. */
  aiMode: AiCategorizationMode
  /** Sensitivity gate (0–100) for AUTO_HIGH_CONFIDENCE. */
  aiConfidenceThreshold: number
  currentCycleStart: string
  currentCycleEnd: string
}

export interface BudgetSettingsRequest {
  cycleStartDay: number
  logoFetchEnabled: boolean
  aiCategorizationEnabled: boolean
  aiMode: AiCategorizationMode
  aiConfidenceThreshold: number
}

/** Live status of an async AI categorization job. */
export interface AiJobStatus {
  running: boolean
  total: number
  processed: number
  applied: number
  suggested: number
  done: boolean
  error: string | null
}

/**
 * A pocket/vault/wallet found during a Revolut discovery pass, held server-side until
 * confirmed. `type` mirrors `AccountType` but Revolut only ever discovers checking/savings.
 * `parentExternalId` groups pockets/vaults under their parent wallet.
 */
export interface DiscoveredRevolutAccount {
  externalId: string
  name: string
  type: 'CHECKING' | 'SAVINGS'
  currency: string
  balance: number
  parentExternalId: string | null
  alreadyImported: boolean
  transactionCount: number
}

/**
 * Live progress of a background bank sync job (Revolut now, Trade Republic later).
 * `phase` is a provider-specific string (see `RevolutSyncPhase` on the backend) — the
 * frontend maps it to an i18n key. `discovered` is only populated once `done` for a
 * Revolut sync that requires account selection; empty otherwise.
 */
export interface SyncProgress {
  running: boolean
  phase: string | null
  elapsedSeconds: number | null
  remainingSeconds: number | null
  accountsFound: number | null
  done: boolean
  error: string | null
  discovered: DiscoveredRevolutAccount[]
}

export interface CashflowBucket {
  start: string
  end: string
  label: string
  income: number
  expense: number   // positive magnitude
  net: number
}

export interface CashflowResponse {
  period: CashflowPeriod
  from: string
  to: string
  income: number
  expense: number   // positive magnitude
  net: number
  series: CashflowBucket[]
}

/** Sankey node role — drives colour/position; HUB and SAVINGS/drawdown are synthetic. */
export type FlowNodeType = 'INCOME' | 'HUB' | 'EXPENSE' | 'SAVINGS'

/**
 * One node in the income→budget→expense Sankey. `key` is `cat:<id>` for a real category,
 * or a `__…__` sentinel for a synthetic node (hub, "other income", savings, drawdown,
 * uncategorized, rolled-up tail). Synthetic nodes carry `label`/`color` null and are
 * labelled/coloured on the frontend.
 */
export interface FlowNode {
  key: string
  label: string | null
  color: string | null
  type: FlowNodeType
}

/** A weighted edge: indices into the response's `nodes` array. */
export interface FlowLink {
  source: number
  target: number
  value: number
}

export interface CashflowFlowResponse {
  period: CashflowPeriod
  from: string
  to: string
  income: number
  expense: number   // positive magnitude
  net: number
  nodes: FlowNode[]
  links: FlowLink[]
}

/**
 * One row of the ranked expense breakdown. `categoryId`/`slug`/`name` null = uncategorized.
 * Rows are always leaf-scoped (no double-counting); `parent*` lets the client group a subtree.
 * `parentId` null = a root category or the uncategorized bucket.
 */
export interface CategorySpend {
  categoryId: number | null
  slug: string | null
  name: string | null
  color: string | null
  icon: string | null
  amount: number    // positive magnitude
  count: number
  share: number     // fraction of totalExpense, 0..1 (4 decimals)
  parentId: number | null
  parentName: string | null
  parentColor: string | null
}

export interface SpendingByCategoryResponse {
  period: CashflowPeriod
  from: string
  to: string
  totalExpense: number
  categories: CategorySpend[]
}

/** Per-child rollup shown above the transaction list when drilling a parent. `total` signed. */
export interface ChildSpend {
  categoryId: number
  name: string
  color: string | null
  icon: string | null
  total: number     // signed sum
  count: number
}

/**
 * A single category's transactions over the period (the spending drill page). When the
 * category is a parent, `total`/`count`/`transactions` span its whole subtree and `children`
 * carries the per-child rollup; for a leaf category `children` is empty.
 */
export interface SpendingDetailResponse {
  categoryId: number
  slug: string | null
  name: string
  color: string | null
  icon: string | null
  period: CashflowPeriod
  from: string
  to: string
  total: number     // signed sum
  count: number
  transactions: Transaction[]
  children: ChildSpend[]
}

export interface AllocationStock {
  assetClass: AssetClass
  amount: number
  percent: number
}

export interface AllocationContribution {
  accountId: number
  accountName: string
  assetClass: AssetClass
  color: string | null
  amount: number
}

export interface AllocationResponse {
  period: CashflowPeriod
  from: string
  to: string
  totalStock: number
  stock: AllocationStock[]
  totalContributions: number
  contributions: AllocationContribution[]
}

export interface RecurringSeries {
  id: number
  label: string
  counterparty: string | null
  expectedAmount: number   // signed
  cadence: RecurringCadence
  status: RecurringStatus
  nextDueDate: string | null
  lastSeenDate: string | null
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
  // ── Detection v2 (M3) ──
  confidence: number | null          // 0–1; null for a manually-declared series
  amountMin: number | null           // observed amount envelope (signed)
  amountMax: number | null
  variable: boolean                  // amount legitimately drifts each period (e.g. a utility bill)
  previousAmount: number | null      // expected amount before the last price step
  priceChangedAt: string | null      // ISO date the expected amount last moved
  autoConfirmed: boolean             // confirmed silently by the detector, not the user
  runtimeStatus: RecurringRuntimeStatus
}

export interface RecurringSeriesRequest {
  label: string
  counterparty?: string
  expectedAmount: number
  cadence: RecurringCadence
  nextDueDate?: string
  categoryId?: number
}

export interface RecurringOccurrence {
  seriesId: number
  label: string
  counterparty: string | null
  expectedAmount: number
  dueDate: string
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
}

/**
 * One entry in the recurring "what changed" activity feed — derived from series state, not a stored
 * log. {@link RecurringActivityType#PRICE_CHANGE} carries the pre-change `previousAmount`; an
 * {@link RecurringActivityType#AUTO_CONFIRMED} entry leaves it null. Each entry is reversible.
 */
export interface RecurringActivity {
  seriesId: number
  label: string
  type: RecurringActivityType
  occurredOn: string | null
  expectedAmount: number
  previousAmount: number | null
  cadence: RecurringCadence
  categoryId: number | null
  categoryName: string | null
  categoryColor: string | null
  categoryIcon: string | null
}

export interface AiCallLog {
  id: number
  createdAt: string
  memberId: number | null
  transactionId: number | null
  merchantLabel: string | null
  batchId: string | null
  provider: string
  model: string | null
  prompt: string | null
  response: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  status: string
  error: string | null
  chosenSlug: string | null
  confidence: number | null
  applied: boolean
}

export interface AiCallLogPage {
  items: AiCallLog[]
  total: number
  totalTokens: number
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

// --- Analysis: the investment pyramid ---

export type WealthTier = 'SAFETY_NET' | 'REAL_ESTATE' | 'EQUITY' | 'CRYPTO' | 'ALTERNATIVE'

export interface TierAccount {
  accountId: number
  name: string
  color: string
  valueEur: number
}

/** Only the four investment tiers appear; the cushion is measured in euros, not as a share. */
export interface WealthTierLine {
  tier: Exclude<WealthTier, 'SAFETY_NET'>
  valueEur: number
  actualPercent: number
  targetPercent: number
  /** What the target percentage is worth today — a gap in euros is actionable, points are not. */
  targetEur: number
  gapPercent: number
  accounts: TierAccount[]
}

export interface SafetyNetLine {
  /** Savings passbooks only — a current account is not an emergency fund. */
  valueEur: number
  /** Current-account money: reported so it is visible, scored nowhere. */
  dailyCashEur: number
  /** null until the member states their monthly expenses. */
  targetEur: number | null
  coverage: number | null
  excessEur: number
  known: boolean
  score: number | null
}

export interface WealthScore {
  /** Null when neither sub-score could be computed — nothing to allocate and no stated expenses. */
  global: number | null
  /** Null when nothing is allocatable. Having no allocation is not a perfect allocation. */
  allocation: number | null
  misplacedPercent: number
  cryptoPenalty: number
  leverageBonus: number
  cryptoTopTenShare: number | null
  loanToValue: number | null
}

/**
 * An observation about the portfolio's shape that holds whatever the member's targets say.
 *
 * The score measures conformity to self-chosen targets, so it cannot question the targets. These
 * come from the portfolio alone and cannot be silenced by editing one.
 */
export interface WealthAlert {
  code: 'SINGLE_ASSET_CONCENTRATION' | 'EMPTY_TIER' | 'CUSHION_OVERFUNDED'
  label: string | null
  valueEur: number
  percent: number
}

export interface WealthPyramid {
  totalAssetsEur: number
  allocatableEur: number
  safetyNet: SafetyNetLine
  tiers: WealthTierLine[]
  score: WealthScore
  alerts: WealthAlert[]
}

export interface AllocationTargets {
  monthlyEssentialExpenses: number | null
  safetyNetMonths: number
  realEstatePct: number
  equityPct: number
  cryptoPct: number
  alternativePct: number
}

export type AllocationTargetsRequest = AllocationTargets

export interface EssentialExpenseEstimate {
  estimate: number | null
  monthsObserved: number
  excludedTransferCount: number
}

// --- Analysis: sector and geographic diversification ---

/**
 * What a country breakdown is measuring. An ETF's countries are look-through *exposure*; a
 * directly held share contributes its *domicile*. Once both are present the bar mixes two
 * different quantities, and says so.
 */
export type DiversificationBasis = 'EXPOSURE' | 'MIXED'

export interface DiversificationBreakdown {
  score: number
  effectiveCount: number
  targetCount: number
  basis: DiversificationBasis
  /**
   * What this axis alone could place. Not the same as the other axis's: a share often has a
   * known sector and no domicile, and a fund may disclose its countries far more completely than
   * its sectors. The top-level coveragePercent reports the more generous of the two.
   */
  classifiedValueEur: number
  coveragePercent: number
  slices: DiversificationSlice[]
}

/**
 * One bar of a breakdown, with the holdings behind it.
 *
 * Distinct from WeightedSlice, which is shared with the single-security insight modal where a
 * contributor means nothing.
 */
export interface DiversificationSlice {
  label: string
  percent: number
  valueEur: number
  contributors: SliceContributor[]
  /** The real number of holdings, which exceeds contributors.length once the tail is folded. */
  contributorCount: number
}

/** One holding's share of one slice — why "France" is 8.4 %. */
export interface SliceContributor {
  /** Null on the folded tail of small contributors, rendered as "and N others". */
  ticker: string | null
  valueEur: number
  sharePercent: number
}

/** Every security appearing as a contributor, once, so slices need not repeat its name. */
export interface DiversificationSecurity {
  ticker: string
  name: string | null
  accountId: number | null
  valueEur: number
}

/** A holding a breakdown could not fully place, with what the editor needs to fix it. */
export interface UnclassifiedLine {
  ticker: string
  name: string | null
  /** An account holding it — the write is account-scoped because ownership authorises it. */
  accountId: number | null
  valueEur: number
  sectorMissing: boolean
  countryMissing: boolean
  /** False means no provider lookup has run yet, so a refresh may still fix it on its own. */
  profileLooked: boolean
}

export interface Diversification {
  totalValueEur: number
  classifiedValueEur: number
  unclassifiedValueEur: number
  coveragePercent: number
  unclassified: UnclassifiedLine[]
  sectors: DiversificationBreakdown
  countries: DiversificationBreakdown
  securities: DiversificationSecurity[]
}

export interface HoldingClassificationRequest {
  wealthTier: WealthTier | null
  sectorKey: string | null
  countryKey: string | null
}

export interface HoldingClassificationResponse {
  ticker: string
  wealthTier: WealthTier | null
  sectorKey: string | null
  countryKey: string | null
}

/**
 * What the editor opens on. The member's override and the providers' guess are separate: a form
 * pre-filled with a guess cannot tell you whether you are confirming it or reading your own
 * earlier decision, and saving it would freeze the guess in place forever.
 */
export interface HoldingClassificationView {
  ticker: string
  wealthTier: WealthTier | null
  sectorKey: string | null
  countryKey: string | null
  inferredSectorKey: string | null
  inferredCountryKey: string | null
  profileLooked: boolean
}

export interface SecurityProfileRefresh {
  queuedTickers: number
  alreadyRunning: boolean
}

// --- Member profile (personal + fiscal context) ---

export type HouseholdStatus = 'SINGLE' | 'COUPLE'
export type RiskProfile = 'PRUDENT' | 'BALANCED' | 'DYNAMIC' | 'AGGRESSIVE'

/**
 * Every field is nullable and stays that way: null means "never stated", which is not the same
 * as zero. The API omits nulls, so read these with `== null`, never `=== null`.
 */
export interface MemberProfile {
  birthDate: string | null
  /** Derived server-side from `birthDate`, so it cannot go stale in a cache. */
  age: number | null
  marginalTaxRate: number | null
  householdStatus: HouseholdStatus | null
  taxHouseholdParts: number | null
  dependents: number | null
  /** Gross: the figure a payslip states. Fiscal context; nothing is computed from it. */
  annualGrossIncome: number | null
  /** The payslip's "net à payer avant impôt" — after contributions, before withholding. */
  monthlyNetBeforeTax: number | null
  /** Taux de prélèvement à la source, in percent. */
  withholdingTaxRate: number | null
  /**
   * What reaches the account: `monthlyNetBeforeTax × (1 − withholdingTaxRate)`, derived
   * server-side. **Null unless both inputs are stated** — a blank rate means "not said", not
   * zero, so the savings rate is withheld rather than built on a guess.
   */
  monthlyNetIncome: number | null
  monthlySavingsCapacity: number | null
  targetRetirementAge: number | null
  riskProfile: RiskProfile | null
}

/** A full replacement: a null field clears what was stored. */
export type MemberProfileRequest = Omit<MemberProfile, 'age' | 'monthlyNetIncome'>
