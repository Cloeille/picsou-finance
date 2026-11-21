//! CashflowFlowService: the income -> hub -> expense/savings Sankey, the ranked expense breakdown
//! and one category's drill. Same range, transfer exclusion and sign split as CashflowService.

use std::collections::HashMap;

use axum::extract::State;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use super::cashflow::is_transfer;
use super::categories::require;
use super::cycle::{self, CashflowPeriod};
use super::params::Params;
use super::repo::{self, Refs};
use super::{jdec, settings};
use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::Path;
use crate::ledger::manual_transactions::TransactionResponse;
use crate::ledger::model::{Account, AssetClass, Category, Transaction};
use crate::routes::Routes;

/// Beyond this many expense bands the Sankey tail rolls into one "more" node.
const MAX_EXPENSE_NODES: usize = 8;
const TRADE_REPUBLIC: &str = "Trade Republic";

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/cashflow/flow", flow_handler)
        .get("/api/spending/by-category", by_category_handler)
        .get(
            "/api/spending/category/{categoryId}",
            category_detail_handler,
        );
}

/// The iteration order of a java.util.HashMap<Long, V> filled in this insertion order: by
/// bucket of the final table, then insertion order within a bucket. Ties of the stable sorts
/// that follow keep this order, as they do in Java.
pub fn java_hash_map_order<V>(entries: Vec<(Option<i64>, V)>) -> Vec<(Option<i64>, V)> {
    let mut capacity: usize = 16;
    while entries.len() * 4 > capacity * 3 {
        capacity *= 2;
    }
    let bucket = |key: Option<i64>| -> usize {
        let h = key.map_or(0u32, |v| {
            let bits = v.to_ne_bytes();
            let v = u64::from_ne_bytes(bits);
            ((v ^ (v >> 32)) & 0xFFFF_FFFF) as u32
        });
        let spread = h ^ (h >> 16);
        (spread as usize) & (capacity - 1)
    };
    let mut indexed: Vec<(usize, usize, (Option<i64>, V))> = entries
        .into_iter()
        .enumerate()
        .map(|(i, e)| (bucket(e.0), i, e))
        .collect();
    indexed.sort_by_key(|(b, i, _)| (*b, *i));
    indexed.into_iter().map(|(_, _, e)| e).collect()
}

/// A per-category tally (positive magnitude for income/expense, signed for the drill).
#[derive(Debug, Clone)]
struct Agg {
    category: Option<Category>,
    sum: Decimal,
    count: i32,
}

/// Tallies into an insertion-ordered list keyed like Java's HashMap, then returns Java's order.
#[derive(Default)]
struct Tally {
    entries: Vec<(Option<i64>, Agg)>,
}

impl Tally {
    fn add(&mut self, category: Option<&Category>, amount: Decimal) {
        let key = category.map(|c| c.id);
        match self.entries.iter_mut().find(|(k, _)| *k == key) {
            Some((_, agg)) => {
                agg.sum += amount;
                agg.count += 1;
            }
            None => self.entries.push((
                key,
                Agg {
                    category: category.cloned(),
                    sum: Decimal::ZERO + amount,
                    count: 1,
                },
            )),
        }
    }

    fn in_java_order(self) -> Vec<Agg> {
        java_hash_map_order(self.entries)
            .into_iter()
            .map(|(_, agg)| agg)
            .collect()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NodeType {
    Income,
    Hub,
    Expense,
    Savings,
    Withdrawal,
    TransferIn,
    TransferOut,
    Unspent,
    Shortfall,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowNode {
    pub key: String,
    pub label: Option<String>,
    pub color: Option<String>,
    #[serde(rename = "type")]
    pub node_type: NodeType,
    pub asset_class: Option<AssetClass>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowLink {
    pub source: usize,
    pub target: usize,
    #[serde(with = "jdec")]
    pub value: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CashflowFlowResponse {
    pub period: CashflowPeriod,
    pub from: NaiveDate,
    pub to: NaiveDate,
    #[serde(with = "jdec")]
    pub income: Decimal,
    #[serde(with = "jdec")]
    pub expense: Decimal,
    #[serde(with = "jdec")]
    pub net: Decimal,
    #[serde(with = "jdec")]
    pub saved: Decimal,
    #[serde(with = "jdec")]
    pub withdrawn: Decimal,
    #[serde(with = "jdec")]
    pub transferred_out: Decimal,
    #[serde(with = "jdec")]
    pub transferred_in: Decimal,
    pub nodes: Vec<FlowNode>,
    pub links: Vec<FlowLink>,
}

struct NodeValue {
    node: FlowNode,
    value: Decimal,
}

fn synthetic(key: &str, node_type: NodeType) -> FlowNode {
    FlowNode {
        key: key.to_owned(),
        label: None,
        color: None,
        node_type,
        asset_class: None,
    }
}

fn category_node(category: &Category, node_type: NodeType) -> FlowNode {
    FlowNode {
        key: format!("cat:{}", category.id),
        label: Some(category.name.clone()),
        color: Some(category.color.clone()),
        node_type,
        asset_class: None,
    }
}

fn account_node(account: &Account, node_type: NodeType) -> FlowNode {
    FlowNode {
        key: format!("acct:{}", account.id),
        label: Some(account.name.clone()),
        color: Some(account.color.clone()),
        node_type,
        asset_class: Some(AssetClass::of(account.account_type)),
    }
}

/// Largest first; a stable sort, so ties keep the incoming order.
fn sort_desc(nodes: &mut [NodeValue]) {
    nodes.sort_by_key(|n| std::cmp::Reverse(n.value));
}

fn positive(value: Decimal) -> bool {
    value > Decimal::ZERO
}

fn negative(value: Decimal) -> bool {
    value < Decimal::ZERO
}

/// The stem of a Trade Republic external id ending in the suffix (abc_cash -> abc).
fn trade_republic_stem<'a>(tx: &'a Transaction, suffix: &str) -> Option<&'a str> {
    let id = tx.external_id.as_deref()?;
    if !id.ends_with(suffix) || id.len() == suffix.len() {
        return None;
    }
    Some(&id[..id.len() - suffix.len()])
}

type PairKey = (String, NaiveDate, Decimal);

/// The Trade Republic cash legs that are not transfers: a mirrored investment leg of the same
/// trade is not money moving between accounts, so it is left out of the transfer tally.
fn trade_republic_cash_legs(rows: &[Transaction], refs: &Refs) -> HashMap<PairKey, usize> {
    let mut legs = HashMap::new();
    for tx in rows {
        let account = refs.account(tx);
        let Some(stem) = trade_republic_stem(tx, "_cash") else {
            continue;
        };
        if is_transfer(refs, tx)
            || account.provider.as_deref() != Some(TRADE_REPUBLIC)
            || AssetClass::of(account.account_type).tracks_contributions()
        {
            continue;
        }
        *legs
            .entry((stem.to_owned(), tx.date, tx.amount.normalize()))
            .or_insert(0) += 1;
    }
    legs
}

fn consume_mirrored_leg(
    tx: &Transaction,
    account: &Account,
    legs: &mut HashMap<PairKey, usize>,
) -> bool {
    if account.provider.as_deref() != Some(TRADE_REPUBLIC) {
        return false;
    }
    let Some(stem) = trade_republic_stem(tx, "_inv") else {
        return false;
    };
    let key = (stem.to_owned(), tx.date, (-tx.amount).normalize());
    match legs.get_mut(&key) {
        Some(count) if *count > 1 => {
            *count -= 1;
            true
        }
        Some(_) => {
            legs.remove(&key);
            true
        }
        None => false,
    }
}

/// Every TRANSFER leg: the signed net per savings/investment account (insertion order) and the
/// signed sum across all accounts.
struct Transfers {
    tracked: Vec<(Account, Decimal)>,
    all_legs: Decimal,
}

async fn transfers(
    state: &AppState,
    member_id: i64,
    from: NaiveDate,
    to: NaiveDate,
    mut cash_legs: HashMap<PairKey, usize>,
) -> AppResult<Transfers> {
    let rows = repo::member_transfers_between(&state.db, member_id, from, to).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    let mut tracked: Vec<(Account, Decimal)> = Vec::new();
    let mut all_legs = Decimal::ZERO;
    for tx in &rows {
        let account = refs.account(tx);
        let tracked_account = AssetClass::of(account.account_type).tracks_contributions();
        if tracked_account && consume_mirrored_leg(tx, account, &mut cash_legs) {
            continue;
        }
        all_legs += tx.amount;
        if tracked_account {
            match tracked.iter_mut().find(|(a, _)| a.id == account.id) {
                Some((_, sum)) => *sum += tx.amount,
                None => tracked.push((account.clone(), Decimal::ZERO + tx.amount)),
            }
        }
    }
    Ok(Transfers { tracked, all_legs })
}

pub async fn flow(
    state: &AppState,
    member_id: i64,
    period: CashflowPeriod,
    today: NaiveDate,
) -> AppResult<CashflowFlowResponse> {
    let day = settings::cycle_start_day(&state.db, member_id).await?;
    let (from, to) = cycle::range(period, today, day);
    let rows = repo::member_between(&state.db, member_id, from, to).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    let mut income = Tally::default();
    let mut expense = Tally::default();
    let mut total_income = Decimal::ZERO;
    let mut total_expense = Decimal::ZERO;
    for tx in rows.iter().filter(|tx| !is_transfer(&refs, tx)) {
        let category = refs.category(tx);
        if negative(tx.amount) {
            let magnitude = jdec::negate(tx.amount);
            total_expense += magnitude;
            expense.add(category, magnitude);
        } else {
            total_income += tx.amount;
            income.add(category, tx.amount);
        }
    }
    let cash_legs = trade_republic_cash_legs(&rows, &refs);
    let transfers = transfers(state, member_id, from, to, cash_legs).await?;
    let mut total_saved = Decimal::ZERO;
    let mut total_withdrawn = Decimal::ZERO;
    for (_, sum) in &transfers.tracked {
        if positive(*sum) {
            total_saved += *sum;
        } else if negative(*sum) {
            total_withdrawn += jdec::negate(*sum);
        }
    }
    let external = jdec::negate(transfers.all_legs);
    let transfer_out = if positive(external) {
        external
    } else {
        Decimal::ZERO
    };
    let transfer_in = if negative(external) {
        jdec::negate(external)
    } else {
        Decimal::ZERO
    };
    let net = total_income - total_expense;
    if total_income.is_zero()
        && total_expense.is_zero()
        && total_saved.is_zero()
        && total_withdrawn.is_zero()
        && external.is_zero()
    {
        return Ok(CashflowFlowResponse {
            period,
            from,
            to,
            income: Decimal::ZERO,
            expense: Decimal::ZERO,
            net: Decimal::ZERO,
            saved: Decimal::ZERO,
            withdrawn: Decimal::ZERO,
            transferred_out: Decimal::ZERO,
            transferred_in: Decimal::ZERO,
            nodes: Vec::new(),
            links: Vec::new(),
        });
    }
    let total_in = total_income + total_withdrawn + transfer_in;
    let total_out = total_expense + total_saved + transfer_out;

    let mut sources = income_nodes(income);
    sources.extend(withdrawal_nodes(&transfers.tracked));
    if positive(transfer_in) {
        sources.push(NodeValue {
            node: synthetic("__transfer_in__", NodeType::TransferIn),
            value: transfer_in,
        });
    }
    if total_out > total_in {
        sources.push(NodeValue {
            node: synthetic("__shortfall__", NodeType::Shortfall),
            value: total_out - total_in,
        });
    }
    let mut nodes: Vec<FlowNode> = Vec::new();
    let mut links: Vec<FlowLink> = Vec::new();
    let hub = sources.len();
    for (i, source) in sources.into_iter().enumerate() {
        nodes.push(source.node);
        links.push(FlowLink {
            source: i,
            target: hub,
            value: source.value,
        });
    }
    nodes.push(synthetic("__hub__", NodeType::Hub));

    let mut sinks = expense_nodes(expense);
    sinks.extend(savings_nodes(&transfers.tracked));
    if positive(transfer_out) {
        sinks.push(NodeValue {
            node: synthetic("__transfer_out__", NodeType::TransferOut),
            value: transfer_out,
        });
    }
    if total_in > total_out {
        sinks.push(NodeValue {
            node: synthetic("__unspent__", NodeType::Unspent),
            value: total_in - total_out,
        });
    }
    for sink in sinks {
        links.push(FlowLink {
            source: hub,
            target: nodes.len(),
            value: sink.value,
        });
        nodes.push(sink.node);
    }
    Ok(CashflowFlowResponse {
        period,
        from,
        to,
        income: total_income,
        expense: total_expense,
        net,
        saved: total_saved,
        withdrawn: total_withdrawn,
        transferred_out: transfer_out,
        transferred_in: transfer_in,
        nodes,
        links,
    })
}

fn income_nodes(income: Tally) -> Vec<NodeValue> {
    let mut out: Vec<NodeValue> = income
        .in_java_order()
        .into_iter()
        .filter(|agg| positive(agg.sum))
        .map(|agg| NodeValue {
            node: match &agg.category {
                Some(c) => category_node(c, NodeType::Income),
                None => synthetic("__income_other__", NodeType::Income),
            },
            value: agg.sum,
        })
        .collect();
    sort_desc(&mut out);
    out
}

fn expense_nodes(expense: Tally) -> Vec<NodeValue> {
    let mut all: Vec<NodeValue> = expense
        .in_java_order()
        .into_iter()
        .filter(|agg| positive(agg.sum))
        .map(|agg| NodeValue {
            node: match &agg.category {
                Some(c) => category_node(c, NodeType::Expense),
                None => synthetic("__expense_uncat__", NodeType::Expense),
            },
            value: agg.sum,
        })
        .collect();
    sort_desc(&mut all);
    if all.len() <= MAX_EXPENSE_NODES {
        return all;
    }
    let rest = all
        .split_off(MAX_EXPENSE_NODES - 1)
        .into_iter()
        .fold(Decimal::ZERO, |acc, n| acc + n.value);
    all.push(NodeValue {
        node: synthetic("__expense_more__", NodeType::Expense),
        value: rest,
    });
    all
}

fn savings_nodes(tracked: &[(Account, Decimal)]) -> Vec<NodeValue> {
    let mut out: Vec<NodeValue> = tracked
        .iter()
        .filter(|(_, sum)| positive(*sum))
        .map(|(account, sum)| NodeValue {
            node: account_node(account, NodeType::Savings),
            value: *sum,
        })
        .collect();
    sort_desc(&mut out);
    out
}

fn withdrawal_nodes(tracked: &[(Account, Decimal)]) -> Vec<NodeValue> {
    let mut out: Vec<NodeValue> = tracked
        .iter()
        .filter(|(_, sum)| negative(*sum))
        .map(|(account, sum)| NodeValue {
            node: account_node(account, NodeType::Withdrawal),
            value: jdec::negate(*sum),
        })
        .collect();
    sort_desc(&mut out);
    out
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CategorySpend {
    pub category_id: Option<i64>,
    pub slug: Option<String>,
    pub name: Option<String>,
    pub color: Option<String>,
    pub icon: Option<String>,
    #[serde(with = "jdec")]
    pub amount: Decimal,
    pub count: i32,
    #[serde(with = "jdec")]
    pub share: Decimal,
    pub parent_id: Option<i64>,
    pub parent_name: Option<String>,
    pub parent_color: Option<String>,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpendingByCategoryResponse {
    pub period: CashflowPeriod,
    pub from: NaiveDate,
    pub to: NaiveDate,
    #[serde(with = "jdec")]
    pub total_expense: Decimal,
    pub categories: Vec<CategorySpend>,
}

pub async fn spending_by_category(
    state: &AppState,
    member_id: i64,
    period: CashflowPeriod,
    today: NaiveDate,
) -> AppResult<SpendingByCategoryResponse> {
    let day = settings::cycle_start_day(&state.db, member_id).await?;
    let (from, to) = cycle::range(period, today, day);
    let rows = repo::member_between(&state.db, member_id, from, to).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    let mut expense = Tally::default();
    let mut total = Decimal::ZERO;
    for tx in &rows {
        if is_transfer(&refs, tx) || !negative(tx.amount) {
            continue;
        }
        let magnitude = jdec::negate(tx.amount);
        total += magnitude;
        expense.add(refs.category(tx), magnitude);
    }
    let mut categories: Vec<CategorySpend> = expense
        .in_java_order()
        .into_iter()
        .filter(|agg| positive(agg.sum))
        .map(|agg| {
            let share = if positive(total) {
                crate::ledger::decimal::divide(agg.sum, total, 4, crate::ledger::decimal::HALF_UP)
            } else {
                Decimal::ZERO
            };
            let category = agg.category.as_ref();
            let parent = category
                .and_then(|c| c.parent_id)
                .and_then(|id| refs.by_id(id));
            CategorySpend {
                category_id: category.map(|c| c.id),
                slug: category.and_then(|c| c.slug.clone()),
                name: category.map(|c| c.name.clone()),
                color: category.map(|c| c.color.clone()),
                icon: category.and_then(|c| c.icon.clone()),
                amount: agg.sum,
                count: agg.count,
                share,
                parent_id: parent.map(|p| p.id),
                parent_name: parent.map(|p| p.name.clone()),
                parent_color: parent.map(|p| p.color.clone()),
            }
        })
        .collect();
    categories.sort_by_key(|c| std::cmp::Reverse(c.amount));
    Ok(SpendingByCategoryResponse {
        period,
        from,
        to,
        total_expense: total,
        categories,
    })
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChildSpend {
    pub category_id: i64,
    pub name: String,
    pub color: String,
    pub icon: Option<String>,
    #[serde(with = "jdec")]
    pub total: Decimal,
    pub count: i32,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpendingDetailResponse {
    pub category_id: i64,
    pub slug: Option<String>,
    pub name: String,
    pub color: String,
    pub icon: Option<String>,
    pub period: CashflowPeriod,
    pub from: NaiveDate,
    pub to: NaiveDate,
    #[serde(with = "jdec")]
    pub total: Decimal,
    pub count: usize,
    pub transactions: Vec<TransactionResponse>,
    pub children: Vec<ChildSpend>,
}

pub async fn category_detail(
    state: &AppState,
    member_id: i64,
    category_id: i64,
    period: CashflowPeriod,
    today: NaiveDate,
) -> AppResult<SpendingDetailResponse> {
    let category = require(&state.db, category_id, member_id).await?;
    let day = settings::cycle_start_day(&state.db, member_id).await?;
    let (from, to) = cycle::range(period, today, day);
    let children = repo::children(&state.db, member_id, category_id).await?;
    let mut ids = vec![category_id];
    ids.extend(children.iter().map(|c| c.id));
    let rows = repo::member_in_categories_between(&state.db, member_id, &ids, from, to).await?;
    let refs = Refs::load(&state.db, &rows).await?;
    let mut total = Decimal::ZERO;
    let mut per_child: HashMap<i64, (Decimal, i32)> = HashMap::new();
    let mut transactions = Vec::with_capacity(rows.len());
    for tx in &rows {
        total += tx.amount;
        transactions.push(refs.response(tx));
        if let Some(id) = tx.category_id {
            let entry = per_child.entry(id).or_insert((Decimal::ZERO, 0));
            entry.0 += tx.amount;
            entry.1 += 1;
        }
    }
    let children = children
        .iter()
        .map(|child| {
            let (sum, count) = per_child
                .get(&child.id)
                .copied()
                .unwrap_or((Decimal::ZERO, 0));
            ChildSpend {
                category_id: child.id,
                name: child.name.clone(),
                color: child.color.clone(),
                icon: child.icon.clone(),
                total: sum,
                count,
            }
        })
        .collect();
    Ok(SpendingDetailResponse {
        category_id: category.id,
        slug: category.slug.clone(),
        name: category.name.clone(),
        color: category.color.clone(),
        icon: category.icon.clone(),
        period,
        from,
        to,
        total,
        count: rows.len(),
        transactions,
        children,
    })
}

async fn flow_handler(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<CashflowFlowResponse>> {
    let (period, anchor) = params.period_and_anchor(state.clock.today())?;
    Ok(axum::Json(
        flow(&state, auth.member_id, period, anchor).await?,
    ))
}

async fn by_category_handler(
    State(state): State<AppState>,
    auth: Auth,
    params: Params,
) -> AppResult<axum::Json<SpendingByCategoryResponse>> {
    let (period, anchor) = params.period_and_anchor(state.clock.today())?;
    Ok(axum::Json(
        spending_by_category(&state, auth.member_id, period, anchor).await?,
    ))
}

async fn category_detail_handler(
    State(state): State<AppState>,
    auth: Auth,
    Path(category_id): Path<i64>,
    params: Params,
) -> AppResult<axum::Json<SpendingDetailResponse>> {
    let (period, anchor) = params.period_and_anchor(state.clock.today())?;
    Ok(axum::Json(
        category_detail(&state, auth.member_id, category_id, period, anchor).await?,
    ))
}
