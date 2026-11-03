#!/usr/bin/env python3
"""Compare the response headers of two Picsou deployments, path by path.

    python3 docker/tests/header_diff.py BASE_URL CANDIDATE_URL [--mcp-key psk_...]
                                        [--candidate-hsts-disabled]

Used to prove that moving static serving and headers from nginx into Spring Boot changes nothing
a browser can observe. Each path is requested twice with gzip accepted: once plain and once with
`X-Forwarded-Proto: https`. Status and normalised headers must match; exit status 1 otherwise.
Each deployment's own index.html names its hashed bundles, so two builds can be compared.
Standard library only.
"""
import argparse
import collections
import http.client
import re
import sys
import urllib.parse

# Hop-by-hop or per-response values that legitimately differ between two servers.
IGNORED = {"date", "server", "connection", "keep-alive", "transfer-encoding", "content-length", "vary"}
# Compared by presence only: the value depends on the file's timestamp or on the clock.
PRESENCE_ONLY = {"etag", "last-modified", "expires"}

# Paths owned by the backend (the prefixes of `SpaPaths.isBackend`); everything else is the SPA.
BACKEND_PREFIXES = ("/api", "/actuator", "/oauth2", "/.well-known", "/mcp")
OAUTH_PREFIXES = ("/oauth2", "/.well-known/oauth-")
HASHED_PREFIX = "/assets/"


def is_spa_path(path):
    return not path.startswith(BACKEND_PREFIXES)


def is_unhashed_spa_path(path):
    """index.html, client routes and the files copied from frontend/public; not Vite's hashed output."""
    return is_spa_path(path) and not path.startswith(HASHED_PREFIX)


# Differences reviewed and kept on purpose when the base is the nginx image (issue #342). Each is
# printed as "accepted", never hidden. Keyed by header name; the predicate receives the path, the
# base value and the candidate value as printed by `diff` ("absent" when the header is missing).
ACCEPTED = {
    # Spring's resource handler supports ranges and says so; nginx omitted it on gzip responses.
    "accept-ranges": lambda path, base, candidate: is_spa_path(path),
    # Spring Security's default value, already sent on every backend response before #342.
    "x-xss-protection": lambda path, base, candidate: is_spa_path(path),
    # RFC 9239 makes text/javascript the registered type; browsers treat both alike.
    "content-type": lambda path, base, candidate: path.endswith(".js"),
    # A missing hashed bundle stays a 404, with Spring's problem body instead of nginx's HTML page.
    "content-encoding": lambda path, base, candidate: path == "/assets/does-not-exist.js",
    "content-disposition": lambda path, base, candidate: path == "/assets/does-not-exist.js",
    # Only the content-hashed files under /assets/ stay cached for a year. index.html and the
    # unhashed public files now revalidate: nginx sent no Cache-Control on index.html and a
    # one-year immutable on /favicon.svg, Spring sends no-cache without Expires.
    "cache-control": lambda path, base, candidate: is_unhashed_spa_path(path) and candidate == "['no-cache']",
    "expires": lambda path, base, candidate: is_unhashed_spa_path(path) and candidate == "absent",
}


def candidate_hsts_dropped(path, base, candidate):
    """HSTS on the OAuth2 chain used to ignore HSTS_ENABLED (bug fix): present on the base, now absent."""
    return path.startswith(OAUTH_PREFIXES) and base != "absent" and candidate == "absent"


SCENARIOS = (
    {},
    {"X-Forwarded-Proto": "https"},
)


def normalise(status, headers):
    """Reduce (status, [(name, value), ...]) to a comparable dict of name -> frozenset."""
    result = {}
    for name, value in headers:
        name = name.lower()
        if name in IGNORED:
            continue
        if name in PRESENCE_ONLY:
            result[name] = frozenset({"<present>"})
        elif name == "cache-control":
            result.setdefault(name, set()).update(d.strip().lower() for d in value.split(",") if d.strip())
        elif name == "content-type":
            result.setdefault(name, set()).add(re.sub(r"\s+", "", value).lower())
        else:
            result.setdefault(name, set()).add(value.strip())
    return {"status": status, "headers": {k: frozenset(v) for k, v in result.items()}}


def diff(base, candidate):
    """Human-readable differences between two normalised responses; empty when identical."""
    lines = []
    if base["status"] != candidate["status"]:
        lines.append(f"status: {base['status']} != {candidate['status']}")
    for name in sorted(set(base["headers"]) | set(candidate["headers"])):
        left = base["headers"].get(name)
        right = candidate["headers"].get(name)
        if left != right:
            lines.append(f"{name}: {sorted(left) if left else 'absent'} != {sorted(right) if right else 'absent'}")
    return lines


def split_difference(line):
    """('name', 'base value', 'candidate value') for a header line produced by `diff`."""
    name, _, values = line.partition(": ")
    base, _, candidate = values.partition(" != ")
    return name, base, candidate


def is_accepted(path, line, candidate_hsts_disabled=False):
    """True when a diff line names a header whose difference on this path was reviewed and kept.

    The HSTS difference only holds when the candidate really runs with HSTS_ENABLED off, which the
    caller must say; otherwise a candidate that lost its HSTS header by mistake would pass.
    """
    name, base, candidate = split_difference(line)
    if name == "strict-transport-security":
        return candidate_hsts_disabled and candidate_hsts_dropped(path, base, candidate)
    predicate = ACCEPTED.get(name)
    return predicate is not None and predicate(path, base, candidate)


def fetch(base_url, path, headers, read_body=True):
    parts = urllib.parse.urlsplit(base_url)
    connection_class = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    connection = connection_class(parts.netloc, timeout=15)
    try:
        connection.request("GET", path, headers={"Accept-Encoding": "gzip", **headers})
        response = connection.getresponse()
        body = response.read() if read_body else b""
        return normalise(response.status, response.getheaders()), body
    finally:
        connection.close()


ASSET_EXTENSIONS = ("js", "css")
LEADING_PATHS = ("/", "/accounts/12")
TRAILING_PATHS = ("/favicon.svg", "/assets/does-not-exist.js", "/api/auth/me", "/actuator/health",
                  "/oauth2/jwks", "/.well-known/oauth-authorization-server")

# One request on each deployment. `base` and `candidate` differ for a hashed bundle, and are None
# when that deployment's index.html does not reference one.
Target = collections.namedtuple("Target", "label base candidate")


def index_assets(html):
    """The first /assets/*.<extension> an index.html references, per extension; None when absent."""
    found = {}
    for extension in ASSET_EXTENSIONS:
        match = re.search(rf'["\'](/assets/[^"\']+\.{extension})["\']', html)
        found[extension] = match.group(1) if match else None
    return found


def targets(base_html, candidate_html):
    """Requests to compare: fixed paths as is, each hashed bundle paired by extension.

    Two builds name their bundles differently, so asking the candidate for the base's hash would
    report a 404 that is not a regression.
    """
    base_assets, candidate_assets = index_assets(base_html), index_assets(candidate_html)
    bundles = []
    for extension in ASSET_EXTENSIONS:
        base, candidate = base_assets[extension], candidate_assets[extension]
        if base is None or candidate is None:
            label = f"index {extension}"
        else:
            label = base if base == candidate else f"{base} (candidate {candidate})"
        bundles.append(Target(label, base, candidate))
    return [*(Target(p, p, p) for p in LEADING_PATHS), *bundles,
            *(Target(p, p, p) for p in TRAILING_PATHS), Target("/mcp", "/mcp", "/mcp")]


def index_html(base_url):
    body = fetch(base_url, "/", {"Accept-Encoding": "identity"})[1]
    return body.decode("utf-8", "replace")


def fetch_mcp(base_url, extra, mcp_key):
    """GET /mcp as an event stream: read the headers only, then close the connection."""
    headers = {"Accept": "text/event-stream", **extra}
    if mcp_key:
        headers["Authorization"] = f"Bearer {mcp_key}"
    parts = urllib.parse.urlsplit(base_url)
    connection_class = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    connection = connection_class(parts.netloc, timeout=15)
    try:
        connection.request("GET", "/mcp", headers={"Accept-Encoding": "gzip", **headers})
        response = connection.getresponse()
        return normalise(response.status, response.getheaders())
    finally:
        connection.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("base_url")
    parser.add_argument("candidate_url")
    parser.add_argument("--mcp-key", default=None, help="psk_ access key, to open the /mcp stream")
    parser.add_argument("--candidate-hsts-disabled", action="store_true",
                        help="the candidate runs with HSTS_ENABLED off: accept that the OAuth2 "
                             "endpoints no longer send Strict-Transport-Security")
    args = parser.parse_args(argv)

    failures = 0
    checks = 0
    compared = []
    for target in targets(index_html(args.base_url), index_html(args.candidate_url)):
        if target.base is None or target.candidate is None:
            sides = [side for side, path in (("base", target.base), ("candidate", target.candidate)) if path is None]
            checks += 1
            failures += 1
            print(f"DIFF {target.label}: the {' and '.join(sides)} index.html references no such asset")
        else:
            compared.append(target)
    for extra in SCENARIOS:
        label = "https" if extra else "plain"
        for target in compared:
            if target.base == "/mcp":
                left, right = fetch_mcp(args.base_url, extra, args.mcp_key), fetch_mcp(args.candidate_url, extra, args.mcp_key)
            else:
                left, right = fetch(args.base_url, target.base, extra)[0], fetch(args.candidate_url, target.candidate, extra)[0]
            checks += 1
            differences = diff(left, right)
            accepted = [line for line in differences if is_accepted(target.base, line, args.candidate_hsts_disabled)]
            differences = [line for line in differences if line not in accepted]
            for line in accepted:
                print(f"accepted [{label}] {target.label}: {line}")
            if differences:
                failures += 1
                print(f"DIFF [{label}] {target.label}")
                for line in differences:
                    print(f"    {line}")
            else:
                print(f"ok   [{label}] {target.label} -> {left['status']}")
    print(f"{checks - failures}/{checks} paths identical")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
