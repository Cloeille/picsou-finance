from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from header_diff import diff, is_accepted, normalise  # noqa: E402


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
    assert is_accepted("/oauth2/jwks", "strict-transport-security: ['max-age=1'] != absent")
    assert not is_accepted("/api/auth/me", "strict-transport-security: ['max-age=1'] != absent")
    assert not is_accepted("/", "content-security-policy: ['x'] != absent")
