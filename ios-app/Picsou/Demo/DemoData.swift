import Foundation

/// Canned dashboard data for the demo build. Mirrors the shape of the web app's
/// `src/demo/data/dashboard.ts`, plus a loan liability so every section is populated.
enum DemoData {

    private static let assets: [(name: String, color: String, balance: Double, type: String, holdings: Bool)] = [
        ("Livret A",       "#4F46E5", 12000,    "SAVINGS",  false),
        ("PEA",            "#10B981", 15500,    "PEA",      true),
        ("Compte courant", "#F59E0B", 3200,     "CHECKING", false),
        ("Bitcoin",        "#EF4444", 8900,     "CRYPTO",   true),
        ("Assurance-vie",  "#8B5CF6", 6262.35,  "OTHER",    false),
    ]

    private static let loanBalance: Double = 18000
    private static let loanMonthly: Double = 650

    static func dashboard(range: TimeRange) -> DashboardResponse {
        let totalAssets = assets.reduce(0) { $0 + $1.balance }
        let netWorth = totalAssets - loanBalance

        let distribution = assets.enumerated().map { index, account in
            DistributionItem(
                accountId: Int64(index + 1),
                name: account.name,
                color: account.color,
                balanceEur: Decimal(account.balance),
                percentage: (account.balance / totalAssets * 1000).rounded() / 10,
                accountType: account.type,
                hasHoldings: account.holdings
            )
        }

        let liabilities = [
            LiabilityEntry(
                accountId: 99,
                name: "Prêt immobilier",
                color: "#DC2626",
                balanceEur: Decimal(loanBalance),
                percentage: 100,
                accountType: "LOAN",
                hasHoldings: false,
                monthlyPayment: Decimal(loanMonthly),
                percentPaid: 42.5
            )
        ]

        let goals = [
            GoalProgress(id: 1, name: "Fonds d'urgence", targetAmount: 15000, currentTotal: 12000,
                         percentComplete: 80, deadline: "2026-12-01", monthlyNeeded: 960, monthsLeft: 5,
                         isOnTrack: true, accounts: [GoalAccountRef(id: 1)]),
            GoalProgress(id: 2, name: "Vacances Japon", targetAmount: 5000, currentTotal: 1500,
                         percentComplete: 30, deadline: "2026-09-01", monthlyNeeded: 700, monthsLeft: 5,
                         isOnTrack: false, accounts: [GoalAccountRef(id: 3)]),
        ]

        return DashboardResponse(
            totalNetWorth: Decimal(netWorth),
            totalLiabilities: Decimal(loanBalance),
            totalMonthlyPayment: Decimal(loanMonthly),
            netWorthHistory: history(netWorth: netWorth, range: range),
            distribution: distribution,
            liabilities: liabilities,
            goalSummaries: goals
        )
    }

    private static func history(netWorth: Double, range: TimeRange) -> [NetWorthPoint] {
        // 12 monthly points ramping to the current net worth; invested tracks a little below total.
        let totals: [Double] = [20000, 20800, 21500, 22300, 22100, 23400, 24200, 24900, 25600, 26400, 27100, netWorth]
        let invested: [Double] = [16000, 16500, 17000, 17500, 17500, 18500, 19000, 19500, 20000, 20600, 21000, 21400]

        let calendar = Calendar(identifier: .gregorian)
        let now = Date()
        let startOfMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: now)) ?? now

        var points: [NetWorthPoint] = []
        for i in 0..<totals.count {
            let monthOffset = -(totals.count - 1 - i)
            guard let date = calendar.date(byAdding: .month, value: monthOffset, to: startOfMonth) else { continue }
            let total = Decimal(totals[i])
            let inv = Decimal(invested[i])
            points.append(NetWorthPoint(
                date: DateParsing.localDate.string(from: date),
                total: total,
                invested: inv,
                pnl: total - inv
            ))
        }

        return Array(points.suffix(max(2, monthsToKeep(range))))
    }

    private static func monthsToKeep(_ range: TimeRange) -> Int {
        switch range {
        case .day, .week, .month: return 2
        case .quarter: return 4
        case .ytd: return Calendar(identifier: .gregorian).component(.month, from: Date())
        case .year, .all: return 12
        }
    }

    // MARK: - Account detail / transactions (Slice 1)

    private static let loanId: Int64 = 99

    static func account(id: Int64) -> Account {
        if id == loanId {
            return Account(id: loanId, name: "Prêt immobilier", accountType: "LOAN",
                           provider: "Crédit Agricole", currency: "EUR",
                           currentBalance: -18000, currentBalanceEur: -18000,
                           lastSyncedAt: "2026-07-03T08:05:00Z", manual: false, color: "#DC2626",
                           ticker: nil, parentAccountId: nil,
                           debt: DebtInfo(borrowedAmount: 31000, interestRate: 1.9, monthlyPayment: 650,
                                          lenderName: "Crédit Agricole", startDate: "2022-01-01", endDate: "2045-01-01"),
                           hidden: false)
        }
        let index = max(0, min(Int(id) - 1, assets.count - 1))
        let a = assets[index]
        return Account(id: id, name: a.name, accountType: a.type,
                       provider: provider(for: a.type), currency: "EUR",
                       currentBalance: Decimal(a.balance), currentBalanceEur: Decimal(a.balance),
                       lastSyncedAt: "2026-07-03T08:05:00Z", manual: false, color: a.color,
                       ticker: nil, parentAccountId: nil, debt: nil, hidden: false)
    }

    static func holdings(id: Int64) -> [Holding] {
        switch id {
        case 2: return [
            Holding(ticker: "CW8", name: "Amundi MSCI World", quantity: 42,
                    currentValueEur: 9800, pnlEur: 1450, pnlPercent: 17.4, priceUpdatedAt: "2026-07-03T16:00:00Z"),
            Holding(ticker: "ESE", name: "BNP S&P 500", quantity: 18,
                    currentValueEur: 5700, pnlEur: 820, pnlPercent: 16.8, priceUpdatedAt: "2026-07-03T16:00:00Z"),
        ]
        case 4: return [
            Holding(ticker: "BTC", name: "Bitcoin", quantity: 0.14,
                    currentValueEur: 8900, pnlEur: 2100, pnlPercent: 30.9, priceUpdatedAt: "2026-07-03T16:00:00Z"),
        ]
        default: return []
        }
    }

    static func transactions(id: Int64) -> [Transaction] {
        // (label, amount, categoryId, merchant, aiSuggestedCategoryId, aiConfidence) — the last two
        // rows are left uncategorized so the demo categorization inbox has real content: one with
        // an AI suggestion pre-filled, one without (so both card variants are demoable).
        let rows: [(String, Double, Int64?, String?, Int64?, Int?)] = [
            ("Carrefour Market", -54.30, 1, "Carrefour", nil, nil),
            ("Salaire", 2450.00, 9, nil, nil, nil),
            ("Netflix", -13.49, 4, "Netflix", nil, nil),
            ("SNCF", -78.00, 2, "SNCF", nil, nil),
            ("Boulangerie du coin", -6.80, 1, nil, nil, nil),
            ("EDF", -89.00, 7, "EDF", nil, nil),
            ("Amazon", -34.99, nil, "Amazon", 6, 88),
            ("Pharmacie Centrale", -12.40, nil, nil, nil, nil),
        ]
        let name = account(id: id).name
        let calendar = Calendar(identifier: .gregorian)
        let now = Date()
        return rows.enumerated().map { i, row in
            let date = calendar.date(byAdding: .day, value: -i * 2, to: now) ?? now
            return Transaction(id: Int64(i + 1), date: DateParsing.localDate.string(from: date),
                               description: row.0, amount: Decimal(row.1), nativeCurrency: "EUR",
                               manual: false, txType: row.1 < 0 ? "WITHDRAWAL" : "DEPOSIT",
                               categoryId: row.2, categoryName: row.2.flatMap(categoryName(for:)),
                               merchantLabel: row.3, merchantBrandId: nil, counterparty: nil,
                               accountId: id, accountName: name,
                               aiSuggestedCategoryId: row.4, aiConfidence: row.5)
        }
    }

    static func loanSchedule() -> LoanSchedule {
        var remaining = 18000.0
        let calendar = Calendar(identifier: .gregorian)
        let now = Date()
        var rows: [LoanInstallment] = []
        for n in 0..<12 {
            let interest = remaining * 0.019 / 12
            let capital = 650 - interest
            remaining -= capital
            let date = calendar.date(byAdding: .month, value: n, to: now) ?? now
            rows.append(LoanInstallment(number: n + 1, date: DateParsing.localDate.string(from: date),
                                        capital: Decimal(capital), interest: Decimal(interest),
                                        totalPayment: 650, remainingBalance: Decimal(max(0, remaining))))
        }
        return LoanSchedule(
            summary: LoanSummary(monthlyPayment: 650, remainingBalance: 18000, capitalRepaidPct: 42.5,
                                 endDate: "2045-01-01", paidInstallments: 102, totalInstallments: 240),
            schedule: rows)
    }

    static func makeTransaction(from request: TransactionRequest, accountId: Int64) -> Transaction {
        Transaction(id: Int64.random(in: 100_000...999_999), date: request.date,
                    description: request.description, amount: request.amount,
                    nativeCurrency: request.currency ?? "EUR", manual: true, txType: request.txType,
                    categoryId: request.categoryId, categoryName: request.categoryId.flatMap(categoryName(for:)),
                    merchantLabel: nil, merchantBrandId: nil, counterparty: nil,
                    accountId: accountId, accountName: account(id: accountId).name,
                    aiSuggestedCategoryId: nil, aiConfidence: nil)
    }

    static func accountsList() -> [Account] {
        (1...assets.count).map { account(id: Int64($0)) }
    }

    static func familyMembers() -> [FamilyMember] {
        [
            FamilyMember(id: 1, displayName: "Chloé", avatarColor: "#6366F1", managed: false,
                         hasLogin: true, activated: true, loginName: "chloe", mfaEnabled: true),
            FamilyMember(id: 2, displayName: "Alex", avatarColor: "#10B981", managed: false,
                         hasLogin: true, activated: true, loginName: "alex", mfaEnabled: false),
            FamilyMember(id: 3, displayName: "Léa", avatarColor: "#F59E0B", managed: true,
                         hasLogin: false, activated: false, loginName: nil, mfaEnabled: false),
        ]
    }

    static func accessKeys() -> [AccessKey] {
        [
            AccessKey(id: 1, name: "Claude Desktop", keyPrefix: "pk_a1b2c3d4",
                      scopes: ["accounts:read", "transactions:read", "dashboard:read"],
                      lastUsedAt: "2026-07-04T18:00:00Z", expiresAt: nil, revokedAt: nil,
                      createdAt: "2026-06-15T10:00:00Z"),
            AccessKey(id: 2, name: "Script export", keyPrefix: "pk_e5f6g7h8",
                      scopes: ["transactions:read"],
                      lastUsedAt: nil, expiresAt: nil, revokedAt: "2026-07-01T00:00:00Z",
                      createdAt: "2026-05-01T10:00:00Z"),
        ]
    }

    static func bankConnections() -> [BankConnection] {
        [
            BankConnection(id: 1, institutionName: "Crédit Agricole", institutionId: "ca_fr",
                           status: "LINKED", authLink: nil, lastSyncedAt: "2026-07-04T07:30:00Z"),
            BankConnection(id: 2, institutionName: "Boursorama", institutionId: "bourso_fr",
                           status: "FAILED", authLink: nil, lastSyncedAt: "2026-07-01T09:00:00Z"),
        ]
    }

    static func cashflow() -> CashflowSummary {
        CashflowSummary(from: "2026-07-01", to: "2026-07-31", income: 2450, expense: 1684, net: 766)
    }

    // MARK: - Categories (canonical demo catalog, shared by transactions/envelopes/spending/recurring)

    private static let categoryCatalog: [(id: Int64, name: String, color: String, kind: CategoryKind)] = [
        (1, "Alimentation", "#10B981", .expense),
        (2, "Transport", "#F59E0B", .expense),
        (3, "Loisirs", "#8B5CF6", .expense),
        (4, "Abonnements", "#6366F1", .expense),
        (5, "Restaurants", "#EF4444", .expense),
        (6, "Shopping", "#EC4899", .expense),
        (7, "Énergie", "#06B6D4", .expense),
        (8, "Santé", "#14B8A6", .expense),
        (9, "Revenus", "#22C55E", .income),
    ]

    static func categories() -> [Category] {
        categoryCatalog.enumerated().map { index, c in
            Category(id: c.id, name: c.name, kind: c.kind, color: c.color, icon: nil,
                     isDefault: true, archived: false, sortOrder: index, parentId: nil)
        }
    }

    static func categoryName(for id: Int64) -> String? { categoryCatalog.first { $0.id == id }?.name }
    static func categoryColor(for id: Int64) -> String { categoryCatalog.first { $0.id == id }?.color ?? Theme.fallbackColorHex }

    static func budgetEnvelopes() -> [BudgetEnvelope] {
        func env(_ id: Int64, _ categoryId: Int64, _ limit: Double, _ spent: Double) -> BudgetEnvelope {
            let percent = limit > 0 ? min(999, spent / limit * 100) : 0
            let c = categoryCatalog.first { $0.id == categoryId }!
            return BudgetEnvelope(id: id, categoryId: categoryId, categoryName: c.name, categoryColor: c.color,
                                  categoryKind: c.kind.rawValue,
                                  monthlyLimit: Decimal(limit), spent: Decimal(spent),
                                  remaining: Decimal(limit - spent), percent: Decimal(percent),
                                  overBudget: spent > limit, cycleStart: "2026-07-01", cycleEnd: "2026-07-31")
        }
        return [
            env(1, 1, 500, 543),
            env(2, 2, 150, 118),
            env(3, 3, 200, 96),
            env(4, 4, 80, 64),
            env(5, 5, 180, 152),
        ]
    }

    static func budgetSettings() -> BudgetSettings {
        BudgetSettings(cycleStartDay: 1, logoFetchEnabled: true, aiCategorizationEnabled: true,
                       aiMode: .autoHighConfidence, aiConfidenceThreshold: 80,
                       currentCycleStart: "2026-07-01", currentCycleEnd: "2026-07-31")
    }

    static func spendingByCategory() -> SpendingByCategory {
        let rows: [(Int64, Double, Int)] = [(1, 543, 12), (5, 152, 6), (2, 118, 4), (7, 89, 1), (4, 64, 2), (3, 96, 3)]
        let total = rows.reduce(0) { $0 + $1.1 }
        let items = rows.map { id, amount, count -> CategorySpend in
            let c = categoryCatalog.first { $0.id == id }!
            return CategorySpend(categoryId: id, slug: nil, name: c.name, color: c.color, icon: nil,
                                  amount: Decimal(amount), count: count,
                                  share: total > 0 ? Decimal(amount / total) : 0, parentId: nil, parentName: nil, parentColor: nil)
        }
        return SpendingByCategory(period: "CYCLE", from: "2026-07-01", to: "2026-07-31",
                                  totalExpense: Decimal(total), categories: items)
    }

    static func spendingDetail(categoryId: Int64, period: CashflowPeriod) -> SpendingDetail {
        let c = categoryCatalog.first { $0.id == categoryId } ?? categoryCatalog[0]
        let txs = transactions(id: 3).filter { $0.categoryId == categoryId }
        let total = txs.reduce(Decimal(0)) { $0 + $1.amount }
        return SpendingDetail(categoryId: categoryId, slug: nil, name: c.name, color: c.color, icon: nil,
                              period: period.rawValue, from: "2026-07-01", to: "2026-07-31",
                              total: total, count: txs.count, transactions: txs)
    }

    static func recurringSeries() -> [RecurringSeries] {
        [
            RecurringSeries(id: 1, label: "Netflix", counterparty: "NETFLIX.COM", expectedAmount: -15.49,
                            cadence: .monthly, status: .confirmed, nextDueDate: "2026-08-05", lastSeenDate: "2026-07-05",
                            categoryId: 4, categoryName: "Abonnements", categoryColor: "#6366F1",
                            confidence: 96, variable: false, amountMin: nil, amountMax: nil,
                            previousAmount: -13.49, priceChangedAt: "2026-07-05",
                            autoConfirmed: false, runtimeStatus: .scheduled),
            RecurringSeries(id: 2, label: "Spotify", counterparty: "SPOTIFY", expectedAmount: -10.99,
                            cadence: .monthly, status: .suggested, nextDueDate: "2026-07-28", lastSeenDate: "2026-06-28",
                            categoryId: 4, categoryName: "Abonnements", categoryColor: "#6366F1",
                            confidence: 91, variable: false, amountMin: nil, amountMax: nil,
                            previousAmount: nil, priceChangedAt: nil,
                            autoConfirmed: false, runtimeStatus: .dueSoon),
            RecurringSeries(id: 3, label: "Assurance habitation", counterparty: "MAIF", expectedAmount: -18.20,
                            cadence: .monthly, status: .confirmed, nextDueDate: "2026-07-15", lastSeenDate: "2026-06-15",
                            categoryId: nil, categoryName: nil, categoryColor: nil,
                            confidence: 84, variable: true, amountMin: -16, amountMax: -24,
                            previousAmount: nil, priceChangedAt: nil,
                            autoConfirmed: true, runtimeStatus: .late),
            RecurringSeries(id: 4, label: "Salle de sport", counterparty: "BASIC FIT", expectedAmount: -29.99,
                            cadence: .monthly, status: .ignored, nextDueDate: "2026-07-20", lastSeenDate: "2026-06-20",
                            categoryId: 3, categoryName: "Loisirs", categoryColor: "#8B5CF6",
                            confidence: 77, variable: false, amountMin: nil, amountMax: nil,
                            previousAmount: nil, priceChangedAt: nil,
                            autoConfirmed: false, runtimeStatus: .scheduled),
        ]
    }

    static func recurringActivity() -> [RecurringActivity] {
        [
            RecurringActivity(seriesId: 1, label: "Netflix", type: .priceChange, occurredOn: "2026-07-05",
                              expectedAmount: -15.49, previousAmount: -13.49, cadence: .monthly,
                              categoryId: 4, categoryName: "Abonnements", categoryColor: "#6366F1"),
            RecurringActivity(seriesId: 3, label: "Assurance habitation", type: .autoConfirmed, occurredOn: "2026-06-15",
                              expectedAmount: -18.20, previousAmount: nil, cadence: .monthly,
                              categoryId: nil, categoryName: nil, categoryColor: nil),
        ]
    }

    static func recurringCalendar() -> [RecurringOccurrence] {
        [
            RecurringOccurrence(seriesId: 3, label: "Assurance habitation", counterparty: "MAIF", expectedAmount: -18.20,
                                dueDate: "2026-07-15", categoryId: nil, categoryName: nil, categoryColor: nil),
            RecurringOccurrence(seriesId: 4, label: "Salle de sport", counterparty: "BASIC FIT", expectedAmount: -29.99,
                                dueDate: "2026-07-20", categoryId: 3, categoryName: "Loisirs", categoryColor: "#8B5CF6"),
            RecurringOccurrence(seriesId: 2, label: "Spotify", counterparty: "SPOTIFY", expectedAmount: -10.99,
                                dueDate: "2026-07-28", categoryId: 4, categoryName: "Abonnements", categoryColor: "#6366F1"),
            RecurringOccurrence(seriesId: 1, label: "Netflix", counterparty: "NETFLIX.COM", expectedAmount: -15.49,
                                dueDate: "2026-08-05", categoryId: 4, categoryName: "Abonnements", categoryColor: "#6366F1"),
        ]
    }

    static func makeGoal(from request: GoalRequest, id: Int64) -> GoalProgress {
        GoalProgress(id: id, name: request.name, targetAmount: request.targetAmount,
                     currentTotal: 0, percentComplete: 0, deadline: request.deadline,
                     monthlyNeeded: nil, monthsLeft: nil, isOnTrack: false)
    }

    private static func provider(for type: String) -> String {
        switch type {
        case "PEA", "COMPTE_TITRES": return "Bourse Direct"
        case "CRYPTO": return "Ledger"
        case "SAVINGS", "LEP": return "Crédit Agricole"
        case "CHECKING": return "Boursorama"
        default: return "Manuel"
        }
    }
}
