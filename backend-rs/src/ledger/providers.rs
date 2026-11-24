//! Provider names the valuation depends on, hoisted from the sync services that own them, and
//! the account classifications built on them.

use super::model::{Account, AccountType};

pub const BOURSE_DIRECT: &str = "Bourse Direct";
pub const AMUNDI: &str = "Amundi Épargne Salariale";
pub const CMES: &str = "Crédit Mutuel Épargne Salariale";
pub const NATIXIS: &str = "Natixis Interépargne";
pub const BOURSO: &str = "BoursoBank";
pub const FORTUNEO: &str = "Fortuneo";
pub const BNP: &str = "BNP Paribas";
pub const HELLO_BANK: &str = "Hello bank!";
pub const CAISSE_EPARGNE: &str = "Caisse d'Epargne";
pub const BANQUE_POPULAIRE: &str = "Banque Populaire";
/// The externalAccountId prefix of Banque Populaire accounts.
pub const BANQUE_POPULAIRE_ID_PREFIX: &str = "bp_";

/// Providers that report an authoritative EUR valuation of every holding: a partial market total
/// would understate them, so their own total wins when a line cannot be priced.
pub const PROVIDER_VALUED: &[&str] = &[
    BOURSE_DIRECT,
    AMUNDI,
    CMES,
    NATIXIS,
    BOURSO,
    FORTUNEO,
    BNP,
    HELLO_BANK,
];

/// Provider-valued accounts never priced from market quotes: Natixis employer funds are quoted
/// nowhere, so a quote found for one of their tickers belongs to another instrument.
pub const NEVER_MARKET_PRICED: &[&str] = &[NATIXIS];

/// AccountService.isProviderValued: a synced CORUM Life contract, or a provider-valued provider.
pub fn is_provider_valued(account: &Account) -> bool {
    account.is_synced_corum_life()
        || account
            .provider
            .as_deref()
            .is_some_and(|p| PROVIDER_VALUED.contains(&p))
}

pub fn is_never_market_priced(account: &Account) -> bool {
    account
        .provider
        .as_deref()
        .is_some_and(|p| NEVER_MARKET_PRICED.contains(&p))
}

/// CaisseEpargneSyncService.isInsurerContract: a synced PER or assurance vie of Caisse d'Epargne
/// or Banque Populaire, valued from the insurer's own unit prices.
pub fn is_insurer_contract(account: &Account) -> bool {
    let provider = account.provider.as_deref();
    let id = account.external_account_id.as_deref();
    matches!(
        account.account_type,
        AccountType::Per | AccountType::AssuranceVie
    ) && !account.is_manual
        && (provider == Some(CAISSE_EPARGNE)
            || provider.is_some_and(|p| p.starts_with(BANQUE_POPULAIRE))
            || id.is_some_and(|i| i.starts_with(BANQUE_POPULAIRE_ID_PREFIX)))
}
