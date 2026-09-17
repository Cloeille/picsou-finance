"""Parsing helpers for Fortuneo account, position and transaction pages.

The modern account response is multipart GraphQL: a base document followed
by deferred fragments addressed by paths. Securities positions and their
history use legacy HTML pages. This module validates and normalizes those
provider-owned structures without logging their financial contents.

The generic French-number decimal coercion is shared with the Bourse Direct
parser because both providers use the same localized number formats.
"""

import hashlib
import html as html_lib
import json
import re
import unicodedata
from decimal import Decimal, InvalidOperation
from typing import Any


class PortfolioFormatError(ValueError):
    """Raised when an upstream monetary field is present but unusable."""


# Fortuneo `productType` (account-api/v2/accounts) -> Picsou AccountType.
# `cash-account` is deliberately excluded: it is a CTO's linked cash
# pocket, not a standalone Picsou account (see module docstring).
ACCOUNT_TYPE_BY_PRODUCT_TYPE: dict[str, str] = {
    "current-account": "CHECKING",
    "ordinary-securities-account": "COMPTE_TITRES",
    "share-savings-plan": "PEA",
    "sme-share-savings-plan": "PEA",  # PEA-PME; Picsou has no distinct type
}

# Fortuneo `type.value` -> the `/mes-comptes/{segment}/situation/` URL
# segment for that account's position holdings. Distinct from
# ACCOUNT_TYPE_BY_PRODUCT_TYPE: PEA and PEA-PME both collapse to Picsou
# `PEA` but need different URL segments to fetch positions for the right
# account, since an individual can hold one of each simultaneously.
PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT: dict[str, str] = {
    "share-savings-plan": "pea",
    "sme-share-savings-plan": "ppe",
    "ordinary-securities-account": "compte-titres-pea",
}

# The `/mes-comptes/{segment}/situation/portefeuille-temps-reel.jsp` URL
# segment for each securities-account family, as observed in the synthese-
# bourse HTML fragment -- NOT the "ORD"/"PEA"/"PPE" codes that also appear
# elsewhere in that fragment (in an unrelated `optAjax_...` element id),
# which this parser does not rely on.
_BOURSE_URL_SEGMENT_TO_TYPE = {
    "compte-titres-pea": "COMPTE_TITRES",  # CTO; segment name is historical/misleading
    "pea": "PEA",
    "ppe": "PEA",  # PEA-PME; Picsou has no distinct type
}


_CURRENCY_CODE = r"(?:EUR|USD|GBP|CHF|JPY|CAD|AUD|NZD|SEK|NOK|DKK|GBX)"


def _normalize_spaces(value: str) -> str:
    """Fold every Unicode space separator to a plain space."""
    return "".join(
        " " if (unicodedata.category(char) == "Zs" or char in "  ") else char
        for char in value
    )


def decimal_value(raw: Any, field: str = "value") -> Decimal:
    """Parse a required decimal without turning protocol drift into a false zero."""
    if raw is None or isinstance(raw, bool):
        raise PortfolioFormatError(f"Missing {field}")
    value = _normalize_spaces(str(raw)).strip()
    negative_parentheses = value.startswith("(") and value.endswith(")")
    if negative_parentheses:
        value = value[1:-1].strip()
    # Accept presentation-only currency/percentage affixes, but reject letters
    # embedded in the numeric value instead of silently deleting them (for
    # example, "1e3" must never become "13").
    value = re.sub(
        rf"^(?:{_CURRENCY_CODE}|[€$£¥])\s*",
        "",
        value,
        flags=re.IGNORECASE,
    )
    while True:
        stripped = re.sub(
            rf"\s*(?:{_CURRENCY_CODE}|[€$£¥%])$",
            "",
            value,
            flags=re.IGNORECASE,
        )
        if stripped == value:
            break
        value = stripped
    cleaned = value.replace(" ", "").replace("'", "")
    if negative_parentheses:
        cleaned = "-" + cleaned
    if not re.fullmatch(r"[+-]?[0-9][0-9,.-]*", cleaned):
        raise PortfolioFormatError(f"Invalid {field}")
    if not cleaned or cleaned in {"-", ".", ",", "-.", "-,"}:
        raise PortfolioFormatError(f"Missing {field}")
    if "," in cleaned and "." in cleaned:
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    elif cleaned.count(",") > 1:
        head, tail = cleaned.rsplit(",", 1)
        cleaned = head.replace(",", "") + "." + tail
    elif cleaned.count(".") > 1:
        groups = cleaned.lstrip("+-").split(".")
        if all(len(group) == 3 for group in groups[1:]):
            cleaned = cleaned.replace(".", "")
        else:
            head, tail = cleaned.rsplit(".", 1)
            cleaned = head.replace(".", "") + "." + tail
    else:
        cleaned = cleaned.replace(",", ".")
    try:
        return Decimal(cleaned)
    except InvalidOperation as exc:
        raise PortfolioFormatError(f"Invalid {field}") from exc


def optional_decimal_value(raw: Any, field: str = "value") -> Decimal | None:
    """Parse an optional decimal, while still rejecting malformed present values."""
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None
    return decimal_value(raw, field)


def account_balance(account_json: dict[str, Any]) -> Decimal | None:
    """Extract the `balance`-named amount from an account-api account object.

    Cash-type accounts (`current-account`, `cash-account`) carry a
    `balances` array with one entry named "balance"; securities accounts
    have no `balances` key at all. (account-api/v2/accounts is not the
    primary data source -- see `extract_accounts_from_equipment` -- but this
    helper is kept since the REST shape is confirmed and may be useful as a
    cross-check or fallback.)
    """
    for entry in account_json.get("balances") or []:
        if entry.get("name") == "balance":
            amount = entry.get("amount") or {}
            return optional_decimal_value(amount.get("value"), "balance")
    return None


# Equipment GraphQL category path -> Picsou AccountType. Classifying by
# category path (rather than each account's own `type.value`) also handles
# savings products whose subtype is not part of the explicit mapping.
_EQUIPMENT_CATEGORY_ACCOUNT_TYPE: dict[tuple[str, str], str] = {
    ("financialPortfolio", "shareSavingsPlan"): "PEA",
    ("financialPortfolio", "ordinarySecurities"): "COMPTE_TITRES",
    ("banking", "current"): "CHECKING",
    ("banking", "savings"): "SAVINGS",
}
# A CTO's linked cash pocket: not a standalone Picsou account (see module
# docstring) -- extracted separately so callers can fold its balance into
# the parent CTO via `fold_cash_pockets_into_securities_accounts`.
_EQUIPMENT_CASH_POCKET_PATH: tuple[str, str] = ("financialPortfolio", "cash")


def parse_equipment_multipart(raw: str) -> dict[str, Any]:
    """Merge a GraphQL `Equipment` query's chunked multipart/mixed response
    (deferred fragments, `Content-Type: multipart/mixed; boundary="-"`) into
    one plain dict -- the unwrapped contents of the response's top-level
    `data.equipment` key.

    Each part after the first is `{"hasNext": bool, "incremental": [{"data":
    {...}, "path": [...]}]}`; `data` is merged (dict.update, shallow) into
    the base structure at the location `path` points to. Raises
    PortfolioFormatError if the response has no parseable base part, so a
    transport glitch never silently yields an empty/partial equipment tree.
    """
    if not isinstance(raw, str):
        raise PortfolioFormatError("Equipment response was not text")

    merged: dict[str, Any] | None = None
    for part in re.split(r"(?:\A|\r?\n)---\r?\n", raw):
        part = part.strip()
        if not part:
            continue
        body = re.split(r"\r?\n\r?\n", part, maxsplit=1)
        json_text = body[-1] if len(body) > 1 else body[0]
        json_text = re.sub(r"-+\s*$", "", json_text).strip()
        if not json_text or not json_text.startswith("{"):
            continue
        try:
            chunk = json.loads(json_text)
        except json.JSONDecodeError as exc:
            raise PortfolioFormatError("Malformed Equipment multipart chunk") from exc
        if not isinstance(chunk, dict):
            raise PortfolioFormatError("Equipment multipart chunk was not an object")

        if merged is None:
            data = chunk.get("data")
            if not isinstance(data, dict):
                raise PortfolioFormatError("Equipment base data was not an object")
            merged = data
            continue
        incremental = chunk.get("incremental", [])
        if not isinstance(incremental, list):
            raise PortfolioFormatError("Equipment incremental data was not a list")
        for item in incremental:
            if not isinstance(item, dict):
                raise PortfolioFormatError("Equipment fragment was not an object")
            path = item.get("path")
            if not isinstance(path, list) or not path:
                raise PortfolioFormatError("Equipment fragment has no valid path")
            target = merged
            try:
                for key in path[:-1]:
                    target = target[key]
                last = path[-1]
                if isinstance(target[last], dict):
                    data = item.get("data")
                    if not isinstance(data, dict):
                        raise PortfolioFormatError("Equipment fragment data was not an object")
                    target[last].update(data)
                else:
                    target[last] = item.get("data")
            except (KeyError, IndexError, TypeError) as exc:
                raise PortfolioFormatError("Equipment fragment path did not resolve") from exc

    if not merged or "equipment" not in merged:
        raise PortfolioFormatError("Equipment response had no base data")
    equipment = merged["equipment"]
    if not isinstance(equipment, dict):
        raise PortfolioFormatError("Equipment response root was not an object")
    return equipment


def extract_accounts_from_equipment(equipment: dict[str, Any]) -> list[dict[str, Any]]:
    """Flatten the merged `Equipment` tree into raw account records.

    Returns one dict per account under the categories Picsou syncs today:
    `{"webId", "type", "productType", "label", "accountNumber", "typeLabel",
    "balanceEur"}`, where `type` is a Picsou `AccountType` string, or `None`
    for `financialPortfolio.cash` entries (CTO cash pockets -- see
    `fold_cash_pockets_into_securities_accounts`). `productType` is
    Fortuneo's own `type.value` (e.g. `share-savings-plan` vs.
    `sme-share-savings-plan`) -- needed downstream to pick the right
    `.../situation/` URL segment for position scraping, since PEA and
    PEA-PME both collapse to Picsou `PEA` but live at different URLs (see
    `PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT`). `typeLabel` is Fortuneo's own
    display label for the account type (e.g. "PEA", "Compte courant"), kept
    for building a human-readable account name -- Fortuneo's `label` field
    is just the holder's name, not a per-account name. `lifeInsurance`,
    `retirementSavingsPlan` (PER), `mortgages`, and `external` accounts are
    out of scope and skipped entirely.
    """
    if not isinstance(equipment, dict):
        raise PortfolioFormatError("Equipment root was not an object")

    accounts: list[dict[str, Any]] = []
    paths = list(_EQUIPMENT_CATEGORY_ACCOUNT_TYPE.items()) + [(_EQUIPMENT_CASH_POCKET_PATH, None)]
    for (top, category), account_type in paths:
        top_node = equipment.get(top)
        if top_node is None:
            top_node = {}
        if not isinstance(top_node, dict):
            raise PortfolioFormatError(f"Equipment {top} category was not an object")
        node = top_node.get(category)
        if node is None:
            node = {}
        if not isinstance(node, dict):
            raise PortfolioFormatError(f"Equipment {top}.{category} category was not an object")
        category_accounts = node.get("accounts")
        if category_accounts is None:
            category_accounts = []
        if not isinstance(category_accounts, list):
            raise PortfolioFormatError(f"Equipment {top}.{category} accounts was not a list")
        for account in category_accounts:
            if not isinstance(account, dict):
                raise PortfolioFormatError("Equipment account was not an object")
            balance = account.get("balance")
            account_type_info = account.get("type")
            if balance is None:
                balance = {}
            if account_type_info is None:
                account_type_info = {}
            if not isinstance(balance, dict) or not isinstance(account_type_info, dict):
                raise PortfolioFormatError("Equipment account fields had an invalid shape")
            accounts.append({
                "webId": account.get("id"),
                "type": account_type,
                "productType": account_type_info.get("value"),
                "label": account.get("label"),
                "accountNumber": account.get("accountNumber"),
                "typeLabel": account_type_info.get("label"),
                "balanceEur": optional_decimal_value(balance.get("amount"), "balance"),
            })
    return accounts


def fold_cash_pockets_into_securities_accounts(
    accounts: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Fold CTO cash-pocket entries (`type is None`) into their CTO's
    `cashBalance`, returning a new list with the cash-pocket entries removed.

    Pairs 1:1 only when there is exactly one `COMPTE_TITRES` account and one
    cash-pocket entry. The Equipment pocket is merely a later cross-check:
    the legacy portfolio page supplies the authoritative per-account cash, so
    unmatched or ambiguous pockets are discarded rather than blocking every
    otherwise valid account in the response.

    CHECKING/SAVINGS accounts get `cashBalance = balanceEur` (genuinely no
    positions). Securities accounts otherwise get `cashBalance = None`; the
    caller replaces it with cash from the same legacy snapshot as their
    positions and total.
    """
    if not isinstance(accounts, list) or any(not isinstance(a, dict) for a in accounts):
        raise PortfolioFormatError("Equipment accounts had an invalid shape")
    if any("type" not in a or "balanceEur" not in a for a in accounts):
        raise PortfolioFormatError("Equipment account was missing required fields")

    ctos = [a for a in accounts if a["type"] == "COMPTE_TITRES"]
    pockets = [a for a in accounts if a["type"] is None]
    paired_cto = ctos[0] if len(ctos) == 1 and len(pockets) == 1 else None
    paired_pocket_balance = pockets[0]["balanceEur"] if paired_cto is not None else None
    cash_only_types = {"CHECKING", "SAVINGS"}
    result: list[dict[str, Any]] = []
    for account in accounts:
        if account["type"] is None:
            continue  # cash pocket, folded below
        folded = dict(account)
        if account is paired_cto:
            folded["cashBalance"] = paired_pocket_balance
        elif account["type"] in cash_only_types:
            folded["cashBalance"] = account["balanceEur"]
        else:
            folded["cashBalance"] = None
        result.append(folded)
    return result


_SYNTHESE_ACCOUNT_BLOCK = re.compile(
    r'href="[^"]*?/mes-comptes/(?P<segment>[a-z-]+)/situation/portefeuille-temps-reel\.jsp\?ca=(?P<web_id>[A-Za-z0-9_-]+)"'
    r'[^>]*>\s*'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_numero_compte">\s*(?P<numero>[^<]*?)\s*</span>'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_compte_tiret">[^<]*</span>\s*(?P<label>[^\n<]*)\s*'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_solde_compte\s*">\s*(?P<solde>[^<]*?)\s*</span>'
    r'(?:(?!</a>).)*?</a>',
    re.DOTALL,
)

_SYNTHESE_CASH_POCKET_BLOCK = re.compile(
    r'href="[^"]*?consulter-solde\.jsp\?ca=(?P<web_id>[A-Za-z0-9_-]+)"'
    r'[^>]*class="synthese_compte_associe"'
    r'(?:(?!</a>).)*?'
    r'<span class="synthese_solde_compte_espece_associe\s*">\s*(?P<solde>[^<]*?)\s*</span>'
    r'(?:(?!</a>).)*?</a>',
    re.DOTALL,
)


def parse_bourse_synthese_table(html: str) -> list[dict[str, Any]]:
    """Parse the `famille_bourse` HTML fragment into per-account totals.

    Returns one dict per securities account:
    ``{"webId": str, "type": "PEA" | "COMPTE_TITRES", "label": str,
    "totalEur": Decimal, "cashWebId": str | None}``.

    ``cashWebId`` is set only for a CTO row that has an associated
    "Compte espèces" sub-row immediately following it in the fragment
    (`synthese_compte_ligne_autre`) -- PEA/PEA-PME never have one, their
    cash pocket is implicit. The associated cash account's own balance
    must be fetched separately via account-api and folded into the CTO's
    `cashBalance`; this function only resolves *which* cash account id
    belongs to which securities account.

    Raises PortfolioFormatError if an account block's total is missing or
    malformed -- never silently drops or zeroes a valuation.
    """
    accounts: list[dict[str, Any]] = []
    account_matches = list(_SYNTHESE_ACCOUNT_BLOCK.finditer(html))
    cash_matches = list(_SYNTHESE_CASH_POCKET_BLOCK.finditer(html))

    for i, match in enumerate(account_matches):
        segment = match.group("segment")
        account_type = _BOURSE_URL_SEGMENT_TO_TYPE.get(segment)
        if account_type is None:
            raise PortfolioFormatError(f"Unrecognized Fortuneo bourse account segment: {segment!r}")
        total = decimal_value(html_lib.unescape(match.group("solde")), "totalEur")

        # A cash-pocket block belongs to this account if it appears after
        # this account's block and before the next one.
        block_end = match.end()
        next_start = account_matches[i + 1].start() if i + 1 < len(account_matches) else len(html)
        cash_web_id = next(
            (c.group("web_id") for c in cash_matches if block_end <= c.start() < next_start),
            None,
        )

        accounts.append({
            "webId": match.group("web_id"),
            "type": account_type,
            "label": html_lib.unescape(match.group("label").strip()),
            "totalEur": total,
            "cashWebId": cash_web_id,
        })

    return accounts


# Each holding is a `<tr name="{code}princip" class="l1"|"l2">` row
# (zebra-striped) on a securities account's `.../situation/` page. The
# Equipment GraphQL query has no position field. `{code}`'s trailing 12 characters are the
# ISIN (e.g. "FTN000000XX0000000001" -> "XX0000000001").
_POSITION_ROW = re.compile(
    r'<tr name="(?P<code>[A-Za-z0-9]+)princip" class="l[12]"\s*>(?P<body>.*?)</tr>',
    re.DOTALL,
)
_ISIN_FROM_CODE = re.compile(r"([A-Z]{2}[A-Z0-9]{10})$")
_POSITION_LABEL = re.compile(r'libelle_valeur_tableau">([^<]+)<')
# Every numeric cell below requires a leading digit in the capture group --
# several *other* `<td class="numb">` cells in the same row are blank
# (whitespace-only, for SRD-related columns this account type doesn't use)
# and would otherwise be matched first by a naive `[\d.,\s]+` pattern,
# silently producing a spurious zero-ish value.
_POSITION_PRICE = re.compile(r'<td class="numb">\s*<b>\s*(\d[\d.,\s\xa0]*?)\s*<span title=', re.DOTALL)
_POSITION_QUANTITY = re.compile(r'<td class="numb">\s*<b>\s*(\d[\d\s\xa0]*)\s*</b>\s*</td>', re.DOTALL)
_POSITION_PRU = re.compile(r'<td class="numb">\s*(\d[\d.,\s\xa0]*)\s*</td>')
_POSITION_VALUATION = re.compile(
    r'<td class="numb" id="idValorisation_\d+_tab__categorie_\d+"\s*>\s*(\d[\d.,\s\xa0]*)\s*</td>'
)
_POSITION_PNL = re.compile(
    # No `&nbsp;` here: _normalize_nbsp has already folded it to \xa0, which
    # the character class covers. Spelling the entity would match only the
    # raw-HTTP form and silently drop P&L from browser-serialised DOM.
    r'<td class="numb">\s*([+-]\s*\d[\d\s.,\xa0]*)<span class="(?:augmente|baisse)\s*"',
    re.DOTALL,
)


def _normalize_nbsp(html: str) -> str:
    """Fold non-breaking-space entities to the literal character.

    The same page reaches this module in two forms: raw HTTP text, where
    Fortuneo's thousands separator is a literal U+00A0, and
    browser-serialised DOM (Playwright's `content()`), where it is the
    entity `&nbsp;`. Every numeric pattern below would otherwise have to
    spell both, and missing one fails silently. Only this entity is folded, not
    the whole document: a blanket `html.unescape` would turn `&lt;` into a
    real `<` and invent tags that were never in the markup.
    """
    return (
        html.replace("&nbsp;", "\xa0")
        .replace("&#160;", "\xa0")
        .replace("&#xa0;", "\xa0")
        .replace("&#xA0;", "\xa0")
    )


def parse_portfolio_positions(html: str) -> list[dict[str, Any]]:
    """Parse a securities account's `.../situation/` page into per-position
    holdings: `{"isin", "symbol", "label", "quantity", "buyingPriceEur",
    "currentPrice", "quoteCurrency", "currentValueEur", "pnlEur"}` per
    holding.

    `symbol` is set to the ISIN, not a parsed ticker -- Fortuneo's own
    ticker suffix (e.g. "SYNTHETIC FUND (SYN)") is stripped from
    `label` rather than used, since the existing OpenFIGI ISIN->ticker
    resolution downstream (mirroring Bourse Direct) is the authoritative
    path; a security with no real ticker shows "(-)" here; both cases are
    resolved (or safely fall back to the ISIN) on the Java side.
    `quoteCurrency` is hardcoded "EUR": a PEA can only hold EU-domiciled,
    EUR-quoted securities by French regulation. A CTO may hold securities
    quoted in another currency; that response shape remains unsupported.

    `pnlEur` is optional: a position exactly at cost (no gain/loss) has no
    coloured span to match and legitimately parses as `None`.
    `quantity` and `currentValueEur` are mandatory; missing either raises
    PortfolioFormatError rather than silently dropping a holding's value.
    """
    html = _normalize_nbsp(html)
    positions: list[dict[str, Any]] = []
    for match in _POSITION_ROW.finditer(html):
        code = match.group("code")
        body = match.group("body")
        isin_match = _ISIN_FROM_CODE.search(code)
        isin = isin_match.group(1) if isin_match else None

        label_match = _POSITION_LABEL.search(body)
        label = html_lib.unescape(label_match.group(1)).strip() if label_match else code
        label = re.sub(r"\s*\([^()]*\)\s*$", "", label).strip() or label

        quantity_match = _POSITION_QUANTITY.search(body)
        valuation_match = _POSITION_VALUATION.search(body)
        if not quantity_match or not valuation_match:
            raise PortfolioFormatError(
                "Fortuneo position row is missing quantity or valuation"
            )
        price_match = _POSITION_PRICE.search(body)
        pru_match = _POSITION_PRU.search(body)
        pnl_match = _POSITION_PNL.search(body)

        positions.append({
            "isin": isin,
            "symbol": isin or code,
            "label": label,
            "quantity": decimal_value(quantity_match.group(1), "quantity"),
            "buyingPriceEur": optional_decimal_value(
                pru_match.group(1) if pru_match else None, "buyingPriceEur"
            ),
            "currentPrice": optional_decimal_value(
                price_match.group(1) if price_match else None, "currentPrice"
            ),
            "quoteCurrency": "EUR",
            "currentValueEur": decimal_value(valuation_match.group(1), "currentValueEur"),
            "pnlEur": optional_decimal_value(
                pnl_match.group(1).replace(" ", "") if pnl_match else None, "pnlEur"
            ),
        })
    return positions


# The legacy site keys accounts by its own 32-hex `ca` identifier, which is
# a DIFFERENT identifier space from the modern Equipment API's `id` (a
# 22-char base64-ish token). The `ca` values do not appear in the GraphQL
# response. Passing an Equipment id as `ca`
# does not error -- the portfolio page still renders, just without any
# holdings -- so this has to be read from the legacy side.
#
# The legacy home page (LEGACY_HOME_URL) links to every account's own
# situation page, which is where the mapping comes from.
_LEGACY_ACCOUNT_LINK = re.compile(
    r"/fr/prive/mes-comptes/(?P<segment>[a-z0-9-]+)/(?:consulter-)?situation/"
    r"\?ca=(?P<ca>[0-9a-f]{32})"
)


# The legacy site's own links are the only trustworthy source for where an
# account's operation history lives: the URL shape is not guessable and has
# already changed at least once (`situation/` vs `consulter-situation/`).
# Matches history-like page links under /fr/prive/, so one page can report
# which candidates exist instead of the connector guessing a path.
_LEGACY_HISTORY_LINK = re.compile(
    r"/fr/prive/(?P<path>[a-z0-9/-]*"
    r"(?:historique|operation|mouvement|releve)[a-z0-9/._-]*)",
    re.IGNORECASE,
)


def find_legacy_history_paths(html: str) -> list[str]:
    """Distinct history-page *paths* linked from a legacy page, query stripped.

    Discovery aid only: returns paths (never their query strings, which carry
    account identifiers). Sorted and de-duplicated so the result is a stable,
    loggable description of the site's structure rather than of any account.
    """
    return sorted({
        match.group("path").rstrip("?&")
        for match in _LEGACY_HISTORY_LINK.finditer(html)
    })


_TAGS = re.compile(r"<[^>]+>")
_WHITESPACE = re.compile(r"\s+")


def _text_of(fragment: str) -> str:
    return _WHITESPACE.sub(" ", html_lib.unescape(_TAGS.sub(" ", fragment))).strip()


_FORM = re.compile(r"<form\b(?P<attrs>[^>]*)>(?P<body>.*?)</form>", re.IGNORECASE | re.DOTALL)
_FIELD = re.compile(
    r"<(?:input|select|textarea)\b[^>]*?\bname=[\"'](?P<name>[^\"']+)[\"']",
    re.IGNORECASE,
)
_IFRAME_SRC = re.compile(r"<iframe\b[^>]*?\bsrc=[\"'](?P<src>[^\"']+)[\"']", re.IGNORECASE)
_SELECT = re.compile(
    r"<select\b[^>]*?\bname=[\"'](?P<name>[^\"']+)[\"'][^>]*>(?P<body>.*?)</select>",
    re.IGNORECASE | re.DOTALL,
)
_OPTION_VALUE = re.compile(r"<option\b[^>]*?\bvalue=[\"'](?P<value>[^\"']*)[\"']", re.IGNORECASE)
# A <select> on this site can enumerate the customer's own holdings: the results
# page fills `codeReferentiel` with one option per security the account has
# traded, each carrying its ISIN. Option values are therefore reported only for
# the fields whose vocabulary is known to belong to the site rather than to the
# account; everything else is reduced to how many options it had.
_SITE_OWNED_SELECTS = frozenset({
    "typeOperation",
    "nbResultats",
    "triColonne",
    "sensTriColonne",
})
_INPUT = re.compile(r"<input\b(?P<attrs>[^>]*)>", re.IGNORECASE)
_NAME_ATTR = re.compile(r"\bname=[\"']([^\"']+)[\"']", re.IGNORECASE)
_VALUE_ATTR = re.compile(r"\bvalue=[\"']([^\"']*)[\"']", re.IGNORECASE)
_DIGIT = re.compile(r"\d")
_TABLE = re.compile(r"<table\b.*?</table>", re.IGNORECASE | re.DOTALL)
_HEADER_CELL = re.compile(r"<th\b[^>]*>(.*?)</th>", re.IGNORECASE | re.DOTALL)
_ROW = re.compile(r"<tr\b(?P<attrs>[^>]*)>(?P<body>.*?)</tr>", re.IGNORECASE | re.DOTALL)
_CELL = re.compile(r"<t[dh]\b", re.IGNORECASE)
_ATTR_NAME = re.compile(r"\b([a-zA-Z-]+)\s*=")
_CLASS_ATTR = re.compile(r"\bclass=[\"']([^\"']*)[\"']", re.IGNORECASE)
_METHOD_ATTR = re.compile(r"\bmethod=[\"']([^\"']*)[\"']", re.IGNORECASE)
_ACTION_ATTR = re.compile(r"\baction=[\"']([^\"']*)[\"']", re.IGNORECASE)
# Rows a legacy table uses for its own column labels. Deliberately excludes
# "titre": in French that is as likely to mean a *security* as a heading, and
# reading a data row's text is exactly what this must never do.
_HEADER_ROW_CLASS = re.compile(r"\b(?:entete|en-tete|tete|header|thead|libelle-colonne)\b", re.IGNORECASE)


def _path_of(url: str) -> str:
    """Strip the query string: it carries the account's legacy `ca` identifier."""
    return url.split("?", 1)[0].split("#", 1)[0]


def describe_legacy_page_structure(html: str) -> dict[str, Any]:
    """Describe a legacy page's *shape* so a parser can be written against it.

    Reports the page's schema -- form actions and field names, iframe paths,
    table column headers, row markup signatures and cell counts. It never
    reports a cell value: on a securities history page every cell is a date, a
    security name or an amount. Column labels are reported (they are the page's
    vocabulary, not the customer's data), and only from `<th>` cells or from
    rows a table itself marks as heading rows.
    """
    forms = []
    for match in _FORM.finditer(html):
        attrs = match.group("attrs")
        action = _ACTION_ATTR.search(attrs)
        method = _METHOD_ATTR.search(attrs)
        body = match.group("body")
        # A site-owned select's option values are a closed vocabulary -- "all
        # operations / purchase / sale" -- and are the one thing that can say
        # whether a dividend is selectable structurally. See _SITE_OWNED_SELECTS
        # for why every other select is reduced to a count.
        selects: dict[str, Any] = {}
        for select in _SELECT.finditer(body):
            name = select.group("name")
            values = sorted(set(_OPTION_VALUE.findall(select.group("body"))))
            selects[name] = (
                values if name in _SITE_OWNED_SELECTS else f"<{len(values)} options>"
            )
        # Default input values are masked to their *shape* (every digit becomes
        # N), which is what a parser needs -- "NN/NN/NNNN" says dd/mm/yyyy --
        # without ever recording the date or amount a field was pre-filled with.
        defaults = {}
        for field in _INPUT.finditer(body):
            name = _NAME_ATTR.search(field.group("attrs"))
            value = _VALUE_ATTR.search(field.group("attrs"))
            if name and value and value.group(1):
                defaults[name.group(1)] = _DIGIT.sub("N", value.group(1))
        forms.append({
            "action": _path_of(action.group(1)) if action else None,
            "method": (method.group(1).lower() if method else "get"),
            "fields": sorted(set(_FIELD.findall(body))),
            "selects": selects,
            "default_shapes": dict(sorted(defaults.items())),
        })

    tables = []
    for match in _TABLE.finditer(html):
        table = match.group(0)
        headers = [_text_of(cell) for cell in _HEADER_CELL.findall(table)]
        headers = [header for header in headers if header]
        row_classes: set[str] = set()
        row_attrs: set[str] = set()
        cells_per_row: dict[int, int] = {}
        for row in _ROW.finditer(table):
            attrs = row.group("attrs")
            row_attrs.update(name.lower() for name in _ATTR_NAME.findall(attrs))
            class_attr = _CLASS_ATTR.search(attrs)
            if class_attr:
                row_classes.update(class_attr.group(1).split())
                if not headers and _HEADER_ROW_CLASS.search(class_attr.group(1)):
                    headers = [
                        _text_of(cell)
                        for cell in re.findall(
                            r"<t[dh]\b[^>]*>(.*?)</t[dh]>",
                            row.group("body"),
                            re.IGNORECASE | re.DOTALL,
                        )
                    ]
                    headers = [header for header in headers if header]
            count = len(_CELL.findall(row.group("body")))
            if count:
                cells_per_row[count] = cells_per_row.get(count, 0) + 1
        if headers or cells_per_row:
            tables.append({
                "headers": headers,
                "rows": sum(cells_per_row.values()),
                "cells_per_row": dict(sorted(cells_per_row.items())),
                "row_classes": sorted(row_classes),
                # Attribute *names* only: a `name`/`id` value on a legacy row can
                # embed a security code, which would disclose a holding.
                "row_attrs": sorted(row_attrs),
            })

    return {
        "bytes": len(html),
        "forms": forms,
        "iframes": sorted({_path_of(src) for src in _IFRAME_SRC.findall(html)}),
        "tables": tables,
    }


# The securities-history results table has 10 columns. Observed body rows carry
# exactly 10 cells, alternate `class="l1"`/`class="l2"`, and bear no other
# attribute. Column order, from the page's own headers:
#
#     Libelle | Operation | Place | Date | Qte | Prix d'exe |
#     Montant brut | Courtage/Prelevement | Montant Net | Devise
#
# Unlike the positions table there is no per-row `name` attribute to key on,
# so rows are matched on that class and validated by their cell count: a
# layout change that adds or drops a column stops the parse instead of
# silently shifting every value one place to the left.
_HISTORY_ROW = re.compile(
    r"<tr\b[^>]*\bclass=[\"'][^\"']*\bl[12]\b[^\"']*[\"'][^>]*>(?P<body>.*?)</tr>",
    re.IGNORECASE | re.DOTALL,
)
_HISTORY_CELL = re.compile(r"<td\b[^>]*>(?P<cell>.*?)</td>", re.IGNORECASE | re.DOTALL)
_HISTORY_COLUMNS = 10
_HISTORY_DATE = re.compile(r"^(?P<day>\d{2})/(?P<month>\d{2})/(?P<year>\d{4})$")


def _history_cell_text(cell: str) -> str:
    return _WHITESPACE.sub(" ", html_lib.unescape(_TAGS.sub(" ", cell))).strip()


def _mask(value: str) -> str:
    """Reduce a cell to its shape: digits become N, letters A, punctuation stays.

    Any non-ASCII character is rendered as its codepoint rather than itself, so
    an unfamiliar separator can be identified from a log line instead of arriving
    as an unreadable byte. Reveals no number, no security name, no content.
    """
    masked = re.sub(r"[^\W\d_]", "A", value, flags=re.UNICODE)
    masked = re.sub(r"[0-9]", "N", masked)
    return "".join(
        char if char.isascii() else f"<U+{ord(char):04X}>" for char in masked
    )


def _history_amount(value: str, field: str) -> Decimal | None:
    """Parse one amount cell, reporting an unexpected format by shape alone."""
    normalized = _normalize_spaces(value).strip()
    try:
        return optional_decimal_value(normalized or None, field)
    except PortfolioFormatError as exc:
        raise PortfolioFormatError(
            f"Fortuneo history {field} had an unexpected format (shape={_mask(value)!r})"
        ) from exc


def parse_securities_history(html: str) -> list[dict[str, Any]]:
    """Parse one page of the legacy securities-history table.

    Returns one dict per operation, with the raw operation label kept as the
    provider reported it: classifying it (a purchase, a sale, a dividend) is a
    decision for the caller, and this module must not guess at a vocabulary it
    has not seen.

    Amounts are optional throughout. A dividend line carries no execution price
    and no quantity, while a trade carries both -- so requiring either would
    reject exactly the rows this page was opened for. The date is not optional:
    a row that cannot be placed in time is unusable, and dropping it silently
    would understate the history.
    """
    html = _normalize_nbsp(html)
    operations: list[dict[str, Any]] = []
    saw_malformed_data_row = False
    for match in _HISTORY_ROW.finditer(html):
        cells = [
            _history_cell_text(cell) for cell in _HISTORY_CELL.findall(match.group("body"))
        ]
        if len(cells) != _HISTORY_COLUMNS:
            # Layout rows (spacers and pagination) legitimately share the class
            # but contain at most one cell. A multi-cell row is provider data
            # whose column layout changed and must not look like empty history.
            saw_malformed_data_row = saw_malformed_data_row or len(cells) > 1
            continue
        label, operation, place, raw_date, quantity, price, gross, fees, net, currency = cells
        parsed_date = _HISTORY_DATE.match(raw_date)
        if not parsed_date:
            raise PortfolioFormatError(
                f"Fortuneo history row has an unparseable date: {raw_date!r}"
            )
        operations.append({
            "date": (
                f"{parsed_date.group('year')}-"
                f"{parsed_date.group('month')}-{parsed_date.group('day')}"
            ),
            "label": label,
            "operation": operation,
            "place": place or None,
            "quantity": _history_amount(quantity, "quantity"),
            "unitPrice": _history_amount(price, "unitPrice"),
            "grossAmount": _history_amount(gross, "grossAmount"),
            "fees": _history_amount(fees, "fees"),
            "netAmount": _history_amount(net, "netAmount"),
            "currency": (currency or None),
        })
    if saw_malformed_data_row and not operations:
        raise PortfolioFormatError(
            "Fortuneo history rows did not have the expected column count"
        )
    return operations


# Fortuneo's securities-operation vocabulary comes from the `typeOperation`
# options exposed by the search form. It is mapped onto
# Picsou's transaction types by longest-prefix match on a normalised label
# (accents stripped, lower-cased, whitespace collapsed).
#
# Anything unrecognised is imported WITHOUT a type rather than guessed at: an
# unknown operation stays visible in the ledger, and a wrong type would silently
# corrupt the dividend and realized-P&L aggregates built on top of it.
_HISTORY_CANCELLATION = "annul."

# A dividend has an entitlement leg when the coupon detaches and a cash leg when
# the payment lands. Importing both would double every dividend, so the
# entitlement leg is dropped and only the cash leg is kept.
_HISTORY_IGNORED = (
    "ost de creation de coupons - detachement",
)

_HISTORY_TYPES = (
    ("achat comptant", "BUY"),
    ("vente comptant", "SELL"),
    # Covers both "...interet/dividende" and "...sur OPCVM": the cash leg of a
    # distribution, whichever instrument paid it.
    ("encaissement coupons", "DIVIDEND"),
    ("taxe transac finan", "FEE"),
)


def _normalize_operation(operation: str) -> str:
    """Lower-case, unaccent, and drop a cancellation prefix.

    A cancellation reverses an earlier operation and carries the negated amount,
    so it is classified as whatever it reverses: the pair then nets to zero in
    every aggregate instead of leaving a stray untyped row behind.
    """
    normalized = _WHITESPACE.sub(" ", _strip_accents(operation or "")).strip().lower()
    if normalized.startswith(_HISTORY_CANCELLATION):
        normalized = normalized[len(_HISTORY_CANCELLATION):].strip()
    return normalized


def is_ignored_securities_operation(operation: str) -> bool:
    """Whether this operation duplicates another and must not be imported."""
    normalized = _normalize_operation(operation)
    return any(normalized.startswith(prefix) for prefix in _HISTORY_IGNORED)


def classify_securities_operation(operation: str) -> str | None:
    """Map an operation label to a Picsou transaction type, or None if unknown.

    None is a deliberate outcome, not a failure: the row is still imported and
    still visible, it simply does not join a typed aggregate. Guessing would be
    worse than abstaining -- "Indemnisation", for instance, covers several
    unrelated broker events and cannot be typed from its label alone.
    """
    normalized = _normalize_operation(operation)
    for prefix, tx_type in _HISTORY_TYPES:
        if normalized.startswith(prefix):
            return tx_type
    return None


def fingerprint_securities_history(
    operations: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Give each operation a stable synthetic identifier.

    The legacy history page exposes no per-row id, unlike the transaction API.
    Without one the backend falls back to replacing a rolling 90-day window,
    which for a ledger fetched in full would both truncate the history and
    duplicate it on the next sync -- so an identifier has to be derived.

    It is a digest of every column of the row, plus a counter that distinguishes
    otherwise-identical rows (two identical purchases on the same day are a real
    thing). The digest is stable across syncs because the same full history is
    read in the same provider order every time. The one way it can shift is a
    backdated row appearing *between* two identical rows, which renumbers only
    those identical rows -- and since they are identical, the rewrite is a no-op.
    """
    seen: dict[str, int] = {}
    fingerprinted: list[dict[str, Any]] = []
    for operation in operations:
        material = "|".join(
            "" if operation.get(field) is None else str(operation.get(field))
            for field in (
                "date", "operation", "label", "place",
                "quantity", "unitPrice", "grossAmount", "fees", "netAmount", "currency",
            )
        )
        occurrence = seen.get(material, 0)
        seen[material] = occurrence + 1
        digest = hashlib.sha256(f"{material}|{occurrence}".encode()).hexdigest()[:32]
        fingerprinted.append({**operation, "externalId": f"ft_h_{digest}"})
    return fingerprinted


# The results page fills its `codeReferentiel` filter with one option per
# security the account has traded: the option value carries Fortuneo's internal
# reference with the ISIN as its last twelve characters, and the option text is
# the very label its history rows use. That makes the label -> ISIN mapping the
# provider's own, not a guess -- which is the only basis on which an instrument
# may be attached to a ledger row (see docs/features/fortuneo.md).
_REFERENTIAL_SELECT = re.compile(
    r"<select\b[^>]*?\bname=[\"']codeReferentiel[\"'][^>]*>(?P<body>.*?)</select>",
    re.IGNORECASE | re.DOTALL,
)
_REFERENTIAL_OPTION = re.compile(
    r"<option\b[^>]*?\bvalue=[\"'](?P<value>[^\"']*)[\"'][^>]*>(?P<label>.*?)</option>",
    re.IGNORECASE | re.DOTALL,
)
_ISIN = re.compile(r"(?P<isin>[A-Z]{2}[A-Z0-9]{9}\d)$")


def _history_label_key(label: str) -> str:
    """Normalise a security label for exact matching between the two sources."""
    return _WHITESPACE.sub(" ", _strip_accents(label or "")).strip().casefold()


def parse_securities_referential(html: str) -> dict[str, str]:
    """Map security label -> ISIN, from the history page's own filter options.

    Returns only entries whose value ends in something ISIN-shaped. A label the
    provider lists twice is kept once: repeated labels would be ambiguous, so
    the first wins and the caller sees a single answer rather than a silent
    overwrite.
    """
    select = _REFERENTIAL_SELECT.search(html)
    if not select:
        return {}
    referential: dict[str, str] = {}
    for option in _REFERENTIAL_OPTION.finditer(select.group("body")):
        isin_match = _ISIN.search(option.group("value").strip())
        label = _history_label_key(_text_of(option.group("label")))
        if isin_match and label:
            referential.setdefault(label, isin_match.group("isin"))
    return referential


def attach_securities_isins(
    operations: list[dict[str, Any]],
    referential: dict[str, str],
) -> tuple[list[dict[str, Any]], int]:
    """Attach an ISIN to each row whose label the referential knows exactly.

    Returns the operations and how many stayed unmatched. Matching is exact on a
    normalised label: a fuzzy match would attach the wrong instrument to a real
    trade, which is worse in every way than attaching none -- a row without an
    ISIN is simply left out of the realized-P&L stream.
    """
    attached: list[dict[str, Any]] = []
    unmatched = 0
    for operation in operations:
        isin = referential.get(_history_label_key(operation.get("label", "")))
        if isin is None:
            unmatched += 1
        attached.append({**operation, "isin": isin})
    return attached, unmatched


def parse_legacy_account_ids(html: str) -> dict[str, str]:
    """Map legacy URL segment -> that account's legacy `ca` id, read from
    the legacy home page's own account links.

    First occurrence wins per segment. An individual can hold only one PEA
    and one PEA-PME by French regulation, so those are unambiguous; a
    customer with several CTOs would need disambiguating beyond the segment
    (the same open question as the multi-CTO cash-pocket case).
    """
    ids: dict[str, str] = {}
    for match in _LEGACY_ACCOUNT_LINK.finditer(html):
        ids.setdefault(match.group("segment"), match.group("ca"))
    return ids


# The situation page carries its own authoritative summary table. Its
# "Évaluation Titres"
# reconciles against the parsed holdings -- catching the case where the
# page renders but its table doesn't, which is otherwise indistinguishable
# from a genuinely empty account. "Solde espèces EUR" supplies cash without
# deriving it by subtraction.
# The value cell is captured loosely and validated afterwards, rather than
# matched with a numeric pattern inline. The same page reaches us in two
# forms: raw HTTP text, where the thousands separator is a literal U+00A0,
# and browser-serialised DOM (frame.content()), where it is the entity
# `&nbsp;`. A numeric-only pattern would silently fail to match that form.
_SUMMARY_ROW = re.compile(
    r"<tr[^>]*>\s*<td[^>]*>\s*(?:<strong>\s*)?(?P<label>[^<]+?)\s*(?:</strong>\s*)?</td>\s*"
    r"<td[^>]*>\s*(?P<value>[^<]*?)\s*</td>",
    re.DOTALL,
)
_SUMMARY_NUMBER = re.compile(r"-?\d[\d\s.,\xa0]*")
_SUMMARY_FIELDS = (
    ("securitiesEur", ("evaluation titres",)),
    ("cashEur", ("solde especes",)),
    ("totalEur", ("valorisation totale",)),
)


def _strip_accents(value: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFD", value) if unicodedata.category(c) != "Mn"
    )


def parse_portfolio_summary(html: str) -> dict[str, Any]:
    """Read the situation page's own valuation summary.

    Returns `{"securitiesEur", "cashEur", "totalEur"}`, each `None` when
    that row is absent. Labels are matched accent- and case-insensitively
    on a prefix, so "Solde espèces EUR" still matches if Fortuneo appends
    or re-cases anything; the ordering of rows is not relied on.
    """
    html = _normalize_nbsp(html)
    found: dict[str, Any] = {key: None for key, _ in _SUMMARY_FIELDS}
    for match in _SUMMARY_ROW.finditer(html):
        label = _strip_accents(html_lib.unescape(match.group("label"))).lower().strip()
        for key, prefixes in _SUMMARY_FIELDS:
            if found[key] is None and any(label.startswith(p) for p in prefixes):
                value = html_lib.unescape(match.group("value")).strip()
                if _SUMMARY_NUMBER.fullmatch(value):
                    found[key] = optional_decimal_value(value, key)
                break
    return found
