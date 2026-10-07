import Foundation

/// Mirrors the backend `AccountType` enum. Unknown values fall back to `.other` so a new server
/// type never breaks decoding. Labels mirror the web's `accountTypes.*` (fr.json).
enum AccountType: String, CaseIterable {
    case lep = "LEP"
    case livretA = "LIVRET_A"
    case ldds = "LDDS"
    case livretJeune = "LIVRET_JEUNE"
    case pel = "PEL"
    case cel = "CEL"
    case pea = "PEA"
    case compteTitres = "COMPTE_TITRES"
    case crypto = "CRYPTO"
    case checking = "CHECKING"
    case savings = "SAVINGS"
    case realEstate = "REAL_ESTATE"
    case scpi = "SCPI"
    case loan = "LOAN"
    case employeeSavings = "EMPLOYEE_SAVINGS"
    case assuranceVie = "ASSURANCE_VIE"
    case creditCard = "CREDIT_CARD"
    case other = "OTHER"

    init(raw: String) {
        self = AccountType(rawValue: raw) ?? .other
    }

    var label: String {
        switch self {
        case .lep: return "LEP"
        case .livretA: return "Livret A"
        case .ldds: return "LDDS"
        case .livretJeune: return "Livret Jeune"
        case .pel: return "PEL"
        case .cel: return "CEL"
        case .pea: return "PEA"
        case .compteTitres: return "Compte-titres"
        case .crypto: return "Crypto"
        case .checking: return "Compte courant"
        case .savings: return "Livret d'épargne"
        case .realEstate: return "Immobilier"
        case .scpi: return "SCPI"
        case .loan: return "Prêt"
        case .employeeSavings: return "Épargne salariale"
        case .assuranceVie: return "Assurance vie"
        case .creditCard: return "Carte de crédit"
        case .other: return "Autre"
        }
    }

    /// Accounts whose positions are worth fetching (`GET /api/accounts/{id}/holdings`).
    var holdsPositions: Bool {
        [.pea, .compteTitres, .crypto, .assuranceVie, .employeeSavings].contains(self)
    }

    /// Money owed rather than wealth (backend `AccountType.isLiability`).
    var isLiability: Bool { self == .loan || self == .creditCard }

    /// Coarse grouping used by the accounts screen, in display order (the web's asset filters).
    var category: String {
        switch self {
        case .checking: return "Banque"
        case .lep, .livretA, .ldds, .livretJeune, .pel, .cel, .savings: return "Épargne"
        case .pea, .compteTitres, .employeeSavings, .assuranceVie, .crypto: return "Investissement"
        case .realEstate, .scpi: return "Immobilier"
        case .loan, .creditCard: return "Dettes"
        case .other: return "Autre"
        }
    }

    var categoryRank: Int {
        ["Banque", "Épargne", "Investissement", "Immobilier", "Dettes"].firstIndex(of: category) ?? 99
    }
}
