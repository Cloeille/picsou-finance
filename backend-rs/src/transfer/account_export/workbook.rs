//! AccountsWorkbookService and AccountSheetWriter: a summary sheet, a debt sheet, then one sheet
//! per selected account with its identity, positions and property, loan or peer-loan detail.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};

use chrono::NaiveDate;
use rust_decimal::Decimal;
use rust_xlsxwriter::{Workbook, Worksheet, XlsxError};

use super::labels::{LabelKey as L, SheetLabels};
use super::sheet::{Cursor, Styles, Value};
use crate::AppState;
use crate::db::{AsDbHandle, DbFromRow, DbHandle};
use crate::error::{AppError, AppResult};
use crate::ledger::decimal::{HALF_UP, divide};
use crate::ledger::loans::{self, LoanScheduleResponse};
use crate::ledger::model::{
    Account, AccountHolding, AccountType, Debt, HOLDING_COLUMNS, PeerLoanRatePeriod,
    PeerLoanRepayment, PeerLoanTerms,
};
use crate::ledger::peer_loans::{self, Computed};
use crate::ledger::sync_values::java_trim;
use crate::ledger::{access, accounts, liabilities, providers, valuation};

const MAX_SHEET_NAME: usize = 31;
const HUNDRED: Decimal = Decimal::ONE_HUNDRED;

struct DebtData {
    debt: Debt,
    loan_account: Option<Account>,
    linked_account: Option<Account>,
    schedule: LoanScheduleResponse,
}

impl DebtData {
    fn loan_account_name(&self) -> Option<&str> {
        self.loan_account.as_ref().map(|a| a.name.as_str())
    }

    fn outstanding(&self) -> Option<Decimal> {
        self.loan_account.as_ref().map(|a| a.current_balance)
    }

    fn financed_account_name(&self) -> Option<&str> {
        self.linked_account.as_ref().map(|a| a.name.as_str())
    }
}

struct HoldingRow {
    ticker: String,
    name: Option<String>,
    quantity: Decimal,
    average_buy_in: Option<Decimal>,
    current_price: Option<Decimal>,
    quote_currency: Option<String>,
    current_value_eur: Option<Decimal>,
    cost_basis_eur: Option<Decimal>,
    pnl_eur: Option<Decimal>,
    pnl_percent: Option<Decimal>,
    price_as_of: Option<NaiveDate>,
    price_stale: bool,
}

#[derive(DbFromRow)]
struct RealEstate {
    purchase_price: Option<Decimal>,
    purchase_date: Option<NaiveDate>,
    agency_fees: Option<Decimal>,
    notary_fees: Option<Decimal>,
    works_cost: Option<Decimal>,
    property_type: Option<String>,
    category: Option<String>,
    address: Option<String>,
    postal_code: Option<String>,
    city: Option<String>,
    country: Option<String>,
    surface_area: Option<Decimal>,
    land_area: Option<Decimal>,
    construction_year: Option<i16>,
    rooms: Option<i16>,
    energy_class: Option<String>,
    rental_income: Option<Decimal>,
    valuation_mode: Option<String>,
}

impl RealEstate {
    fn cost_basis(&self) -> Decimal {
        [self.agency_fees, self.notary_fees, self.works_cost]
            .into_iter()
            .flatten()
            .fold(self.purchase_price.unwrap_or_default(), |a, b| a + b)
    }

    /// PropertyKind.parse, or the free text when it names no kind.
    fn kind_or_type(&self) -> Option<String> {
        let raw = self.property_type.as_deref()?;
        let upper = java_trim(raw).to_uppercase();
        let kind = match upper.as_str() {
            "HOUSE" | "MAISON" | "VILLA" => Some("HOUSE"),
            "APARTMENT" | "APPARTEMENT" | "APPART" | "FLAT" | "STUDIO" => Some("APARTMENT"),
            "BUILDING" | "IMMEUBLE" => Some("BUILDING"),
            "LAND" | "TERRAIN" => Some("LAND"),
            "PARKING" | "GARAGE" | "BOX" => Some("PARKING"),
            "COMMERCIAL" | "COMMERCE" | "LOCAL_COMMERCIAL" | "BUREAU" | "OFFICE" => {
                Some("COMMERCIAL")
            }
            _ => None,
        };
        Some(kind.map_or_else(|| raw.to_owned(), str::to_owned))
    }
}

#[derive(DbFromRow)]
struct PropertyValuation {
    valued_at: NaiveDate,
    estimated_value: Decimal,
    low_value: Option<Decimal>,
    high_value: Option<Decimal>,
    price_per_sqm: Option<Decimal>,
    provider: String,
    confidence: Option<String>,
    sample_size: Option<i32>,
    source_year: Option<i16>,
}

struct PeerLoanData {
    terms: PeerLoanTerms,
    counterparty: Option<String>,
    linked_account: Option<String>,
    closed_at: Option<NaiveDate>,
    computed: Computed,
    planned_interest: Option<Decimal>,
    repayments: Vec<(PeerLoanRepayment, Option<Decimal>, Option<Decimal>)>,
}

struct AccountData {
    account: Account,
    balance_eur: Decimal,
    share_percent: Option<Decimal>,
    holdings: Vec<HoldingRow>,
    real_estate: Option<(RealEstate, Option<NaiveDate>)>,
    valuations: Vec<PropertyValuation>,
    debt: Option<DebtData>,
    financing: Vec<DebtData>,
    peer_loan: Option<PeerLoanData>,
}

#[derive(DbFromRow, Default)]
struct Profile {
    birth_date: Option<NaiveDate>,
    marginal_tax_rate: Option<Decimal>,
    household_status: Option<String>,
    tax_household_parts: Option<Decimal>,
    dependents: Option<i16>,
    annual_gross_income: Option<Decimal>,
    monthly_net_before_tax: Option<Decimal>,
    withholding_tax_rate: Option<Decimal>,
    monthly_savings_capacity: Option<Decimal>,
    target_retirement_age: Option<i16>,
    risk_profile: Option<String>,
}

impl Profile {
    fn age(&self, today: NaiveDate) -> Option<i64> {
        let birth = self.birth_date?;
        let mut years = i64::from(today.years_since(birth).unwrap_or(0));
        if birth > today {
            // Period.between counts backwards for a date in the future.
            years = -i64::from(birth.years_since(today).unwrap_or(0));
        }
        Some(years)
    }

    fn monthly_net_income(&self) -> Option<Decimal> {
        let net = self.monthly_net_before_tax?;
        let rate = self.withholding_tax_rate?;
        Some(divide(net * (HUNDRED - rate), HUNDRED, 2, HALF_UP))
    }
}

#[derive(DbFromRow)]
struct Plan {
    id: i64,
    name: String,
    monthly_amount: Option<Decimal>,
    expected_return: Option<Decimal>,
    start_date: Option<NaiveDate>,
    end_date: Option<NaiveDate>,
}

struct PlanData {
    plan: Plan,
    account_name: Option<String>,
    allocations: Vec<(String, Option<String>, Decimal)>,
}

/// Builds the workbook; every account read goes through the member-scoped path, so an id the
/// member may not read fails the whole export with a 404.
pub async fn build(
    state: &AppState,
    account_ids: &[i64],
    member_id: i64,
    labels: &SheetLabels,
) -> AppResult<Vec<u8>> {
    let today = state.clock.today();
    let mut conn = state.db.acquire().await?;
    let mut seen = HashSet::new();
    let mut data = Vec::new();
    for id in account_ids {
        if seen.insert(*id) {
            data.push(gather(state, &mut conn.as_handle(), *id, member_id, today).await?);
        }
    }
    let mut debts = Vec::new();
    for debt in liabilities::debts_of_member(&mut conn, member_id).await? {
        debts.push(debt_data(&mut conn.as_handle(), debt, today).await?);
    }
    let profile: Profile = crate::db::query_as(
        "SELECT birth_date, marginal_tax_rate, household_status, tax_household_parts, dependents, \
         annual_gross_income, monthly_net_before_tax, withholding_tax_rate, monthly_savings_capacity, \
         target_retirement_age, risk_profile FROM member_profile WHERE member_id = $1",
    )
    .bind(member_id)
    .fetch_optional(&mut conn)
    .await?
    .unwrap_or_default();
    let plans = recurring_plans(&mut conn.as_handle(), member_id).await?;
    drop(conn);

    write(state, labels, &data, &profile, &plans, &debts, today)
        .map_err(|e| AppError::internal(format!("xlsx: {e}")))
}

async fn gather(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    id: i64,
    member_id: i64,
    today: NaiveDate,
) -> AppResult<AccountData> {
    let account = access::require_readable(conn, id, member_id).await?;
    let share = access::share_for(&mut *conn, &account, member_id).await?;
    let share_percent = (share != HUNDRED).then_some(share);
    let prices = &*state.ledger.prices;
    let balance_eur = valuation::live_balance_eur(conn, prices, &account, today).await?;

    accounts::get_or_throw(&mut *conn, id, member_id).await?;
    let mut stored: Vec<AccountHolding> = crate::db::query_as(&format!(
        "SELECT {HOLDING_COLUMNS} FROM account_holding h WHERE h.account_id = $1 ORDER BY h.id"
    ))
    .bind(id)
    .fetch_all(&mut *conn)
    .await?;
    // Match PostgreSQL `ORDER BY h.current_price DESC, h.id` (NULLS FIRST + exact Decimal order).
    stored.sort_by(|a, b| {
        match (a.current_price, b.current_price) {
            (None, None) => Ordering::Equal,
            (None, Some(_)) => Ordering::Less,
            (Some(_), None) => Ordering::Greater,
            (Some(pa), Some(pb)) => pb.cmp(&pa),
        }
        .then_with(|| a.id.cmp(&b.id))
    });
    let quotes = valuation::quotes_for(prices, &account, &stored, today).await;
    let holdings = stored
        .iter()
        .map(|h| holding_row(&account, h, &quotes))
        .collect();

    let (real_estate, valuations) = if account.account_type == AccountType::RealEstate {
        let valuations: Vec<PropertyValuation> = crate::db::query_as(
            "SELECT valued_at, estimated_value, low_value, high_value, price_per_sqm, provider, confidence, \
             sample_size, source_year FROM property_valuation WHERE account_id = $1 ORDER BY valued_at DESC, id DESC",
        )
        .bind(id)
        .fetch_all(&mut *conn)
        .await?;
        let meta: Option<RealEstate> = crate::db::query_as(
            "SELECT purchase_price, purchase_date, agency_fees, notary_fees, works_cost, property_type, category, \
             address, postal_code, city, country, surface_area, land_area, construction_year, rooms, energy_class, \
             rental_income, valuation_mode FROM real_estate_metadata WHERE account_id = $1",
        )
        .bind(id)
        .fetch_optional(&mut *conn)
        .await?;
        let last = valuations.first().map(|v| v.valued_at);
        (meta.map(|m| (m, last)), valuations)
    } else {
        (None, Vec::new())
    };

    let debt = if account.account_type == AccountType::Loan {
        match liabilities::debt_of_account(&mut *conn, id).await? {
            Some(d) => Some(debt_data(conn, d, today).await?),
            None => None,
        }
    } else {
        None
    };

    let peer_loan = if account.account_type == AccountType::PeerLoan {
        peer_loan(conn, id, today).await?
    } else {
        None
    };

    let mut financing = Vec::new();
    for d in liabilities::debts_linked_to(&mut *conn, id).await? {
        financing.push(debt_data(conn, d, today).await?);
    }

    Ok(AccountData {
        account,
        balance_eur,
        share_percent,
        holdings,
        real_estate,
        valuations,
        debt,
        financing,
        peer_loan,
    })
}

async fn debt_data(conn: &mut DbHandle<'_>, debt: Debt, today: NaiveDate) -> AppResult<DebtData> {
    let loan_account = accounts::find_by_id(&mut *conn, debt.account_id).await?;
    let linked_account = match debt.linked_account_id {
        Some(id) => accounts::find_by_id(&mut *conn, id).await?,
        None => None,
    };
    let schedule = loans::compute(&debt, today);
    Ok(DebtData {
        debt,
        loan_account,
        linked_account,
        schedule,
    })
}

async fn peer_loan(
    conn: &mut DbHandle<'_>,
    account_id: i64,
    today: NaiveDate,
) -> AppResult<Option<PeerLoanData>> {
    let Some(terms) = liabilities::peer_loan_terms_of_account(&mut *conn, account_id).await? else {
        return Ok(None);
    };
    let repayments = liabilities::peer_loan_repayments_of_account(&mut *conn, account_id).await?;
    let computed = peer_loans::compute(&terms, &repayments, today);
    let planned_interest = peer_loans::plan(&terms, Some(computed.principal_remaining), today)
        .map_err(|e| AppError::BadRequest(e.to_string()))?
        .map(|p| p.planned_interest);
    let (counterparty, closed_at): (Option<String>, Option<NaiveDate>) = crate::db::query_as(
        "SELECT c.name, t.closed_at FROM peer_loan_terms t LEFT JOIN peer_counterparty c ON c.id = t.counterparty_id \
         WHERE t.id = $1",
    )
    .bind(terms.id)
    .fetch_one(&mut *conn)
    .await?;
    let linked_account = match terms.linked_account_id {
        Some(id) => accounts::find_by_id(&mut *conn, id).await?.map(|a| a.name),
        None => None,
    };
    let rows = repayments
        .into_iter()
        .enumerate()
        .map(|(i, r)| {
            let split = computed.splits.get(i);
            let (interest, capital) = (split.map(|s| s.interest), split.map(|s| s.capital));
            (r, interest, capital)
        })
        .collect();
    Ok(Some(PeerLoanData {
        terms,
        counterparty,
        linked_account,
        closed_at,
        computed,
        planned_interest,
        repayments: rows,
    }))
}

async fn recurring_plans(conn: &mut DbHandle<'_>, member_id: i64) -> AppResult<Vec<PlanData>> {
    let plans: Vec<Plan> = crate::db::query_as(
        "SELECT id, name, monthly_amount, expected_return, start_date, end_date FROM goal \
         WHERE member_id = $1 AND type = 'RECURRING_INVESTMENT' ORDER BY created_at, id",
    )
    .bind(member_id)
    .fetch_all(&mut *conn)
    .await?;
    let first_sql = if conn.is_sqlite() {
        "SELECT a.id, a.name FROM goal_account ga JOIN account a ON a.id = ga.account_id \
         WHERE ga.goal_id = $1 AND a.deleted_at IS NULL ORDER BY ga.rowid LIMIT 1"
    } else {
        "SELECT a.id, a.name FROM goal_account ga JOIN account a ON a.id = ga.account_id \
         WHERE ga.goal_id = $1 AND a.deleted_at IS NULL ORDER BY ga.ctid LIMIT 1"
    };
    let mut out = Vec::new();
    for plan in plans {
        let first: Option<(i64, String)> = crate::db::query_as(first_sql)
            .bind(plan.id)
            .fetch_optional(&mut *conn)
            .await?;
        let lines: Vec<(String, Decimal)> = crate::db::query_as(
            "SELECT ticker, monthly_amount FROM goal_allocation WHERE goal_id = $1 ORDER BY id",
        )
        .bind(plan.id)
        .fetch_all(&mut *conn)
        .await?;
        let mut names = HashMap::new();
        if let Some((account_id, _)) = &first
            && !lines.is_empty()
        {
            let held: Vec<(String, Option<String>)> = crate::db::query_as(
                "SELECT ticker, name FROM account_holding WHERE account_id = $1",
            )
            .bind(account_id)
            .fetch_all(&mut *conn)
            .await?;
            names.extend(held);
        }
        out.push(PlanData {
            allocations: lines
                .into_iter()
                .map(|(t, m)| {
                    let name = names.get(&t).cloned().flatten();
                    (t, name, m)
                })
                .collect(),
            account_name: first.map(|(_, n)| n),
            plan,
        });
    }
    Ok(out)
}

/// AccountService.toHoldingResponse, without the logos the sheet does not print.
fn holding_row(
    account: &Account,
    holding: &AccountHolding,
    quotes: &HashMap<String, crate::ledger::prices::Quote>,
) -> HoldingRow {
    let mut current_price = holding.current_price;
    let quantity = holding.quantity;
    let mut quote_currency = holding.quote_currency.clone();
    let mut current_price_eur = None;
    let mut price_as_of = None;
    let mut price_stale = false;
    let has_ticker = !crate::ledger::sync_values::java_is_blank(&holding.ticker);
    let insurer = providers::is_insurer_contract(account);
    let has_provider_value = holding.provider_value_eur.is_some();
    let provider_value = holding
        .provider_value_eur
        .filter(|v| !(insurer && *v <= Decimal::ZERO));
    if has_ticker {
        let quote = quotes.get(&holding.ticker.to_uppercase());
        let usable = quote.is_some_and(|q| !insurer || q.price > Decimal::ZERO);
        if usable {
            let q = quote.expect("checked above");
            current_price_eur = Some(q.price);
            if insurer {
                current_price = Some(q.price);
                quote_currency = Some("EUR".into());
            }
            price_as_of = Some(q.as_of);
            price_stale = !q.live;
        } else if insurer && (quote.is_some() || has_provider_value) {
            price_stale = true;
        }
    } else if insurer && has_provider_value {
        price_stale = true;
    }
    if insurer
        && current_price_eur.is_none()
        && let Some(value) = provider_value
    {
        current_price = (!quantity.is_zero()).then(|| divide(value, quantity, 8, HALF_UP));
        quote_currency = Some("EUR".into());
        price_as_of = holding.provider_price_date;
        price_stale = true;
    }
    let average_buy_in = holding.average_buy_in;
    let mut cost_basis = provider_value.and_then(|_| valuation::provider_cost_basis_eur(holding));
    if cost_basis.is_none() {
        cost_basis = average_buy_in.map(|a| a * quantity);
    }
    let current_value_eur = current_price_eur.map(|p| p * quantity).or(provider_value);
    let pnl_eur = match (current_value_eur, cost_basis) {
        (Some(v), Some(c)) => Some(v - c),
        _ => holding.provider_pnl_eur,
    };
    let pnl_percent = match (pnl_eur, cost_basis) {
        (Some(p), Some(c)) if !c.is_zero() => Some(divide(p, c.abs(), 4, HALF_UP) * HUNDRED),
        _ => None,
    };
    HoldingRow {
        ticker: holding.ticker.clone(),
        name: holding.name.clone(),
        quantity,
        average_buy_in,
        current_price,
        quote_currency,
        current_value_eur,
        cost_basis_eur: cost_basis,
        pnl_eur,
        pnl_percent,
        price_as_of,
        price_stale,
    }
}

/// WorkbookUtil.createSafeSheetName: at most 31 characters, the forbidden ones replaced by a
/// space, an apostrophe at either end too.
fn create_safe_sheet_name(name: &str) -> String {
    if name.is_empty() {
        return "empty".into();
    }
    let chars: Vec<char> = name.chars().take(MAX_SHEET_NAME).collect();
    let last = chars.len() - 1;
    chars
        .iter()
        .enumerate()
        .map(|(i, c)| match c {
            '\u{0}' | '\u{3}' | ':' | '/' | '\\' | '?' | '*' | ']' | '[' => ' ',
            '\'' if i == 0 || i == last => ' ',
            other => *other,
        })
        .collect()
}

fn safe_name(raw: Option<&str>, fallback: &str) -> String {
    let mut candidate = java_trim(raw.unwrap_or_default()).to_owned();
    if candidate.is_empty() {
        candidate = fallback.to_owned();
    }
    let safe = java_trim(&create_safe_sheet_name(&candidate)).to_owned();
    if safe.is_empty() {
        create_safe_sheet_name(fallback)
    } else {
        safe
    }
}

fn unique_sheet_name(
    account: &Account,
    labels: &SheetLabels,
    used: &mut HashSet<String>,
) -> String {
    let fallback = format!("{} {}", labels.get(L::AccountFallbackName), account.id);
    let base = safe_name(Some(&account.name), &fallback);
    let mut candidate = base.clone();
    let mut suffix = 2;
    while !used.insert(candidate.to_lowercase()) {
        let tail = format!(" ({suffix})");
        suffix += 1;
        let keep = base
            .chars()
            .count()
            .min(MAX_SHEET_NAME - tail.chars().count());
        candidate = base.chars().take(keep).collect::<String>() + &tail;
    }
    candidate
}

/// POI's setColumnWidth(first * 256) on column 0 and (18 * 256) on columns 1..=rest.
fn set_widths(sheet: &mut Worksheet, first: u16, rest: u16) -> Result<(), XlsxError> {
    sheet.set_column_width(0, first)?;
    for i in 1..=rest {
        sheet.set_column_width(i, 18)?;
    }
    Ok(())
}

fn sum(debts: &[DebtData], field: impl Fn(&DebtData) -> Option<Decimal>) -> Decimal {
    debts.iter().map(|d| field(d).unwrap_or_default()).sum()
}

fn rate_percent(ratio: Option<Decimal>) -> Option<Decimal> {
    ratio.map(|r| r * HUNDRED)
}

fn active_on(plan: &Plan, on: NaiveDate) -> bool {
    if plan.start_date.is_some_and(|s| s > on) {
        return false;
    }
    plan.end_date.is_none_or(|e| e >= on)
}

fn write(
    state: &AppState,
    labels: &SheetLabels,
    data: &[AccountData],
    profile: &Profile,
    plans: &[PlanData],
    debts: &[DebtData],
    today: NaiveDate,
) -> Result<Vec<u8>, XlsxError> {
    let styles = Styles::new();
    let zone = state.clock.zone();
    let mut workbook = Workbook::new();

    let summary_name = safe_name(Some(labels.get(L::SummarySheet)), "Summary");
    let debt_name = safe_name(Some(labels.get(L::DebtSheet)), "Debts");
    {
        let sheet = workbook.add_worksheet();
        sheet.set_name(&summary_name)?;
        set_widths(sheet, 34, 7)?;
        let mut c = Cursor::new(sheet, &styles, zone);
        c.field(labels.get(L::ExportedAt), Some(state.clock.now()))?;
        write_profile(&mut c, labels, profile, today)?;
        write_plans(&mut c, labels, profile, plans, today)?;
        write_debt_block(&mut c, labels, debts)?;
        c.blank();
        c.header_row(&[
            labels.get(L::AccountName),
            labels.get(L::AccountType),
            labels.get(L::Provider),
            labels.get(L::Currency),
            labels.get(L::Balance),
            labels.get(L::BalanceEur),
            labels.get(L::SharePercent),
            labels.get(L::LastSyncedAt),
        ])?;
        for d in data {
            let a = &d.account;
            let mut row = c.row();
            row.text(Some(&a.name))?;
            row.text(Some(a.account_type.as_str()))?;
            row.text(a.provider.as_deref())?;
            row.text(Some(&a.currency))?;
            row.money(Some(a.current_balance))?;
            row.money(Some(d.balance_eur))?;
            row.percent(d.share_percent)?;
            row.date_time(a.last_synced_at)?;
        }
    }
    {
        let sheet = workbook.add_worksheet();
        sheet.set_name(&debt_name)?;
        set_widths(sheet, 30, 14)?;
        write_debt_sheet(&mut Cursor::new(sheet, &styles, zone), labels, debts)?;
    }
    let mut used: HashSet<String> = [summary_name.to_lowercase(), debt_name.to_lowercase()].into();
    for d in data {
        let name = unique_sheet_name(&d.account, labels, &mut used);
        let sheet = workbook.add_worksheet();
        sheet.set_name(&name)?;
        set_widths(sheet, 30, 12)?;
        write_account(&mut Cursor::new(sheet, &styles, zone), labels, d)?;
    }
    workbook.save_to_buffer()
}

fn write_profile(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    p: &Profile,
    today: NaiveDate,
) -> Result<(), XlsxError> {
    let fields: Vec<(L, Value)> = [
        (L::Age, Value::from(p.age(today))),
        (
            L::TargetRetirementAge,
            p.target_retirement_age.map(i64::from).into(),
        ),
        (L::HouseholdStatus, p.household_status.clone().into()),
        (L::TaxHouseholdParts, p.tax_household_parts.into()),
        (L::Dependents, p.dependents.map(i64::from).into()),
        (L::AnnualGrossIncome, p.annual_gross_income.into()),
        (L::MonthlyNetBeforeTax, p.monthly_net_before_tax.into()),
        (L::MonthlyNetIncome, p.monthly_net_income().into()),
        (L::MonthlySavingsCapacity, p.monthly_savings_capacity.into()),
        (L::RiskProfile, p.risk_profile.clone().into()),
    ]
    .into_iter()
    .filter(|(_, v)| !matches!(v, Value::None))
    .collect();
    let has_rates = p.marginal_tax_rate.is_some() || p.withholding_tax_rate.is_some();
    if fields.is_empty() && !has_rates {
        return Ok(());
    }
    c.blank();
    c.title(labels.get(L::Profile))?;
    for (key, value) in fields {
        c.field(labels.get(key), value)?;
    }
    if p.marginal_tax_rate.is_some() {
        c.field_percent(labels.get(L::MarginalTaxRate), p.marginal_tax_rate)?;
    }
    if p.withholding_tax_rate.is_some() {
        c.field_percent(labels.get(L::WithholdingTaxRate), p.withholding_tax_rate)?;
    }
    Ok(())
}

fn write_plans(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    profile: &Profile,
    plans: &[PlanData],
    today: NaiveDate,
) -> Result<(), XlsxError> {
    if plans.is_empty() {
        return Ok(());
    }
    let monthly: Decimal = plans
        .iter()
        .filter(|p| active_on(&p.plan, today))
        .filter_map(|p| p.plan.monthly_amount)
        .sum();
    let rate = profile
        .monthly_net_income()
        .filter(|n| *n > Decimal::ZERO)
        .map(|n| divide(monthly * HUNDRED, n, 1, HALF_UP));
    c.blank();
    c.title(labels.get(L::RecurringInvestments))?;
    c.field(labels.get(L::MonthlyInvestedTotal), monthly)?;
    if rate.is_some() {
        c.field_percent(labels.get(L::SavingsRate), rate)?;
    }
    c.blank();
    c.header_row(&[
        labels.get(L::PlanName),
        labels.get(L::PlanAccount),
        labels.get(L::MonthlyAmount),
        labels.get(L::ExpectedReturn),
        labels.get(L::StartDate),
        labels.get(L::EndDate),
    ])?;
    for p in plans {
        let mut row = c.row();
        row.text(Some(&p.plan.name))?;
        row.text(p.account_name.as_deref())?;
        row.money(p.plan.monthly_amount)?;
        row.percent(p.plan.expected_return)?;
        row.date(p.plan.start_date)?;
        row.date(p.plan.end_date)?;
    }
    if plans.iter().all(|p| p.allocations.is_empty()) {
        return Ok(());
    }
    c.blank();
    c.title(labels.get(L::PositionBreakdown))?;
    c.header_row(&[
        labels.get(L::PlanName),
        labels.get(L::PlanAccount),
        labels.get(L::Ticker),
        labels.get(L::PositionName),
        labels.get(L::MonthlyAmount),
    ])?;
    for p in plans.iter().filter(|p| !p.allocations.is_empty()) {
        let mut allocated = Decimal::ZERO;
        for (ticker, name, amount) in &p.allocations {
            let mut row = c.row();
            row.text(Some(&p.plan.name))?;
            row.text(p.account_name.as_deref())?;
            row.text(Some(ticker))?;
            row.text(name.as_deref())?;
            row.money(Some(*amount))?;
            allocated += amount;
        }
        let remainder = p
            .plan
            .monthly_amount
            .map_or(Decimal::ZERO, |m| m - allocated);
        if remainder > Decimal::ZERO {
            let mut row = c.row();
            row.text(Some(&p.plan.name))?;
            row.text(p.account_name.as_deref())?;
            row.text(Some(labels.get(L::Unallocated)))?;
            row.text(None)?;
            row.money(Some(remainder))?;
        }
    }
    Ok(())
}

fn write_debt_block(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    debts: &[DebtData],
) -> Result<(), XlsxError> {
    c.blank();
    c.title(labels.get(L::Debts))?;
    if debts.is_empty() {
        c.field(labels.get(L::NoDebt), Value::None)?;
        c.field(labels.get(L::TotalOutstanding), Decimal::ZERO)?;
        return Ok(());
    }
    c.field(
        labels.get(L::TotalBorrowed),
        sum(debts, |d| Some(d.debt.borrowed_amount)),
    )?;
    c.field(
        labels.get(L::TotalOutstanding),
        sum(debts, DebtData::outstanding),
    )?;
    c.field(
        labels.get(L::TotalMonthlyPayment),
        sum(debts, |d| loans::resolve_monthly_payment(&d.debt)),
    )?;
    c.blank();
    c.header_row(&[
        labels.get(L::LoanAccount),
        labels.get(L::Lender),
        labels.get(L::LinkedAccount),
        labels.get(L::BorrowedAmount),
        labels.get(L::RemainingBalance),
        labels.get(L::MonthlyPayment),
        labels.get(L::InterestRate),
        labels.get(L::EndDate),
    ])?;
    for d in debts {
        let mut row = c.row();
        row.text(d.loan_account_name())?;
        row.text(d.debt.lender_name.as_deref())?;
        row.text(d.financed_account_name())?;
        row.money(Some(d.debt.borrowed_amount))?;
        row.money(d.outstanding())?;
        row.money(d.debt.monthly_payment)?;
        row.percent(rate_percent(d.debt.interest_rate))?;
        row.date(d.debt.end_date)?;
    }
    Ok(())
}

fn write_debt_sheet(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    debts: &[DebtData],
) -> Result<(), XlsxError> {
    c.title(labels.get(L::Debts))?;
    c.field(labels.get(L::DebtScopeNote), Value::None)?;
    if debts.is_empty() {
        c.blank();
        c.field(labels.get(L::NoDebt), Value::None)?;
        c.field(labels.get(L::TotalOutstanding), Decimal::ZERO)?;
        return Ok(());
    }
    c.blank();
    c.header_row(&[
        labels.get(L::LoanAccount),
        labels.get(L::Lender),
        labels.get(L::LinkedAccount),
        labels.get(L::BorrowedAmount),
        labels.get(L::RemainingBalance),
        labels.get(L::InterestRate),
        labels.get(L::MonthlyPayment),
        labels.get(L::InsuranceMonthly),
        labels.get(L::FileFees),
        labels.get(L::StartDate),
        labels.get(L::EndDate),
        labels.get(L::PaidInstallments),
        labels.get(L::TotalInstallments),
        labels.get(L::TotalInterestCost),
        labels.get(L::TotalInsuranceCost),
    ])?;
    for d in debts {
        let s = &d.schedule.summary;
        let mut row = c.row();
        row.text(d.loan_account_name())?;
        row.text(d.debt.lender_name.as_deref())?;
        row.text(d.financed_account_name())?;
        row.money(Some(d.debt.borrowed_amount))?;
        row.money(d.outstanding())?;
        row.percent(rate_percent(d.debt.interest_rate))?;
        row.money(d.debt.monthly_payment)?;
        row.money(d.debt.insurance_monthly)?;
        row.money(d.debt.file_fees)?;
        row.date(d.debt.start_date)?;
        row.date(d.debt.end_date)?;
        row.integer(Some(i64::from(s.paid_installments)))?;
        row.integer(Some(i64::from(s.total_installments)))?;
        row.money(Some(s.total_interest_cost))?;
        row.money(Some(s.total_insurance_cost))?;
    }
    Ok(())
}

fn write_account(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    d: &AccountData,
) -> Result<(), XlsxError> {
    let a = &d.account;
    c.title(&a.name)?;
    c.field(labels.get(L::AccountType), Some(a.account_type.as_str()))?;
    if a.opened_at.is_some() {
        c.field(labels.get(L::OpenedAt), a.opened_at)?;
    }
    c.field(labels.get(L::Provider), a.provider.as_deref())?;
    c.field(labels.get(L::Currency), Some(a.currency.as_str()))?;
    c.field(labels.get(L::Balance), a.current_balance)?;
    c.field(labels.get(L::BalanceEur), d.balance_eur)?;
    if a.cash_balance.is_some() {
        c.field(labels.get(L::CashBalance), a.cash_balance)?;
    }
    if d.share_percent.is_some() {
        c.field_percent(labels.get(L::SharePercent), d.share_percent)?;
    }
    c.field(labels.get(L::LastSyncedAt), a.last_synced_at)?;
    c.field(labels.get(L::CreatedAt), Some(a.created_at))?;

    if !d.holdings.is_empty() {
        write_positions(c, labels, &d.holdings)?;
    }
    if let Some((re, last_valued_at)) = &d.real_estate {
        write_property(c, labels, re, *last_valued_at, &d.valuations)?;
        write_financing(c, labels, &d.financing)?;
    }
    if let Some(debt) = &d.debt {
        write_loan(c, labels, debt)?;
    }
    if let Some(peer) = &d.peer_loan {
        write_peer_loan(c, labels, peer)?;
    }
    Ok(())
}

fn write_positions(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    holdings: &[HoldingRow],
) -> Result<(), XlsxError> {
    c.blank();
    c.title(labels.get(L::Positions))?;
    c.header_row(&[
        labels.get(L::Ticker),
        labels.get(L::PositionName),
        labels.get(L::Quantity),
        labels.get(L::AverageBuyIn),
        labels.get(L::CurrentPrice),
        labels.get(L::QuoteCurrency),
        labels.get(L::CurrentValueEur),
        labels.get(L::CostBasisEur),
        labels.get(L::PnlEur),
        labels.get(L::PnlPercent),
        labels.get(L::PriceAsOf),
        labels.get(L::PriceStale),
    ])?;
    for h in holdings {
        let mut row = c.row();
        row.text(Some(&h.ticker))?;
        row.text(h.name.as_deref())?;
        row.quantity(Some(h.quantity))?;
        row.quantity(h.average_buy_in)?;
        row.quantity(h.current_price)?;
        row.text(h.quote_currency.as_deref())?;
        row.money(h.current_value_eur)?;
        row.money(h.cost_basis_eur)?;
        row.money(h.pnl_eur)?;
        row.percent(h.pnl_percent)?;
        row.date(h.price_as_of)?;
        row.text(Some(labels.get(if h.price_stale { L::Yes } else { L::No })))?;
    }
    Ok(())
}

fn write_property(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    re: &RealEstate,
    last_valued_at: Option<NaiveDate>,
    valuations: &[PropertyValuation],
) -> Result<(), XlsxError> {
    c.blank();
    c.title(labels.get(L::Property))?;
    c.field(labels.get(L::PurchasePrice), re.purchase_price)?;
    c.field(labels.get(L::PurchaseDate), re.purchase_date)?;
    c.field(labels.get(L::AgencyFees), re.agency_fees)?;
    c.field(labels.get(L::NotaryFees), re.notary_fees)?;
    c.field(labels.get(L::WorksCost), re.works_cost)?;
    c.field(labels.get(L::CostBasis), re.cost_basis())?;
    c.field(labels.get(L::PropertyType), re.kind_or_type())?;
    c.field(labels.get(L::PropertyCategory), re.category.clone())?;
    c.field(labels.get(L::Address), re.address.clone())?;
    c.field(labels.get(L::PostalCode), re.postal_code.clone())?;
    c.field(labels.get(L::City), re.city.clone())?;
    c.field(labels.get(L::Country), re.country.clone())?;
    c.field(labels.get(L::SurfaceArea), re.surface_area)?;
    c.field(labels.get(L::LandArea), re.land_area)?;
    c.field(
        labels.get(L::ConstructionYear),
        re.construction_year.map(i64::from),
    )?;
    c.field(labels.get(L::Rooms), re.rooms.map(i64::from))?;
    c.field(labels.get(L::EnergyClass), re.energy_class.clone())?;
    c.field(labels.get(L::RentalIncome), re.rental_income)?;
    c.field(labels.get(L::ValuationMode), re.valuation_mode.clone())?;
    c.field(labels.get(L::LastValuedAt), last_valued_at)?;
    if valuations.is_empty() {
        return Ok(());
    }
    c.blank();
    c.title(labels.get(L::ValuationHistory))?;
    c.header_row(&[
        labels.get(L::ValuedAt),
        labels.get(L::EstimatedValue),
        labels.get(L::LowValue),
        labels.get(L::HighValue),
        labels.get(L::PricePerSqm),
        labels.get(L::ValuationProvider),
        labels.get(L::Confidence),
        labels.get(L::SampleSize),
        labels.get(L::SourceYear),
    ])?;
    for v in valuations {
        let mut row = c.row();
        row.date(Some(v.valued_at))?;
        row.money(Some(v.estimated_value))?;
        row.money(v.low_value)?;
        row.money(v.high_value)?;
        row.money(v.price_per_sqm)?;
        row.text(Some(&v.provider))?;
        row.text(v.confidence.as_deref())?;
        row.integer(v.sample_size.map(i64::from))?;
        row.integer(v.source_year.map(i64::from))?;
    }
    Ok(())
}

fn write_financing(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    financing: &[DebtData],
) -> Result<(), XlsxError> {
    c.blank();
    c.field(
        labels.get(L::PropertyDebt),
        sum(financing, DebtData::outstanding),
    )?;
    if financing.is_empty() {
        return Ok(());
    }
    c.header_row(&[
        labels.get(L::LoanAccount),
        labels.get(L::Lender),
        labels.get(L::BorrowedAmount),
        labels.get(L::RemainingBalance),
        labels.get(L::MonthlyPayment),
        labels.get(L::EndDate),
    ])?;
    for d in financing {
        let mut row = c.row();
        row.text(d.loan_account_name())?;
        row.text(d.debt.lender_name.as_deref())?;
        row.money(Some(d.debt.borrowed_amount))?;
        row.money(d.outstanding())?;
        row.money(d.debt.monthly_payment)?;
        row.date(d.debt.end_date)?;
    }
    Ok(())
}

fn write_loan(c: &mut Cursor<'_>, labels: &SheetLabels, d: &DebtData) -> Result<(), XlsxError> {
    let debt = &d.debt;
    c.blank();
    c.title(labels.get(L::Loan))?;
    c.field(labels.get(L::Lender), debt.lender_name.clone())?;
    c.field(labels.get(L::BorrowedAmount), debt.borrowed_amount)?;
    c.field_percent(
        labels.get(L::InterestRate),
        rate_percent(debt.interest_rate),
    )?;
    c.field(labels.get(L::MonthlyPayment), debt.monthly_payment)?;
    c.field(labels.get(L::InsuranceMonthly), debt.insurance_monthly)?;
    c.field(labels.get(L::FileFees), debt.file_fees)?;
    c.field(labels.get(L::StartDate), debt.start_date)?;
    c.field(labels.get(L::EndDate), debt.end_date)?;
    c.field(labels.get(L::LinkedAccount), d.financed_account_name())?;
    let s = &d.schedule.summary;
    c.field(labels.get(L::RemainingBalance), s.remaining_balance)?;
    c.field(
        labels.get(L::TotalInstallments),
        Some(i64::from(s.total_installments)),
    )?;
    c.field(
        labels.get(L::PaidInstallments),
        Some(i64::from(s.paid_installments)),
    )?;
    c.field(labels.get(L::TotalInterestCost), s.total_interest_cost)?;
    c.field(labels.get(L::TotalInsuranceCost), s.total_insurance_cost)?;
    c.field(labels.get(L::CapitalRepaid), s.capital_repaid)?;
    if d.schedule.schedule.is_empty() {
        return Ok(());
    }
    c.blank();
    c.title(labels.get(L::Amortization))?;
    c.header_row(&[
        labels.get(L::InstallmentNumber),
        labels.get(L::InstallmentDate),
        labels.get(L::Capital),
        labels.get(L::Interest),
        labels.get(L::Insurance),
        labels.get(L::TotalPayment),
        labels.get(L::RemainingBalance),
    ])?;
    for i in &d.schedule.schedule {
        let mut row = c.row();
        row.integer(Some(i64::from(i.number)))?;
        row.date(Some(i.date))?;
        row.money(Some(i.capital))?;
        row.money(Some(i.interest))?;
        row.money(Some(i.insurance))?;
        row.money(Some(i.total_payment))?;
        row.money(Some(i.remaining_balance))?;
    }
    Ok(())
}

/// The stored rate is annualized; a monthly loan prints it per month, as the member typed it.
fn rate_in_stated_period(t: &PeerLoanTerms) -> Option<Decimal> {
    let annual = t.annual_rate? * HUNDRED;
    if t.rate_period != PeerLoanRatePeriod::Monthly {
        return Some(annual);
    }
    Some(divide(annual, Decimal::from(12), 6, HALF_UP))
}

fn write_peer_loan(
    c: &mut Cursor<'_>,
    labels: &SheetLabels,
    p: &PeerLoanData,
) -> Result<(), XlsxError> {
    let t = &p.terms;
    c.blank();
    c.title(labels.get(L::PeerLoan))?;
    c.field(labels.get(L::Borrower), p.counterparty.clone())?;
    c.field(labels.get(L::Principal), t.principal)?;
    c.field(labels.get(L::LentAt), Some(t.lent_at))?;
    c.field(labels.get(L::ExpectedBack), t.expected_back)?;
    c.field_percent(labels.get(L::InterestRate), rate_in_stated_period(t))?;
    c.field(labels.get(L::RatePeriod), Some(t.rate_period.as_str()))?;
    c.field(labels.get(L::PlannedMonthly), t.planned_monthly_amount)?;
    c.field(
        labels.get(L::PlannedMonths),
        t.planned_months.map(i64::from),
    )?;
    c.field(labels.get(L::PlannedInterest), p.planned_interest)?;
    c.field(labels.get(L::CashAccount), p.linked_account.clone())?;
    c.field(labels.get(L::Note), t.note.clone())?;
    c.field(labels.get(L::ClosedAt), p.closed_at)?;
    let k = &p.computed;
    c.field(labels.get(L::PrincipalRemaining), k.principal_remaining)?;
    c.field(labels.get(L::InterestAccrued), k.interest_accrued)?;
    c.field(labels.get(L::InterestReceived), k.interest_received)?;
    c.field(labels.get(L::CapitalRepaid), k.principal_paid)?;
    c.field(labels.get(L::WrittenOff), k.written_off)?;
    if p.repayments.is_empty() {
        return Ok(());
    }
    c.blank();
    c.title(labels.get(L::RepaymentJournal))?;
    c.header_row(&[
        labels.get(L::PaidOn),
        labels.get(L::Amount),
        labels.get(L::Kind),
        labels.get(L::Interest),
        labels.get(L::Capital),
        labels.get(L::Note),
    ])?;
    for (r, interest, capital) in &p.repayments {
        let mut row = c.row();
        row.date(Some(r.paid_on))?;
        row.money(Some(r.amount))?;
        row.text(Some(r.kind.as_str()))?;
        row.money(*interest)?;
        row.money(*capital)?;
        row.text(r.note.as_deref())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sheet_names_are_made_safe() {
        assert_eq!(create_safe_sheet_name("a/b:c'"), "a b c ");
        assert_eq!(create_safe_sheet_name("'quoted' inside"), " quoted' inside");
        assert_eq!(safe_name(Some("  "), "Account 7"), "Account 7");
        assert_eq!(create_safe_sheet_name(&"x".repeat(40)).len(), 31);
    }
}
