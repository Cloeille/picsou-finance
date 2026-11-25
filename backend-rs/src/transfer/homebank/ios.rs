//! The HomeBank iOS export (the JSON half of HomeBankFileParser): an optional AES-256-GCM
//! container keyed by PBKDF2, a raw DEFLATE stream, then schema-3 JSON read as a stream.

use std::collections::{HashMap, HashSet};

use aes_gcm::Aes256Gcm;
use aes_gcm::aead::{Aead, KeyInit};
use chrono::NaiveDate;
use flate2::{Decompress, FlushDecompress, Status};
use regex::Regex;
use rust_decimal::Decimal;

use super::json::{MAX_STRING_CHARS, Parser, Syntax, Token};
use super::source::{
    BigDec, ParsedHomeBankData, SourceAccount, SourceCategory, SourceTransaction, jlen,
};

pub const CORRUPT: &str = "Invalid or corrupted HomeBank file";
const MAX_JSON_BYTES: usize = 50 * 1024 * 1024;
const MAX_ROW_TOKENS: usize = 256;
const MAX_ACCOUNTS: usize = 100;
const MAX_CATEGORIES: usize = 200;
const MAX_PAYEES: usize = 10_000;
const MAX_TRANSACTIONS: usize = 25_000;
const SALT_BYTES: usize = 32;
const NONCE_BYTES: usize = 12;
const PBKDF2_ITERATIONS: u32 = 600_000;
const ACCOUNT_TYPES: [&str; 6] = [
    "bank",
    "savings",
    "cash",
    "creditcard",
    "asset",
    "liability",
];

type Fail = String;

fn invalid(message: &str) -> Fail {
    message.to_owned()
}

impl From<Syntax> for Fail {
    fn from(_: Syntax) -> Self {
        CORRUPT.to_owned()
    }
}

/// A .hbk file (raw DEFLATE) or, with encrypted, a .hbexport container.
pub fn parse(
    file: &[u8],
    encrypted: bool,
    password: Option<&str>,
) -> Result<ParsedHomeBankData, Fail> {
    let mut compressed = if encrypted {
        Some(decrypt(file, password)?)
    } else {
        None
    };
    let result = inflate(compressed.as_deref().unwrap_or(file)).and_then(|mut json| {
        let parsed = parse_json(&json);
        json.fill(0);
        parsed
    });
    if let Some(bytes) = compressed.as_mut() {
        bytes.fill(0);
    }
    result
}

fn decrypt(file: &[u8], password: Option<&str>) -> Result<Vec<u8>, Fail> {
    let password = password
        .filter(|p| !p.is_empty())
        .ok_or_else(|| invalid("Password required for HomeBank export"))?;
    if file.len() < 4 + SALT_BYTES + NONCE_BYTES + 16 {
        return Err(invalid("Invalid or corrupted HomeBank export"));
    }
    if file[..4] != [0, 0, 0, 1] {
        return Err(invalid("Unsupported HomeBank export encryption version"));
    }
    let salt = &file[4..4 + SALT_BYTES];
    let nonce = &file[4 + SALT_BYTES..4 + SALT_BYTES + NONCE_BYTES];
    let ciphertext = &file[4 + SALT_BYTES + NONCE_BYTES..];
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(password.as_bytes(), salt, PBKDF2_ITERATIONS, &mut key);
    let wrong = || invalid("Incorrect password or corrupted HomeBank export");
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| wrong());
    key.fill(0);
    let nonce = aes_gcm::Nonce::try_from(nonce).map_err(|_| wrong())?;
    cipher?.decrypt(&nonce, ciphertext).map_err(|_| wrong())
}

fn inflate(compressed: &[u8]) -> Result<Vec<u8>, Fail> {
    let mut inflater = Decompress::new(false);
    let mut output = Vec::with_capacity(8192);
    let mut buffer = [0u8; 8192];
    loop {
        let consumed = usize::try_from(inflater.total_in()).map_err(|_| invalid(CORRUPT))?;
        let before = inflater.total_out();
        let status = inflater
            .decompress(&compressed[consumed..], &mut buffer, FlushDecompress::None)
            .map_err(|_| invalid(CORRUPT))?;
        let count = usize::try_from(inflater.total_out() - before).map_err(|_| invalid(CORRUPT))?;
        if count > 0 {
            if output.len() + count > MAX_JSON_BYTES {
                return Err(invalid(
                    "HomeBank export exceeds the decompressed size limit",
                ));
            }
            output.extend_from_slice(&buffer[..count]);
        }
        match status {
            Status::StreamEnd => break,
            Status::Ok if count > 0 => {}
            _ => {
                let consumed_now =
                    usize::try_from(inflater.total_in()).map_err(|_| invalid(CORRUPT))?;
                if count == 0 && consumed_now == consumed {
                    return Err(invalid(CORRUPT));
                }
            }
        }
    }
    let consumed = usize::try_from(inflater.total_in()).map_err(|_| invalid(CORRUPT))?;
    if consumed != compressed.len() {
        return Err(invalid("Trailing data after HomeBank compressed payload"));
    }
    Ok(output)
}

/// One record, materialized as Jackson's tree would be.
#[derive(Debug, Clone)]
enum Node {
    Object(Vec<(String, Node)>),
    Array(Vec<Node>),
    Text(String),
    Int(String),
    Float(String),
    Bool(bool),
    Null,
}

impl Node {
    fn get(&self, field: &str) -> Option<&Node> {
        match self {
            Node::Object(fields) => fields.iter().find(|(k, _)| k == field).map(|(_, v)| v),
            _ => None,
        }
    }
}

fn read_node(parser: &mut Parser<'_>, token: Token) -> Result<Node, Fail> {
    let mut tokens = 0;
    read_bounded(parser, token, &mut tokens)
}

fn complexity() -> Fail {
    invalid("HomeBank record exceeds the supported complexity")
}

fn read_bounded(parser: &mut Parser<'_>, token: Token, tokens: &mut usize) -> Result<Node, Fail> {
    *tokens += 1;
    if *tokens > MAX_ROW_TOKENS {
        return Err(complexity());
    }
    Ok(match token {
        Token::StartObject => {
            let mut fields = Vec::new();
            loop {
                match parser.next_token()?.ok_or(Syntax)? {
                    Token::EndObject => break,
                    Token::Field(name) => {
                        *tokens += 1;
                        if *tokens > MAX_ROW_TOKENS {
                            return Err(complexity());
                        }
                        let child = parser.next_token()?.ok_or(Syntax)?;
                        let value = read_bounded(parser, child, tokens)?;
                        fields.push((name, value));
                    }
                    _ => return Err(invalid("Invalid HomeBank record")),
                }
            }
            Node::Object(fields)
        }
        Token::StartArray => {
            let mut items = Vec::new();
            loop {
                match parser.next_token()?.ok_or(Syntax)? {
                    Token::EndArray => break,
                    child => items.push(read_bounded(parser, child, tokens)?),
                }
            }
            Node::Array(items)
        }
        Token::Str(text) => {
            if jlen(&text) > MAX_STRING_CHARS {
                return Err(invalid(CORRUPT));
            }
            Node::Text(text)
        }
        Token::Int(text) => Node::Int(text),
        Token::Float(text) => {
            BigDec::parse(&text).ok_or(Syntax)?;
            Node::Float(text)
        }
        Token::True => Node::Bool(true),
        Token::False => Node::Bool(false),
        Token::Null => Node::Null,
        Token::EndObject | Token::EndArray | Token::Field(_) => {
            return Err(invalid("Invalid HomeBank record"));
        }
    })
}

#[derive(Default)]
struct Seen {
    manifest: bool,
    data: bool,
    accounts: bool,
    categories: bool,
    payees: bool,
    transactions: bool,
}

struct Pending {
    transaction: SourceTransaction,
    payee_id: Option<String>,
}

fn parse_json(json: &[u8]) -> Result<ParsedHomeBankData, Fail> {
    let mut accounts: Vec<SourceAccount> = Vec::new();
    let mut categories: Vec<SourceCategory> = Vec::new();
    let mut payees: HashMap<String, String> = HashMap::new();
    let mut transactions: Vec<Pending> = Vec::new();
    let mut seen = Seen::default();
    let mut parser = Parser::new(json);
    if parser.next_token()? != Some(Token::StartObject) {
        return Err(invalid("Invalid HomeBank JSON root"));
    }
    loop {
        let field = match parser.next_token()?.ok_or(Syntax)? {
            Token::EndObject => break,
            Token::Field(name) => name,
            _ => return Err(invalid("Invalid HomeBank JSON root")),
        };
        let value = parser.next_token()?.ok_or(Syntax)?;
        match field.as_str() {
            "manifest" => {
                seen.manifest = true;
                check_manifest(&read_node(&mut parser, value)?)?;
            }
            "data" => {
                seen.data = true;
                if value != Token::StartObject {
                    return Err(invalid("Invalid HomeBank field"));
                }
                loop {
                    let list = match parser.next_token()?.ok_or(Syntax)? {
                        Token::EndObject => break,
                        Token::Field(name) => name,
                        _ => return Err(invalid("Invalid HomeBank field")),
                    };
                    let token = parser.next_token()?.ok_or(Syntax)?;
                    match list.as_str() {
                        "accounts" => {
                            seen.accounts = true;
                            read_array(&mut parser, token, MAX_ACCOUNTS, |row| {
                                add_account(&mut accounts, &row)
                            })?;
                        }
                        "categories" => {
                            seen.categories = true;
                            read_array(&mut parser, token, MAX_CATEGORIES, |row| {
                                add_category(&mut categories, &row)
                            })?;
                        }
                        "payees" => {
                            seen.payees = true;
                            read_array(&mut parser, token, MAX_PAYEES, |row| {
                                add_payee(&mut payees, &row)
                            })?;
                        }
                        "transactions" => {
                            seen.transactions = true;
                            read_array(&mut parser, token, MAX_TRANSACTIONS, |row| {
                                transactions.push(parse_transaction(&row)?);
                                Ok(())
                            })?;
                        }
                        _ => parser.skip_children(&token)?,
                    }
                }
            }
            _ => parser.skip_children(&value)?,
        }
    }
    if parser.next_token()?.is_some() {
        return Err(invalid("Trailing data after HomeBank JSON"));
    }
    if !(seen.manifest
        && seen.data
        && seen.accounts
        && seen.categories
        && seen.payees
        && seen.transactions)
    {
        return Err(invalid("Missing HomeBank field"));
    }
    if accounts.is_empty() {
        return Err(invalid("HomeBank export has no accounts"));
    }
    validate_category_parents(&categories)?;
    let mut ids = HashSet::new();
    let mut parsed = Vec::with_capacity(transactions.len());
    for Pending {
        mut transaction,
        payee_id,
    } in transactions
    {
        if !ids.insert(transaction.id.clone()) {
            return Err(invalid("Duplicate transaction id"));
        }
        let account = accounts
            .iter()
            .find(|a| a.id == transaction.account_id)
            .ok_or_else(|| invalid("Unknown transaction account"))?;
        if account.currency != transaction.currency {
            return Err(invalid("Transaction currency mismatch"));
        }
        if transaction
            .category_id
            .as_ref()
            .is_some_and(|id| !categories.iter().any(|c| &c.id == id))
        {
            return Err(invalid("Unknown transaction category"));
        }
        if let Some(payee_id) = payee_id {
            transaction.payee = Some(
                payees
                    .get(&payee_id)
                    .cloned()
                    .ok_or_else(|| invalid("Unknown transaction payee"))?,
            );
        }
        if transaction
            .transfer_account_id
            .as_ref()
            .is_some_and(|id| !accounts.iter().any(|a| &a.id == id))
        {
            return Err(invalid("Unknown transfer account"));
        }
        parsed.push(transaction);
    }
    Ok(ParsedHomeBankData {
        accounts,
        categories,
        transactions: parsed,
    })
}

fn check_manifest(manifest: &Node) -> Result<(), Fail> {
    let unsupported = || invalid("Unsupported HomeBank export schema");
    if !matches!(manifest, Node::Object(_)) {
        return Err(unsupported());
    }
    if int_value(required(manifest, "schemaVersion")?, "schemaVersion")? != 3
        || text(required(manifest, "platform")?, "platform")? != "iOS"
    {
        return Err(unsupported());
    }
    Ok(())
}

fn read_array(
    parser: &mut Parser<'_>,
    token: Token,
    max: usize,
    mut consumer: impl FnMut(Node) -> Result<(), Fail>,
) -> Result<(), Fail> {
    if token != Token::StartArray {
        return Err(invalid("Invalid HomeBank list"));
    }
    let mut count = 0;
    loop {
        let row = parser.next_token()?.ok_or(Syntax)?;
        if row == Token::EndArray {
            return Ok(());
        }
        count += 1;
        if count > max {
            return Err(invalid("HomeBank list exceeds the supported limit"));
        }
        consumer(read_node(parser, row)?)?;
    }
}

fn add_account(accounts: &mut Vec<SourceAccount>, node: &Node) -> Result<(), Fail> {
    let id = id(required(node, "id")?)?;
    let name = limited_text(required(node, "name")?, "account name", 100)?;
    let source_type = text(required(node, "type")?, "account type")?;
    if !ACCOUNT_TYPES.contains(&source_type.as_str()) {
        return Err(invalid("Unsupported HomeBank account type"));
    }
    let currency = currency(required_object(node, "currency")?, "code")?;
    let initial = required_object(node, "initialAmount")?;
    let initial_currency = text(required(initial, "currencyCode")?, "currencyCode")?;
    if currency != initial_currency {
        return Err(invalid("Account balance currency mismatch"));
    }
    let institution = optional_limited_text(node, "institution", 100)?;
    let initial_balance = money(required(initial, "amount")?)?;
    let closed = boolean(required(node, "isClosed")?, "isClosed")?;
    if accounts.iter().any(|a| a.id == id) {
        return Err(invalid("Duplicate account id"));
    }
    accounts.push(SourceAccount {
        id,
        name,
        institution,
        source_type,
        currency,
        initial_balance,
        closed,
    });
    Ok(())
}

fn add_category(categories: &mut Vec<SourceCategory>, node: &Node) -> Result<(), Fail> {
    let id = id(required(node, "id")?)?;
    let name = limited_text(required(node, "name")?, "category name", 100)?;
    let parent_id = optional_id(node, "parentID")?;
    let income = boolean(required(node, "isIncomeType")?, "isIncomeType")?;
    if categories.iter().any(|c| c.id == id) {
        return Err(invalid("Duplicate category id"));
    }
    categories.push(SourceCategory {
        id,
        name,
        parent_id,
        income,
        kind_inferred: false,
    });
    Ok(())
}

fn add_payee(payees: &mut HashMap<String, String>, node: &Node) -> Result<(), Fail> {
    let id = id(required(node, "id")?)?;
    let name = limited_text(required(node, "name")?, "payee name", 255)?;
    if payees.contains_key(&id) {
        return Err(invalid("Duplicate payee id"));
    }
    payees.insert(id, name);
    Ok(())
}

fn parse_transaction(node: &Node) -> Result<Pending, Fail> {
    let transaction_id = id(required(node, "id")?)?;
    let account_id = id(required(node, "accountID")?)?;
    let amount_node = required_object(node, "amount")?;
    let amount = money(required(amount_node, "amount")?)?;
    let amount_currency = text(
        required(amount_node, "currencyCode")?,
        "transaction currency",
    )?;
    let date_node = required_object(node, "date")?;
    let year = int_value(required(date_node, "year")?, "year")?;
    let month = int_value(required(date_node, "month")?, "month")?;
    let day = int_value(required(date_node, "day")?, "day")?;
    let date = u32::try_from(month)
        .ok()
        .zip(u32::try_from(day).ok())
        .and_then(|(m, d)| NaiveDate::from_ymd_opt(year, m, d))
        .ok_or_else(|| invalid("Invalid transaction date"))?;
    let category_id = match node.get("category") {
        None | Some(Node::Null) => None,
        Some(category @ Node::Object(_)) => Some(
            optional_id(category, "id")?.ok_or_else(|| invalid("Unknown transaction category"))?,
        ),
        Some(_) => return Err(invalid("Invalid transaction category")),
    };
    let payee_id = match node.get("payee") {
        None | Some(Node::Null) => None,
        Some(payee @ Node::Object(_)) => {
            Some(optional_id(payee, "id")?.ok_or_else(|| invalid("Unknown transaction payee"))?)
        }
        Some(_) => return Err(invalid("Invalid transaction payee")),
    };
    let transfer_account_id = optional_id(node, "transferAccountID")?;
    match node.get("splits") {
        None | Some(Node::Null) => {}
        Some(Node::Array(items)) if items.is_empty() => {}
        Some(_) => return Err(invalid("Split transactions are not supported")),
    }
    let notes = optional_limited_text(node, "memo", 255)?;
    let forecast = boolean(required(node, "isForecast")?, "isForecast")?;
    Ok(Pending {
        transaction: SourceTransaction {
            id: transaction_id,
            account_id,
            date,
            amount,
            currency: amount_currency,
            payee: None,
            notes,
            category_id,
            transfer_account_id,
            forecast,
        },
        payee_id,
    })
}

fn validate_category_parents(categories: &[SourceCategory]) -> Result<(), Fail> {
    for category in categories {
        if let Some(parent_id) = &category.parent_id {
            let parent = categories.iter().find(|c| &c.id == parent_id);
            if parent.is_none_or(|p| p.parent_id.is_some()) {
                return Err(invalid("Invalid category parent"));
            }
        }
    }
    Ok(())
}

fn required_object<'n>(parent: &'n Node, field: &str) -> Result<&'n Node, Fail> {
    let value = required(parent, field)?;
    if !matches!(value, Node::Object(_)) {
        return Err(invalid("Invalid HomeBank field"));
    }
    Ok(value)
}

fn required<'n>(parent: &'n Node, field: &str) -> Result<&'n Node, Fail> {
    match parent.get(field) {
        None | Some(Node::Null) => Err(invalid("Missing HomeBank field")),
        Some(value) => Ok(value),
    }
}

fn text(value: &Node, field: &str) -> Result<String, Fail> {
    match value {
        Node::Text(text) if !text.is_empty() => Ok(text.clone()),
        _ => Err(format!("Invalid {field}")),
    }
}

fn limited_text(value: &Node, field: &str, max_chars: usize) -> Result<String, Fail> {
    let result = text(value, field)?;
    if jlen(&result) > max_chars {
        return Err(format!("Invalid {field}"));
    }
    Ok(result)
}

fn optional_limited_text(
    parent: &Node,
    field: &str,
    max_chars: usize,
) -> Result<Option<String>, Fail> {
    match parent.get(field) {
        None | Some(Node::Null) => Ok(None),
        Some(value) => limited_text(value, field, max_chars).map(Some),
    }
}

fn boolean(value: &Node, field: &str) -> Result<bool, Fail> {
    match value {
        Node::Bool(b) => Ok(*b),
        _ => Err(format!("Invalid {field}")),
    }
}

/// isIntegralNumber and canConvertToInt.
fn int_value(value: &Node, field: &str) -> Result<i32, Fail> {
    match value {
        Node::Int(text) => text.parse::<i32>().map_err(|_| format!("Invalid {field}")),
        _ => Err(format!("Invalid {field}")),
    }
}

fn currency(object: &Node, field: &str) -> Result<String, Fail> {
    let value = text(required(object, field)?, "currency")?;
    if !(value.len() == 3 && value.bytes().all(|b| b.is_ascii_uppercase())) {
        return Err(invalid("Invalid currency code"));
    }
    Ok(value)
}

fn money(node: &Node) -> Result<Decimal, Fail> {
    let text = match node {
        Node::Int(text) | Node::Float(text) => text,
        _ => return Err(invalid("Invalid monetary amount")),
    };
    let value = BigDec::parse(text).ok_or_else(|| invalid(CORRUPT))?;
    let integer_digits = (value.precision() - value.scale).max(0);
    if integer_digits > 12 || value.scale > 8 {
        return Err(invalid("Monetary amount exceeds supported precision"));
    }
    value.to_decimal().ok_or_else(|| invalid(CORRUPT))
}

fn uuid_pattern() -> &'static Regex {
    static PATTERN: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    PATTERN.get_or_init(|| {
        Regex::new("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .expect("a valid pattern")
    })
}

/// UUID.fromString(value).toString(): the canonical lower-case form.
fn id(node: &Node) -> Result<String, Fail> {
    let value = text(node, "id")?;
    if !uuid_pattern().is_match(&value) {
        return Err(invalid("Invalid HomeBank identifier"));
    }
    Ok(value.to_ascii_lowercase())
}

fn optional_id(parent: &Node, field: &str) -> Result<Option<String>, Fail> {
    match parent.get(field) {
        None | Some(Node::Null) => Ok(None),
        Some(node) => id(node).map(Some),
    }
}
