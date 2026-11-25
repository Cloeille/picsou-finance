# Dependency map: Java backend (com.picsou) for the Rust rewrite tracks

Method: every class under `backend/src/main/java/com/picsou` (100,942 lines, 71 controllers) parsed; edges are constructor/field-injected types (expanded transitively), static calls and `new` (counted but NOT expanded, they need no bean), entity mentions (terminal), and port-to-implementation edges (interface to `implements` class). Roots per track = the controllers in plan.md; T8 = `mcp/tools/*`, SchedulerService, CatchUpScheduler, StartupSyncService, runners. Line counts are `wc -l` of the file. Config/filter classes (SecurityConfig etc.) are not traversed; they belong to F.

Caveats that change the reading (all verified in source):
- `AccountService` (1396 lines) is the hub. Everything it injects shows up as "used by T2-T7" because T3-T7 controllers/services reach it. Real direct controller use is only AccountController, but sync services (T5/T6), HistoryService, GoalService, PeerLoanService, DashboardService, FamilyViewService, AllocationService, MCP tools all inject it.
- `AccountConnectionService` (T2, delete-account flow) injects ALL 18 sync services (T5/T6) to call `lockMemberForAccountDeletion` on Corum/Louve; I pruned that edge. It is a real T2 to T5/T6 coupling: expose a small `ConnectionLocks` trait in L, or move the two calls behind it.
- `AccountService` compile-time constants: `PROVIDER_VALUED` reads `BourseDirectSyncService.PROVIDER, AmundiSyncService.PROVIDER, CmesSyncService.PROVIDER, NatixisSyncService.PROVIDER, BoursoSyncService.PROVIDER, FortuneoSyncService.PROVIDER, BnpSyncService.PROVIDER/HELLO_BANK_PROVIDER`; hoist these provider-name constants into L.
- `CaisseEpargneSyncService.isInsurerContract(Account)` is a static used by AccountService (lines 793,1133,1311) and HistoryService (148,342,563,699): extract to L, otherwise T2 depends on the whole BPCE engine (CaisseEpargneSyncService, BpceSyncEngine 1378, CaisseEpargnePerService).
- `AccountController` calls `CryptoExchangeSyncService.getPositions` (line 170): T2 to T5 coupling (small; expose via a read repo in L: `crypto_exchange_position`).
- `BankLogoResolver` (AccountService) injects `BankConnectorPort`, which drags EnableBankingBankConnector (943) and PowensBankConnector (360) in. Only the logo URL lookup is needed.

Track key: F foundation, T1 identity, T2 accounts/family, T3 budget, T4 wealth/market, T5 sync, T6 connectors, T7 transfer, T8 automation (jobs/mcp/ai). L = proposed ledger.

## 1a. Classes used by ONE track only (service/adapter/repository/port/export/imports/finary/ibkr), per track

### F: 3 classes, 327 lines
ActivationService 75, CryptoKeyGeneratorService 115, IntegrationsHealthService 137

### T1: 7 classes, 912 lines
AccessKeyRepository 32, AdminIdentityService 44, EnableBankingCallLogger 50, InstanceStatusService 386, ReleaseCheckService 226, SidecarTarget 11, SignInMethodsService 163

### T2: 21 classes, 4080 lines
AccountConnectionService 393, AccountOwnershipService 174, AllocationTargetService 73, DashboardService 300, EssentialExpenseEstimator 236, FamilyService 293, FamilyViewService 213, HoldingClassificationRepository 16, HoldingClassificationService 100, InstitutionLogoService 100, MemberAllocationProfileRepository 12, PortfolioDiversificationService 424, ProjectionService 447, RealizedPnlService 138, SecurityInsightService 171, SecurityProfileRefreshRunner 90, SecurityProfileService 316, SharedResourceRepository 13, SharingSettingsRepository 11, UsernameDeriver 48, WealthPyramidService 512

### T3: 11 classes, 1783 lines
AiCategorizationJobService 225, AllocationService 190, BudgetRepository 19, BudgetService 158, CashflowFlowService 509, CashflowService 119, DynamicTransactionCategorizer 27, MerchantLogoService 93, NoopCategorizer 24, RecurringSeriesService 248, SpringAiCategorizer 171

### T4: 6 classes, 1743 lines
NameBasedSavingsBookDetector 110, PeerCounterpartyRepository 16, PeerLoanService 1163, SavingsBookDetector 29, SavingsInterestService 277, SavingsService 148

### T5: 51 classes, 8092 lines
BankTransactionImportService 428, BoundedUtf8Body 92, CategorizedFinaryAccount 13, FinaryAccountCurrency 10, FinaryAccountDto 42, FinaryAccountInstitution 11, FinaryApiClient 549, FinaryApiSyncService 637, FinaryAssetRefDto 14, FinaryHoldingsImporter 347, FinaryLoanDto 32, FinaryPositionDto 35, FinarySecurityRefDto 15, FinaryTransactionAccount 11, FinaryTransactionCategory 10, FinaryTransactionCurrency 10, FinaryTransactionDto 18, IbkrConnectionRepository 13, IbkrFlexClient 434, IbkrStatusWriter 40, IbkrSyncService 460, LouveAdapter 299, LouveClientRegistry 89, LouveConnectionRepository 43, LouveMcpClient 328, LouveOAuthClient 318, LouvePortfolioCodes 53, LouvePortfolioMapper 215, LouveSyncService 502, LouveTransport 84, RequisitionLifecycleWriter 49, SimplefinClient 114, SimplefinConnectionRepository 12, SimplefinHoldings 187, SimplefinJson 280, SimplefinPort 81, SimplefinStatusWriter 30, SimplefinSyncService 453, SimplefinTransport 12, SimplefinUrls 138, SyncService 606, SyncSessionData 20, WalletPort 44, WalletSyncService 404, WiseClient 120, WiseConnectionRepository 12, WiseJson 77, WisePort 28, WiseStatusWriter 39, WiseSyncService 222, WiseTransport 12

### T6: 63 classes, 20025 lines
AmexAdapter 178, AmexSyncService 801, AmundiAdapter 117, AmundiSyncService 485, BalanceHistoryReconstructor 350, BanquePopulaireAdapter 82, BanquePopulairePort 17, BanquePopulaireRegion 36, BanquePopulaireSyncService 381, BnpAdapter 290, BnpSyncService 766, BourseDirectAdapter 89, BourseDirectSyncService 465, BoursoAdapter 178, BoursoSyncService 797, BpceBank 43, BpcePort 179, BpceSidecarAdapter 233, BpceSyncEngine 1379, CaisseEpargneAdapter 77, CaisseEpargnePerService 329, CaisseEpargnePort 17, CicAdapter 197, CicSyncService 561, CmesAdapter 148, CmesSyncService 741, CorumAdapter 230, CorumLifeOperationWriter 304, CorumLifeWriter 437, CorumSyncService 521, DegiroAdapter 237, DegiroSessionStatusWriter 44, DegiroSyncService 325, FortuneoAdapter 157, FortuneoSyncService 906, FortuneoTransactionWriter 61, InternalTransferPairingService 202, LbpAdapter 331, LbpSyncService 993, NatixisAdapter 157, NatixisSyncService 1041, ReceivedDividendClassifier 50, RevolutAdapter 207, RevolutPort 91, RevolutSyncService 645, SavingsAutoConfigService 79, SharedSidecarTransactionImportService 211, SidecarAuthenticationException 30, SidecarBaseUrl 117, SidecarClient 203, SidecarInitiate 13, SidecarSyncJob 8, SidecarSyncStrategy 41, SidecarSyncTemplate 369, SidecarTransaction 26, SidecarWebClientFactory 120, SofidyAdapter 169, SofidySyncService 448, SyncProgressService 152, SyncProvider 8, SyncValues 113, TradeRepublicAdapter 1049, TradeRepublicSyncService 994

### T7: 31 classes, 6263 lines
AccountExportData 33, AccountSheetWriter 314, AccountsWorkbookService 538, ActualBudgetDatabaseReader 504, ActualBudgetFileParser 199, ActualBudgetImportService 742, CsvDialect 10, CsvDialectDetector 113, CsvReader 88, CsvValueParser 68, CsvWriter 64, DataExportService 143, DebtExportData 36, DecimalStyle 14, ExportContext 15, FinaryImportService 356, HomeBankFileParser 457, HomeBankImportService 746, HomeBankQifParser 505, ImportPreviewStore 137, LabelKey 183, ParsedActualBudget 35, ParsedHomeBankData 31, PeerLoanExportData 35, SavingsRateCalculator 69, SheetCursor 158, SheetLabels 76, TransactionAmountCalculator 44, TransactionImportService 320, TransactionRowMapper 168, WorkbookStyles 62

### Reached only from T8 (jobs/mcp/ai), no domain controller: 7 classes
NasdaqDividendProvider 172, MemberSyncService 310, CatchUpScheduler 157, SchedulerService 355, DividendHistoryService 559, SyncStatusService 372, NasdaqDividend 18

### Reached by no controller/T8 root (wired via Spring collections/providers; assign to their natural track)
GoalContributorRepository 12, JustEtfClient 55, SolanaWalletAdapter 147, BoursoramaCompositionProvider 206, BinanceAdapter 117, EvmWalletAdapter 372, BoursoramaEquityProfileProvider 92, BoursoramaLabels 80, MeriaAdapter 454, BoursoramaClient 88, BitcoinWalletAdapter 150, JustEtfProvider 199, JustEtfLabels 105, PeerCounterpartiesExporter 68, BankConnectionsExporter 80, LinkedIdentitiesExporter 67, AccountsExporter 94, WalletAddressesExporter 66, ProfileExporter 79, EntityExporter 45, GoalsExporter 151, PeerLoanRepaymentsExporter 85, PropertyValuationsExporter 104, DebtsExporter 88, BalanceSnapshotsExporter 110, HoldingsExporter 94, SharedResourcesExporter 67, TransactionsExporter 99, AccountOwnershipExporter 74, PeerLoanTermsExporter 110, RegulatedRates 37, SidecarSessionView 12, CryptoExchangePort 114, EtfCompositionProvider 24, EquityProfileProvider 25, FinaryOrganization 12, FinaryApiError 10, FinaryOrgUser 10, FinaryOrgMember 11, FinaryEnvelope 11, JsonRpcResponse 50, BitcoinKeyUtils 316, SidecarSessionState 5, SsoIntent 36, SsoFlowException 17, SsoRoleSyncService 90, RevolutSyncPhase 16

Notes: exporters (`export/*Exporter`, 16 classes) are injected as `List<EntityExporter>` into `DataExportService` (MeExportController, T7), and `AccountsWorkbookService` + `export/xlsx` serve AccountExportController (T7). `NasdaqDividendProvider`, `JustEtf*`, `Boursorama*` providers are reached through ports (EquityProfileProvider/EtfCompositionProvider/DividendHistory): T4 or T2 SecurityProfileService. Wallet adapters (Evm/Solana/Bitcoin/Meria/Binance) are picked by WalletPort/CryptoExchangePort collections: T5.

## 1b. Classes used by TWO OR MORE tracks (the shared "ledger" candidates): 124 classes, 21805 lines

Format: Class | lines | tracks | what it does. Sorted by breadth. "T8" additionally means jobs/mcp use it.

- AppUserRepository | 42 | all +T8 | app_user queries (login lookup, member link, admin flags)
- FamilyMemberRepository | 21 | all +T8 | family_member lookup and row lock; every ingestion path resolves the member here
- UserContext | 86 | all +T8 | resolves current AppUser/FamilyMember/admin flag from SecurityContext (member override for admin impersonation)
- AccountAccessResolver | 211 | T2-T7 (via AccountService) +T8 | readable/owned account ids and ownership share percent per member (sharing + ownership rules)
- AccountDisplayPrefRepository | 49 | T2-T7 (via AccountService) +T8 | per-member account hidden/order/collapsed prefs
- AccountHoldingRepository | 113 | T2-T7 (via AccountService) +T8 | account_holding queries (by account, ticker, delete/flush)
- AccountOwnershipRepository | 34 | T2-T7 (via AccountService) +T8 | account_ownership share rows
- AccountRepository | 208 | T2-T7 (via AccountService) +T8 | account queries incl. iban/externalAccountId, soft-deleted checks, member scoping
- AccountService | 1396 | T2-T7 (via AccountService) +T8 | account CRUD, upsertSnapshot*/upsertHolding/pruneHoldings, valuation(), liveBalanceEur, toResponse, real-estate/debt metadata; the hub
- BalanceSnapshotRepository | 71 | T2-T7 (via AccountService) +T8 | balance_snapshot find-by-account+date, ranges
- BankConnectorPort | 127 | T2-T7 (via AccountService) +T8 | SPI for Enable Banking/Powens: institutions, balances, fetchTransactions
- BankLogoResolver | 121 | T2-T7 (via AccountService) +T8 | resolves bank logo URL through BankConnectorPort institution catalog (only reason AccountService drags the connectors in)
- CaisseEpargneSyncService | 356 | T2-T7 (via AccountService) +T8 | CE/BPCE sync; shared only through static isInsurerContract(account)
- CategoryRepository | 25 | T2-T7 (via AccountService) +T8 | category rows per member
- CoinGeckoPriceProvider | 751 | T2-T7 (via AccountService) +T8 | crypto prices/history/intraday via CoinGecko
- CompositePriceProvider | 211 | T2-T7 (via AccountService) +T8 | ordered chain of price providers (Yahoo, CoinGecko, metals)
- CryptoAssetRegistry | 148 | T2-T7 (via AccountService) +T8 | ticker to CoinGecko-id registry, learns/persists crypto_asset
- CryptoAssetRepository | 24 | T2-T7 (via AccountService) +T8 | crypto_asset table
- CryptoLogoService | 127 | T2-T7 (via AccountService) +T8 | crypto logo URLs for holdings/accounts
- DebtRepository | 24 | T2-T7 (via AccountService) +T8 | debt (loan terms) per account
- DividendHistory | 23 | T2-T7 (via AccountService) +T8 | record in port package, dividend history DTO
- EnableBankingBankConnector | 944 | T2-T7 (via AccountService) +T8 | Enable Banking HTTP connector (JWT signing, sessions)
- FinaryPersistenceHelper | 408 | T2-T7 (via AccountService) +T8 | Finary import persistence + computeManualLedgerBalance/reconstructSnapshotsFromDb used by AccountService
- InstrumentLogoPort | 53 | T2-T7 (via AccountService) +T8 | logo provider SPI for instruments
- InstrumentLogoRepository | 44 | T2-T7 (via AccountService) +T8 | instrument_logo cache
- InstrumentLogoService | 275 | T2-T7 (via AccountService) +T8 | instrument logo resolution and cache
- LoanAmortizationService | 281 | T2-T7 (via AccountService) +T8 | pure loan schedule math, remaining balance (valuation of debt accounts)
- LogoProviderPort | 36 | T2-T7 (via AccountService) +T8 | generic logo provider SPI
- PeerLoanCalculator | 331 | T2-T7 (via AccountService) +T8 | pure peer-loan accrual/interest math, used by valuation
- PeerLoanRepaymentRepository | 30 | T2-T7 (via AccountService) +T8 | peer_loan_repayment rows
- PeerLoanTermsRepository | 48 | T2-T7 (via AccountService) +T8 | peer_loan_terms rows
- PeerLoanTransactionLinks | 79 | T2-T7 (via AccountService) +T8 | annotates transactions with peer-loan links
- PowensBankConnector | 361 | T2-T7 (via AccountService) +T8 | Powens connector (alternative BankConnectorPort impl)
- PreciousMetalPriceProvider | 304 | T2-T7 (via AccountService) +T8 | gold/silver spot and product prices
- PriceProviderPort | 63 | T2-T7 (via AccountService) +T8 | SPI: quotes, FX, history, intraday
- PriceService | 609 | T2-T7 (via AccountService) +T8 | price+FX facade: getQuotes, getFxRateEur, toEur, refresh, backfill, price cache (609)
- PriceSnapshotRepository | 91 | T2-T7 (via AccountService) +T8 | price_snapshot (price + FX history)
- PropertyValuationRepository | 21 | T2-T7 (via AccountService) +T8 | property_valuation history
- RealEstateMetadataRepository | 11 | T2-T7 (via AccountService) +T8 | real_estate_metadata per account
- SavingsInterestConfigRepository | 39 | T2-T7 (via AccountService) +T8 | savings_interest_config per account
- ScpiFundReconciler | 387 | T2-T7 (via AccountService) +T8 | reconciles SCPI provider funds with positions
- ScpiFundService | 135 | T2-T7 (via AccountService) +T8 | SCPI fund catalog, tombstones, withdrawal values
- ScpiPositionRepository | 65 | T2-T7 (via AccountService) +T8 | scpi_position rows
- ScpiPositionService | 207 | T2-T7 (via AccountService) +T8 | SCPI position CRUD + withdrawalValue
- ScpiProviderFundRepository | 19 | T2-T7 (via AccountService) +T8 | scpi_provider_fund catalog
- TransactionRepository | 436 | T2-T7 (via AccountService) +T8 | transaction queries: dedupe lookups, windows, exists, aggregates (436)
- YahooCooldown | 64 | T2-T7 (via AccountService) +T8 | Yahoo rate-limit cooldown state
- YahooFinancePriceProvider | 663 | T2-T7 (via AccountService) +T8 | Yahoo quotes/history/FX
- YahooQuotePageLogoProvider | 275 | T2-T7 (via AccountService) +T8 | logo scraped from Yahoo quote page
- YahooQuotePageParser | 154 | T2-T7 (via AccountService) +T8 | parser for the Yahoo quote page
- BudgetSettingsRepository | 12 | T2-T6 (via AccountService/categorization) +T8 | budget_settings per member
- CategorizationRuleRepository | 20 | T2-T6 (via AccountService/categorization) +T8 | categorization_rule ordered by priority
- CategorizationService | 600 | T2-T6 (via AccountService/categorization) +T8 | rule CRUD + enrich/apply/autoCategorize pipeline used by every ingestion (600)
- CategoryService | 268 | T2-T6 (via AccountService/categorization) +T8 | category CRUD, ensureSeeded, findOrCreateDefault
- MerchantAliasRepository | 9 | T2-T6 (via AccountService/categorization) +T8 | merchant_alias
- MerchantBrandRepository | 9 | T2-T6 (via AccountService/categorization) +T8 | merchant_brand
- MerchantKnowledgeBase | 144 | T2-T6 (via AccountService/categorization) +T8 | offline brand KB, merchant label to brand/default category
- MerchantNormalizer | 128 | T2-T6 (via AccountService/categorization) +T8 | counterparty/description normalizer to merchant label and match key
- OpenFigiIsinConverter | 449 | T2,T4,T5,T6,T7 +T8 | ISIN/ticker conversion via OpenFIGI
- SymbolCatalogPort | 39 | T2,T4,T5,T6,T7 +T8 | SPI for ISIN to symbol lookups
- TransactionCategorizerPort | 73 | T2-T6 (via AccountService/categorization) +T8 | AI categorizer SPI
- SidecarErrorCode | 93 | F,T1,T5,T6 +T8 | enum of sidecar error codes and mappings
- UserMfaRepository | 14 | F,T1,T2,T7 +T8 | user_mfa rows
- AppSettingRepository | 44 | F,T1,T5 +T8 | app_setting key/value store (telemetry, CORS, cookies, AI/EB config)
- FinarySessionRepository | 14 | F,T1,T5 +T8 | finary_session rows
- GoalManualContributionRepository | 14 | T2,T4,T7 +T8 | goal manual contributions
- GoalMonthOverrideRepository | 13 | T2,T4,T7 +T8 | goal month overrides
- GoalRepository | 25 | T2,T4,T7 +T8 | goal rows
- GoalService | 591 | T2,T4,T7 +T8 | goal progress/allocation computing over account values
- HistoryService | 714 | T2,T4,T7 +T8 | net-worth/account history, chart series (714)
- HoldingComputeService | 113 | T2,T4,T7 +T8 | recomputes holdings from BUY/SELL transactions (VWAP)
- InstrumentFieldResolver | 70 | T2,T4,T7 +T8 | resolves ticker/ISIN/name for typed transactions
- LbpSessionRepository | 49 | F,T1,T6 +T8 | lbp_session rows
- MfaService | 268 | F,T1,T7 +T8 | TOTP enrol/verify/recovery codes
- SecurityIdentityService | 100 | T2,T5,T6 +T8 | ISIN/ticker identity for security_profile
- SecurityProfileRepository | 34 | T2,T5,T6 +T8 | security_profile rows
- SidecarBank | 341 | F,T1,T6 +T8 | enum of 17 sidecar banks with URLs/labels/paths (341)
- SidecarSessionRepository | 107 | F,T1,T6 +T8 | sidecar_session rows and locking queries
- SourceSyncResult | 32 | T2,T5,T6 +T8 | record: per-source sync outcome
- UserIdentityRepository | 42 | F,T1,T2 +T8 | user_identity (SSO links)
- UserMfaRecoveryCodeRepository | 22 | F,T1,T7 +T8 | MFA recovery codes
- AiCallLogRepository | 22 | T1,T3 +T8 | ai_call_log rows
- AiCallLogService | 49 | T1,T3 +T8 | records AI call logs
- BudgetCycle | 51 | T2,T3 +T8 | budget cycle date math
- BudgetSettingsService | 91 | T2,T3 +T8 | budget settings (cycle start etc.)
- CeremaDv3fValuationProvider | 330 | T2,T4 +T8 | DVF/Cerema property valuation provider
- CryptoExchangePositionRepository | 29 | T2,T5 +T8 | crypto_exchange_position rows
- CryptoExchangeSessionRepository | 19 | T2,T5 +T8 | crypto_exchange_session rows
- CryptoExchangeStatusWriter | 56 | T2,T5 +T8 | writes exchange connection status
- CryptoExchangeSyncService | 573 | T2,T5 +T8 | exchange sync (Binance); AccountController also calls getPositions
- DegiroSessionRepository | 11 | T1,T6 +T8 | degiro_session rows
- DividendForecastService | 619 | T4,T6 +T8 | dividend forecast over holdings
- DividendReceiptService | 588 | T4,T6 +T8 | matches received dividends to transactions; classifyRecent
- DividendSourceRepository | 26 | T4,T6 +T8 | dividend_source rows
- DuckDuckGoLogoProvider | 76 | T2,T3 | merchant logo via DuckDuckGo
- EnableBankingKeyPairService | 201 | F,T1 | generates/stores Enable Banking RSA keypair
- FinaryAccountMatcher | 164 | T5,T7 +T8 | matches Finary accounts to local accounts
- GeocodingPort | 56 | T2,T4 +T8 | geocoding SPI
- GeoplateformeGeocoder | 183 | T2,T4 +T8 | IGN Geoplateforme geocoder
- GroupsClaimParser | 33 | F,T1 +T8 | SSO groups claim parsing
- HoldingDedup | 84 | T5,T6 +T8 | HoldingAgg + vwapMerge(prev,next) weighted merge of duplicate positions
- InseeBdmIndexProvider | 231 | T2,T4 +T8 | INSEE index series provider
- IntegrationsService | 141 | F,T1 | integration status/config for setup and admin
- ManualTransactionService | 170 | T2,T4 +T8 | manual transaction add/update/delete; categorize, recompute holdings
- MemberProfileRepository | 13 | T1,T7 | member_profile rows
- MemberProfileService | 115 | T1,T7 | member profile CRUD
- MerchantLogoPort | 25 | T2,T3 | merchant logo SPI
- PersistentSessionRepository | 50 | F,T1 | persistent_session rows
- PersistentSessionService | 301 | F,T1 | remember-me session issue/rotate/revoke
- PropertyAdjustments | 346 | T2,T4 +T8 | real-estate valuation adjustment math
- PropertyValuationService | 440 | T2,T4 +T8 | property valuation orchestration
- RealEstateSummaryService | 286 | T2,T4 +T8 | real-estate summary figures
- ReAuthService | 118 | T1,T7 | step-up re-authentication
- RecurringDetectionService | 364 | T3,T5 +T8 | detects recurring series from transactions
- RecurringSeriesRepository | 29 | T3,T5 +T8 | recurring_series rows
- RequisitionRepository | 39 | T2,T5 +T8 | requisition (Enable Banking) rows
- RevolutSessionRepository | 14 | T1,T6 +T8 | revolut_session rows
- SessionIssuer | 145 | F,T1 | issues JWT/cookie sessions
- SetupAuditRepository | 8 | F,T1 | setup_audit rows
- SetupAuditService | 62 | F,T1 | setup audit log
- SetupService | 326 | F,T1 +T8 | first-run setup, admin creation
- SsoAccountService | 222 | F,T1 +T8 | SSO account provisioning/linking
- SsoIdentity | 31 | F,T1 +T8 | record of SSO identity
- WalletAddressRepository | 16 | T2,T5 +T8 | wallet_address rows

## 2. Shared entities (model/) and tables

Writers = classes that build the entity or call save/delete on its repository (found by grep). Readers/writers are given by owning track of the class, not the transitive noise.

| Entity (lines) | Table | Written by | Read by |
|---|---|---|---|
| Account (182) | account | T5/T6 sync services (every `*SyncService`, `BpceSyncEngine`, `CorumLifeWriter`), T2 AccountService/AccountConnectionService/ManualTransactionService, T4 ScpiFundReconciler/PeerLoan, T7 import services + FinaryPersistenceHelper, T8 IsinTickerRepairRunner | all tracks |
| AccountHolding (70) | account_holding | T5/T6 syncs (Wallet, CryptoExchange, Ibkr, Simplefin, Degiro, Bnp, Bourso, Corum, Fortuneo, TradeRepublic...), T2 AccountService.upsertHolding, HoldingComputeService (T7/T2 manual tx), FinaryHoldingsImporter (T7) | T2 valuation/history/dashboard, T4 dividends/goals/diversification, T5/T6, T7 export, T8 |
| Transaction (145) | transaction | T5/T6 syncs, SharedSidecarTransactionImportService, BankTransactionImportService, T7 TransactionImportService/ActualBudget/HomeBank/Finary, T2 ManualTransactionService, T3 CategorizationService/InternalTransferPairing/RecurringDetection, T4 PeerLoanService/DividendReceiptService, T8 IsinTickerRepairRunner | T2, T3 (spending/cashflow/recurring/budget), T4, T7 export |
| BalanceSnapshot (56) | balance_snapshot | AccountService.upsertSnapshot*, all syncs via it, FinaryPersistenceHelper, BalanceHistoryReconstructor (T6 Fortuneo), PeerLoanService (T4), T8 SchedulerService.dailySnapshots | T2 History/Dashboard, T4 Goal/Savings, T7 export |
| AccountOwnership (52) | account_ownership | T2 AccountOwnershipService (PUT shares) | AccountAccessResolver (everyone reading accounts), T7 exporter |
| AccountDisplayPref (53) | account_display_pref | T2 AccountService (hide/order/collapse) | T2 only |
| FamilyMember (30), AppUser (73) | family_member, app_user | F/T1 SetupService, SsoAccountService, T2 FamilyService | every track (member scoping, UserContext, locks) |
| Category (80) | category | T3 CategoryService; T3-owned seeding invoked by ingestion (CategorizationService.loadContext lazily seeds); T4 PeerLoanService, T7 ActualBudget/HomeBank | T3, ingestion (T5/T6/T7), T2 transaction response |
| CategorizationRule (56) | categorization_rule | T3 CategorizationService (rule CRUD, learnRule) | ingestion (T5/T6/T7 via autoCategorize), T3 |
| MerchantBrand (55), MerchantAlias (51) | merchant_brand, merchant_alias | no Java writer found (seed/migration data) | CategorizationService/MerchantKnowledgeBase (ingestion), T3 MerchantLogoService |
| Debt (62) | debt | T2 AccountService.updateDebtMetadata, BpceSyncEngine (T6 mortgage sync) | valuation (L), T2 loan summary |
| RealEstateMetadata (189), PropertyValuation (83) | real_estate_metadata, property_valuation | T2 AccountService.updateRealEstateMetadata, T4 PropertyValuationService | T2 AccountResponse, T4 RealEstateSummary |
| SavingsInterestConfig (74) | savings_interest_config | T4 SavingsService, SavingsAutoConfigService (T6 syncs) | T2 AccountResponse, T4 |
| PeerLoanTerms (84), PeerLoanRepayment (59), PeerCounterparty (37) | peer_loan_terms, peer_loan_repayment, peer_counterparty | T4 PeerLoanService | valuation (AccountService, L), T4, T3 PeerLoanTransactionLinks |
| ScpiPosition (99), ScpiProviderFund (57) | scpi_position, scpi_provider_fund | T2 AccountService delete, T4 ScpiFundService/ScpiPositionService/ScpiFundReconciler (called by Corum/Sofidy/Louve syncs, T6/T5) | T2 AccountResponse, T4, T6 |
| PriceSnapshot (39), CryptoAsset (41) | price_snapshot, crypto_asset | T4 PriceService, CryptoAssetRegistry, PriceFxCleanupRunner (T8) | FX/price reads everywhere (valuation, History, Dashboard, snapshot writing), BalanceHistoryReconstructor (T6) |
| InstrumentLogo (63) | instrument_logo | T2 InstrumentLogoService | T2 AccountResponse holdings |
| SecurityProfile (124), SecurityCompositionSlice (48) | security_profile, security_composition_slice | T2 SecurityProfileService (+ SecurityIdentityService from T5/T6 syncs) | T2 diversification/insight, T4 |
| Goal (103), GoalAllocation (51), GoalContributor, GoalManualContribution, GoalMonthOverride | goal, goal_allocation, goal_contributor, goal_manual_contribution, goal_month_override | T4 GoalService | T2 FamilyView/History, T4, T7 export |
| RecurringSeries (102) | recurring_series | T3 RecurringSeriesService, RecurringDetectionService (also run after sync) | T3, T5 |
| BudgetSettings (73) | budget_settings | T3 BudgetSettingsService | T3, T2 (BudgetCycle/Dashboard), CategorizationService |
| AppSetting (29) | app_setting | F SetupService/IntegrationsService, TelemetryService (T1), CatchUpScheduler/IsinTickerRepairRunner/PriceFxCleanupRunner (T8) | SecurityConfig, CORS, cookies, LouveClientRegistry (T5); see section 5 |
| SharingSettings (30), SharedResource (33) | sharing_settings, shared_resource | T2 FamilyService | T2 FamilyViewService, T7 SharedResourcesExporter |
| Sidecar/connection sessions: SidecarSession (256), LbpSession, RevolutSession, DegiroSession, BpceSession, CryptoExchangeSession, FinarySession, IbkrConnection, WiseConnection, SimplefinConnection, LouveConnection, WalletAddress, Requisition | sidecar_session, lbp_session, ... (per name) | owning T5/T6 sync services | F/T1 read status via IntegrationsService/IntegrationsHealthService; T2 delete flow |

Entity count: 63 @Entity classes. Enums/value types of the ledger (AccountType 115, TransactionType, SnapshotSource, CategoryKind, RuleSource, AssetClass, ValuationMode/Status/Confidence, CardNature) travel with the entities.

## 3. The ingestion path (what every sync/import uses to write the ledger)

There is no single ingestion service. Five families share primitives; the common primitives are all in `AccountService`, `CategorizationService`, `TransactionRepository`. Order of calls, as implemented:

**Common per-account write order (all providers):** (1) resolve member (`FamilyMemberRepository`, `lockMember` in SidecarSyncTemplate) -> (2) upsert `Account` by externalAccountId, then IBAN+currency+member; skip soft-deleted (`AccountRepository.existsSoftDeletedBy...`, SyncService.upsertAccount lines 490-501); `isManual=false`, user-set type/name preserved -> (3) holdings (replace or in-place by ticker, see below) -> (4) balance snapshot `AccountService.upsertSnapshot(account, eur, invested, date)` or `upsertSnapshotFromNative` (converts native to EUR with `PriceService.toEur`) -> (5) transactions -> (6) post-import: `ReceivedDividendClassifier.afterImport(memberId)` = `DividendReceiptService.classifyRecent` then `InternalTransferPairingService.pairRecent`. Some providers invert 3/4/5 (SimpleFIN: holdings, snapshot, transactions; Enable Banking: snapshot first, then transactions best-effort; Fortuneo: holdings, `syncTransactions`, snapshot, `BalanceHistoryReconstructor.reconstruct`; Bnp: transactions, holdings delete+saveAll, snapshot).

**Accounts.** `SyncService.upsertAccount` (EB, line 480-553), `SimplefinSyncService` (200-240), `Bnp/Bourso/...SyncService` via `SidecarSyncTemplate.execute -> markRunning -> strategy.fetch -> commit(tx, strategy.commit(job, prepared, syncedAt)) -> strategy.afterCommit`, Finary via `FinaryPersistenceHelper`/`FinaryApiSyncService`, CSV/Actual/HomeBank via their own import services. Manual create: `AccountService.create`.

**Holdings.** `AccountService.upsertHolding/pruneHoldings` (Wallet, CryptoExchange), delete+saveAll in sidecar syncs (`holdingRepository.deleteByAccountId; flush; saveAll`), in-place by ticker in SimplefinSyncService.replaceHoldings (keeps hand-entered buy-in), merge duplicates with `HoldingDedup.vwapMerge` (Degiro, Ibkr, TradeRepublic, FinaryHoldingsImporter), recompute from BUY/SELL rows with `HoldingComputeService.recomputeHoldings` (manual transactions, CSV import). Ticker identity: `SecurityIdentityService`, `OpenFigiIsinConverter`, `SymbolCatalogPort`.

**Transactions and deduplication.**
- `BankTransactionImportService.importFor/importProvided` (EB via SyncService, SimpleFIN; WiseSyncService only uses its static `fitsLedgerAmount`/`clip`): window = latest synced date - OVERLAP_DAYS (`windowStart`, uses `TransactionRepository.findLatestSyncedDateByAccountId`); `selectNew` loads `findByAccountIdAndIsManualFalseAndDateGreaterThanEqual`, matches stable bank id + content fingerprint (date, amount, normalized description <=255 cp) one-for-one, then fingerprint-only overlap against legacy/sidecar rows; never edits an existing row; `fitsLedgerAmount`; EUR-only filter in `importFor`; then `transactionRepository.saveAll`. Note: it does NOT inject CategorizationService (rows are saved with the provider `category` string only, `budgetNeutral = account type is investment`; categories are applied later by `recategorizeUncategorized`/AI). Only SharedSidecar... and the legacy path categorize at insert.
- `SharedSidecarTransactionImportService.importFor(account, List<SidecarTransaction>, CategorizationContext, providerKeyPrefix)` (Bnp, Bourso, Revolut): known keys from `findByAccountIdAndIsManualFalse`, legacy overlap consumption (`findLegacyOverlap`, keeps the legacy row and its category), key `prefix:sourceKey` or synthetic sha256, EUR-only, then `categorizationService.autoCategorize(tx, ctx)` then `transactionRepository.save`. Revolut TRANSFER rows forced to the TRANSFER category.
- Bespoke dedupe (same idea, own code) in FortuneoSyncService/FortuneoTransactionWriter, LbpSyncService, NatixisSyncService, AmexSyncService, BpceSyncEngine, CorumLifeOperationWriter (all `findByAccountIdAndIsManualFalse`), TradeRepublicSyncService and HomeBankImportService (`existsByAccountIdAndExternalId`), ActualBudgetImportService (`findStoredExternalIds*`), FinaryPersistenceHelper.importTransactions, TransactionImportService (T7, CSV, `TransactionRowMapper`). Dead code: `SyncService.ingestTransactions` (private, unused).

**Manual-category preservation.** `Transaction.categoryManual` (set by `CategorizationService.categorize`) and `categoryRef != null`. `autoCategorize` = `enrich` (MerchantNormalizer + MerchantKnowledgeBase stamps merchant_label/brand) -> return if `categoryRef != null` (never overrides) -> `apply(rules)` (priority-desc, first match) -> brand default category fallback. `retroApply`/`recategorizeUncategorized` skip `isCategoryManual()`. Importers never update existing rows, so a manual category survives re-sync. Manual transactions (`ManualTransactionService`, `isManual=true`) are excluded from dedupe queries (`IsManualFalse`).

**Internal transfer pairing.** `InternalTransferPairingService.pairRecent(memberId, today)` (@Transactional): visible non-excluded accounts of the member -> `findByAccountIdInAndDateBetweenOrderByDateAsc` -> candidates (`isCandidate`: not categoryManual, not manual-with-category, trade legs excluded) -> `uniquePairs` (each leg other's only candidate, `matches` debit/credit amount within MAX_DAY_GAP, two different accounts) -> `categorizationService.categoriesBySlug(memberId).get("transfer")` (kind TRANSFER) -> `apply` sets category, `saveAll`. Idempotent. Triggered only from `ReceivedDividendClassifier.afterImport`, called by MemberSyncService (3 entry points), BoursoSyncService.afterCommit and FortuneoSyncService.afterCommit.

**Snapshot writing.** `AccountService.upsertSnapshot(account, balance, [invested,] date)`: `findByAccountIdAndDate` then update balance+invested, else create; invested defaults to `calculateInvestedAmount = valuation(account).investedEur` (needs holdings, quotes, debt/peer-loan math). `upsertSnapshotFromNative` converts via `PriceService.toEur(balance, currency, ticker)` and, for `SnapshotSource.MANUAL` rows, refreshes `nativeBalance` and `statementEnteredAt`. Other writers: `SchedulerService.dailySnapshots` (T8, uses `valuation().liveEur`), `BalanceHistoryReconstructor.reconstruct` (back-fills derived rows from the ledger, Fortuneo only, `Propagation.MANDATORY`), `FinaryPersistenceHelper.reconstructSnapshots*`, `AccountService.addManualSnapshot`, `PeerLoanService`, `HomeBankImportService`.

Callers of `upsertSnapshot*`: Wise, Revolut, Simplefin, SyncService(EB), Wallet, CryptoExchange, Ibkr, BourseDirect, Lbp, Bnp, Degiro, Fortuneo, Corum (CorumLifeWriter), Cic, Cmes, Natixis, Bpce, Bourso, Amundi, TradeRepublic, Amex, PeerLoanService, AccountService. All call into a package-private method (`BalanceSnapshot upsertSnapshot(...)` has no `public`), so L must expose it to the sync crates.

## 4. FX and price conversion services used outside the price track (T4)

`PriceService` (609) is the only FX/price facade; providers sit behind `PriceProviderPort` (63) -> `CompositePriceProvider` (211) -> `YahooFinancePriceProvider` (662), `CoinGeckoPriceProvider` (750, with `CryptoAssetRegistry`/`CryptoAssetRepository`), `PreciousMetalPriceProvider` (303). Cache + history = `PriceSnapshotRepository` (`price_snapshot`, also holds FX series). Method usage by class (found by grep):
- `toEur(amount, currency, ticker)`: AccountService (`toSnapshotEur`, valuation of foreign-currency accounts), DashboardService, EssentialExpenseEstimator.
- `getFxRateEur(currency)`: AccountService, SimplefinSyncService (non-EUR accounts/holdings), FinaryPersistenceHelper (`toEurOrFail`/`toNativeOrFail`); also FX inside `IbkrSyncService`, `IbkrFlexClient`, `DividendHistoryService` (their own `fxRate*` helpers).
- `getQuotes` / `getCryptoQuotes`: AccountService (valuation, `quotesFor`), CryptoExchangeSyncService (`getCryptoQuotes`, `refreshCryptoQuotes`).
- `getPriceEur` / `getIntradayPricesEur` / `getCryptoIntradayPricesEur`: HistoryService (T2 charts), InsightTools (T8), PriceController (T4).
- `refreshPrices` / `refreshCryptoPrices`: WalletSyncService (T5), SchedulerService (T8), PriceController. `backfillHistoricalPrices`: CaisseEpargnePerService, FortuneoSyncService (T6), PriceBackfillRunner (T8). `clearPriceCache`: PriceFxCleanupRunner (T8).
- Direct `PriceSnapshotRepository` readers outside T4: HistoryService, BalanceHistoryReconstructor (T6), InstrumentLogoService (T2), CryptoAssetRegistry.
- `CurrencyValidator`/`@ValidCurrency` (validation/, 53 lines) on DTOs: all request DTOs with a currency; `RegulatedRates` (36) used by SavingsAutoConfigService/NameBasedSavingsBookDetector.
Consequence: the ledger needs the read side of PriceService (quote lookup, `toEur`, `getFxRateEur`, price_snapshot reads) behind a trait; T4 owns the providers and refresh/backfill write side.

## 5. Cross-cutting services touched by many controllers

- **Current user/member**: `UserContext` (86) is injected by 57 of the 73 `*Controller` classes, plus IntegrationsService and all 10 MCP tools (T8). Reads `AppUserRepository`; admin member override. Port with F (auth extractor) or L; do not duplicate.
- **Account visibility / access**: `AccountAccessResolver` (211): `readableAccounts`, `readableAccountIds`, `shareFor/sharesFor` (ownership %), `requireReadable`, `requireOwner`, `weigh`. Used by AccountService, HistoryService, DashboardService, GoalService, FamilyViewService, WealthPyramidService, PortfolioDiversificationService, ProjectionService, RealEstateSummaryService, EssentialExpenseEstimator, DividendReceiptService, DividendForecastService, HoldingClassificationService, PropertyValuationService, AccountOwnershipService. Depends only on `AccountRepository` + `AccountOwnershipRepository`.
- **Ownership shares**: `AccountOwnershipService` (173; GET/PUT /accounts/{id}/ownership; only AccountController, T2) writes `account_ownership`; `AccountOwnershipRepository` is read by AccountAccessResolver and AccountOwnershipExporter. Share weighting (`AccountAccessResolver.weigh`) is applied in AccountService.toResponse and every aggregate.
- **Sharing settings / family view**: `FamilyService` (T2; writes `sharing_settings`, `shared_resource`, `family_member`, `app_user`) and `FamilyViewService` (reads them + AccountService + GoalRepository). `SharingSettingsRepository` and `SharedResourceRepository` have no other users (plus SharedResourcesExporter in T7). Safe to keep inside T2.
- **Account display order / hidden / collapsed**: `AccountDisplayPrefRepository` is used only by AccountService (`setHidden`, `setListCollapsed`, `setDisplayOrder`, `overlayDisplayPrefs`); `AccountRepository.findAllByMemberIdAndHiddenFalse...` is also used by InternalTransferPairingService and sync services. Hidden is a column on `account`; order/collapsed are in `account_display_pref`.
- **App settings**: `AppSettingRepository` (44) is used by SecurityConfig, DynamicCorsConfigurationSource, SecureCookieProvider (F), SetupService + IntegrationsService (F/T1), TelemetryService (T1), LouveClientRegistry (T5), CatchUpScheduler, IsinTickerRepairRunner, PriceFxCleanupRunner (T8). Config providers `EnableBankingConfigProvider`, `AiConfigProvider` (config/, not traversed) also read it. Port as a typed key/value store in F (`db`/`config`), not in L.
- **Categorization pipeline**: `CategorizationService` (600) + `CategoryService` (268) + `MerchantNormalizer`/`MerchantKnowledgeBase`: used by T3 controllers and by every ingestion path (T5/T6/T7) and T4 (PeerLoanService, DividendReceiptService) and MCP BudgetTools.
- **Member sync orchestration**: `MemberSyncService` (310) fans out to all sync services and then `ReceivedDividendClassifier`; used by SyncController (T5) and T8 schedulers. `SchedulerService`/`CatchUpScheduler`/`SyncStatusService` are T8 only.

## 6. Recommended scope of track L (ledger), minimal and unblocking

Principle: L = everything that a sync/import/transfer path or a valuation must call to write or value accounts, holdings, transactions and balance snapshots, plus the read primitives every domain track needs (member, access, ownership shares, FX). Everything else stays with its domain track. Ports/traits in L let T4 (price providers, dividend classification) and T5/T6 (connection locks) plug in without circular crates.

| Java class | file lines | ported portion lines | note |
|---|---|---|---|
| UserContext | 86 | 86 | or F; every controller needs it |
| AppUserRepository | 42 | 42 | member/user lookup (shared with F/T1) |
| FamilyMemberRepository | 21 | 21 | member lookup + lock |
| AccountAccessResolver | 211 | 211 | readable ids, ownership shares |
| AccountRepository | 208 | 208 |  |
| AccountHoldingRepository | 113 | 113 |  |
| BalanceSnapshotRepository | 71 | 71 |  |
| AccountOwnershipRepository | 34 | 34 |  |
| AccountDisplayPrefRepository | 49 | 49 | hidden/order, read by account listings |
| TransactionRepository | 436 | 150 (est.) | port only the ~15 ingestion/dedupe/window/valuation queries; T3 adds budget aggregates |
| PriceSnapshotRepository | 91 | 60 (est.) | read side + upsert used by PriceService |
| CategoryRepository | 25 | 25 |  |
| CategorizationRuleRepository | 20 | 20 |  |
| MerchantBrandRepository | 9 | 9 |  |
| MerchantAliasRepository | 9 | 9 |  |
| BudgetSettingsRepository | 12 | 12 | injected by CategorizationService |
| DebtRepository | 24 | 24 | valuation |
| PeerLoanTermsRepository | 48 | 48 | valuation |
| PeerLoanRepaymentRepository | 30 | 30 | valuation |
| AccountService | 1396 | 480 (est.) | only getOrThrow, upsertSnapshot*/createSnapshot, upsertHolding, pruneHoldings, calculateInvestedAmount, valuation/liveBalanceEur/signedLiveBalanceEur, costBasisOf, isProviderValued, providerCostBasisEur (lines 550-657, 681-1046, 1389-1396); CRUD, display prefs, delete, metadata, toResponse go to T2 |
| PriceService | 609 | 190 (est.) | read side only: getQuotes/getCryptoQuotes cache+snapshot lookup, getFxRateEur, toEur (lines 54-135, 413-462) behind a `PriceRead` trait; providers/refresh/backfill = T4 |
| PriceProviderPort | 63 | 63 | trait definition (Quote, FX) so L can compile without T4 |
| LoanAmortizationService | 281 | 281 | pure math, needed by valuation of debt accounts |
| PeerLoanCalculator | 331 | 331 | pure math, needed by valuation |
| CategorizationService | 600 | 150 (est.) | only CategorizationContext, loadContext, categoriesBySlug, enrich, apply, autoCategorize, applyBrandFallback, matches (lines 69-260); rule CRUD/AI/retroApply = T3 |
| CategoryService | 268 | 60 (est.) | only ensureSeeded + findOrCreateDefault (lines 105-158); CRUD = T3 |
| MerchantNormalizer | 128 | 128 |  |
| MerchantKnowledgeBase | 144 | 144 |  |
| BankTransactionImportService | 428 | 400 (est.) | selectNew/importProvided/windowStart/fingerprint/dedupKey/toEntity; drop the connector fetch in importFor (T5 EB) |
| SharedSidecarTransactionImportService | 211 | 211 |  |
| InternalTransferPairingService | 202 | 202 |  |
| ReceivedDividendClassifier | 50 | 50 | with a `DividendClassifier` trait; DividendReceiptService stays T4 |
| HoldingComputeService | 113 | 113 |  |
| HoldingDedup | 84 | 84 |  |
| InstrumentFieldResolver | 70 | 70 |  |
| ManualTransactionService | 170 | 170 | used by T2 AccountController, T4 PeerLoanService, MCP TransactionTools |
| SyncValues | 113 | 113 |  |
| SidecarErrorCode | 93 | 93 | enum, hoisted (F/T1/T6 use it) |
| SidecarBank | 341 | 341 | enum + metadata, hoisted (F/T1 IntegrationsService, T6) |
| SidecarTransaction | 26 | 26 | record |
| SourceSyncResult | 32 | 32 | record |
| FinaryPersistenceHelper | 408 | 125 (est.) | only computeManualLedgerBalance + reconstructSnapshotsFromDb (lines 224-350); the Finary import parts = T7 |
| **Total (42 classes)** | **7700** | **~5079** | |

Plus entity/enum structs and sqlx row mappings (28 model classes, 1513 Java lines): Account, AccountHolding, Transaction, BalanceSnapshot, AccountOwnership, AccountDisplayPref, FamilyMember, AppUser, Category, CategorizationRule, MerchantBrand, MerchantAlias, BudgetSettings, Debt, PeerLoanTerms, PeerLoanRepayment, PriceSnapshot, AccountType, TransactionType, SnapshotSource, CategoryKind, RuleSource, AssetClass, ValuationMode, ValuationStatus, ValuationConfidence, CardNature, AuditableEntity.

Grand total: about 5079 Java lines of logic (7700 counting the whole files) plus 1513 lines of entity/enum definitions, roughly 6592 lines (6592 vs 100,942 for the backend, about 7%).

Also hoist into L (tiny, no class of their own): provider-name constants read by `AccountService.PROVIDER_VALUED/NEVER_MARKET_PRICED` (the `PROVIDER` constants of BourseDirect, Amundi, Cmes, Natixis, Bourso, Fortuneo, Bnp/HelloBank sync services), and `CaisseEpargneSyncService.isInsurerContract(Account)`.

Traits L must define and domain tracks implement: `PriceRead` (T4: providers, refresh, `price_snapshot` writes), `DividendClassifier` (T4: `DividendReceiptService.classifyRecent`), `ConnectionLocks` (T5/T6: Corum/Louve `lockMemberForAccountDeletion`, used by T2 AccountConnectionService), `CurrentMember` (F/T1).

What L explicitly does NOT take (so it stays small): AccountService CRUD, display prefs, metadata updates, delete, `toResponse` (T2); real-estate/SCPI/dividend/goal/peer-loan services (T4); rule CRUD, AI categorization, recurring, cashflow (T3); `BalanceHistoryReconstructor` (350, Fortuneo only: T6); `SidecarSyncTemplate` (369) and per-bank writers (`CorumLife*`, `FortuneoTransactionWriter`: T6); `FinaryPersistenceHelper` import parts (T7); `InstrumentLogoService`/`CryptoLogoService`/`BankLogoResolver` (T2; HoldingResponse logos only; cut the `BankConnectorPort` dependency); `MemberSyncService`, schedulers (T5/T8).

Order inside L (each step compiles and tests alone): (1) entities + repositories + `CurrentMember`; (2) `AccountAccessResolver`, `FamilyMember` lock; (3) FX/quote read trait + `LoanAmortizationService` + `PeerLoanCalculator`; (4) `AccountService` core: snapshots, holdings, `valuation`; (5) categorization pipeline subset; (6) `BankTransactionImportService` + `SharedSidecarTransactionImportService` + `HoldingDedup` + `HoldingComputeService` + `ManualTransactionService`; (7) `InternalTransferPairingService` + classifier hook; (8) `FinaryPersistenceHelper` manual-ledger part.

Risks to flag to the team: (a) `AccountService` has no seam: `valuation()` (lines 723-943) is the pivot that blocks T2/T4/T5/T6/T7/T8; port it first with golden tests. (b) The dozen or so dedupe implementations (section 3) differ subtly; L ports the two shared ones (`BankTransactionImportService`, `SharedSidecar...`), per-bank dedupe stays with T6 and must keep its own tests. (c) `upsertSnapshot*` are package-private in Java; the Rust API must be public across crates. (d) Contract tests should assert manual category survival and transfer-pairing idempotence at L level.
