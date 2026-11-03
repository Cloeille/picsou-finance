from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from header_diff import diff, index_assets, is_accepted, normalise, targets  # noqa: E402


def test_ignored_headers_do_not_matter():
    left = normalise(200, [("Date", "a"), ("Server", "nginx"), ("Content-Length", "1"), ("Vary", "Origin"),
                           ("Connection", "keep-alive"), ("Transfer-Encoding", "chunked")])
    right = normalise(200, [("Date", "b"), ("Server", "Apache-Coyote")])
    assert diff(left, right) == []


def test_names_are_case_insensitive():
    assert diff(normalise(200, [("X-Frame-Options", "DENY")]), normalise(200, [("x-frame-options", "DENY")])) == []


def test_etag_last_modified_and_expires_are_compared_by_presence():
    left = normalise(200, [("ETag", '"a-1"'), ("Last-Modified", "x"), ("Expires", "y")])
    right = normalise(200, [("ETag", 'W/"b-2"'), ("Last-Modified", "z"), ("Expires", "w")])
    assert diff(left, right) == []
    assert diff(left, normalise(200, [("ETag", '"a-1"')])) == [
        "expires: ['<present>'] != absent",
        "last-modified: ['<present>'] != absent",
    ]


def test_cache_control_is_a_set_of_directives():
    left = normalise(200, [("Cache-Control", "max-age=31536000, public, immutable")])
    right = normalise(200, [("Cache-Control", "immutable,public,Max-Age=31536000")])
    assert diff(left, right) == []
    assert diff(left, normalise(200, [("Cache-Control", "public")])) != []


def test_content_type_ignores_case_and_spaces():
    left = normalise(200, [("Content-Type", "text/html; charset=UTF-8")])
    right = normalise(200, [("Content-Type", "text/html;charset=utf-8")])
    assert diff(left, right) == []


def test_multi_value_headers_are_sets_without_exact_duplicates():
    left = normalise(200, [("X-Frame-Options", "DENY"), ("X-Frame-Options", "DENY")])
    right = normalise(200, [("X-Frame-Options", "DENY")])
    assert diff(left, right) == []
    other = normalise(200, [("X-Frame-Options", "DENY"), ("X-Frame-Options", "SAMEORIGIN")])
    assert diff(left, other) != []


def test_status_difference_is_reported():
    assert diff(normalise(200, []), normalise(404, [])) == ["status: 200 != 404"]


def test_missing_header_is_reported_with_its_value():
    left = normalise(200, [("Strict-Transport-Security", "max-age=1")])
    assert diff(left, normalise(200, [])) == ["strict-transport-security: ['max-age=1'] != absent"]


def test_accepted_differences_are_scoped_to_their_paths():
    assert is_accepted("/accounts/12", "x-xss-protection: absent != ['0']")
    assert not is_accepted("/api/auth/me", "x-xss-protection: absent != ['0']")
    assert not is_accepted("/", "content-security-policy: ['x'] != absent")


HSTS_DROPPED = "strict-transport-security: ['max-age=1'] != absent"
HSTS_ADDED = "strict-transport-security: absent != ['max-age=1']"


def test_oauth_hsts_is_accepted_only_when_the_candidate_runs_with_hsts_disabled():
    for path in ("/oauth2/jwks", "/.well-known/oauth-authorization-server"):
        assert is_accepted(path, HSTS_DROPPED, candidate_hsts_disabled=True)
        assert not is_accepted(path, HSTS_DROPPED)
        assert not is_accepted(path, HSTS_DROPPED, candidate_hsts_disabled=False)


def test_oauth_hsts_is_rejected_when_the_candidate_sends_it_and_the_base_does_not():
    assert not is_accepted("/oauth2/jwks", HSTS_ADDED, candidate_hsts_disabled=True)
    assert not is_accepted("/oauth2/jwks", HSTS_ADDED)


def test_hsts_difference_is_rejected_outside_the_oauth_endpoints():
    for path in ("/", "/api/auth/me", "/actuator/health"):
        assert not is_accepted(path, HSTS_DROPPED, candidate_hsts_disabled=True)


def test_hsts_value_change_is_rejected_even_with_the_flag():
    changed = "strict-transport-security: ['max-age=1'] != ['max-age=2']"
    assert not is_accepted("/oauth2/jwks", changed, candidate_hsts_disabled=True)


def test_spring_no_cache_replaces_nginx_caching_on_unhashed_spa_paths():
    for path in ("/", "/accounts/12", "/favicon.svg", "/theme-init.js", "/providers/bank.svg"):
        assert is_accepted(path, "cache-control: absent != ['no-cache']")
        assert is_accepted(path, "cache-control: ['immutable', 'max-age=31536000', 'public'] != ['no-cache']")
        assert is_accepted(path, "expires: ['<present>'] != absent")


def test_cache_differences_stay_failures_where_caching_is_unchanged():
    # Hashed assets keep their one-year immutable policy, and the backend keeps no-store.
    assert not is_accepted("/assets/app-1.js", "cache-control: ['immutable'] != ['no-cache']")
    assert not is_accepted("/assets/app-1.js", "expires: ['<present>'] != absent")
    assert not is_accepted("/api/auth/me", "cache-control: ['no-store'] != ['no-cache']")
    # Anything other than plain no-cache (or a missing Expires) is not the reviewed change.
    assert not is_accepted("/", "cache-control: absent != ['max-age=60']")
    assert not is_accepted("/", "expires: absent != ['<present>']")


INDEX_A = ('<script type="module" crossorigin src="/assets/index-AAA.js"></script>'
           '<link rel="stylesheet" crossorigin href="/assets/index-AAA.css">')
INDEX_B = ('<link rel="stylesheet" href="/assets/index-BBB.css">'
           '<script type="module" src="/assets/index-BBB.js"></script>')


def test_index_assets_picks_the_first_bundle_per_extension():
    assert index_assets(INDEX_A) == {"js": "/assets/index-AAA.js", "css": "/assets/index-AAA.css"}
    assert index_assets('<script src="/assets/a.js"></script><script src="/assets/b.js"></script>')["js"] == "/assets/a.js"
    assert index_assets("<html></html>") == {"js": None, "css": None}


def by_label(found):
    return {target.label: target for target in found}


def test_bundles_are_paired_by_extension_across_builds_with_different_hashes():
    found = targets(INDEX_A, INDEX_B)
    js = next(t for t in found if t.base == "/assets/index-AAA.js")
    css = next(t for t in found if t.base == "/assets/index-AAA.css")
    assert js.candidate == "/assets/index-BBB.js"
    assert css.candidate == "/assets/index-BBB.css"
    assert js.label == "/assets/index-AAA.js (candidate /assets/index-BBB.js)"


def test_identical_builds_ask_for_the_same_paths_and_fixed_paths_are_shared():
    found = targets(INDEX_A, INDEX_A)
    assert all(t.base == t.candidate for t in found)
    assert {"/", "/accounts/12", "/favicon.svg", "/assets/does-not-exist.js", "/api/auth/me",
            "/assets/index-AAA.js", "/assets/index-AAA.css", "/mcp"} <= {t.label for t in found}


def test_a_bundle_missing_from_one_index_is_reported_not_skipped():
    candidate_without_css = '<script src="/assets/index-BBB.js"></script>'
    found = targets(INDEX_A, candidate_without_css)
    css = next(t for t in found if t.label == "index css")
    assert (css.base, css.candidate) == ("/assets/index-AAA.css", None)
    base_without_js = '<link href="/assets/index-AAA.css" rel="stylesheet">'
    js = next(t for t in targets(base_without_js, INDEX_B) if t.label == "index js")
    assert (js.base, js.candidate) == (None, "/assets/index-BBB.js")


def test_targets_keep_the_content_type_predicate_working_for_the_base_path():
    js = next(t for t in targets(INDEX_A, INDEX_B) if t.base.endswith(".js") and "index-" in t.base)
    assert is_accepted(js.base, "content-type: ['application/javascript'] != ['text/javascript']")
