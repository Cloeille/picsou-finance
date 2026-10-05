"""
Pure parsing helpers for DEGIRO's /trading/secure/v5/update response shape.
Kept dependency-free (no fastapi/httpx) so it can be unit tested in isolation —
mirrors services/bourse-direct-auth/portfolio_parser.py.

The "value" array-of-{name, value}-pairs shape modeled here was reconstructed from
public reference implementations and has since been confirmed against a live
account. See docs/features/degiro-sync.md "Known limitations" for what remains open.
"""


def _sanitize(value):
    """DEGIRO's product info endpoint has been observed returning the literal
    string "NULL" (or blank) for a missing field, rather than omitting the key
    or returning JSON null. Treat both as absent so a garbage literal never
    flows downstream as if it were a real ISIN/symbol/name."""
    if value is None:
        return None
    if isinstance(value, str) and value.strip().upper() in ("NULL", ""):
        return None
    return value


def sanitize_product_info(info: dict) -> dict:
    """Applies `_sanitize` to the fields build_positions reads from product info.

    NOTE: a real product-info response (confirmed live) has no `breakEvenPrice`
    field at all — average cost lives on the portfolio row itself, not here
    (see `parse_raw_positions`). This DOES carry `closePrice` (last close),
    the more reliable fallback for current price than the portfolio row's own
    `price` field.
    """
    return {
        "isin": _sanitize(info.get("isin")),
        "symbol": _sanitize(info.get("symbol")),
        "name": _sanitize(info.get("name")),
        # Sanitized like the rest: the literal string "NULL" is truthy, so an unsanitized
        # one would win the `closePrice or price` fallback in build_positions and reach
        # Java as a non-numeric node — decoded as 0, pricing the holding at zero and
        # silently understating the account balance with no error anywhere.
        "closePrice": _sanitize(info.get("closePrice")),
    }


def value_pairs_to_dict(item: dict) -> dict:
    """Flattens DEGIRO's {"value": [{"name": ..., "value": ...}, ...]} rows.

    Falls back to the item itself if it's already a flat dict, so this stays
    safe if a future DEGIRO response no longer uses the name/value-pair shape.
    """
    raw = item.get("value")
    if isinstance(raw, list):
        return {pair.get("name"): pair.get("value") for pair in raw if isinstance(pair, dict)}
    return item


def parse_cash_eur(cash_funds_rows: list) -> float:
    for row in cash_funds_rows or []:
        flat = value_pairs_to_dict(row)
        if flat.get("currencyCode") == "EUR":
            return float(flat.get("value", 0) or 0)
    return 0.0


def is_real_product_id(product_id) -> bool:
    """DEGIRO's portfolio.value rows are not all real instruments — a cash
    sub-position (observed live: id "FLATEX_EUR", the EUR balance held at its
    partner bank Flatex) is mixed in among real security rows. Real DEGIRO
    product ids are numeric; anything else is a pseudo-position that must never
    be sent to the product-info lookup (a single bad id previously crashed
    resolution for the whole batch — see main.py._fetch_product_info) or
    treated as a priceable holding."""
    return product_id is not None and str(product_id).strip().isdigit()


def parse_raw_positions(portfolio_rows: list) -> list[dict]:
    """Extracts non-zero, real-instrument positions as {productId, size, price,
    breakEvenPrice} — before ISIN/name enrichment. Cash sub-positions (see
    `is_real_product_id`) are skipped: DEGIRO's cashFunds rows already carry
    that balance.

    `price` and `breakEvenPrice` (average cost) are both read from the
    portfolio row itself — confirmed live: DEGIRO's product-info response has
    no `breakEvenPrice` field at all, average cost lives here instead.
    """
    positions = []
    for row in portfolio_rows or []:
        flat = value_pairs_to_dict(row)
        size = float(flat.get("size", 0) or 0)
        if size == 0:
            continue
        product_id = flat.get("id") or row.get("id")
        if not is_real_product_id(product_id):
            continue
        price = float(flat.get("price", 0) or 0)
        positions.append({
            "productId": product_id,
            "size": size,
            "price": price,
            "breakEvenPrice": float(flat.get("breakEvenPrice", price) or price),
        })
    return positions


def build_product_info_map(data: dict) -> dict:
    """Turns the raw {pid: info} payload from /product_search/secure/v5/products/info
    into what `build_positions` expects, sanitized and keyed the same way
    `parse_raw_positions` keys `productId` (a string — JSON object keys, and
    `pid` here, always are one).

    This used to be inlined in main.py, coercing the key to `int(pid)` — which
    silently never matched `build_positions`' string lookup, making every
    position fall back to its raw id as symbol/name regardless of how clean
    the DEGIRO response actually was. Extracted here so that specific mismatch
    has a unit test that doesn't need fastapi/httpx to run.
    """
    products = {}
    for pid, info in (data or {}).items():
        if not is_real_product_id(pid):
            continue
        products[pid] = sanitize_product_info(info)
    return products


def build_positions(raw_positions: list[dict], products: dict) -> list[dict]:
    """Merges raw positions with resolved product info (ISIN/name), keyed by productId.

    A position always survives even without product info: an incomplete label
    (raw productId standing in for symbol/name) is recoverable; dropping the
    position on an enrichment failure would silently understate holdings.

    Current price prefers product info's `closePrice` (DEGIRO's own reference
    price) over the portfolio row's `price`, falling back to the latter only
    when product info wasn't resolved for this id.
    """
    positions = []
    for p in raw_positions:
        info = products.get(p["productId"], {}) if p["productId"] is not None else {}
        positions.append({
            "isin": info.get("isin"),
            "symbol": info.get("symbol") or str(p["productId"]),
            "name": info.get("name") or str(p["productId"]),
            "quantity": p["size"],
            "buyingPrice": p["breakEvenPrice"],
            "currentPrice": info.get("closePrice") or p["price"],
        })
    return positions
