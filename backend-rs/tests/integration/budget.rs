//! Integration tests of the budget module (track T3), over HTTP.

use std::sync::Arc;

use chrono::NaiveDate;
use picsou::budget::ai_job::{
    AiCategorizer, CategorizationInput, CategorizationResult, CategoryOption, CategorySuggestion,
    Example,
};
use picsou::budget::logos::DuckDuckGo;
use picsou::ledger::accounts::{self, NewAccount};
use picsou::ledger::model::{Account, AccountType, NewTransaction};
use picsou::ledger::transactions;
use picsou::testing::{Client, TestApp, epoch};
use rust_decimal::Decimal;
use serde_json::{Value, json};

fn d(v: &str) -> Decimal {
    v.parse().unwrap()
}

fn day(v: &str) -> NaiveDate {
    v.parse().unwrap()
}

struct Ctx {
    app: TestApp,
    member: i64,
    client: Client,
}

async fn ctx() -> Ctx {
    let app = TestApp::with_admin().await;
    let (_, member) = app.create_member("alice", "password-1234").await;
    let client = app.login("alice", "password-1234").await;
    Ctx {
        app,
        member,
        client,
    }
}

impl Ctx {
    async fn account(&self, name: &str, account_type: AccountType) -> Account {
        let mut draft = NewAccount::new(self.member, name, account_type);
        draft.current_balance = d("1000");
        accounts::insert(&self.app.state.db, &draft, epoch())
            .await
            .unwrap()
    }

    async fn tx(
        &self,
        account: &Account,
        date: &str,
        amount: &str,
        label: &str,
        category: Option<i64>,
    ) -> i64 {
        let mut row = NewTransaction::new(account.id, day(date), label, d(amount));
        row.counterparty = Some(label.to_owned());
        row.category_id = category;
        transactions::insert(&self.app.state.db, &row, epoch())
            .await
            .unwrap()
            .id
    }

    async fn category(&self, name: &str, kind: &str, parent: Option<i64>) -> i64 {
        let r = self
            .client
            .post("/api/categories")
            .json(&json!({"name": name, "kind": kind, "parentId": parent}))
            .send()
            .await;
        assert_eq!(r.status, 201, "{}", r.text());
        r.json()["id"].as_i64().unwrap()
    }

    async fn get(&self, path: &str) -> Value {
        let r = self.client.get(path).send().await;
        assert_eq!(r.status, 200, "{path}: {}", r.text());
        r.json()
    }
}

fn num(v: &Value) -> String {
    v.to_string()
}

#[tokio::test]
async fn categories_are_seeded_on_first_read_and_follow_the_one_level_tree() {
    let c = ctx().await;
    let all = c.get("/api/categories").await;
    assert_eq!(all.as_array().unwrap().len(), 20);
    assert_eq!(all[0]["name"], "Courses");

    let leisure = c.category("Sorties", "EXPENSE", None).await;
    assert_eq!(c.get("/api/categories").await[20]["sortOrder"], 20);
    let cinema = c.category("Cinéma", "EXPENSE", Some(leisure)).await;
    let r = c
        .client
        .post("/api/categories")
        .json(&json!({"name": "Prime", "kind": "INCOME", "parentId": leisure}))
        .send()
        .await;
    assert_eq!(
        (r.status.as_u16(), r.json()["detail"].clone()),
        (400, json!("A sub-category must share its parent's kind"))
    );
    let r = c
        .client
        .post("/api/categories")
        .json(&json!({"name": "x", "kind": "EXPENSE", "parentId": cinema}))
        .send()
        .await;
    assert_eq!(
        r.json()["detail"],
        "Categories support a single level of nesting"
    );
    let r = c
        .client
        .put(&format!("/api/categories/{cinema}"))
        .json(&json!({"name": "x", "kind": "EXPENSE", "parentId": cinema}))
        .send()
        .await;
    assert_eq!(r.json()["detail"], "A category cannot be its own parent");
    let r = c
        .client
        .put(&format!("/api/categories/{leisure}"))
        .json(&json!({"name": "x", "kind": "INCOME"}))
        .send()
        .await;
    assert_eq!(
        r.json()["detail"],
        "A category with sub-categories cannot change kind"
    );
    let r = c
        .client
        .post("/api/categories")
        .json(&json!({"name": "x", "kind": "EXPENSE", "parentId": 999999}))
        .send()
        .await;
    assert_eq!(
        (r.status.as_u16(), r.json()["detail"].clone()),
        (404, json!("Category not found"))
    );
    let r = c
        .client
        .post("/api/categories")
        .json(&json!({"name": " "}))
        .send()
        .await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["errors"],
        json!({"name": "must not be blank", "kind": "must not be null"})
    );

    let r = c
        .client
        .delete(&format!("/api/categories/{leisure}"))
        .send()
        .await;
    assert_eq!(r.status, 204);
    let all = c.get("/api/categories").await;
    let archived: Vec<_> = all
        .as_array()
        .unwrap()
        .iter()
        .filter(|x| x["archived"] == true)
        .map(|x| x["id"].as_i64().unwrap())
        .collect();
    assert_eq!(archived, vec![leisure, cinema]);
    let r = c
        .client
        .post(&format!("/api/categories/{leisure}/unarchive"))
        .send()
        .await;
    assert_eq!(r.json()["archived"], false);
    assert!(
        c.get("/api/categories")
            .await
            .as_array()
            .unwrap()
            .iter()
            .all(|x| x["archived"] == false)
    );
}

#[tokio::test]
async fn categories_are_member_scoped() {
    let c = ctx().await;
    let mine = c.category("Mine", "EXPENSE", None).await;
    c.app.create_member("bob", "password-1234").await;
    let bob = c.app.login("bob", "password-1234").await;
    let r = bob
        .put(&format!("/api/categories/{mine}"))
        .json(&json!({"name": "x", "kind": "EXPENSE"}))
        .send()
        .await;
    assert_eq!(r.status, 404);
    let r = bob
        .post("/api/budgets")
        .json(&json!({"categoryId": mine, "monthlyLimit": 10}))
        .send()
        .await;
    assert_eq!(r.status, 404);
}

#[tokio::test]
async fn settings_are_created_on_first_read_and_validated() {
    let c = ctx().await;
    let s = c.get("/api/budget/settings").await;
    assert_eq!(
        s,
        json!({"cycleStartDay": 1, "logoFetchEnabled": false, "aiCategorizationEnabled": false,
        "aiMode": "AUTO_HIGH_CONFIDENCE", "aiConfidenceThreshold": 75,
        "currentCycleStart": "2026-01-01", "currentCycleEnd": "2026-01-31"})
    );
    let rows: i64 =
        picsou::db::query_scalar("SELECT COUNT(*) FROM budget_settings WHERE member_id = $1")
            .bind(c.member)
            .fetch_one(&c.app.state.db)
            .await
            .unwrap();
    assert_eq!(rows, 1);
    let r = c
        .client
        .put("/api/budget/settings")
        .json(&json!({"cycleStartDay": 25, "aiMode": "SUGGEST", "aiConfidenceThreshold": 80}))
        .send()
        .await;
    assert_eq!(r.json()["currentCycleStart"], "2025-12-25");
    assert_eq!(r.json()["currentCycleEnd"], "2026-01-24");
    let r = c
        .client
        .put("/api/budget/settings")
        .json(&json!({"cycleStartDay": 29, "aiConfidenceThreshold": 101}))
        .send()
        .await;
    assert_eq!(r.status, 422);
    assert_eq!(
        r.json()["errors"],
        json!({"cycleStartDay": "must be less than or equal to 28",
        "aiMode": "must not be null", "aiConfidenceThreshold": "must be less than or equal to 100"})
    );
    let r = c
        .client
        .put("/api/budget/settings")
        .json(&json!({"aiMode": "SUGGEST"}))
        .header("Accept-Language", "fr")
        .send()
        .await;
    assert_eq!(
        r.json()["errors"]["cycleStartDay"],
        "doit être supérieur ou égal à 1"
    );
}

#[tokio::test]
async fn envelopes_score_the_current_cycle_with_refunds_rollups_and_java_scales() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let food = c.category("Food", "EXPENSE", None).await;
    let snacks = c.category("Snacks", "EXPENSE", Some(food)).await;

    let r = c
        .client
        .post("/api/budgets")
        .json(&json!({"categoryId": food, "monthlyLimit": 400.0}))
        .send()
        .await;
    assert_eq!(r.status, 201);
    let created = r.json();
    assert_eq!(
        (
            num(&created["spent"]),
            num(&created["percent"]),
            num(&created["remaining"])
        ),
        ("0".into(), "0.0000".into(), "400.0".into())
    );
    assert_eq!(created["rollup"], true);
    let id = created["id"].as_i64().unwrap();

    c.tx(&checking, "2026-01-05", "-123.45", "Shop", Some(food))
        .await;
    c.tx(&checking, "2026-01-06", "-100", "Chips", Some(snacks))
        .await;
    c.tx(&checking, "2026-01-07", "20", "Refund", Some(food))
        .await;
    c.tx(&checking, "2025-12-31", "-999", "Last month", Some(food))
        .await;
    let hidden = c.account("Copy", AccountType::Checking).await;
    picsou::db::query("UPDATE account SET hidden = true WHERE id = $1")
        .bind(hidden.id)
        .execute(&c.app.state.db)
        .await
        .unwrap();
    c.tx(&hidden, "2026-01-05", "-500", "Dup", Some(food)).await;

    let b = &c.get("/api/budgets").await[0];
    assert_eq!(num(&b["spent"]), "203.45000000");
    assert_eq!(num(&b["monthlyLimit"]), "400.00");
    assert_eq!(num(&b["remaining"]), "196.55000000");
    assert_eq!(num(&b["percent"]), "50.8600");
    assert_eq!(b["overBudget"], false);

    let r = c
        .client
        .put(&format!("/api/budgets/{id}"))
        .json(&json!({"categoryId": food, "monthlyLimit": 150.5}))
        .send()
        .await;
    assert_eq!(num(&r.json()["monthlyLimit"]), "150.5");
    assert_eq!(r.json()["overBudget"], true);

    let r = c
        .client
        .post("/api/budgets")
        .json(&json!({"categoryId": snacks, "monthlyLimit": 10}))
        .send()
        .await;
    assert_eq!(
        r.json()["detail"],
        "This category's parent already has a budget"
    );
    let r = c
        .client
        .post("/api/budgets")
        .json(&json!({"categoryId": food, "monthlyLimit": 10}))
        .send()
        .await;
    assert_eq!(
        r.json()["detail"],
        "A budget already exists for this category"
    );
    let r = c
        .client
        .post("/api/budgets")
        .json(&json!({"categoryId": food, "monthlyLimit": -1}))
        .send()
        .await;
    assert_eq!(
        r.json()["errors"],
        json!({"monthlyLimit": "must be greater than or equal to 0"})
    );

    assert_eq!(
        c.client
            .delete(&format!("/api/budgets/{id}"))
            .send()
            .await
            .status,
        204
    );
    let r = c
        .client
        .post("/api/budgets")
        .json(&json!({"categoryId": snacks, "monthlyLimit": 10}))
        .send()
        .await;
    assert_eq!(r.status, 201);
    let r = c
        .client
        .post("/api/budgets")
        .json(&json!({"categoryId": food, "monthlyLimit": 10}))
        .send()
        .await;
    assert_eq!(
        r.json()["detail"],
        "A sub-category of this category already has a budget"
    );
    assert_eq!(
        c.client.delete("/api/budgets/424242").send().await.json()["detail"],
        "Budget not found"
    );
}

#[tokio::test]
async fn cashflow_splits_by_sign_excludes_transfers_and_buckets_per_cycle() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let transfer = c.category("Move", "TRANSFER", None).await;
    c.tx(&checking, "2026-01-02", "2000", "Salary", None).await;
    c.tx(&checking, "2026-01-03", "-500", "Rent", None).await;
    c.tx(
        &checking,
        "2026-01-04",
        "-300",
        "To savings",
        Some(transfer),
    )
    .await;
    c.tx(&checking, "2025-12-10", "-50", "December", None).await;

    let cf = c.get("/api/cashflow").await;
    assert_eq!(
        (cf["from"].clone(), cf["to"].clone()),
        (json!("2026-01-01"), json!("2026-01-31"))
    );
    assert_eq!(
        (num(&cf["income"]), num(&cf["expense"]), num(&cf["net"])),
        (
            "2000.00000000".into(),
            "500.00000000".into(),
            "1500.00000000".into()
        )
    );
    assert_eq!(cf["series"].as_array().unwrap().len(), 1);
    assert_eq!(cf["series"][0]["label"], "2026-01");

    let past = c.get("/api/cashflow?period=CYCLE&anchor=2025-12-20").await;
    assert_eq!(past["from"], "2025-12-01");
    assert_eq!(num(&past["expense"]), "50.00000000");
    let ytd = c.get("/api/cashflow?period=YTD&anchor=2025-12-31").await;
    assert_eq!(
        (ytd["from"].clone(), ytd["to"].clone()),
        (json!("2025-01-01"), json!("2025-12-31"))
    );
    assert_eq!(ytd["series"].as_array().unwrap().len(), 12);

    let r = c.client.get("/api/cashflow?period=MONTH").send().await;
    assert_eq!(
        (r.status.as_u16(), r.json()["detail"].clone()),
        (400, json!("Failed to convert 'period' with value: 'MONTH'"))
    );
    let r = c.client.get("/api/cashflow?anchor=nope").send().await;
    assert_eq!(
        r.json()["detail"],
        "Failed to convert 'anchor' with value: 'nope'"
    );
}

#[tokio::test]
async fn flow_balances_the_hub_with_savings_and_unspent() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let livret = c.account("Livret A", AccountType::LivretA).await;
    let transfer = c.category("Move", "TRANSFER", None).await;
    let salary = c.category("Pay", "INCOME", None).await;
    c.tx(&checking, "2026-01-02", "2000", "Salary", Some(salary))
        .await;
    c.tx(&checking, "2026-01-03", "-500", "Rent", None).await;
    c.tx(&checking, "2026-01-04", "-300", "To livret", Some(transfer))
        .await;
    c.tx(
        &livret,
        "2026-01-04",
        "300",
        "From checking",
        Some(transfer),
    )
    .await;

    let f = c.get("/api/cashflow/flow").await;
    let types: Vec<_> = f["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .map(|n| n["type"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(
        types,
        vec!["INCOME", "HUB", "EXPENSE", "SAVINGS", "UNSPENT"]
    );
    assert_eq!(f["nodes"][3]["key"], format!("acct:{}", livret.id));
    assert_eq!(f["nodes"][3]["assetClass"], "SAVINGS");
    assert_eq!(f["nodes"][2]["key"], "__expense_uncat__");
    assert!(f["nodes"][1].get("label").is_none());
    assert_eq!(num(&f["saved"]), "300.00000000");
    assert_eq!(num(&f["transferredOut"]), "0");
    let unspent = f["links"].as_array().unwrap().last().unwrap();
    assert_eq!(num(&unspent["value"]), "1200.00000000");

    let empty = c.get("/api/cashflow/flow?anchor=2024-06-01").await;
    assert_eq!(empty["nodes"], json!([]));
    assert_eq!(num(&empty["income"]), "0");
}

#[tokio::test]
async fn flow_caps_expense_nodes_and_rolls_the_tail_into_more() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    for i in 1..=10 {
        let cat = c.category(&format!("C{i}"), "EXPENSE", None).await;
        c.tx(
            &checking,
            "2026-01-05",
            &format!("-{}", i * 10),
            "x",
            Some(cat),
        )
        .await;
    }
    let f = c.get("/api/cashflow/flow").await;
    let expense: Vec<_> = f["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|n| n["type"] == "EXPENSE")
        .collect();
    assert_eq!(expense.len(), 8);
    assert_eq!(expense[7]["key"], "__expense_more__");
    let more = f["links"]
        .as_array()
        .unwrap()
        .iter()
        .find(|l| l["target"].as_u64() == Some(1 + 1 + 7))
        .unwrap();
    assert_eq!(num(&more["value"]), "60.00000000");
}

#[tokio::test]
async fn spending_ranks_leaves_with_parents_and_drills_a_subtree() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let food = c.category("Food", "EXPENSE", None).await;
    let snacks = c.category("Snacks", "EXPENSE", Some(food)).await;
    let drinks = c.category("Drinks", "EXPENSE", Some(food)).await;
    c.tx(&checking, "2026-01-05", "-30", "a", Some(food)).await;
    c.tx(&checking, "2026-01-06", "-60", "b", Some(snacks))
        .await;
    c.tx(&checking, "2026-01-07", "-10", "c", None).await;
    c.tx(&checking, "2026-01-08", "50", "income", None).await;

    let s = c.get("/api/spending/by-category").await;
    assert_eq!(num(&s["totalExpense"]), "100.00000000");
    let cats = s["categories"].as_array().unwrap();
    assert_eq!(cats.len(), 3);
    assert_eq!(cats[0]["categoryId"], snacks);
    assert_eq!(cats[0]["parentId"], food);
    assert_eq!(num(&cats[0]["share"]), "0.6000");
    assert!(cats[1].get("parentId").is_none());
    assert!(cats[2].get("categoryId").is_none());

    let detail = c.get(&format!("/api/spending/category/{food}")).await;
    assert_eq!(num(&detail["total"]), "-90.00000000");
    assert_eq!(detail["count"], 2);
    assert_eq!(detail["transactions"][0]["categoryName"], "Snacks");
    let children = detail["children"].as_array().unwrap();
    assert_eq!(children[0]["categoryId"], snacks);
    assert_eq!(
        (num(&children[1]["total"]), children[1]["count"].clone()),
        ("0".into(), json!(0))
    );
    assert_eq!(children[1]["categoryId"], drinks);
    assert_eq!(
        c.client
            .get("/api/spending/category/999999")
            .send()
            .await
            .status,
        404
    );
}

#[tokio::test]
async fn allocation_groups_stock_by_class_and_counts_incoming_transfers() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let livret = c.account("Livret", AccountType::LivretA).await;
    c.account("House", AccountType::RealEstate).await;
    let transfer = c.category("Move", "TRANSFER", None).await;
    c.tx(&checking, "2026-01-04", "-300", "out", Some(transfer))
        .await;
    c.tx(&livret, "2026-01-04", "300", "in", Some(transfer))
        .await;
    c.tx(&livret, "2026-01-05", "200", "in", Some(transfer))
        .await;
    let a = c.get("/api/allocation").await;
    let classes: Vec<_> = a["stock"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s["assetClass"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(classes, vec!["CURRENT", "SAVINGS"]);
    assert_eq!(num(&a["stock"][0]["percent"]), "50.00");
    assert_eq!(a["contributions"].as_array().unwrap().len(), 1);
    assert_eq!(num(&a["totalContributions"]), "500.00000000");
    assert_eq!(a["contributions"][0]["accountId"], livret.id);
}

#[tokio::test]
async fn a_learned_rule_is_found_again_whatever_the_case_of_its_accents() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let food = c.category("Food", "EXPENSE", None).await;
    let upper = c
        .tx(&checking, "2026-01-02", "-12.00", "ÉPICERIE DU COIN", None)
        .await;
    let lower = c
        .tx(&checking, "2026-01-03", "-8.00", "épicerie du coin", None)
        .await;

    for id in [upper, lower] {
        let r = c
            .client
            .put(&format!("/api/transactions/{id}/category"))
            .json(&json!({"categoryId": food, "createRule": true}))
            .send()
            .await;
        assert_eq!(r.status, 204);
    }

    let rules = c.get("/api/categorization-rules").await;
    assert_eq!(rules.as_array().unwrap().len(), 1, "{rules}");
}

#[tokio::test]
async fn manual_categorization_learns_a_rule_and_never_overrides_a_manual_choice() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let subs = c.category("Subs", "EXPENSE", None).await;
    let other = c.category("Other", "EXPENSE", None).await;
    let first = c
        .tx(&checking, "2026-01-02", "-9.99", "NETFLIX", None)
        .await;
    let second = c
        .tx(&checking, "2026-01-03", "-9.99", "NETFLIX", None)
        .await;
    let manual = c
        .tx(&checking, "2026-01-04", "-9.99", "NETFLIX", None)
        .await;
    let r = c
        .client
        .put(&format!("/api/transactions/{manual}/category"))
        .json(&json!({"categoryId": other}))
        .send()
        .await;
    assert_eq!(r.status, 204);

    let r = c
        .client
        .put(&format!("/api/transactions/{first}/category"))
        .json(&json!({"categoryId": subs, "createRule": true}))
        .send()
        .await;
    assert_eq!(r.status, 204);
    let rules = c.get("/api/categorization-rules").await;
    assert_eq!(rules[0]["source"], "AUTO");
    assert_eq!(rules[0]["matchType"], "COUNTERPARTY");
    let tx_cat = |id: i64| {
        let db = c.app.state.db.clone();
        async move {
            picsou::db::query_scalar::<Option<i64>>(
                "SELECT category_id FROM \"transaction\" WHERE id = $1",
            )
            .bind(id)
            .fetch_one(&db)
            .await
            .unwrap()
        }
    };
    assert_eq!(tx_cat(second).await, Some(subs));
    assert_eq!(tx_cat(manual).await, Some(other));
    assert!(
        c.get("/api/transactions/uncategorized")
            .await
            .as_array()
            .unwrap()
            .is_empty()
    );

    let r = c
        .client
        .put("/api/transactions/999999/category")
        .json(&json!({"categoryId": subs}))
        .send()
        .await;
    assert_eq!(r.json()["detail"], "Transaction not found");
    let r = c
        .client
        .put(&format!("/api/transactions/{first}/category"))
        .json(&json!({}))
        .send()
        .await;
    assert_eq!(
        r.json()["errors"],
        json!({"categoryId": "must not be null"})
    );
}

#[tokio::test]
async fn rules_crud_preview_and_recategorize() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let food = c.category("Food", "EXPENSE", None).await;
    c.tx(&checking, "2026-01-02", "-12", "CARREFOUR CITY 12", None)
        .await;
    c.tx(&checking, "2026-01-03", "-5", "Bakery", None).await;

    let p = c
        .client
        .post("/api/categorization-rules/preview")
        .json(&json!({"matchType": "KEYWORD", "pattern": "  carrefour "}))
        .send()
        .await
        .json();
    assert_eq!(p["matchCount"], 1);
    assert_eq!(p["transactions"][0]["date"], "2026-01-02");

    let r = c.client.post("/api/categorization-rules").json(&json!({"matchType": "KEYWORDS_ALL", "pattern": " city carrefour ", "categoryId": food})).send().await;
    assert_eq!(r.status, 201);
    let rule = r.json();
    assert_eq!(
        (
            rule["pattern"].clone(),
            rule["priority"].clone(),
            rule["source"].clone()
        ),
        (json!("city carrefour"), json!(0), json!("USER"))
    );
    let id = rule["id"].as_i64().unwrap();
    let r = c
        .client
        .post("/api/categorization-rules/recategorize")
        .send()
        .await
        .json();
    assert_eq!(r, json!({"categorized": 1}));
    assert_eq!(
        c.get("/api/transactions/uncategorized")
            .await
            .as_array()
            .unwrap()
            .len(),
        1
    );

    let r = c.client.put(&format!("/api/categorization-rules/{id}")).json(&json!({"matchType": "KEYWORD", "pattern": "bakery", "categoryId": food, "priority": 5})).send().await;
    assert_eq!(r.json()["priority"], 5);
    assert_eq!(
        c.client
            .delete(&format!("/api/categorization-rules/{id}"))
            .send()
            .await
            .status,
        204
    );
    let r = c
        .client
        .delete(&format!("/api/categorization-rules/{id}"))
        .send()
        .await;
    assert_eq!(r.json()["detail"], "Categorization rule not found");
    let r = c
        .client
        .post("/api/categorization-rules")
        .json(&json!({"pattern": ""}))
        .send()
        .await;
    assert_eq!(r.status, 422);
}

#[tokio::test]
async fn search_skips_hidden_accounts_unless_asked_and_requires_the_range() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let hidden = c.account("Hidden", AccountType::Checking).await;
    picsou::db::query("UPDATE account SET hidden = true WHERE id = $1")
        .bind(hidden.id)
        .execute(&c.app.state.db)
        .await
        .unwrap();
    c.tx(&checking, "2026-01-02", "-1", "a", None).await;
    c.tx(&hidden, "2026-01-03", "-2", "b", None).await;
    let all = c
        .get("/api/transactions?from=2026-01-01&to=2026-01-31")
        .await;
    assert_eq!(all.as_array().unwrap().len(), 1);
    let one = c
        .get(&format!(
            "/api/transactions?from=2026-01-01&to=2026-01-31&accountId={}",
            hidden.id
        ))
        .await;
    assert_eq!(one[0]["accountName"], "Hidden");
    let r = c.client.get("/api/transactions?to=2026-01-31").send().await;
    assert_eq!(
        (r.status.as_u16(), r.json()["detail"].clone()),
        (400, json!("Required parameter 'from' is not present."))
    );
}

#[tokio::test]
async fn recurring_series_lifecycle_calendar_and_activity() {
    let c = ctx().await;
    let r = c.client.post("/api/recurring").json(&json!({"label": " Gym ", "expectedAmount": -29.999, "cadence": "MONTHLY", "nextDueDate": "2026-01-20"})).send().await;
    assert_eq!(r.status, 201);
    let s = r.json();
    assert_eq!(
        (
            s["label"].clone(),
            s["status"].clone(),
            s["runtimeStatus"].clone()
        ),
        (json!("Gym"), json!("CONFIRMED"), json!("SCHEDULED"))
    );
    assert_eq!(num(&s["expectedAmount"]), "-29.999");
    let id = s["id"].as_i64().unwrap();
    let list = c.get("/api/recurring").await;
    assert_eq!(num(&list[0]["expectedAmount"]), "-30.00");
    assert_eq!(list[0]["runtimeStatus"], "DUE_SOON");

    let cal = c.get("/api/recurring/calendar?horizonDays=60").await;
    let dues: Vec<_> = cal
        .as_array()
        .unwrap()
        .iter()
        .map(|o| o["dueDate"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(dues, vec!["2026-01-20", "2026-02-20"]);

    let card = c.account("Amex", AccountType::CreditCard).await;
    picsou::db::query("UPDATE account SET payment_due_date = '2026-01-25', payment_due_amount = 120.5 WHERE id = $1").bind(card.id).execute(&c.app.state.db).await.unwrap();
    let cal = c.get("/api/recurring/calendar").await;
    assert_eq!(cal[1]["seriesId"], -card.id);
    assert_eq!(cal[1]["creditCardPayment"], true);
    assert_eq!(num(&cal[1]["expectedAmount"]), "-120.50000000");

    let r = c
        .client
        .post(&format!("/api/recurring/{id}/ignore"))
        .send()
        .await;
    assert_eq!(r.json()["status"], "IGNORED");
    assert_eq!(c.get("/api/recurring?status=CONFIRMED").await, json!([]));
    assert_eq!(
        c.client
            .get("/api/recurring?status=NOPE")
            .send()
            .await
            .status,
        400
    );
    assert_eq!(
        c.client
            .delete(&format!("/api/recurring/{id}"))
            .send()
            .await
            .status,
        204
    );
    let r = c
        .client
        .post(&format!("/api/recurring/{id}/confirm"))
        .send()
        .await;
    assert_eq!(r.json()["detail"], "Recurring series not found");
    let r = c
        .client
        .post("/api/recurring")
        .json(&json!({"label": ""}))
        .send()
        .await;
    assert_eq!(
        r.json()["errors"],
        json!({"label": "must not be blank", "expectedAmount": "must not be null", "cadence": "must not be null"})
    );
}

#[tokio::test]
async fn detection_auto_confirms_a_fixed_monthly_series_and_never_resurrects_an_ignored_one() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    for date in ["2025-10-05", "2025-11-05", "2025-12-05", "2026-01-05"] {
        c.tx(&checking, date, "-15.99", "SPOTIFY", None).await;
    }
    for (date, amount) in [
        ("2025-10-10", "-80"),
        ("2025-11-10", "-100"),
        ("2025-12-10", "-110"),
        ("2026-01-10", "-95"),
    ] {
        c.tx(&checking, date, amount, "EDF", None).await;
    }
    c.tx(&checking, "2026-01-02", "-3", "Once", None).await;
    let r = c.client.post("/api/recurring/detect").send().await.json();
    assert_eq!(r, json!({"detected": 2}));
    let list = c.get("/api/recurring").await;
    let spotify = list
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["label"] == "SPOTIFY")
        .unwrap();
    assert_eq!(
        (
            spotify["status"].clone(),
            spotify["autoConfirmed"].clone(),
            spotify["cadence"].clone()
        ),
        (json!("CONFIRMED"), json!(true), json!("MONTHLY"))
    );
    assert_eq!(spotify["nextDueDate"], "2026-02-05");
    assert_eq!(num(&spotify["confidence"]), "0.904");
    let edf = list
        .as_array()
        .unwrap()
        .iter()
        .find(|s| s["label"] == "EDF")
        .unwrap();
    assert_eq!(
        (edf["status"].clone(), edf["variable"].clone()),
        (json!("SUGGESTED"), json!(true))
    );

    let feed = c.get("/api/recurring/activity").await;
    assert_eq!(feed[0]["type"], "AUTO_CONFIRMED");
    let id = spotify["id"].as_i64().unwrap();
    let undone = c
        .client
        .post(&format!("/api/recurring/{id}/undo"))
        .send()
        .await
        .json();
    assert_eq!(
        (undone["status"].clone(), undone["autoConfirmed"].clone()),
        (json!("IGNORED"), json!(false))
    );
    let r = c.client.post("/api/recurring/detect").send().await.json();
    assert_eq!(r, json!({"detected": 1}));
    let linked: i64 = picsou::db::query_scalar(
        "SELECT COUNT(*) FROM \"transaction\" WHERE recurring_series_id = $1",
    )
    .bind(id)
    .fetch_one(&c.app.state.db)
    .await
    .unwrap();
    assert_eq!(linked, 4);
}

#[tokio::test]
async fn recurring_label_matching_is_unicode_case_insensitive() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;

    // Create a confirmed series with an uppercase accented initial.
    let created = c
        .client
        .post("/api/recurring")
        .json(&json!({"label": "Élodie", "expectedAmount": -25.00, "cadence": "MONTHLY", "nextDueDate": "2026-02-05"}))
        .send()
        .await;
    assert_eq!(created.status, 201, "{}", created.text());
    let series_id = created.json()["id"].as_i64().unwrap();

    // Direct lookup with lowercase accented equivalent must find the exact same row on both PG and SQLite.
    let found = picsou::budget::recurring::find_by_label(&c.app.state.db, c.member, "élodie")
        .await
        .unwrap();
    assert_eq!(found.map(|s| s.id), Some(series_id));

    // Detection on transactions with lowercase accented label updates the existing series instead of duplicating it.
    for date in ["2025-10-05", "2025-11-05", "2025-12-05", "2026-01-05"] {
        c.tx(&checking, date, "-25.00", "élodie", None).await;
    }
    let detected = c.client.post("/api/recurring/detect").send().await.json();
    assert_eq!(detected, json!({"detected": 1}));

    let list = c.get("/api/recurring").await;
    let rows = list.as_array().unwrap();
    assert_eq!(
        rows.len(),
        1,
        "expected existing accented series to be reused, got: {rows:?}"
    );
    assert_eq!(rows[0]["id"], series_id);
}

#[tokio::test]
async fn ai_job_is_a_no_op_when_disabled_and_runs_the_plugged_categorizer_when_enabled() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    let tx = c
        .tx(&checking, "2026-01-02", "-12", "Mystery shop", None)
        .await;
    let idle = c.get("/api/transactions/categorize-ai/status").await;
    assert_eq!(
        idle,
        json!({"running": false, "total": 0, "processed": 0, "applied": 0, "suggested": 0, "done": false})
    );
    let r = c
        .client
        .post("/api/transactions/categorize-ai")
        .send()
        .await;
    assert_eq!(r.status, 202);
    assert_eq!(r.json()["done"], true);
    assert_eq!(r.json()["total"], 0);

    struct Fixed;
    #[async_trait::async_trait]
    impl AiCategorizer for Fixed {
        async fn max_concurrency(&self) -> usize {
            2
        }
        async fn provider(&self) -> (String, Option<String>) {
            ("test".into(), Some("m".into()))
        }
        async fn categorize(
            &self,
            _: &CategorizationInput,
            _: &[CategoryOption],
            _: &[Example],
        ) -> CategorizationResult {
            CategorizationResult {
                suggestion: Some(CategorySuggestion {
                    category_slug: "courses".into(),
                    confidence: d("0.5"),
                }),
                ..CategorizationResult::empty("OK")
            }
        }
    }
    c.app.state.budget.ai.set(Arc::new(Fixed));
    c.client.put("/api/budget/settings").json(&json!({"cycleStartDay": 1, "aiCategorizationEnabled": true, "aiMode": "AUTO_HIGH_CONFIDENCE", "aiConfidenceThreshold": 80})).send().await;
    let r = c
        .client
        .post("/api/transactions/categorize-ai")
        .send()
        .await
        .json();
    assert_eq!(
        (r["running"].clone(), r["total"].clone()),
        (json!(true), json!(1))
    );
    let mut status = Value::Null;
    for _ in 0..100 {
        status = c.get("/api/transactions/categorize-ai/status").await;
        if status["done"] == true {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(
        (
            status["processed"].clone(),
            status["suggested"].clone(),
            status["applied"].clone()
        ),
        (json!(1), json!(1), json!(0))
    );
    let confidence: Option<i32> =
        picsou::db::query_scalar("SELECT ai_confidence FROM \"transaction\" WHERE id = $1")
            .bind(tx)
            .fetch_one(&c.app.state.db)
            .await
            .unwrap();
    assert_eq!(confidence, Some(50));
    let logs: i64 =
        picsou::db::query_scalar("SELECT COUNT(*) FROM ai_call_log WHERE provider = 'test'")
            .fetch_one(&c.app.state.db)
            .await
            .unwrap();
    assert_eq!(logs, 1);
}

#[tokio::test]
async fn logo_proxy_is_opt_in_cached_and_falls_back_to_404() {
    let c = ctx().await;
    let brand: (i64, String) = picsou::db::query_as("SELECT id, logo_domain FROM merchant_brand WHERE logo_domain IS NOT NULL ORDER BY id LIMIT 1")
        .fetch_one(&c.app.state.db).await.unwrap();
    let r = c
        .client
        .get(&format!("/api/merchants/{}/logo", brand.0))
        .send()
        .await;
    assert_eq!((r.status.as_u16(), r.body.len()), (404, 0));

    let server = wiremock::MockServer::start().await;
    wiremock::Mock::given(wiremock::matchers::path(format!("/ip3/{}.ico", brand.1)))
        .respond_with(
            wiremock::ResponseTemplate::new(200).set_body_raw(vec![1u8, 2, 3], "image/x-icon"),
        )
        .expect(1)
        .mount(&server)
        .await;
    c.app
        .state
        .budget
        .logos
        .set(Arc::new(DuckDuckGo::new(&server.uri())));
    c.client
        .put("/api/budget/settings")
        .json(&json!({"cycleStartDay": 1, "logoFetchEnabled": true, "aiMode": "SUGGEST"}))
        .send()
        .await;
    for _ in 0..2 {
        let r = c
            .client
            .get(&format!("/api/merchants/{}/logo", brand.0))
            .send()
            .await;
        assert_eq!(r.status, 200);
        assert_eq!(r.header("content-type"), Some("image/x-icon"));
        assert_eq!(r.header("cache-control"), Some("max-age=86400, private"));
        assert_eq!(r.body, vec![1u8, 2, 3]);
    }
    assert_eq!(
        c.client
            .get("/api/merchants/999999/logo")
            .send()
            .await
            .status,
        404
    );
}

#[tokio::test]
async fn refs_handles_soft_deleted_account_without_panicking() {
    let c = ctx().await;
    let checking = c.account("Courant", AccountType::Checking).await;
    c.tx(&checking, "2026-01-05", "-15", "Coffee", None).await;
    let rows = picsou::budget::repo::member_between(
        &c.app.state.db,
        c.member,
        day("2026-01-01"),
        day("2026-01-31"),
    )
    .await
    .unwrap();
    assert_eq!(rows.len(), 1);
    picsou::db::query("UPDATE account SET deleted_at = $2 WHERE id = $1")
        .bind(checking.id)
        .bind(c.app.state.clock.now())
        .execute(&c.app.state.db)
        .await
        .unwrap();
    let refs = picsou::budget::repo::Refs::load(&c.app.state.db, &rows)
        .await
        .unwrap();
    assert!(refs.account(&rows[0]).is_none());
    assert!(refs.response(&rows[0]).is_none());
}
