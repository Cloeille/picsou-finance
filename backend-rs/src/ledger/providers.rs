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
/// The externalAccountId prefix of Caisse d'Epargne accounts.
pub const CAISSE_EPARGNE_ID_PREFIX: &str = "ce_";
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
///
/// Sync provenance (`ce_` / `bp_` external id prefix) is authoritative whenever an external id is
/// present, so renaming the user-editable `provider` label never drops classification between
/// syncs and accounts synced from other providers cannot be misclassified. When `external_account_id`
/// is absent (legacy rows without a stored external id), falls back to the provider name for
/// backwards compatibility.
pub fn is_insurer_contract(account: &Account) -> bool {
    if !matches!(
        account.account_type,
        AccountType::Per | AccountType::AssuranceVie
    ) || account.is_manual
    {
        return false;
    }
    match account
        .external_account_id
        .as_deref()
        .filter(|id| !id.trim().is_empty())
    {
        Some(id) => {
            id.starts_with(CAISSE_EPARGNE_ID_PREFIX) || id.starts_with(BANQUE_POPULAIRE_ID_PREFIX)
        }
        None => {
            let provider = account.provider.as_deref();
            provider == Some(CAISSE_EPARGNE)
                || provider.is_some_and(|p| p.starts_with(BANQUE_POPULAIRE))
        }
    }
}

#[cfg(test)]
mod tests {
    use chrono::DateTime;
    use rust_decimal::Decimal;

    use super::*;

    fn account(
        account_type: AccountType,
        is_manual: bool,
        external_id: Option<&str>,
        provider: Option<&str>,
    ) -> Account {
        let now = DateTime::from_timestamp(1_768_474_800, 0).expect("fixed timestamp");
        Account {
            id: 1,
            member_id: 1,
            name: "Contract".to_owned(),
            account_type,
            provider: provider.map(str::to_owned),
            currency: "EUR".to_owned(),
            current_balance: Decimal::ZERO,
            cash_balance: None,
            last_synced_at: None,
            payment_due_amount: None,
            payment_due_date: None,
            card_nature: None,
            reward_points: None,
            external_account_id: external_id.map(str::to_owned),
            is_manual,
            color: "#8b5cf6".to_owned(),
            iban: None,
            ticker: None,
            logo_url: None,
            logo_key: None,
            institution_name: None,
            institution_domain: None,
            requisition_id: None,
            opened_at: None,
            provider_invested_eur: None,
            provider_paid_in_eur: None,
            scheduled_payment_eur: None,
            scheduled_payment_cadence: None,
            deleted_at: None,
            parent_account_id: None,
            hidden: false,
            tracked_isin: None,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn insurer_contract_uses_bpce_provenance_with_legacy_fallback() {
        let cases = [
            // Synced ce_ / bp_ PER and assurance vie stay classified even after provider rename/clear.
            (
                AccountType::Per,
                false,
                Some("ce_1"),
                Some(CAISSE_EPARGNE),
                true,
            ),
            (AccountType::Per, false, Some("ce_1"), Some("Custom"), true),
            (AccountType::Per, false, Some("ce_1"), None, true),
            (
                AccountType::AssuranceVie,
                false,
                Some("ce_2"),
                Some("Custom"),
                true,
            ),
            (
                AccountType::Per,
                false,
                Some("bp_1"),
                Some("BRED Banque Populaire"),
                true,
            ),
            (
                AccountType::AssuranceVie,
                false,
                Some("bp_2"),
                Some("Custom"),
                true,
            ),
            // Manual accounts never qualify.
            (
                AccountType::Per,
                true,
                Some("ce_1"),
                Some(CAISSE_EPARGNE),
                false,
            ),
            (
                AccountType::AssuranceVie,
                true,
                None,
                Some(CAISSE_EPARGNE),
                false,
            ),
            // Account-type guard stays enforced.
            (
                AccountType::Checking,
                false,
                Some("ce_3"),
                Some(CAISSE_EPARGNE),
                false,
            ),
            (
                AccountType::Pea,
                false,
                Some("ce_4"),
                Some(CAISSE_EPARGNE),
                false,
            ),
            // Foreign synced provenance is not misclassified if provider is renamed.
            (
                AccountType::AssuranceVie,
                false,
                Some("corum_life_1"),
                Some(CAISSE_EPARGNE),
                false,
            ),
            (
                AccountType::Per,
                false,
                Some("bourso_1"),
                Some("Banque Populaire Rives de Paris"),
                false,
            ),
            // Legacy non-manual rows without external_account_id fall back to provider name.
            (AccountType::Per, false, None, Some(CAISSE_EPARGNE), true),
            (
                AccountType::AssuranceVie,
                false,
                None,
                Some("Banque Populaire Val de France"),
                true,
            ),
            (AccountType::AssuranceVie, false, None, Some("CORUM"), false),
        ];
        for (kind, is_manual, ext_id, provider, expected) in cases {
            assert_eq!(
                is_insurer_contract(&account(kind, is_manual, ext_id, provider)),
                expected,
                "case=({kind:?}, manual={is_manual}, ext={ext_id:?}, provider={provider:?})"
            );
        }
    }
}
