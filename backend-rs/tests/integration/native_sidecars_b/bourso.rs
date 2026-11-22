//! Comprehensive unit, parser, and wiremock HTTP flow tests for `NativeBourso`.
//!
//! Ported from `services/bourso-auth/test_*.py`. Synthetic fixtures only; zero internet access.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use chrono::NaiveDate;
use http::{Method, StatusCode};
use picsou::native_sidecars::bourso::accounts_parser::{
    AccountKind, FORMAT_CHANGED, IDENTITY_UNSUPPORTED, INCOMPLETE, INVALID_DATA,
    OperationsParseError, PositionPayload, account_type, choose_personal_identity,
    describe_payload, guard_symbol_collisions, is_own_account, money_close, parse_amount,
    parse_dashboard, parse_iban_page, parse_life_insurance, parse_operations, parse_per,
    parse_trading_summary,
};
use picsou::native_sidecars::bourso::cookies::SessionCookieJar;
use picsou::native_sidecars::bourso::html::clean_text;
use picsou::native_sidecars::bourso::virtual_pad::{
    encode_password, extract_challenge, parse_virtual_pad, svg_digest,
};
use picsou::native_sidecars::bourso::{
    BoursoConfig, MAX_MOVEMENT_PAGES, NativeBourso, extract_brs_config, extract_form_token,
    is_fraud_education_page, json_success_body, notice_dismiss_link,
    redirects_to_identity_selector, redirects_to_profile_notice, same_origin_url,
    strong_auth_params, validate_api_url,
};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use wiremock::matchers::{method, path, path_regex, query_param};
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

fn d(raw: &str) -> Decimal {
    raw.parse().expect("valid test decimal")
}

// ─── Synthetic Fixtures ─────────────────────────────────────────────────────

const SVG_BY_DIGIT: [&str; 10] = [
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxwYXRoIGQ9Im0yMS41IDZjNC42I",
        "DAgNi40IDQuOCA2LjQgOC45cy0xLjggOC45LTYuNCA4LjljLTQuNyAwLTYuNC00LjgtNi40LTguOXMxLjgtOC45IDY",
        "uNC04Ljl6bTAgMS40Yy0zLjYgMC00LjggNC00LjggNy42IDAgMy41IDEuMiA3LjYgNC44IDcuNnM0LjgtNCA0LjgtN",
        "y42LTEuMi03LjYtNC44LTcuNnoiIGZpbGw9IiMwMDM4ODMiLz48L3N2Zz4="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxwYXRoIGQ9Im0yMC44IDguMy0yL",
        "jggMy0uOS0xIDMuOC00aDEuM3YxNy4zaC0xLjV2LTE1LjN6IiBmaWxsPSIjMDAzODgzIi8+PC9zdmc+"
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im0xMy45IDM1LjloLTMuNmwtLjYgMS42aC0xbDIuOS03LjJ",
        "oMS4xbDIuOSA3LjJoLTF6bS0zLjMtLjhoM2wtMS41LTMuOXoiLz48cGF0aCBkPSJtMTguNyAzMC4zaDMuMmMxLjIgM",
        "CAyIC44IDIgMS44IDAgLjktLjYgMS41LTEuMyAxLjYuOC4xIDEuNC45IDEuNCAxLjggMCAxLjItLjggMS45LTIuMSA",
        "xLjloLTMuM3YtNy4xem0zIDMuMWMuOCAwIDEuMi0uNSAxLjItMS4yIDAtLjYtLjQtMS4yLTEuMi0xLjJoLTIuMnYyL",
        "jNoMi4yem0wIDMuM2MuOCAwIDEuMy0uNSAxLjMtMS4ycy0uNS0xLjItMS4zLTEuMmgtMi4ydjIuNWgyLjJ6Ii8+PHB",
        "hdGggZD0ibTI3LjMgMzMuOWMwLTIuMiAxLjYtMy43IDMuNy0zLjcgMS4zIDAgMi4yLjYgMi43IDEuNGwtLjguNGMtL",
        "jQtLjYtMS4yLTEtMi0xLTEuNiAwLTIuOCAxLjItMi44IDIuOXMxLjIgMi45IDIuOCAyLjljLjggMCAxLjYtLjQgMi0",
        "xbC44LjRjLS42LjgtMS41IDEuNC0yLjcgMS40LTIuMSAwLTMuNy0xLjUtMy43LTMuN3oiLz48L2c+PHBhdGggZD0ib",
        "TE1LjkgMjIuM2M1LjktNC43IDkuOC04LjEgOS44LTExLjQgMC0yLjUtMi0zLjUtMy45LTMuNS0yLjEgMC0zLjguOS0",
        "0LjcgMi4zbC0xLS45YzEuMi0xLjggMy4zLTIuOCA1LjctMi44IDIuNSAwIDUuNCAxLjQgNS40IDQuOSAwIDMuOC00I",
        "DcuMy05IDExLjNoOS4xdjEuM2gtMTEuNHoiLz48L2c+PC9zdmc+"
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im0xMC4yIDMwLjNoMi41YzIuMiAwIDMuNyAxLjYgMy43IDM",
        "uNnMtMS41IDMuNi0zLjcgMy42aC0yLjV6bTIuNSA2LjRjMS43IDAgMi44LTEuMiAyLjgtMi44IDAtMS41LTEtMi44L",
        "TIuOC0yLjhoLTEuNnY1LjZ6Ii8+PHBhdGggZD0ibTE5LjkgMzAuM2g0Ljd2LjhoLTMuOHYyLjNoMy43di44aC0zLjd",
        "2Mi41aDMuOHYuOGgtNC43eiIvPjxwYXRoIGQ9Im0yOC4xIDMwLjNoNC43di44aC0zLjh2Mi4zaDMuN3YuOGgtMy43d",
        "jMuM2gtLjl6Ii8+PC9nPjxwYXRoIGQ9Im0xNi4zIDIwLjFjMSAxLjQgMi42IDIuNCA0LjggMi40IDIuNyAwIDQuMy0",
        "xLjQgNC4zLTMuNyAwLTIuNS0yLTMuNS00LjYtMy41LS43IDAtMS4zIDAtMS42IDB2LTEuM2gxLjZjMi4zIDAgNC40L",
        "TEgNC40LTMuMyAwLTIuMS0xLjktMy4zLTQuMS0zLjMtMiAwLTMuNC44LTQuNiAyLjJsLS45LS45YzEuMi0xLjUgMy4",
        "xLTIuNyA1LjYtMi43IDMgMCA1LjYgMS42IDUuNiA0LjYgMCAyLjYtMi4yIDMuOC0zLjcgNCAxLjUuMiA0IDEuNCA0I",
        "DQuM3MtMi4xIDQuOS01LjggNC45Yy0yLjggMC00LjktMS4zLTUuOS0yLjl6Ii8+PC9nPjwvc3ZnPg=="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im0xMy42IDMwLjJjMS4zIDAgMi4yLjYgMi44IDEuM2wtLjc",
        "uNWMtLjUtLjYtMS4yLTEtMi4xLTEtMS42IDAtMi44IDEuMi0yLjggMi45czEuMiAyLjkgMi44IDIuOWMuOSAwIDEuN",
        "i0uNCAxLjktLjh2LTEuNWgtMi41di0uOGgzLjR2Mi42Yy0uNy43LTEuNiAxLjItMi44IDEuMi0yIDAtMy43LTEuNS0",
        "zLjctMy43czEuNy0zLjYgMy43LTMuNnoiLz48cGF0aCBkPSJtMjUuMSAzNC4yaC00LjJ2My4zaC0uOXYtNy4yaC45d",
        "jMuMWg0LjJ2LTMuMWguOXY3LjJoLS45eiIvPjxwYXRoIGQ9Im0yOS44IDMwLjNoLjl2Ny4yaC0uOXoiLz48L2c+PHB",
        "hdGggZD0ibTIzLjYgMTguOGgtOC4ydi0xLjNsNy43LTExLjJoMnYxMS4yaDIuNXYxLjNoLTIuNXY0LjdoLTEuNXptL",
        "TYuNy0xLjNoNi43di05Ljd6Ii8+PC9nPjwvc3ZnPg=="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im0xMS42IDM2LjFjLjMuNC43LjcgMS40LjcuOSAwIDEuNC0",
        "uNiAxLjQtMS41di01aC45djVjMCAxLjYtMSAyLjMtMi4zIDIuMy0uOCAwLTEuNC0uMi0xLjktLjh6Ii8+PHBhdGggZ",
        "D0ibTIwLjcgMzQuMy0uNy44djIuNGgtLjl2LTcuMmguOXYzLjdsMy4yLTMuN2gxLjFsLTMgMy40IDMuMiAzLjhoLTE",
        "uMXoiLz48cGF0aCBkPSJtMjcuNyAzMC4zaC45djYuNGgzLjR2LjhoLTQuMnYtNy4yeiIvPjwvZz48cGF0aCBkPSJtM",
        "TcuNCAyMC4xYzEuMSAxLjYgMi42IDIuNSA0LjggMi41IDIuNSAwIDQuMy0xLjggNC4zLTQuMiAwLTIuNi0xLjgtNC4",
        "yLTQuMy00LjItMS42IDAtMi45LjUtNC4yIDEuN2wtMS0uNnYtOWgxMHYxLjNoLTguNXY2LjhjLjktLjggMi4zLTEuN",
        "iA0LjEtMS42IDIuOSAwIDUuNSAxLjkgNS41IDUuNSAwIDMuNC0yLjYgNS42LTUuOCA1LjYtMi45IDAtNC42LTEuMS0",
        "1LjgtMi44eiIvPjwvZz48L3N2Zz4="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im0xMy45IDMxLjYtMi40IDUuOWgtLjRsLTIuNC01Ljl2NS4",
        "5aC0uOXYtNy4yaDEuM2wyLjIgNS40IDIuMi01LjRoMS4zdjcuMmgtLjl6Ii8+PHBhdGggZD0ibTE5LjUgMzEuOHY1L",
        "jdoLS45di03LjJoLjlsNC4xIDUuNnYtNS42aC45djcuMmgtLjl6Ii8+PHBhdGggZD0ibTMxLjcgMzAuMmMyLjEgMCA",
        "zLjYgMS42IDMuNiAzLjdzLTEuNCAzLjctMy42IDMuN2MtMi4xIDAtMy42LTEuNi0zLjYtMy43czEuNC0zLjcgMy42L",
        "TMuN3ptMCAuOGMtMS43IDAtMi43IDEuMi0yLjcgMi45czEgMi45IDIuNiAyLjkgMi42LTEuMiAyLjYtMi45Yy4xLTE",
        "uNy0uOS0yLjktMi41LTIuOXoiLz48L2c+PHBhdGggZD0ibTIyLjYgNmMyLjMgMCAzLjYuOSA0LjcgMi4ybC0uOSAxL",
        "jFjLS44LTEuMS0xLjktMS45LTMuOC0xLjktMy43IDAtNS4xIDMuOS01LjEgNy42di44Yy43LTEuMiAyLjctMi45IDU",
        "tMi45IDMuMSAwIDUuNiAxLjggNS42IDUuNSAwIDIuOC0yLjEgNS41LTUuOCA1LjUtNC43IDAtNi4zLTQuMy02LjMtO",
        "C45IDAtNC41IDEuOC05IDYuNi05em0tLjMgOC4yYy0xLjkgMC0zLjcgMS4yLTQuNyAzIC4yIDIuNCAxLjQgNS40IDQ",
        "uNyA1LjQgMyAwIDQuMy0yLjMgNC4zLTQuMSAwLTIuOS0xLjgtNC4zLTQuMy00LjN6Ii8+PC9nPjwvc3ZnPg=="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im01IDMwLjRoMi45YzEuNCAwIDIuMiAxIDIuMiAyLjJzLS4",
        "4IDIuMi0yLjIgMi4yaC0ydjIuOWgtLjl6bTIuOC44aC0xLjl2Mi44aDEuOWMuOSAwIDEuNC0uNiAxLjQtMS40cy0uN",
        "S0xLjQtMS40LTEuNHoiLz48cGF0aCBkPSJtMTkuMyAzNi43LjcuNy0uNi41LS43LS43Yy0uNS4zLTEuMi41LTEuOS4",
        "1LTIuMSAwLTMuNi0xLjYtMy42LTMuN3MxLjQtMy43IDMuNi0zLjdjMi4xIDAgMy42IDEuNiAzLjYgMy43LS4xIDEuM",
        "S0uNCAyLTEuMSAyLjd6bS0xLjItLjEtMS0xLjEuNi0uNSAxIDEuMWMuNC0uNS43LTEuMi43LTIgMC0xLjctMS0yLjk",
        "tMi42LTIuOXMtMi42IDEuMi0yLjYgMi45IDEgMi45IDIuNiAyLjljLjUtLjEuOS0uMiAxLjMtLjR6Ii8+PHBhdGggZ",
        "D0ibTI2LjIgMzQuOGgtMS40djIuOWgtLjl2LTcuMmgyLjljMS4zIDAgMi4yLjggMi4yIDIuMiAwIDEuMy0uOSAyLTE",
        "uOSAyLjFsMS45IDIuOWgtMXptLjQtMy42aC0xLjl2Mi44aDEuOWMuOCAwIDEuNC0uNiAxLjQtMS40LjEtLjgtLjUtM",
        "S40LTEuNC0xLjR6Ii8+PHBhdGggZD0ibTMyLjcgMzUuOWMuNS41IDEuMiAxIDIuMyAxIDEuMyAwIDEuNy0uNyAxLjc",
        "tMS4yIDAtLjktLjktMS4xLTEuOC0xLjQtMS4yLS4zLTIuNC0uNi0yLjQtMiAwLTEuMiAxLjEtMiAyLjUtMiAxLjEgM",
        "CAxLjkuNCAyLjUgMWwtLjcuN2MtLjUtLjYtMS4zLS45LTIuMS0uOS0uOSAwLTEuNS41LTEuNSAxLjEgMCAuNy44Ljk",
        "gMS43IDEuMiAxLjIuMyAyLjUuNyAyLjUgMi4yIDAgMS0uNyAyLjEtMi42IDIuMS0xLjIgMC0yLjItLjUtMi44LTEuM",
        "XoiLz48L2c+PHBhdGggZD0ibTI0LjkgNy42aC05LjV2LTEuM2gxMS4zdjFsLTcuNCAxNi4yaC0xLjZ6Ii8+PC9nPjw",
        "vc3ZnPg=="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im0xMS44IDMxLjFoLTIuM3YtLjhoNS40di44aC0yLjN2Ni4",
        "0aC0uOXYtNi40eiIvPjxwYXRoIGQ9Im0xOC4zIDMwLjNoLjl2NC40YzAgMS4zLjcgMi4xIDIgMi4xczItLjggMi0yL",
        "jF2LTQuNGguOXY0LjRjMCAxLjgtMSAyLjktMi45IDIuOXMtMi45LTEuMi0yLjktMi45eiIvPjxwYXRoIGQ9Im0yNy4",
        "yIDMwLjNoMWwyLjQgNi4yIDIuNC02LjJoMWwtMi45IDcuMmgtMS4xeiIvPjwvZz48cGF0aCBkPSJtMjAuMyAxNC43Y",
        "y0yLS41LTQtMS45LTQtNC4yIDAtMy4xIDIuOC00LjUgNS42LTQuNSAyLjcgMCA1LjYgMS40IDUuNiA0LjUgMCAyLjM",
        "tMiAzLjYtNCA0LjIgMi4yLjYgNC4zIDIuMiA0LjMgNC42IDAgMi44LTIuNSA0LjYtNS44IDQuNnMtNS45LTEuOC01L",
        "jktNC42Yy0uMS0yLjUgMi00LjEgNC4yLTQuNnptMS42LjZjLTEuMS4xLTQuNCAxLjItNC40IDMuOCAwIDIuMSAyLjE",
        "gMy40IDQuNCAzLjRzNC40LTEuMyA0LjQtMy40YzAtMi42LTMuNC0zLjYtNC40LTMuOHptMC03LjljLTIuMyAwLTQuM",
        "SAxLjItNC4xIDMuMyAwIDIuNCAzLjEgMy4yIDQuMSAzLjQgMS4xLS4yIDQuMS0xIDQuMS0zLjQgMC0yLjEtMS44LTM",
        "uMy00LjEtMy4zeiIvPjwvZz48L3N2Zz4="
    ),
    concat!(
        "data:image/svg+xml;base64, PHN2ZyBlbmFibGUtYmFja2dyb3VuZD0ibmV3IDAgMCA0MiA0MiIgdmlld0JveD0",
        "iMCAwIDQyIDQyIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxnIGZpbGw9IiMwMDM4ODMiPjxnI",
        "GVuYWJsZS1iYWNrZ3JvdW5kPSJuZXciPjxwYXRoIGQ9Im03LjYgMzEuNy0xLjYgNS44aC0xbC0yLTcuMmgxbDEuNiA",
        "2IDEuNi02aC44bDEuNiA2IDEuNi02aDFsLTIgNy4yaC0xeiIvPjxwYXRoIGQ9Im0xOCAzNC40LTIuMyAzLjFoLTEuM",
        "WwyLjgtMy43LTIuNi0zLjVoMS4xbDIuMSAyLjkgMi4xLTIuOWgxLjFsLTIuNiAzLjUgMi44IDMuN2gtMS4xeiIvPjx",
        "wYXRoIGQ9Im0yNi42IDM0LjUtMi44LTQuMWgxbDIuMiAzLjMgMi4yLTMuM2gxbC0yLjggNC4xdjNoLS45di0zeiIvP",
        "jxwYXRoIGQ9Im0zMy4xIDM2LjggNC01LjZoLTR2LS44aDUuMnYuN2wtNCA1LjZoNC4xdi44aC01LjJ2LS43eiIvPjw",
        "vZz48cGF0aCBkPSJtMTcuNyAyMC42Yy44IDEuMSAxLjkgMS45IDMuOCAxLjkgMy44IDAgNS4xLTQgNS4xLTcuNnYtL",
        "jhjLS44IDEuMi0yLjcgMi45LTUuMSAyLjktMy4xIDAtNS42LTEuOC01LjYtNS41LjEtMi44IDIuMi01LjUgNS45LTU",
        "uNSA0LjcgMCA2LjMgNC4zIDYuMyA4LjkgMCA0LjQtMS44IDguOS02LjYgOC45LTIuMyAwLTMuNi0uOS00LjYtMi4ye",
        "m00LjEtMTMuMmMtMyAwLTQuMyAyLjMtNC4zIDQuMSAwIDIuOCAxLjkgNC4yIDQuMyA0LjIgMS45IDAgMy43LTEuMiA",
        "0LjctMy0uMi0yLjMtMS40LTUuMy00LjctNS4zeiIvPjwvZz48L3N2Zz4="
    ),
];

const PAD_KEYS: [&str; 10] = [
    "AAA", "BBB", "CCC", "DDD", "EEE", "FFF", "GGG", "HHH", "III", "JJJ",
];
const SHUFFLED: [usize; 10] = [7, 2, 9, 0, 4, 1, 8, 5, 3, 6];

fn pad_html(order: &[usize], keys: &[&str; 10], svgs: &[String; 10]) -> String {
    order
        .iter()
        .map(|&digit| {
            format!(
                r#"<button class="c-vpad__button" type="button" data-matrix-key="{}" data-brs-vpad-key><img alt="" src="{}"></button>"#,
                keys[digit], svgs[digit]
            )
        })
        .collect()
}

fn default_svgs() -> [String; 10] {
    std::array::from_fn(|i| SVG_BY_DIGIT[i].to_owned())
}

const CHECKING_ID: &str = "e2f509c466f5294f15abd873dbbf8a62";
const LDDS_ID: &str = "a8a23172b7e7c91c538831578242112e";
const PEA_ID: &str = "9651d8edd5975de1b9eff3865505f15f";
const LIFE_INSURANCE_ID: &str = "0a1b2c3d4e5f60718293a4b5c6d7e8f9";
const PER_ID: &str = "70c0b19d3fd74474c13c86e0c906d4ff";
const USER_HASH: &str = "61d55b52615fbdf";
const PERSONAL_TOKEN: &str = "c3ludGhldGljLXBlcnNvbmFs.v1";
const BUSINESS_TOKEN: &str = "c3ludGhldGljLWJ1c2luZXNz.v1";

const DASHBOARD_HTML: &str = r#"
<div class="c-panel"><div class="c-panel__body"><div>
<ul class="c-info-box" data-brs-list-header data-summary-bank>
  <li class="c-panel__item c-info-box__item">
    <a class="c-info-box__link-wrapper" href="/compte/cav/e2f509c466f5294f15abd873dbbf8a62/"
       aria-label="Détails du compte BoursoBank - Solde : 20 810,50 €" title="BoursoBank">
      <span class="c-info-box__account">
        <span class="c-info-box__account-label" data-account-label="e2f509c466f5294f15abd873dbbf8a62">
          BoursoBank
        </span>
        <span class="c-info-box__account-balance">20 810,50 €</span>
      </span>
      <span class="c-info-box__account-sub-label">BoursoBank</span>
    </a>
  </li>
  <li class="c-panel__item c-info-box__item">
    <a class="c-info-box__link-wrapper" href="/budget/compte/a22217240487004d13c8a6b5da422bbf/"
       aria-label="Détails du compte Compte de chèques ****0102 - Solde : 500,40 €" title="Compte de chèques ****0102">
      <span class="c-info-box__account">
        <span class="c-info-box__account-label">Compte de chèques ****0102</span>
        <span class="c-info-box__account-balance">500,40 €</span>
      </span>
      <span class="c-info-box__account-sub-label">CIC</span>
    </a>
  </li>
</ul></div></div></div>
<div class="c-panel"><div class="c-panel__body"><div>
<ul class="c-info-box" data-brs-list-header data-summary-savings>
  <li class="c-panel__item c-info-box__item">
    <a class="c-info-box__link-wrapper" href="/compte/epargne/ldd/a8a23172b7e7c91c538831578242112e/"
       aria-label="Détails du compte LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE - Solde : 11 010,00 €">
      <span class="c-info-box__account">
        <span class="c-info-box__account-label">
          LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE
        </span>
        <span class="c-info-box__account-balance">11 010,00 €</span>
      </span>
      <span class="c-info-box__account-sub-label">BoursoBank</span>
    </a>
  </li>
  <li class="c-panel__item c-info-box__item">
    <a class="c-info-box__link-wrapper" href="/budget/compte/d4e4fd4067b6d4d0b538a15e42238ef9/"
       aria-label="Détails du compte Livret Jeune - Solde : 1 599,72 €">
      <span class="c-info-box__account">
        <span class="c-info-box__account-label">Livret Jeune</span>
        <span class="c-info-box__account-balance">1 599,72 €</span>
      </span>
      <span class="c-info-box__account-sub-label">Crédit Agricole</span>
    </a>
  </li>
</ul></div></div></div>
<div class="c-panel"><div class="c-panel__body"><div>
<ul class="c-info-box" data-brs-list-header data-summary-trading>
  <li class="c-panel__item c-info-box__item">
    <a class="c-info-box__link-wrapper" href="/compte/pea/9651d8edd5975de1b9eff3865505f15f/"
       aria-label="Détails du compte PEA DOE - Solde : 143 088,89 €">
      <span class="c-info-box__account">
        <span class="c-info-box__account-label">PEA DOE</span>
        <span class="c-info-box__account-balance">143 088,89 €</span>
      </span>
      <span class="c-info-box__account-sub-label">BoursoBank</span>
    </a>
  </li>
</ul></div></div></div>
<div class="c-panel"><div class="c-panel__body"><div>
<ul class="c-info-box" data-brs-list-header data-summary-loan>
  <li class="c-panel__item c-info-box__item">
    <a class="c-info-box__link-wrapper" href="/budget/compte/7315a57115ae889992ec98a6bb3571cb/"
       aria-label="Détails du compte Prêt personnel - Solde : − 94 959,82 €">
      <span class="c-info-box__account">
        <span class="c-info-box__account-label">Prêt personnel</span>
        <span class="c-info-box__account-balance">− 94 959,82 €</span>
      </span>
      <span class="c-info-box__account-sub-label">Crédit Agricole</span>
    </a>
  </li>
</ul></div></div></div>
"#;

const RIB_HTML: &str = r#"<div><strong>IBAN</strong><div class="definition"><p>FR76 3000 6000 0112 3456 7890 189</p></div></div>"#;

const MOVEMENTS_HTML: &str = r#"<ul class="list__movement">
<li class="date-line">2 octobre 2026</li>
<li data-id="fixture-op-1"><div class="list-operation-item__amount">− 24,90 €</div>
<div class="list-operation-item__label-name">PAIEMENT CARTE BOULANGERIE</div></li>
<li data-custom-id="fixture-op-2"><div class="list-operation-item__amount">1 250,00 €</div>
<div class="list-operation-item__label-name">VIR SALAIRE</div></li>
</ul>"#;

fn home_html(api_url: &str) -> String {
    let escaped = api_url.replace('/', r"\/");
    format!(
        r#"<html><head><script>window.BRS_CONFIG = {{"API_URL": "{escaped}", "USER_HASH": "{USER_HASH}"}};</script></head><body><a href="/se-deconnecter">Se déconnecter</a></body></html>"#
    )
}

fn valid_session_state(host: &str) -> String {
    json!({
        "cookies": [
            {"name": "brsxds_x", "value": "synthetic-jwt", "domain": host, "path": "/"}
        ]
    })
    .to_string()
}

fn money(value: &str, currency: Option<&str>) -> Value {
    json!({ "value": value, "decimals": 2, "currency": currency })
}

fn trading_position(symbol: Option<&str>, quantity: &str, amount: &str, currency: &str) -> Value {
    json!({
        "symbol": symbol,
        "label": "Amundi MSCI World",
        "isin": "IE00B4L5Y983",
        "permalink": format!("/cours/{}/", symbol.unwrap_or("")),
        "exchangeCode": "XPAR",
        "currency": "EUR",
        "quantity": { "value": quantity, "decimals": 4, "currency": null },
        "buyingPrice": money("75.00", Some("EUR")),
        "amount": money(amount, Some(currency)),
        "last": money("90.00", Some("EUR")),
        "gainLoss": money("150.00", Some("EUR")),
    })
}

fn trading_summary(cash: &str, valuation: &str, total: &str, positions: Vec<Value>) -> Value {
    json!([
        {
            "id": "summary",
            "label": "Synthèse",
            "account": {
                "name": "PEA DOE",
                "reference": "0123",
                "currency": "EUR",
                "balance": money("0", Some("EUR")),
                "cash": money(cash, Some("EUR")),
                "valuation": money(valuation, Some("EUR")),
                "total": money(total, Some("EUR")),
                "gainLoss": money("120.00", Some("EUR")),
            },
            "actions": [],
        },
        {
            "id": "positions",
            "label": "Positions",
            "actions": [],
            "count": positions.len(),
            "positions": positions,
        }
    ])
}

fn default_trading_summary() -> Value {
    trading_summary(
        "3088.89",
        "140000.00",
        "143088.89",
        vec![json!({
            "symbol": "1rTCW8",
            "label": "Amundi MSCI World UCITS ETF",
            "isin": "IE00B4L5Y983",
            "exchangeCode": "XPAR",
            "currency": "EUR",
            "quantity": { "value": "1000", "decimals": 4, "currency": null },
            "buyingPrice": money("128.00", Some("EUR")),
            "amount": money("140000.00", Some("EUR")),
            "last": money("140.00", Some("EUR")),
        })],
    )
}

fn single_fund_summary(balance: &str, quantity: &str) -> Value {
    json!([
        {
            "account": {
                "name": "CONTRAT DOE",
                "currency": "EUR",
                "balance": money(balance, Some("EUR")),
                "gainLoss": money("50.00", Some("EUR")),
            }
        },
        {
            "fund": {
                "isin": "FR0010315770",
                "label": "Lyxor MSCI World",
                "quantity": quantity,
                "price": money("100.00", Some("EUR")),
            }
        }
    ])
}

fn panel(marker: &str, card_id: &str, name: &str, balance: &str) -> String {
    format!(
        r#"<div class="c-panel"><div class="c-panel__body"><div>
        <ul class="c-info-box" data-brs-list-header {marker}>
          <li class="c-panel__item c-info-box__item">
            <a class="c-info-box__link-wrapper" href="/compte/assurance/{card_id}/"
               aria-label="Détails du compte {name} - Solde : {balance} €" title="{name}">
              <span class="c-info-box__account">
                <span class="c-info-box__account-label">{name}</span>
                <span class="c-info-box__account-balance">{balance} €</span>
              </span>
              <span class="c-info-box__account-sub-label">BoursoBank</span>
            </a>
          </li>
        </ul></div></div></div>"#
    )
}

fn dashboard_with_card(card: &str) -> String {
    let idx = DASHBOARD_HTML.find("data-summary-trading>").unwrap();
    let end = DASHBOARD_HTML[idx..].find("</li>").unwrap() + idx + "</li>".len();
    format!(
        "{}{}{}",
        &DASHBOARD_HTML[..end],
        card,
        &DASHBOARD_HTML[end..]
    )
}

fn dashboard_with_life_insurance() -> String {
    let card = format!(
        r#"<li class="c-panel__item c-info-box__item">
        <a class="c-info-box__link-wrapper" href="/compte/assurance-vie/{LIFE_INSURANCE_ID}"
           aria-label="Détails du compte Contrat N° 12345678 - Solde : 3 300,00 €" title="Contrat N° 12345678">
          <span class="c-info-box__account">
            <span class="c-info-box__account-label">Contrat N° 12345678</span>
            <span class="c-info-box__account-balance">3 300,00 €</span>
          </span>
          <span class="c-info-box__account-sub-label">BoursoBank</span>
        </a></li>"#
    );
    dashboard_with_card(&card)
}

fn dashboard_with_per() -> String {
    let card = format!(
        r#"<li class="c-panel__item c-info-box__item">
        <a class="c-info-box__link-wrapper" href="/compte/per/{PER_ID}"
           aria-label="Détails du compte PER BoursoBank - Solde : 2 230,00 €" title="PER BoursoBank">
          <span class="c-info-box__account">
            <span class="c-info-box__account-label">PER BoursoBank</span>
            <span class="c-info-box__account-balance">2 230,00 €</span>
          </span>
          <span class="c-info-box__account-sub-label">BoursoBank</span>
        </a></li>"#
    );
    dashboard_with_card(&card)
}

#[allow(clippy::too_many_arguments)]
fn li_row(
    label: &str,
    mention: Option<&str>,
    symbol: Option<&str>,
    quantity: &str,
    buying: &str,
    price: &str,
    amount: &str,
    gain: &str,
) -> String {
    let link = match symbol {
        Some(sym) => format!(
            r#"<a href="//bourse.boursobank.com/cours/{sym}" class="c-link"><span class="c-link__label ">{label}</span></a>"#
        ),
        None => format!(r#"<span class="c-link__label ">{label}</span>"#),
    };
    let mention_html = mention
        .map(|m| format!(r#"<span class="c-table__mention">{m}</span>"#))
        .unwrap_or_default();
    let cell = |content: String| format!(r#"<td class="c-table__cell"><div>{content}</div></td>"#);
    format!(
        r#"<tr class="c-table__row">{}{}{}{}{}{}{}{}</tr>"#,
        cell(format!("{link}{mention_html}")),
        cell(r#"<span class="c-table__date">22/10/2026</span>"#.to_owned()),
        cell(format!("<span>{quantity}</span>")),
        cell(format!("<span>{buying}</span>")),
        cell(format!("<span>{price}</span>")),
        cell(format!("<span>{amount}</span>")),
        cell(format!("<span>{gain}</span>")),
        cell("<span>1,00 %</span>".to_owned()),
    )
}

fn li_page(rows: &str, valuation: &str) -> String {
    format!(
        r#"<html><body>
        <div class="c-databox__name"> Valorisation au 23/10/2026 </div>
        <div class="c-databox__value u-color-positive"> {valuation} </div>
        <table data-table-trading-operations>
        <thead><tr><th>Valeur</th><th>Date de Valeur</th><th>Quantité</th><th>Px. Revient</th>
        <th>Cours</th><th>Montant</th><th>+/- Latentes</th><th>+/- %</th></tr></thead>
        <tbody>{rows}</tbody></table></body></html>"#
    )
}

fn default_li_rows() -> String {
    [
        li_row(
            "iShares MSCI World SRI UCITS ETF",
            Some("IE00BYX2JD69"),
            Some("1rASUSW"),
            "100,0000",
            "10,00 €",
            "12,00 €",
            "1 200,00 €",
            "200,00 €",
        ),
        li_row(
            "Fonds Actions Europe A",
            Some("LU0123456789"),
            Some("0P0000ABCD"),
            "40,0000",
            "26,00 €",
            "25,00 €",
            "1 000,00 €",
            "−40,00 €",
        ),
        li_row(
            "Fonds Euro Nouvelle Génération",
            None,
            None,
            "",
            "",
            "",
            "1 100,00 €",
            "",
        ),
    ]
    .concat()
}

#[allow(clippy::too_many_arguments)]
fn per_row(
    label: &str,
    mention: Option<&str>,
    symbol: Option<&str>,
    quantity: &str,
    buying: &str,
    price: &str,
    gain: &str,
    pct: &str,
    amount: &str,
) -> String {
    let link = match symbol {
        Some(sym) => format!(
            r#"<a href="//bourse.boursobank.com/cours/{sym}"><span class="c-link__label ">{label}</span></a>"#
        ),
        None => format!(r#"<span class="c-link__label ">{label}</span>"#),
    };
    let mention_html = mention
        .map(|m| format!(r#"<span class="c-table__mention">{m}</span>"#))
        .unwrap_or_default();
    let mut out = format!(r#"<tr><td>{link}{mention_html}</td>"#);
    for val in [quantity, buying, price, gain, pct, amount] {
        out.push_str(&format!(r#"<td><span>{val}</span></td>"#));
    }
    out.push_str("</tr>");
    out
}

fn per_compartment(name: &str, rows: &str, total: Option<&str>) -> String {
    let total_row = total
        .map(|t| format!(r#"<tr><td>Total</td><td>{t}</td></tr>"#))
        .unwrap_or_default();
    format!(
        r#"<h3>{name}</h3>
        <table data-table-trading-operations>
        <thead><tr><th>Valeur</th><th>Quantité</th><th>Px. Revient</th><th>Cours</th>
        <th>+/- Latentes</th><th>+/- %</th><th>Montant</th></tr></thead>
        <tbody>{rows}{total_row}</tbody></table>"#
    )
}

fn per_page(compartments: &[String], solde: &str) -> String {
    format!(
        r#"<html><body>
        <div class="c-databox__name"> Solde au 12/11/2026 </div>
        <div class="c-databox__value"> {solde} </div>
        {}
        </body></html>"#,
        compartments.concat()
    )
}

fn default_per_html() -> String {
    let c1 = per_compartment(
        "Épargne Volontaire Déductible",
        &[
            per_row(
                "iShares MSCI World SRI UCITS ETF",
                Some("IE00BYX2JD69"),
                Some("1rASUSW"),
                "50,0000",
                "10,00 €",
                "12,00 €",
                "100,00 €",
                "20,00 %",
                "600,00 €",
            ),
            per_row(
                "Fonds Actions Europe A",
                Some("LU0123456789"),
                Some("0P0000ABCD"),
                "40,0000",
                "26,00 €",
                "25,00 €",
                "−40,00 €",
                "−3,85 %",
                "1 000,00 €",
            ),
        ]
        .concat(),
        Some("1 600,00 €"),
    );
    let c2 = per_compartment(
        "Épargne Volontaire non Déductible",
        &per_row(
            "iShares MSCI World SRI UCITS ETF",
            Some("IE00BYX2JD69"),
            Some("1rASUSW"),
            "50,0000",
            "8,00 €",
            "12,00 €",
            "200,00 €",
            "50,00 %",
            "600,00 €",
        ),
        Some("600,00 €"),
    );
    let c3 = per_compartment(
        "Épargne Retraite Obligatoire",
        &per_row(
            "iShares MSCI World SRI UCITS ETF",
            Some("IE00BYX2JD69"),
            Some("1rASUSW"),
            "En cours",
            "En cours",
            "12,00 €",
            "0,00 €",
            "En cours",
            "30,00 €",
        ),
        Some("30,00 €"),
    );
    per_page(&[c1, c2, c3], "2 230,00 €")
}

fn identity_card(token: &str, name: &str, kind: &str) -> String {
    format!(
        r#"<a class="c-identity" href="/connexion/changer-identite/{token}"><span class="c-identity__name">{name}</span><span class="c-identity__type">{kind}</span></a>"#
    )
}

fn identity_selector_html(cards: &[String]) -> String {
    let items: String = cards.iter().map(|c| format!("<li>{c}</li>")).collect();
    format!(r#"<html><body><h1>Choisissez votre espace</h1><ul>{items}</ul></body></html>"#)
}

// ─── Virtual Pad Tests ──────────────────────────────────────────────────────

#[test]
fn virtual_pad_decodes_shuffled_digits_and_encodes_passwords() {
    let svgs = default_svgs();
    let expected: [String; 10] = std::array::from_fn(|i| PAD_KEYS[i].to_owned());

    // Shuffled order resolves into 0..=9 order.
    assert_eq!(
        parse_virtual_pad(&pad_html(&SHUFFLED, &PAD_KEYS, &svgs)).unwrap(),
        expected
    );

    // Space after `base64,` is optional.
    let tightened: [String; 10] = std::array::from_fn(|i| svgs[i].replace("base64, ", "base64,"));
    assert_eq!(
        parse_virtual_pad(&pad_html(&SHUFFLED, &PAD_KEYS, &tightened)).unwrap(),
        expected
    );

    // Unknown SVG refused.
    let mut unknown = svgs.clone();
    unknown[4] = "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=".to_owned();
    assert!(parse_virtual_pad(&pad_html(&SHUFFLED, &PAD_KEYS, &unknown)).is_err());

    // Partial pad refused.
    let err = parse_virtual_pad(&pad_html(&[0, 1, 2, 3], &PAD_KEYS, &svgs)).unwrap_err();
    assert!(err.to_string().contains("missing digits"));

    // Repeated digit refused.
    let mut with_dup = SHUFFLED.to_vec();
    with_dup.push(3);
    assert!(parse_virtual_pad(&pad_html(&with_dup, &PAD_KEYS, &svgs)).is_err());

    // Reused key code refused.
    let mut collide = PAD_KEYS;
    collide[8] = collide[2];
    assert!(parse_virtual_pad(&pad_html(&SHUFFLED, &collide, &svgs)).is_err());

    // Non-pad markup refused.
    assert!(parse_virtual_pad("<div>Service indisponible</div>").is_err());

    // All 10 digests distinct.
    let digests: std::collections::HashSet<String> =
        SVG_BY_DIGIT.iter().map(|s| svg_digest(s)).collect();
    assert_eq!(digests.len(), 10);

    // Challenge extraction (script + attribute + missing).
    assert_eq!(
        extract_challenge(r#"jQuery("[data-matrix-random-challenge]").val("kQ3Zx9-challenge")"#)
            .unwrap(),
        "kQ3Zx9-challenge"
    );
    assert_eq!(
        extract_challenge(r#"<div data-matrix-random-challenge="attr-challenge"></div>"#).unwrap(),
        "attr-challenge"
    );
    assert!(extract_challenge("<div></div>").is_err());

    // Password encoding.
    assert_eq!(
        encode_password("1234", &expected).unwrap(),
        "BBB|CCC|DDD|EEE"
    );
    assert_eq!(encode_password("11", &expected).unwrap(), "BBB|BBB");
    for bad in ["", "12a4", "12 4", "abcd", "1²34"] {
        assert!(
            encode_password(bad, &expected).is_err(),
            "expected err for {bad:?}"
        );
    }
}

// ─── Amounts, AccountType, Dashboard Tests ──────────────────────────────────

#[test]
fn amounts_account_types_and_dashboard_parsing() {
    assert_eq!(parse_amount("11 010,00"), Some(d("11010.00")));
    assert_eq!(parse_amount("143 088,89 €"), Some(d("143088.89")));
    assert_eq!(parse_amount("1\u{00a0}234,5"), Some(d("1234.5")));
    assert_eq!(parse_amount("− 94 959,82"), Some(d("-94959.82")));
    for bad in ["", "   ", "n/a", "-", "€"] {
        assert_eq!(parse_amount(bad), None);
    }

    assert_eq!(account_type("trading", "PEA DOE", ""), AccountKind::Pea);
    assert_eq!(account_type("trading", "PEA-PME", ""), AccountKind::Pea);
    assert_eq!(
        account_type("trading", "Compte titres ordinaire", ""),
        AccountKind::CompteTitres
    );
    assert_eq!(
        account_type(
            "trading",
            "Contrat N° 1",
            &format!("/compte/assurance-vie/{LIFE_INSURANCE_ID}")
        ),
        AccountKind::AssuranceVie
    );
    assert_eq!(
        account_type(
            "trading",
            "Mon épargne retraite",
            &format!("/compte/per/{PER_ID}")
        ),
        AccountKind::Per
    );
    assert_eq!(
        account_type("savings", "Livret d'Épargne Populaire", ""),
        AccountKind::Lep
    );
    assert_eq!(
        account_type("savings", "Livret A", ""),
        AccountKind::LivretA
    );
    assert_eq!(account_type("savings", "LDD", ""), AccountKind::Ldds);
    assert_eq!(
        account_type("savings", "Livret Jeune", ""),
        AccountKind::LivretJeune
    );
    assert_eq!(
        account_type("savings", "Plan d'Épargne Logement", ""),
        AccountKind::Pel
    );
    assert_eq!(
        account_type("savings", "Compte d'Épargne Logement", ""),
        AccountKind::Cel
    );
    assert_eq!(
        account_type("savings", "Livret Bourso+", ""),
        AccountKind::Savings
    );
    assert_eq!(
        account_type("savings", "Livret Leplus", ""),
        AccountKind::Savings
    );
    assert_eq!(
        account_type("savings", "Livret Avenir", ""),
        AccountKind::Savings
    );
    assert_eq!(
        account_type("banking", "BoursoBank", ""),
        AccountKind::Checking
    );

    assert!(is_own_account("BoursoBank"));
    assert!(is_own_account("Boursorama Banque"));
    assert!(!is_own_account("Crédit Agricole"));
    assert!(!is_own_account("CIC"));

    let (accounts, third_party) = parse_dashboard(DASHBOARD_HTML).unwrap();
    assert_eq!(third_party, 2);
    assert_eq!(accounts.len(), 3);
    assert_eq!(accounts[0].kind, AccountKind::Checking);
    assert_eq!(accounts[0].balance_eur, d("20810.50"));
    assert_eq!(accounts[1].kind, AccountKind::Ldds);
    assert_eq!(accounts[2].kind, AccountKind::Pea);
    assert_eq!(accounts[2].balance_eur, d("143088.89"));

    // HTML entities decoded once.
    let escaped = DASHBOARD_HTML.replace(
        "LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE",
        "LIVRET D&#039;EPARGNE &amp; CO",
    );
    let (acc_esc, _) = parse_dashboard(&escaped).unwrap();
    assert_eq!(acc_esc[1].name, "LIVRET D'EPARGNE & CO");
    assert_eq!(
        clean_text("A &amp;lt;b&amp;gt; <i>x</i>", 200).as_deref(),
        Some("A &lt;b&gt; x")
    );

    // Insurance and children sections skipped cleanly.
    let with_extra = format!(
        "{}{}{}{}",
        DASHBOARD_HTML,
        panel(
            "data-summary-insurance",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "Assurance Vie **01",
            "45 210,30"
        ),
        panel(
            "data-summary-assurance",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "Bourso Protect",
            "150,00"
        ),
        panel(
            "data-summary-child",
            "dddddddddddddddddddddddddddddddd",
            "LIVRET A (Junior)",
            "2 476,41"
        ),
    );
    let (acc_extra, tp_extra) = parse_dashboard(&with_extra).unwrap();
    assert_eq!(acc_extra.len(), 3);
    assert_eq!(tp_extra, 2);

    // Unknown section marker fails completeness check.
    let unknown_sec = format!(
        "{}{}",
        DASHBOARD_HTML,
        panel(
            "data-summary-foobar",
            "cccccccccccccccccccccccccccccccc",
            "Produit Inconnu",
            "10,00"
        )
    );
    assert_eq!(
        parse_dashboard(&unknown_sec).unwrap_err().code,
        FORMAT_CHANGED
    );

    // Broken card fails.
    let broken = DASHBOARD_HTML.replacen("c-info-box__account-sub-label", "c-info-box__bank", 1);
    assert_eq!(parse_dashboard(&broken).unwrap_err().code, FORMAT_CHANGED);

    // No section fails.
    assert_eq!(
        parse_dashboard("<html><body>Maintenance</body></html>")
            .unwrap_err()
            .code,
        FORMAT_CHANGED
    );

    // Only third-party fails incomplete.
    let foreign = DASHBOARD_HTML.replace("BoursoBank", "Crédit Mutuel");
    assert_eq!(parse_dashboard(&foreign).unwrap_err().code, INCOMPLETE);
}

// ─── Trading Summary & Single Fund Tests ────────────────────────────────────

#[test]
fn trading_summary_single_fund_collisions_and_diagnostics() {
    let parsed = parse_trading_summary(
        &trading_summary(
            "100.00",
            "900.00",
            "1000.00",
            vec![trading_position(Some("1rTCW8"), "10", "900.00", "EUR")],
        ),
        "acc",
    )
    .unwrap();
    assert_eq!(parsed.cash_eur, d("100.00"));
    assert_eq!(parsed.total_eur, d("1000.00"));
    assert_eq!(parsed.positions.len(), 1);
    assert_eq!(parsed.positions[0].pnl_eur, Some(d("150.00")));

    // Tolerance checks.
    assert!(money_close(d("100.04"), d("100.00")));
    assert!(!money_close(d("100.20"), d("100.00")));
    assert!(money_close(d("100050"), d("100000")));
    assert!(!money_close(d("100200"), d("100000")));

    // Mismatched total & truncated lines -> INCOMPLETE.
    assert_eq!(
        parse_trading_summary(
            &trading_summary(
                "100.00",
                "900.00",
                "1500.00",
                vec![trading_position(Some("1rTCW8"), "10", "900.00", "EUR")]
            ),
            "acc"
        )
        .unwrap_err()
        .code,
        INCOMPLETE
    );
    assert_eq!(
        parse_trading_summary(
            &trading_summary(
                "100.00",
                "900.00",
                "1000.00",
                vec![trading_position(Some("1rTCW8"), "10", "400.00", "EUR")]
            ),
            "acc"
        )
        .unwrap_err()
        .code,
        INCOMPLETE
    );

    // Foreign value or gain currency -> INVALID_DATA.
    assert_eq!(
        parse_trading_summary(
            &trading_summary(
                "100.00",
                "900.00",
                "1000.00",
                vec![trading_position(Some("1rTCW8"), "10", "900.00", "USD")]
            ),
            "acc"
        )
        .unwrap_err()
        .code,
        INVALID_DATA
    );
    let mut usd_gain = trading_position(Some("1rTCW8"), "10", "900.00", "EUR");
    usd_gain["gainLoss"] = money("150.00", Some("USD"));
    assert_eq!(
        parse_trading_summary(
            &trading_summary("100.00", "900.00", "1000.00", vec![usd_gain]),
            "acc"
        )
        .unwrap_err()
        .code,
        INVALID_DATA
    );

    // Native USD quote keeps quoteCurrency and drops buyingPriceEur.
    let mut usd_quote = trading_position(Some("1rTCW8"), "10", "900.00", "EUR");
    usd_quote["last"] = money("90.00", Some("USD"));
    usd_quote["buyingPrice"] = money("75.00", Some("USD"));
    let parsed_usd = parse_trading_summary(
        &trading_summary("100.00", "900.00", "1000.00", vec![usd_quote]),
        "acc",
    )
    .unwrap();
    assert_eq!(
        parsed_usd.positions[0].quote_currency.as_deref(),
        Some("USD")
    );
    assert_eq!(parsed_usd.positions[0].buying_price_eur, None);

    // Unlisted line without symbol uses ISIN.
    let mut unlisted = trading_position(None, "10", "400.00", "EUR");
    unlisted["isin"] = json!("fr0013505062");
    let listed = trading_position(Some("1rTCW8"), "10", "500.00", "EUR");
    let parsed_unlisted = parse_trading_summary(
        &trading_summary("100.00", "900.00", "1000.00", vec![listed, unlisted]),
        "acc",
    )
    .unwrap();
    assert_eq!(parsed_unlisted.positions[1].symbol, "FR0013505062");

    // Multi-section union reconciles & deduplicates; conflicting duplicates fail.
    let mut split = trading_summary(
        "100.00",
        "900.00",
        "1000.00",
        vec![trading_position(Some("1rTA"), "6", "600.00", "EUR")],
    );
    let mut second_pos = trading_position(Some("1rTB"), "3", "300.00", "EUR");
    second_pos["isin"] = json!("FR0000000001");
    split
        .as_array_mut()
        .unwrap()
        .push(json!({ "id": "pos2", "positions": [second_pos] }));
    let parsed_split = parse_trading_summary(&split, "acc").unwrap();
    assert_eq!(parsed_split.positions.len(), 2);

    // Repeated partial section cannot hide missing holding.
    let mut dup_partial = trading_summary(
        "100.00",
        "900.00",
        "1000.00",
        vec![trading_position(Some("1rTCW8"), "5", "450.00", "EUR")],
    );
    dup_partial
        .as_array_mut()
        .unwrap()
        .push(json!({ "positions": [trading_position(Some("1rTCW8"), "5", "450.00", "EUR")] }));
    assert_eq!(
        parse_trading_summary(&dup_partial, "acc").unwrap_err().code,
        INCOMPLETE
    );

    // Conflicting duplicate -> INVALID_DATA.
    let mut conflict = trading_summary(
        "100.00",
        "950.00",
        "1050.00",
        vec![trading_position(Some("1rTCW8"), "5", "450.00", "EUR")],
    );
    conflict
        .as_array_mut()
        .unwrap()
        .push(json!({ "positions": [trading_position(Some("1rTCW8"), "6", "500.00", "EUR")] }));
    assert_eq!(
        parse_trading_summary(&conflict, "acc").unwrap_err().code,
        INVALID_DATA
    );

    // Single-fund contract.
    let sf = parse_trading_summary(&single_fund_summary("350.00", "3.5"), "acc").unwrap();
    assert_eq!(sf.cash_eur, Decimal::ZERO);
    assert_eq!(sf.total_eur, d("350.00"));
    assert_eq!(sf.positions[0].symbol, "FR0010315770");
    assert_eq!(sf.positions[0].current_value_eur, d("350.00"));

    // Emptied single-fund reconciles; mismatched single-fund fails.
    assert!(
        parse_trading_summary(&single_fund_summary("0", "0"), "acc")
            .unwrap()
            .positions
            .is_empty()
    );
    assert_eq!(
        parse_trading_summary(&single_fund_summary("350.00", "0"), "acc")
            .unwrap_err()
            .code,
        INCOMPLETE
    );
    assert_eq!(
        parse_trading_summary(&single_fund_summary("0", "3.5"), "acc")
            .unwrap_err()
            .code,
        INCOMPLETE
    );
    assert_eq!(
        parse_trading_summary(&single_fund_summary("-350", "3.5"), "acc")
            .unwrap_err()
            .code,
        INVALID_DATA
    );
    assert_eq!(
        parse_trading_summary(&single_fund_summary("350", "-1"), "acc")
            .unwrap_err()
            .code,
        INVALID_DATA
    );

    // Mixed single-fund + cash refused.
    let mut mixed = single_fund_summary("350.00", "3.5");
    mixed[0]["account"]["cash"] = money("10.00", Some("EUR"));
    assert_eq!(
        parse_trading_summary(&mixed, "acc").unwrap_err().code,
        FORMAT_CHANGED
    );

    // Symbol collisions.
    let mk_pos = |sym: &str, isin: Option<&str>| PositionPayload {
        isin: isin.map(str::to_owned),
        symbol: sym.to_owned(),
        label: "L".to_owned(),
        quantity: d("1"),
        buying_price_eur: None,
        current_price: None,
        quote_currency: Some("EUR".to_owned()),
        current_value_eur: d("10"),
        pnl_eur: None,
    };
    assert!(
        guard_symbol_collisions(&[
            mk_pos("1rTCW8", Some("IE00B4L5Y983")),
            mk_pos("1rTCW8", Some("IE00BJ0KDQ92"))
        ])
        .is_ok()
    );
    assert_eq!(
        guard_symbol_collisions(&[mk_pos("1rTCW8", None), mk_pos("1rtcw8", None)])
            .unwrap_err()
            .code,
        INVALID_DATA
    );

    // Describe payload never leaks values.
    let desc = describe_payload(
        &json!([{"id": "x", "account": {"name": "PEA DOE", "cash": {"value": 143088}}, "positions": null}]),
        0,
    );
    assert!(!desc.contains("143088"));
    assert!(!desc.contains("PEA DOE"));
    assert!(desc.contains("positions:null"));
}

// ─── Identity Selector Tests ────────────────────────────────────────────────

#[test]
fn identity_selector_picks_personal_and_rejects_ambiguous_or_malformed() {
    let personal_and_biz = identity_selector_html(&[
        identity_card(PERSONAL_TOKEN, "Camille Exemple", ""),
        identity_card(BUSINESS_TOKEN, "Camille Exemple", "Entrepreneur individuel"),
    ]);
    assert_eq!(
        choose_personal_identity(&personal_and_biz).unwrap(),
        format!("/connexion/changer-identite/{PERSONAL_TOKEN}")
    );

    for kind in [
        "EI",
        "Espace Pro",
        "Professionnel",
        "Professionnelle",
        "SASU",
        "Auto-entrepreneur",
        "Sociétés",
        "Associations",
        "Artisane",
        "Commerçants",
        "Indépendants",
        "Libéraux",
        "E.I.",
        "EIRL",
    ] {
        let html = identity_selector_html(&[
            identity_card(BUSINESS_TOKEN, "Camille Exemple", kind),
            identity_card(PERSONAL_TOKEN, "Camille Exemple", ""),
        ]);
        assert!(
            choose_personal_identity(&html)
                .unwrap()
                .ends_with(PERSONAL_TOKEN),
            "failed for {kind}"
        );
    }

    // Personal names containing substrings of business words are NOT marked business.
    for name in [
        "Dominique Liberali",
        "Camille Partisan",
        "Sasha Prost",
        "Eli Societano",
    ] {
        let html = identity_selector_html(&[
            identity_card(PERSONAL_TOKEN, name, ""),
            identity_card(BUSINESS_TOKEN, "Atelier Bois Flotté", ""),
        ]);
        let err = choose_personal_identity(&html).unwrap_err();
        assert_eq!(err.code, IDENTITY_UNSUPPORTED);
        assert!(!err.message.contains(PERSONAL_TOKEN));
        assert!(!err.message.contains(BUSINESS_TOKEN));
    }

    // Business-only, lone unmarked -> IDENTITY_SELECTION_UNSUPPORTED.
    assert_eq!(
        choose_personal_identity(&identity_selector_html(&[identity_card(
            BUSINESS_TOKEN,
            "SARL",
            ""
        )]))
        .unwrap_err()
        .code,
        IDENTITY_UNSUPPORTED
    );
    assert_eq!(
        choose_personal_identity(&identity_selector_html(&[identity_card(
            PERSONAL_TOKEN,
            "Camille",
            ""
        )]))
        .unwrap_err()
        .code,
        IDENTITY_UNSUPPORTED
    );

    // Path traversal or link outside anchor -> FORMAT_CHANGED.
    let traversal = identity_selector_html(&[
        identity_card("../../virement", "Camille", ""),
        identity_card(BUSINESS_TOKEN, "Camille", "EI"),
    ]);
    assert_eq!(
        choose_personal_identity(&traversal).unwrap_err().code,
        FORMAT_CHANGED
    );
}

// ─── Assurance Vie & PER Parser Tests ───────────────────────────────────────

#[test]
fn life_insurance_parser_handles_supports_euro_fund_tickers_and_validation() {
    let (dash, _) = parse_dashboard(&dashboard_with_life_insurance()).unwrap();
    let av = dash.iter().find(|a| a.id == LIFE_INSURANCE_ID).unwrap();
    assert_eq!(av.kind, AccountKind::AssuranceVie);
    assert_eq!(av.balance_eur, d("3300.00"));

    let contract = parse_life_insurance(&li_page(&default_li_rows(), "3 300,00 €")).unwrap();
    assert_eq!(contract.total_eur, d("3300.00"));
    assert_eq!(contract.cash_eur, d("1100.00"));
    assert_eq!(contract.valuation_line_count, 3);
    assert_eq!(contract.positions.len(), 2);
    assert_eq!(contract.positions[0].symbol, "1rASUSW");
    assert_eq!(contract.positions[1].pnl_eur, Some(d("-40.00")));

    // Quote link with query string / fragment keeps primary symbol.
    for suffix in [
        "/?returnUrl=/cours/MSFT/",
        "/#/cours/MSFT/",
        "&#47;?returnUrl=&#47;cours&#47;MSFT&#47;",
    ] {
        let rows = li_row(
            "Euro Stoxx 50",
            None,
            Some(&format!("1rTXYZ{suffix}")),
            "10,0000",
            "300,00 €",
            "330,00 €",
            "3 300,00 €",
            "300,00 €",
        );
        let parsed = parse_life_insurance(&li_page(&rows, "3 300,00 €")).unwrap();
        assert_eq!(parsed.positions[0].symbol, "1rTXYZ");
    }

    // Long labels differing only after 200 chars still get distinct <= 30 UTF-16 tickers.
    let prefix = "Support en unités de compte ".repeat(8);
    let rows = format!(
        "{}{}",
        li_row(
            &format!("{prefix}première variante"),
            None,
            None,
            "10,0000",
            "50,00 €",
            "60,00 €",
            "600,00 €",
            "100,00 €"
        ),
        li_row(
            &format!("{prefix}seconde variante"),
            None,
            None,
            "10,0000",
            "50,00 €",
            "60,00 €",
            "600,00 €",
            "100,00 €"
        ),
    );
    let parsed_long = parse_life_insurance(&li_page(&rows, "1 200,00 €")).unwrap();
    assert_ne!(
        parsed_long.positions[0].symbol,
        parsed_long.positions[1].symbol
    );
    assert!(parsed_long.positions[0].symbol.encode_utf16().count() <= 30);
    assert!(guard_symbol_collisions(&parsed_long.positions).is_ok());

    // Emoji UTF-16 limit check (16 emojis = 32 UTF-16 units).
    let emoji_label = "\u{1F4C8}".repeat(16);
    let emoji_rows = li_row(
        &emoji_label,
        None,
        None,
        "10,0000",
        "50,00 €",
        "60,00 €",
        "600,00 €",
        "100,00 €",
    );
    let parsed_emoji = parse_life_insurance(&li_page(&emoji_rows, "600,00 €")).unwrap();
    assert!(parsed_emoji.positions[0].symbol.encode_utf16().count() <= 30);
    assert_ne!(parsed_emoji.positions[0].symbol, emoji_label);

    // Missing support on large contract is NOT hidden by relative tolerance.
    let big_rows = format!(
        "{}{}",
        li_row(
            "ETF World",
            Some("IE00BYX2JD69"),
            Some("1rASUSW"),
            "100,0000",
            "10,00 €",
            "12,00 €",
            "49 910,00 €",
            "200,00 €"
        ),
        li_row(
            "Fonds Euro Nouvelle Génération",
            None,
            None,
            "",
            "",
            "",
            "50 000,00 €",
            ""
        ),
    );
    assert_eq!(
        parse_life_insurance(&li_page(&big_rows, "100 000,00 €"))
            .unwrap_err()
            .code,
        INCOMPLETE
    );

    // Unitless non-euro ("Fonds Europe Actions") refused.
    let europe_rows = li_row(
        "Fonds Europe Actions",
        None,
        None,
        "",
        "",
        "",
        "3 300,00 €",
        "",
    );
    assert_eq!(
        parse_life_insurance(&li_page(&europe_rows, "3 300,00 €"))
            .unwrap_err()
            .code,
        FORMAT_CHANGED
    );
}

#[test]
fn per_parser_merges_compartments_reconciles_and_keeps_shape_only_diagnostics() {
    let plan = parse_per(&default_per_html()).unwrap();
    assert_eq!(plan.total_eur, d("2230.00"));
    assert_eq!(plan.cash_eur, d("30.00"));
    assert_eq!(plan.valuation_line_count, 4);
    assert_eq!(plan.positions.len(), 2);
    let world = plan
        .positions
        .iter()
        .find(|p| p.isin.as_deref() == Some("IE00BYX2JD69"))
        .unwrap();
    assert_eq!(world.quantity, d("100.0000"));
    assert_eq!(world.current_value_eur, d("1200.00"));
    assert_eq!(world.buying_price_eur, Some(d("9")));
    assert_eq!(world.pnl_eur, Some(d("300.00")));

    // Weighted cost price rounded HALF_UP to 8 decimal places.
    let rows = format!(
        "{}{}",
        per_row(
            "World",
            Some("IE00BYX2JD69"),
            Some("1rASUSW"),
            "1,0000",
            "10,00 €",
            "12,00 €",
            "2,00 €",
            "20,00 %",
            "12,00 €"
        ),
        per_row(
            "World",
            Some("IE00BYX2JD69"),
            Some("1rASUSW"),
            "2,0000",
            "11,00 €",
            "12,00 €",
            "2,00 €",
            "9,09 %",
            "24,00 €"
        ),
    );
    let merged = parse_per(&per_page(
        &[per_compartment("A", &rows, Some("36,00 €"))],
        "36,00 €",
    ))
    .unwrap();
    assert_eq!(merged.positions[0].buying_price_eur, Some(d("10.66666667")));

    // Cumulative small gaps across compartments caught by whole-plan lines_close check.
    let small_gap_comps: Vec<String> = ["A", "B", "C"]
        .iter()
        .map(|name| {
            per_compartment(
                name,
                &per_row(
                    "World",
                    Some("IE00BYX2JD69"),
                    Some("1rASUSW"),
                    "1,0000",
                    "10,00 €",
                    "100,00 €",
                    "90,00 €",
                    "900,00 %",
                    "100,00 €",
                ),
                Some("100,05 €"),
            )
        })
        .collect();
    let err = parse_per(&per_page(&small_gap_comps, "300,20 €")).unwrap_err();
    assert_eq!(err.code, INCOMPLETE);
    assert!(
        err.message
            .contains("3 compartment(s), 3 line(s), 0 pending")
    );
    assert!(!err.message.contains('€'));

    // Negative units refused as INVALID_DATA.
    let neg_rows = per_row(
        "World",
        Some("IE00BYX2JD69"),
        Some("1rASUSW"),
        "-1,0000",
        "10,00 €",
        "12,00 €",
        "2,00 €",
        "20,00 %",
        "12,00 €",
    );
    assert_eq!(
        parse_per(&per_page(
            &[per_compartment("A", &neg_rows, Some("12,00 €"))],
            "12,00 €"
        ))
        .unwrap_err()
        .code,
        INVALID_DATA
    );

    // Two ISIN-less lines sharing symbol -> INVALID_DATA.
    let no_isin_rows = format!(
        "{}{}",
        per_row(
            "Fonds Maison",
            None,
            None,
            "1,0000",
            "10,00 €",
            "12,00 €",
            "2,00 €",
            "20,00 %",
            "12,00 €"
        ),
        per_row(
            "Fonds Maison",
            None,
            None,
            "2,0000",
            "11,00 €",
            "12,00 €",
            "2,00 €",
            "9,09 %",
            "24,00 €"
        ),
    );
    assert_eq!(
        parse_per(&per_page(
            &[per_compartment("A", &no_isin_rows, Some("36,00 €"))],
            "36,00 €"
        ))
        .unwrap_err()
        .code,
        INVALID_DATA
    );
}

// ─── Operations & RIB & Page Extractors Tests ───────────────────────────────

#[test]
fn operations_rib_and_page_extractors() {
    assert_eq!(
        parse_iban_page(RIB_HTML).as_deref(),
        Some("FR7630006000011234567890189")
    );
    assert_eq!(parse_iban_page("<html>no rib</html>"), None);

    let html = r#"<ul class="list__movement">
      <li class="list__movement__range-summary" data-operations-next-pagination="token-2"></li>
      <li class="date-line">12 octobre 2026</li>
      <li class="list__movement__line--deffered" data-id="future"><div class="list-operation-item__amount">1,00 €</div><div class="list-operation-item__label-name">VIR A VENIR</div></li>
      <li data-id="split"><div class="list__movement__line--block__split"></div><div class="list-operation-item__amount">2,00 €</div><div class="list-operation-item__label-name">SPLIT</div></li>
      <li data-id="op-1"><div class="list-operation-item__amount">− 24,90 €</div><div class="list-operation-item__label-name">PAIEMENT CARTE BOULANGERIE</div></li>
      <li class="date-line">11 octobre 2026</li>
      <li data-custom-id="op-2"><div class="list-operation-item__amount">1 250,00 €</div><div class="list-operation-item__label-name">VIR SALAIRE</div></li>
      <li class="date-line">30 juin 2026</li>
      <li data-id="old"><div class="list-operation-item__amount">1,00 €</div><div class="list-operation-item__label-name">OLD</div></li>
    </ul>"#;
    let from = NaiveDate::from_ymd_opt(2026, 10, 1).unwrap();
    let to = NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
    let (txs, next) = parse_operations(html, from, to).unwrap();
    assert_eq!(next.as_deref(), Some("token-2"));
    assert_eq!(txs.len(), 2);
    assert_eq!(txs[0].external_id, "op-1");
    assert_eq!(txs[0].amount, d("-24.90"));
    assert_eq!(txs[0].kind, "CARD");
    assert_eq!(txs[1].external_id, "op-2");
    assert_eq!(txs[1].kind, "OTHER");

    // Missing container -> Format(FORMAT_CHANGED); explicit empty container ok.
    assert!(matches!(
        parse_operations("<html>no widget</html>", from, to),
        Err(OperationsParseError::Format(ref e)) if e.code == FORMAT_CHANGED
    ));
    assert_eq!(
        parse_operations(r#"<ul class="list__movement"></ul>"#, from, to).unwrap(),
        (vec![], None)
    );

    // Form token both attribute orders + BRS_CONFIG + strong auth payload + redirect helpers.
    assert_eq!(
        extract_form_token(r#"<input type="hidden" name="form[_token]" value="abc.def">"#).unwrap(),
        "abc.def"
    );
    assert_eq!(
        extract_form_token(r#"<input value="abc.def" name="form[_token]">"#).unwrap(),
        "abc.def"
    );
    assert!(extract_form_token("<html></html>").is_err());

    let (api_url, user_hash) =
        extract_brs_config(&home_html("https://api.boursobank.com/services/api/v1.7")).unwrap();
    assert_eq!(api_url, "https://api.boursobank.com/services/api/v1.7");
    assert_eq!(user_hash, USER_HASH);

    let challenge_json = json!({
        "challenges": [{"parameters": {"formScreen": {"actions": {"check": {"api": {"params": {"resourceId": "otp-42", "formState": "state-xyz"}}}}}}}]
    })
    .to_string()
    .replace('"', "&quot;");
    assert_eq!(
        strong_auth_params(&format!(
            r#"<div data-strong-authentication-payload="{challenge_json}"></div>"#
        ))
        .unwrap(),
        ("otp-42".to_owned(), "state-xyz".to_owned())
    );
    assert!(json_success_body(r#"{"success":true}"#));
    assert!(!json_success_body(r#"{"success":false}"#));
    assert!(!json_success_body("<html>"));

    assert!(is_fraud_education_page(
        r#"<a href="/infos-profil/pedagogie-fraude/abc/1">OK</a>"#
    ));
    assert!(!is_fraud_education_page(&home_html(
        "https://api.boursobank.com"
    )));

    let base = "https://clients.boursobank.com";
    assert!(redirects_to_identity_selector(
        StatusCode::FOUND,
        Some("/connexion/lister-identites"),
        base
    ));
    assert!(!redirects_to_identity_selector(
        StatusCode::FOUND,
        Some("https://evil.example/connexion/lister-identites"),
        base
    ));
    assert!(redirects_to_profile_notice(
        StatusCode::FOUND,
        Some("/infos-profil/"),
        base
    ));
    assert!(!redirects_to_profile_notice(
        StatusCode::FOUND,
        Some("https://evil.example/infos-profil/"),
        base
    ));
    assert_eq!(
        notice_dismiss_link(
            "<a href='/ignorer-bloquante/x?a=1&amp;b=2'>Plus tard</a>",
            base
        )
        .as_deref(),
        Some("/ignorer-bloquante/x?a=1&b=2")
    );

    // Cookie round-trip preserving separate domains.
    let jar = SessionCookieJar::new();
    jar.set("brsxds_x", "jwt", ".boursobank.com", "/");
    jar.set("navSessionId", "nav", "clients.boursobank.com", "/");
    let raw = jar.serialize_json();
    let restored = SessionCookieJar::new();
    restored.restore_json(&raw).unwrap();
    let snap = restored.snapshot();
    assert_eq!(
        snap.iter().find(|c| c.name == "brsxds_x").unwrap().domain,
        ".boursobank.com"
    );
    assert_eq!(
        snap.iter()
            .find(|c| c.name == "navSessionId")
            .unwrap()
            .domain,
        "clients.boursobank.com"
    );
    for broken in [
        "not-json",
        "[]",
        "{}",
        r#"{"cookies":[]}"#,
        r#"{"cookies":[1]}"#,
    ] {
        assert_eq!(
            restored.restore_json(broken).unwrap_err().detail,
            "INVALID_DATA"
        );
    }
}

// ─── Wiremock End-to-End Flow Tests ─────────────────────────────────────────

async fn mount_standard_accounts(server: &MockServer, dashboard: &str, trading: Value) {
    let api_base = format!("{}/services/api/v1.7", server.uri());
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api_base)))
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(ResponseTemplate::new(200).set_body_string(dashboard.to_owned()))
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/trading/accounts/summary/{PEA_ID}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(trading))
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/rib$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(RIB_HTML))
        .mount(server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/mouvements$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(MOVEMENTS_HTML))
        .mount(server)
        .await;
}

fn bourso_for_server(server: &MockServer) -> NativeBourso {
    let mut cfg = BoursoConfig::with_base_url(server.uri());
    cfg.today_override = Some(NaiveDate::from_ymd_opt(2026, 10, 15).unwrap());
    cfg.app_validation_poll = Duration::from_millis(20);
    cfg.app_validation_timeout = Duration::from_millis(200);
    NativeBourso::with_config(cfg)
}

#[tokio::test]
async fn health_contract_errors_and_pending_lifecycle() {
    let server = MockServer::start().await;
    let bourso = bourso_for_server(&server);

    assert_eq!(
        bourso.call(Method::GET, "/health", None).await.unwrap(),
        json!({ "status": "ok" })
    );
    assert_eq!(
        bourso
            .call(Method::GET, "/initiate", None)
            .await
            .unwrap_err(),
        (
            StatusCode::METHOD_NOT_ALLOWED,
            "METHOD_NOT_ALLOWED".to_owned()
        )
    );
    assert_eq!(
        bourso
            .call(Method::POST, "/unknown", None)
            .await
            .unwrap_err(),
        (StatusCode::NOT_FOUND, "NOT_FOUND".to_owned())
    );

    // Extra field refused with 400 INVALID_DATA.
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "customerId": "1", "password": "2", "website": "tc" })),
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_DATA".to_owned())
    );

    // Unknown attempt -> 410 AUTH_ATTEMPT_EXPIRED.
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": "gone" }))
            )
            .await
            .unwrap_err(),
        (StatusCode::GONE, "AUTH_ATTEMPT_EXPIRED".to_owned())
    );

    // Submitted OTP code -> 400 MFA_TYPE_UNSUPPORTED.
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": "p", "code": "123456" })),
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "MFA_TYPE_UNSUPPORTED".to_owned())
    );

    // Malformed sessionState -> 400 INVALID_DATA.
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/accounts",
                Some(json!({ "sessionState": "not-json" }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_REQUEST, "INVALID_DATA".to_owned())
    );
}

#[tokio::test]
async fn initiate_without_mfa_and_with_app_push_mfa_end_to_end() {
    let server = MockServer::start().await;
    let api_base = format!("{}/services/api/v1.7", server.uri());

    // 1. Direct login without MFA.
    let login_hits = Arc::new(AtomicUsize::new(0));
    let login_hits_c = login_hits.clone();
    Mock::given(method("GET"))
        .and(path("/connexion/"))
        .respond_with(move |_req: &Request| {
            let n = login_hits_c.fetch_add(1, Ordering::SeqCst);
            if n == 0 {
                ResponseTemplate::new(200).set_body_string(
                    r#"<script>document.cookie="__brs_mit=mit-token-123";</script>"#,
                )
            } else {
                ResponseTemplate::new(200).set_body_string(
                    r#"<form><input name="form[_token]" value="csrf-tok-1"></form>"#,
                )
            }
        })
        .mount(&server)
        .await;

    let pad_body = format!(
        r#"{}<script>jQuery("[data-matrix-random-challenge]").val("chal-42")</script>"#,
        pad_html(&SHUFFLED, &PAD_KEYS, &default_svgs())
    );
    Mock::given(method("GET"))
        .and(path("/connexion/clavier-virtuel"))
        .respond_with(ResponseTemplate::new(200).set_body_string(pad_body))
        .mount(&server)
        .await;

    Mock::given(method("POST"))
        .and(path("/connexion/saisie-mot-de-passe"))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("Location", "/")
                .insert_header("Set-Cookie", "brsxds_x=session-jwt; Path=/"),
        )
        .mount(&server)
        .await;

    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api_base)))
        .mount(&server)
        .await;

    let bourso = bourso_for_server(&server);
    let init = bourso
        .call(
            Method::POST,
            "/initiate",
            Some(json!({ "customerId": "12345678", "password": "1234" })),
        )
        .await
        .unwrap();
    assert_eq!(init["mfaRequired"], false);
    assert!(init["processId"].is_null());
    let state = init["sessionState"].as_str().unwrap();
    assert!(state.contains("brsxds_x"));
    assert!(state.contains("__brs_mit"));

    // 2. Login requiring APP_PUSH MFA + poll + complete.
    let mfa_server = MockServer::start().await;
    let mfa_api = format!("{}/services/api/v1.7", mfa_server.uri());
    let home_step = Arc::new(AtomicUsize::new(0));
    let home_step_c = home_step.clone();

    Mock::given(method("GET"))
        .and(path("/connexion/"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("Set-Cookie", "__brs_mit=mit-mfa; Path=/")
                .set_body_string(r#"<form><input name="form[_token]" value="csrf-mfa"></form>"#),
        )
        .mount(&mfa_server)
        .await;
    let pad_body2 = format!(
        r#"{}<script>jQuery("[data-matrix-random-challenge]").val("chal-mfa")</script>"#,
        pad_html(&SHUFFLED, &PAD_KEYS, &default_svgs())
    );
    Mock::given(method("GET"))
        .and(path("/connexion/clavier-virtuel"))
        .respond_with(ResponseTemplate::new(200).set_body_string(pad_body2))
        .mount(&mfa_server)
        .await;
    Mock::given(method("POST"))
        .and(path("/connexion/saisie-mot-de-passe"))
        .respond_with(ResponseTemplate::new(302).insert_header("Location", "/securisation"))
        .mount(&mfa_server)
        .await;
    let mfa_api_c = mfa_api.clone();
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(move |_req: &Request| {
            if home_step_c.fetch_add(1, Ordering::SeqCst) == 0 {
                ResponseTemplate::new(200)
                    .set_body_string(r#"<html><a href="/securisation">Valider</a></html>"#)
            } else {
                ResponseTemplate::new(200).set_body_string(home_html(&mfa_api_c))
            }
        })
        .mount(&mfa_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/securisation"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>sec</html>"))
        .mount(&mfa_server)
        .await;
    let challenge_attr = json!({
        "challenges": [{"parameters": {"formScreen": {"actions": {"check": {"api": {"params": {"resourceId": "res-7", "formState": "fs-9"}}}}}}}]
    })
    .to_string()
    .replace('"', "&quot;");
    let validation_html = format!(
        r#"<html><script>window.BRS_CONFIG = {{"API_URL": "{}", "USER_HASH": "{USER_HASH}"}};</script>
        <div class="brs-otp-webtoapp" data-strong-authentication-payload="{challenge_attr}"></div>
        <input name="form[_token]" value="val-tok-1"></html>"#,
        mfa_api.replace('/', r"\/")
    );
    Mock::given(method("GET"))
        .and(path("/securisation/validation"))
        .respond_with(ResponseTemplate::new(200).set_body_string(validation_html))
        .mount(&mfa_server)
        .await;
    Mock::given(method("POST"))
        .and(path(format!(
            "/services/api/v1.7/fr-FR/_user_/_{USER_HASH}_/session/challenge/startwebtoapp/res-7"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "success": true })))
        .mount(&mfa_server)
        .await;

    let polls = Arc::new(AtomicUsize::new(0));
    let polls_c = polls.clone();
    Mock::given(method("POST"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/session/challenge/checkwebtoapp/res-7"
        )))
        .respond_with(move |_req: &Request| {
            let n = polls_c.fetch_add(1, Ordering::SeqCst);
            ResponseTemplate::new(200).set_body_json(json!({ "success": n >= 1 }))
        })
        .mount(&mfa_server)
        .await;
    Mock::given(method("POST"))
        .and(path("/securisation/validation"))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("Set-Cookie", "brsxds_x=mfa-jwt; Path=/")
                .insert_header("Location", "/"),
        )
        .mount(&mfa_server)
        .await;

    let bourso_mfa = bourso_for_server(&mfa_server);
    let mfa_init = bourso_mfa
        .call(
            Method::POST,
            "/initiate",
            Some(json!({ "customerId": "12345678", "password": "5678" })),
        )
        .await
        .unwrap();
    assert_eq!(mfa_init["mfaRequired"], true);
    assert_eq!(mfa_init["mfaType"], "APP_PUSH");
    let pid = mfa_init["processId"].as_str().unwrap().to_owned();
    assert_eq!(bourso_mfa.pending_len().await, 1);

    let completed = bourso_mfa
        .call(
            Method::POST,
            "/complete",
            Some(json!({ "processId": pid, "code": null })),
        )
        .await
        .unwrap();
    assert!(
        completed["sessionState"]
            .as_str()
            .unwrap()
            .contains("mfa-jwt")
    );
    assert_eq!(bourso_mfa.pending_len().await, 0);
}

#[tokio::test]
async fn initiate_rejects_bad_credentials_fraud_ack_and_unsupported_mfa() {
    // Fraud education landing -> 401 FRAUD_ACK_REQUIRED.
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/connexion/"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("Set-Cookie", "__brs_mit=tok; Path=/")
                .set_body_string(r#"<input name="form[_token]" value="t">"#),
        )
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/connexion/clavier-virtuel"))
        .respond_with(ResponseTemplate::new(200).set_body_string(format!(
            r#"{}<div data-matrix-random-challenge="c"></div>"#,
            pad_html(&SHUFFLED, &PAD_KEYS, &default_svgs())
        )))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/connexion/saisie-mot-de-passe"))
        .respond_with(ResponseTemplate::new(302).insert_header("Location", "/"))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_string(r#"<a href="/infos-profil/pedagogie-fraude/abc/1">Notice</a>"#),
        )
        .mount(&server)
        .await;

    let bourso = bourso_for_server(&server);
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "customerId": "1", "password": "0" }))
            )
            .await
            .unwrap_err(),
        (StatusCode::UNAUTHORIZED, "FRAUD_ACK_REQUIRED".to_owned())
    );
}

#[tokio::test]
async fn collect_accounts_full_flow_av_per_identity_notice_and_history_edges() {
    // 1. Standard dashboard: checking + LDDS + PEA with RIB, movements, and trading board.
    let server = MockServer::start().await;
    mount_standard_accounts(&server, DASHBOARD_HTML, default_trading_summary()).await;
    let bourso = bourso_for_server(&server);
    let state = valid_session_state("127.0.0.1");

    let accounts = bourso
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    let list = accounts.as_array().unwrap();
    assert_eq!(list.len(), 3);

    let checking = list.iter().find(|a| a["type"] == "CHECKING").unwrap();
    assert_eq!(checking["externalId"], format!("bourso_{CHECKING_ID}"));
    assert_eq!(checking["iban"], "FR7630006000011234567890189");
    assert_eq!(checking["transactions"].as_array().unwrap().len(), 2);
    assert!(checking["transactionWarning"].is_null());

    let ldds = list.iter().find(|a| a["type"] == "LDDS").unwrap();
    assert_eq!(ldds["externalId"], format!("bourso_{LDDS_ID}"));
    assert_eq!(ldds["transactions"].as_array().unwrap().len(), 2);

    let pea = list.iter().find(|a| a["type"] == "PEA").unwrap();
    assert_eq!(pea["externalId"], format!("bourso_{PEA_ID}"));
    assert_eq!(pea["positions"].as_array().unwrap().len(), 1);
    assert_eq!(pea["snapshotComplete"], true);

    // 2. Assurance vie happy path.
    let av_server = MockServer::start().await;
    mount_standard_accounts(
        &av_server,
        &dashboard_with_life_insurance(),
        default_trading_summary(),
    )
    .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/compte/assurance-vie/{LIFE_INSURANCE_ID}/positions"
        )))
        .respond_with(
            ResponseTemplate::new(200).set_body_string(li_page(&default_li_rows(), "3 300,00 €")),
        )
        .mount(&av_server)
        .await;
    let av_res = bourso_for_server(&av_server)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    let av = av_res
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["type"] == "ASSURANCE_VIE")
        .unwrap();
    assert_eq!(av["valuationLineCount"], 3);
    assert_eq!(av["positions"].as_array().unwrap().len(), 2);

    // 3. PER happy path + unreadable PER skipped without breaking other accounts.
    let per_server = MockServer::start().await;
    mount_standard_accounts(
        &per_server,
        &dashboard_with_per(),
        default_trading_summary(),
    )
    .await;
    Mock::given(method("GET"))
        .and(path(format!("/compte/per/{PER_ID}/positions")))
        .respond_with(ResponseTemplate::new(200).set_body_string(default_per_html()))
        .mount(&per_server)
        .await;
    let per_res = bourso_for_server(&per_server)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    let per = per_res
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["type"] == "PER")
        .unwrap();
    assert_eq!(per["valuationLineCount"], 4);
    assert_eq!(per["positions"].as_array().unwrap().len(), 2);

    let bad_per_server = MockServer::start().await;
    mount_standard_accounts(
        &bad_per_server,
        &dashboard_with_per(),
        default_trading_summary(),
    )
    .await;
    Mock::given(method("GET"))
        .and(path(format!("/compte/per/{PER_ID}/positions")))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_string(default_per_html().replace("2 230,00 €", "9 999,99 €")),
        )
        .mount(&bad_per_server)
        .await;
    let skipped = bourso_for_server(&bad_per_server)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    assert!(
        !skipped
            .as_array()
            .unwrap()
            .iter()
            .any(|a| a["type"] == "PER")
    );
    assert_eq!(skipped.as_array().unwrap().len(), 3);

    // 4. PER outage (503) fails whole sync; PER 401 expires session.
    let per_503_server = MockServer::start().await;
    mount_standard_accounts(
        &per_503_server,
        &dashboard_with_per(),
        default_trading_summary(),
    )
    .await;
    Mock::given(method("GET"))
        .and(path(format!("/compte/per/{PER_ID}/positions")))
        .respond_with(ResponseTemplate::new(503))
        .mount(&per_503_server)
        .await;
    assert_eq!(
        bourso_for_server(&per_503_server)
            .call(
                Method::POST,
                "/accounts",
                Some(json!({ "sessionState": state }))
            )
            .await
            .unwrap_err(),
        (StatusCode::BAD_GATEWAY, "UPSTREAM_UNAVAILABLE".to_owned())
    );
}

#[tokio::test]
async fn identity_selector_and_profile_notice_flows() {
    let state = valid_session_state("127.0.0.1");

    // 1. Identity selector switches once to personal identity.
    let id_server = MockServer::start().await;
    let api_base = format!("{}/services/api/v1.7", id_server.uri());
    let switched = Arc::new(AtomicUsize::new(0));
    let switched_dash = switched.clone();
    let switched_cb = switched.clone();

    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api_base)))
        .mount(&id_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(move |_req: &Request| {
            if switched_dash.load(Ordering::SeqCst) == 0 {
                ResponseTemplate::new(302).insert_header("Location", "/connexion/lister-identites")
            } else {
                ResponseTemplate::new(200).set_body_string(DASHBOARD_HTML)
            }
        })
        .mount(&id_server)
        .await;
    let selector = identity_selector_html(&[
        identity_card(PERSONAL_TOKEN, "Camille Exemple", ""),
        identity_card(BUSINESS_TOKEN, "Camille Exemple", "Entrepreneur individuel"),
    ]);
    Mock::given(method("GET"))
        .and(path("/connexion/lister-identites"))
        .respond_with(ResponseTemplate::new(200).set_body_string(selector))
        .mount(&id_server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/connexion/changer-identite/{PERSONAL_TOKEN}"
        )))
        .respond_with(move |_req: &Request| {
            switched_cb.fetch_add(1, Ordering::SeqCst);
            ResponseTemplate::new(302).insert_header("Location", "/")
        })
        .mount(&id_server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/trading/accounts/summary/{PEA_ID}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(default_trading_summary()))
        .mount(&id_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/rib$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(RIB_HTML))
        .mount(&id_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/mouvements$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(MOVEMENTS_HTML))
        .mount(&id_server)
        .await;

    let res = bourso_for_server(&id_server)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    assert_eq!(res.as_array().unwrap().len(), 3);
    assert_eq!(switched.load(Ordering::SeqCst), 1);

    // 2. Profile notice postponed once via /ignorer-bloquante/...
    let notice_server = MockServer::start().await;
    let api_base2 = format!("{}/services/api/v1.7", notice_server.uri());
    let dismissed = Arc::new(AtomicUsize::new(0));
    let dismissed_dash = dismissed.clone();
    let dismissed_cb = dismissed.clone();

    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api_base2)))
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(move |_req: &Request| {
            if dismissed_dash.load(Ordering::SeqCst) == 0 {
                ResponseTemplate::new(302).insert_header("Location", "/infos-profil/")
            } else {
                ResponseTemplate::new(200).set_body_string(DASHBOARD_HTML)
            }
        })
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/infos-profil/"))
        .respond_with(
            ResponseTemplate::new(302)
                .insert_header("Location", "/infos-profil/renouvellement-carte/1"),
        )
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/infos-profil/renouvellement-carte/1"))
        .respond_with(ResponseTemplate::new(200).set_body_string(
            r#"<html><a href="/ignorer-bloquante/renouvellement-carte">Voir plus tard</a></html>"#,
        ))
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/ignorer-bloquante/renouvellement-carte"))
        .respond_with(move |_req: &Request| {
            dismissed_cb.fetch_add(1, Ordering::SeqCst);
            ResponseTemplate::new(302).insert_header("Location", "/")
        })
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/trading/accounts/summary/{PEA_ID}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(default_trading_summary()))
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/rib$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(RIB_HTML))
        .mount(&notice_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/mouvements$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(MOVEMENTS_HTML))
        .mount(&notice_server)
        .await;

    let res2 = bourso_for_server(&notice_server)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    assert_eq!(res2.as_array().unwrap().len(), 3);
    assert_eq!(dismissed.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn history_deadline_pagination_cap_and_session_expiry() {
    let state = valid_session_state("127.0.0.1");

    // 1. Slow movements hit deadline without losing balances or PEA holdings.
    let slow_server = MockServer::start().await;
    let api_base = format!("{}/services/api/v1.7", slow_server.uri());
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api_base)))
        .mount(&slow_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(ResponseTemplate::new(200).set_body_string(DASHBOARD_HTML))
        .mount(&slow_server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/trading/accounts/summary/{PEA_ID}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(default_trading_summary()))
        .mount(&slow_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/rib$"))
        .respond_with(ResponseTemplate::new(200).set_body_string(RIB_HTML))
        .mount(&slow_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/mouvements$"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_delay(Duration::from_secs(2))
                .set_body_string(MOVEMENTS_HTML),
        )
        .mount(&slow_server)
        .await;

    let mut cfg = BoursoConfig::with_base_url(slow_server.uri());
    cfg.accounts_history_deadline = Duration::from_millis(150);
    cfg.today_override = Some(NaiveDate::from_ymd_opt(2026, 10, 15).unwrap());
    let bourso = NativeBourso::with_config(cfg);

    let res = bourso
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    let list = res.as_array().unwrap();
    let checking = list.iter().find(|a| a["type"] == "CHECKING").unwrap();
    assert_eq!(checking["transactionWarning"], "TRANSACTIONS_UNAVAILABLE");
    assert_eq!(checking["iban"], "FR7630006000011234567890189");
    let pea = list.iter().find(|a| a["type"] == "PEA").unwrap();
    assert_eq!(pea["positions"].as_array().unwrap().len(), 1);
    assert!(pea["transactionWarning"].is_null());

    // 2. Repeated continuation token stops after 2 requests with TRANSACTIONS_INCOMPLETE;
    //    page cap stops at MAX_MOVEMENT_PAGES.
    let loop_server = MockServer::start().await;
    let loop_hits = Arc::new(AtomicUsize::new(0));
    let loop_hits_c = loop_hits.clone();
    Mock::given(method("GET"))
        .and(path_regex(r"/mouvements$"))
        .respond_with(move |_req: &Request| {
            loop_hits_c.fetch_add(1, Ordering::SeqCst);
            ResponseTemplate::new(200).set_body_string(
                r#"<ul class="list__movement"><li data-operations-next-pagination="loop"></li></ul>"#,
            )
        })
        .mount(&loop_server)
        .await;
    let bourso_loop = bourso_for_server(&loop_server);
    let (_, truncated) = bourso_loop
        .fetch_operations_for_href(
            &state,
            &format!("/compte/cav/{CHECKING_ID}/"),
            Some(NaiveDate::from_ymd_opt(2026, 10, 6).unwrap()),
        )
        .await
        .unwrap();
    assert!(truncated);
    assert_eq!(loop_hits.load(Ordering::SeqCst), 2);

    // Distinct tokens hit MAX_MOVEMENT_PAGES cap.
    struct CapResponder(AtomicUsize);
    impl Respond for CapResponder {
        fn respond(&self, _req: &Request) -> ResponseTemplate {
            let n = self.0.fetch_add(1, Ordering::SeqCst) + 1;
            ResponseTemplate::new(200).set_body_string(format!(
                r#"<ul class="list__movement"><li data-operations-next-pagination="tok-{n}"></li></ul>"#
            ))
        }
    }
    let cap_server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path_regex(r"/mouvements$"))
        .respond_with(CapResponder(AtomicUsize::new(0)))
        .expect(MAX_MOVEMENT_PAGES as u64)
        .mount(&cap_server)
        .await;
    let bourso_cap = bourso_for_server(&cap_server);
    let (_, cap_trunc) = bourso_cap
        .fetch_operations_for_href(
            &state,
            &format!("/compte/cav/{CHECKING_ID}/"),
            Some(NaiveDate::from_ymd_opt(2026, 10, 6).unwrap()),
        )
        .await
        .unwrap();
    assert!(cap_trunc);
    // Foreign href refused.
    assert!(
        bourso_cap
            .fetch_operations_for_href(&state, "https://evil.example/compte/mouvements", None)
            .await
            .is_err()
    );

    // 3. Logged-out home -> 401 SESSION_EXPIRED; trading 401 -> 401 SESSION_EXPIRED.
    let expired_server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>Connexion</html>"))
        .mount(&expired_server)
        .await;
    assert_eq!(
        bourso_for_server(&expired_server)
            .call(
                Method::POST,
                "/accounts",
                Some(json!({ "sessionState": state }))
            )
            .await
            .unwrap_err(),
        (StatusCode::UNAUTHORIZED, "SESSION_EXPIRED".to_owned())
    );

    let trading_401 = MockServer::start().await;
    let api401 = format!("{}/services/api/v1.7", trading_401.uri());
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api401)))
        .mount(&trading_401)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(ResponseTemplate::new(200).set_body_string(DASHBOARD_HTML))
        .mount(&trading_401)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/trading/accounts/summary/{PEA_ID}"
        )))
        .and(query_param("_host", "tradingboard.boursobank.com"))
        .respond_with(ResponseTemplate::new(401))
        .mount(&trading_401)
        .await;
    assert_eq!(
        bourso_for_server(&trading_401)
            .call(
                Method::POST,
                "/accounts",
                Some(json!({ "sessionState": state }))
            )
            .await
            .unwrap_err(),
        (StatusCode::UNAUTHORIZED, "SESSION_EXPIRED".to_owned())
    );
}

#[tokio::test]
async fn initiate_and_complete_edge_cases_credentials_sms_timeout_capacity() {
    // 1. Wrong credentials via 200 marker -> 401 INVALID_CREDENTIALS.
    let bad_cred_server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/connexion/"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("Set-Cookie", "__brs_mit=tok; Path=/")
                .set_body_string(r#"<input name="form[_token]" value="t">"#),
        )
        .mount(&bad_cred_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/connexion/clavier-virtuel"))
        .respond_with(ResponseTemplate::new(200).set_body_string(format!(
            r#"{}<div data-matrix-random-challenge="c"></div>"#,
            pad_html(&SHUFFLED, &PAD_KEYS, &default_svgs())
        )))
        .mount(&bad_cred_server)
        .await;
    Mock::given(method("POST"))
        .and(path("/connexion/saisie-mot-de-passe"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_string("<html>Identifiant ou mot de passe invalide</html>"),
        )
        .mount(&bad_cred_server)
        .await;
    assert_eq!(
        bourso_for_server(&bad_cred_server)
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "customerId": "1", "password": "0" })),
            )
            .await
            .unwrap_err(),
        (StatusCode::UNAUTHORIZED, "INVALID_CREDENTIALS".to_owned())
    );

    // 2. SMS second factor -> 501 MFA_TYPE_UNSUPPORTED.
    let sms_server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/connexion/"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("Set-Cookie", "__brs_mit=tok; Path=/")
                .set_body_string(r#"<input name="form[_token]" value="t">"#),
        )
        .mount(&sms_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/connexion/clavier-virtuel"))
        .respond_with(ResponseTemplate::new(200).set_body_string(format!(
            r#"{}<div data-matrix-random-challenge="c"></div>"#,
            pad_html(&SHUFFLED, &PAD_KEYS, &default_svgs())
        )))
        .mount(&sms_server)
        .await;
    Mock::given(method("POST"))
        .and(path("/connexion/saisie-mot-de-passe"))
        .respond_with(ResponseTemplate::new(302).insert_header("Location", "/securisation"))
        .mount(&sms_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(
            ResponseTemplate::new(200).set_body_string(r#"<a href="/securisation">2FA</a>"#),
        )
        .mount(&sms_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/securisation"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>sec</html>"))
        .mount(&sms_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/securisation/validation"))
        .respond_with(
            ResponseTemplate::new(200).set_body_string(r#"<div class="brs-otp-sms"></div>"#),
        )
        .mount(&sms_server)
        .await;
    assert_eq!(
        bourso_for_server(&sms_server)
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "customerId": "1", "password": "0" })),
            )
            .await
            .unwrap_err(),
        (
            StatusCode::NOT_IMPLEMENTED,
            "MFA_TYPE_UNSUPPORTED".to_owned()
        )
    );

    // 3. App push timeout -> 408 APP_VALIDATION_TIMEOUT + pending capacity cap -> 503 UPSTREAM_UNAVAILABLE.
    let timeout_server = MockServer::start().await;
    let api = format!("{}/services/api/v1.7", timeout_server.uri());
    Mock::given(method("GET"))
        .and(path("/connexion/"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("Set-Cookie", "__brs_mit=tok; Path=/")
                .set_body_string(r#"<input name="form[_token]" value="t">"#),
        )
        .mount(&timeout_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/connexion/clavier-virtuel"))
        .respond_with(ResponseTemplate::new(200).set_body_string(format!(
            r#"{}<div data-matrix-random-challenge="c"></div>"#,
            pad_html(&SHUFFLED, &PAD_KEYS, &default_svgs())
        )))
        .mount(&timeout_server)
        .await;
    Mock::given(method("POST"))
        .and(path("/connexion/saisie-mot-de-passe"))
        .respond_with(ResponseTemplate::new(302).insert_header("Location", "/securisation"))
        .mount(&timeout_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(
            ResponseTemplate::new(200).set_body_string(r#"<a href="/securisation">2FA</a>"#),
        )
        .mount(&timeout_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/securisation"))
        .respond_with(ResponseTemplate::new(200).set_body_string("<html>sec</html>"))
        .mount(&timeout_server)
        .await;
    let challenge_attr = json!({
        "challenges": [{"parameters": {"formScreen": {"actions": {"check": {"api": {"params": {"resourceId": "r1", "formState": "f1"}}}}}}}]
    })
    .to_string()
    .replace('"', "&quot;");
    Mock::given(method("GET"))
        .and(path("/securisation/validation"))
        .respond_with(ResponseTemplate::new(200).set_body_string(format!(
            r#"<script>window.BRS_CONFIG = {{"API_URL": "{}", "USER_HASH": "{USER_HASH}"}};</script>
            <div class="brs-otp-webtoapp" data-strong-authentication-payload="{challenge_attr}"></div>
            <input name="form[_token]" value="vt">"#,
            api.replace('/', r"\/")
        )))
        .mount(&timeout_server)
        .await;
    Mock::given(method("POST"))
        .and(path(format!(
            "/services/api/v1.7/fr-FR/_user_/_{USER_HASH}_/session/challenge/startwebtoapp/r1"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "success": true })))
        .mount(&timeout_server)
        .await;
    Mock::given(method("POST"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/session/challenge/checkwebtoapp/r1"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "success": false })))
        .mount(&timeout_server)
        .await;

    let mut cfg = BoursoConfig::with_base_url(timeout_server.uri());
    cfg.app_validation_poll = Duration::from_millis(15);
    cfg.app_validation_timeout = Duration::from_millis(50);
    cfg.max_pending = 2;
    let bourso = NativeBourso::with_config(cfg);

    let first = bourso
        .call(
            Method::POST,
            "/initiate",
            Some(json!({ "customerId": "1", "password": "0" })),
        )
        .await
        .unwrap();
    let _second = bourso
        .call(
            Method::POST,
            "/initiate",
            Some(json!({ "customerId": "1", "password": "0" })),
        )
        .await
        .unwrap();
    assert_eq!(bourso.pending_len().await, 2);

    // Third attempt overflows max_pending -> 503 UPSTREAM_UNAVAILABLE.
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/initiate",
                Some(json!({ "customerId": "1", "password": "0" })),
            )
            .await
            .unwrap_err(),
        (
            StatusCode::SERVICE_UNAVAILABLE,
            "UPSTREAM_UNAVAILABLE".to_owned()
        )
    );

    // Completing the first times out -> 408 APP_VALIDATION_TIMEOUT.
    let pid = first["processId"].as_str().unwrap();
    assert_eq!(
        bourso
            .call(
                Method::POST,
                "/complete",
                Some(json!({ "processId": pid, "code": null })),
            )
            .await
            .unwrap_err(),
        (
            StatusCode::REQUEST_TIMEOUT,
            "APP_VALIDATION_TIMEOUT".to_owned()
        )
    );
    assert_eq!(bourso.pending_len().await, 1);

    // close_all_pending clears remaining attempts.
    bourso.close_all_pending().await;
    assert_eq!(bourso.pending_len().await, 0);
}

#[tokio::test]
async fn every_livret_harvests_history_and_cross_origin_redirects_are_refused() {
    let state = valid_session_state("127.0.0.1");

    for (label, expected_kind) in [
        ("LIVRET A", "LIVRET_A"),
        ("LIVRET D'EPARGNE POPULAIRE", "LEP"),
        ("LIVRET JEUNE", "LIVRET_JEUNE"),
        ("PLAN EPARGNE LOGEMENT", "PEL"),
        ("COMPTE EPARGNE LOGEMENT", "CEL"),
        ("LIVRET BOURSO+", "SAVINGS"),
    ] {
        let server = MockServer::start().await;
        let dash = DASHBOARD_HTML.replace("LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE", label);
        mount_standard_accounts(&server, &dash, default_trading_summary()).await;
        let res = bourso_for_server(&server)
            .call(
                Method::POST,
                "/accounts",
                Some(json!({ "sessionState": state })),
            )
            .await
            .unwrap();
        let livret = res
            .as_array()
            .unwrap()
            .iter()
            .find(|a| a["type"] == expected_kind)
            .unwrap_or_else(|| panic!("missing {expected_kind}"));
        assert_eq!(livret["transactions"].as_array().unwrap().len(), 2);
        assert_eq!(livret["iban"], "FR7630006000011234567890189");
        assert!(livret["transactionWarning"].is_null());
    }

    // Cross-origin 302 on /rib and /mouvements is NOT followed and marks TRANSACTIONS_UNAVAILABLE.
    let redir_server = MockServer::start().await;
    let api = format!("{}/services/api/v1.7", redir_server.uri());
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&api)))
        .mount(&redir_server)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(ResponseTemplate::new(200).set_body_string(DASHBOARD_HTML))
        .mount(&redir_server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!(
            "/services/api/v1.7/_user_/_{USER_HASH}_/trading/accounts/summary/{PEA_ID}"
        )))
        .respond_with(ResponseTemplate::new(200).set_body_json(default_trading_summary()))
        .mount(&redir_server)
        .await;
    Mock::given(method("GET"))
        .and(path_regex(r"/(rib|mouvements)$"))
        .respond_with(
            ResponseTemplate::new(302).insert_header("Location", "https://evil.example/collect"),
        )
        .mount(&redir_server)
        .await;

    let res = bourso_for_server(&redir_server)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap();
    let checking = res
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["type"] == "CHECKING")
        .unwrap();
    assert_eq!(checking["transactionWarning"], "TRANSACTIONS_UNAVAILABLE");
    assert!(checking["iban"].is_null());
}

#[tokio::test]
async fn rejects_cross_origin_redirects_and_tampered_api_url_or_path_segments() {
    // 1. Unit checks for scheme downgrade, embedded credentials, API_URL allowlist, and path segments.
    let base = "https://clients.boursobank.com";
    assert!(!redirects_to_identity_selector(
        StatusCode::FOUND,
        Some("http://clients.boursobank.com/connexion/lister-identites"),
        base
    ));
    assert!(!redirects_to_profile_notice(
        StatusCode::FOUND,
        Some("http://clients.boursobank.com/infos-profil/kyc"),
        base
    ));
    assert!(same_origin_url("https://user:pass@clients.boursobank.com/x", base, base).is_err());
    assert!(same_origin_url("http://clients.boursobank.com/x", base, base).is_err());

    assert!(validate_api_url("https://api.boursobank.com/services/api/v1.7", base).is_ok());
    assert!(validate_api_url("http://api.boursobank.com/services/api/v1.7", base).is_err());
    assert!(validate_api_url("https://evil.example/api", base).is_err());
    assert!(validate_api_url("http://169.254.169.254/latest/meta-data", base).is_err());
    assert!(validate_api_url("https://user:pass@api.boursobank.com/api", base).is_err());
    assert!(validate_api_url("https://api.boursobank.com/api?x=1", base).is_err());

    for bad_hash in ["../../admin", "abc?x=1", "abc#frag", "abc%2fdef", "a/b"] {
        let html = format!(
            r#"<script>window.BRS_CONFIG = {{"API_URL": "https://api.boursobank.com", "USER_HASH": "{bad_hash}"}};</script>"#
        );
        assert!(
            extract_brs_config(&html).is_err(),
            "expected USER_HASH {bad_hash:?} to be rejected"
        );
    }

    let bad_resource = json!({
        "challenges": [{"parameters": {"formScreen": {"actions": {"check": {"api": {"params": {"resourceId": "../escape", "formState": "ok"}}}}}}}]
    })
    .to_string()
    .replace('"', "&quot;");
    assert!(
        strong_auth_params(&format!(
            r#"<div data-strong-authentication-payload="{bad_resource}"></div>"#
        ))
        .is_err()
    );

    // 2. Live cross-origin redirect on GET / must be blocked BEFORE contacting the target host.
    let bank = MockServer::start().await;
    let evil = MockServer::start().await;
    let evil_hits = Arc::new(AtomicUsize::new(0));
    let evil_hits_clone = Arc::clone(&evil_hits);
    Mock::given(method("GET"))
        .and(path("/ssrf"))
        .respond_with(move |_req: &Request| {
            evil_hits_clone.fetch_add(1, Ordering::SeqCst);
            ResponseTemplate::new(200).set_body_string("secret-metadata")
        })
        .mount(&evil)
        .await;
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(
            ResponseTemplate::new(302).insert_header("Location", format!("{}/ssrf", evil.uri())),
        )
        .mount(&bank)
        .await;

    let state = valid_session_state(url::Url::parse(&bank.uri()).unwrap().host_str().unwrap());
    let (status, detail) = bourso_for_server(&bank)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": state })),
        )
        .await
        .unwrap_err();
    assert_eq!(status, StatusCode::BAD_GATEWAY);
    assert_eq!(detail, FORMAT_CHANGED);
    assert_eq!(
        evil_hits.load(Ordering::SeqCst),
        0,
        "cross-origin redirect target must never be contacted"
    );

    // 3. Live tampered API_URL pointing at a different host must be rejected before trading API call.
    let tampered_bank = MockServer::start().await;
    let rogue_api = MockServer::start().await;
    let rogue_hits = Arc::new(AtomicUsize::new(0));
    let rogue_hits_clone = Arc::clone(&rogue_hits);
    Mock::given(method("GET"))
        .respond_with(move |_req: &Request| {
            rogue_hits_clone.fetch_add(1, Ordering::SeqCst);
            ResponseTemplate::new(200).set_body_json(default_trading_summary())
        })
        .mount(&rogue_api)
        .await;
    Mock::given(method("GET"))
        .and(path("/"))
        .respond_with(ResponseTemplate::new(200).set_body_string(home_html(&rogue_api.uri())))
        .mount(&tampered_bank)
        .await;
    Mock::given(method("GET"))
        .and(path("/dashboard/liste-comptes"))
        .respond_with(ResponseTemplate::new(200).set_body_string(DASHBOARD_HTML))
        .mount(&tampered_bank)
        .await;

    let tampered_state = valid_session_state(
        url::Url::parse(&tampered_bank.uri())
            .unwrap()
            .host_str()
            .unwrap(),
    );
    let (status, detail) = bourso_for_server(&tampered_bank)
        .call(
            Method::POST,
            "/accounts",
            Some(json!({ "sessionState": tampered_state })),
        )
        .await
        .unwrap_err();
    assert_eq!(status, StatusCode::BAD_GATEWAY);
    assert_eq!(detail, FORMAT_CHANGED);
    assert_eq!(
        rogue_hits.load(Ordering::SeqCst),
        0,
        "tampered API_URL host must never be contacted"
    );
}
