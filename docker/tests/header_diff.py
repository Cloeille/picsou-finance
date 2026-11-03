#!/usr/bin/env python3
"""Compare the response headers of two Picsou deployments, path by path.

    python3 docker/tests/header_diff.py BASE_URL CANDIDATE_URL [--mcp-key psk_...]

Used to prove that moving static serving and headers from nginx into Spring Boot changes nothing
a browser can observe. Each path is requested twice with gzip accepted: once plain and once with
`X-Forwarded-Proto: https`. Status and normalised headers must match; exit status 1 otherwise.
Standard library only.
"""
import argparse
import http.client
import re
import sys
import urllib.parse

# Hop-by-hop or per-response values that legitimately differ between two servers.
IGNORED = {"date", "server", "connection", "keep-alive", "transfer-encoding", "content-length", "vary"}
# Compared by presence only: the value depends on the file's timestamp or on the clock.
PRESENCE_ONLY = {"etag", "last-modified", "expires"}

# Differences reviewed and kept on purpose when the base is the nginx image (issue #342). Each is
# printed as "accepted", never hidden. Keyed by header name; the predicate receives the path.
ACCEPTED = {
    # Spring's resource handler supports ranges and says so; nginx omitted it on gzip responses.
    "accept-ranges": lambda path: not path.startswith(("/api", "/actuator", "/oauth2", "/.well-known", "/mcp")),
    # Spring Security's default value, already sent on every backend response before #342.
    "x-xss-protection": lambda path: not path.startswith(("/api", "/actuator", "/oauth2", "/.well-known", "/mcp")),
    # RFC 9239 makes text/javascript the registered type; browsers treat both alike. Only that
    # spelling change is accepted, not any other type on a .js path (see is_javascript_type_change).
    "content-type": lambda path: path.endswith(".js"),
    # A missing hashed bundle stays a 404, with Spring's problem body instead of nginx's HTML page.
    "content-encoding": lambda path: path == "/assets/does-not-exist.js",
    "content-disposition": lambda path: path == "/assets/does-not-exist.js",
    # Every file outside the hashed /assets/ output (index.html, client routes, frontend/public
    # copies) is now "no-cache" so a redeploy reaches the browser; nginx sent no Cache-Control on
    # index.html and a one-year immutable on public files.
    "cache-control": lambda path: is_revalidated_spa_path(path),
    # The one-year Expires of public files is gone; only the base may send it.
    "expires": lambda path: is_unhashed_public_file(path),
}

# HSTS on the OAuth2 chain now follows HSTS_ENABLED like every other response (bug fix). Accepted
# only when the caller states the candidate runs with HSTS off, and only as "base sent it,
# candidate did not" on the OAuth2 paths.
OAUTH_PREFIXES = ("/oauth2", "/.well-known/oauth-")
STATIC_EXTENSION = re.compile(r"\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$", re.IGNORECASE)


def is_revalidated_spa_path(path):
    return not path.startswith(("/assets/", "/api", "/actuator", "/oauth2", "/.well-known", "/mcp"))


def is_unhashed_public_file(path):
    return not path.startswith("/assets/") and bool(STATIC_EXTENSION.search(path))

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


def is_accepted(path, line, candidate_hsts_disabled=False):
    """True when a diff line names a header whose difference on this path was reviewed and kept."""
    name = line.split(":", 1)[0]
    if name == "strict-transport-security":
        return candidate_hsts_disabled and path.startswith(OAUTH_PREFIXES) and line.endswith("!= absent")
    if name == "expires" and not line.endswith("!= absent"):
        return False
    if name == "cache-control" and not line.endswith("!= ['no-cache']"):
        return False
    if name == "content-type" and not is_javascript_type_change(line):
        return False
    predicate = ACCEPTED.get(name)
    return predicate is not None and predicate(path)


def is_javascript_type_change(line):
    """True for exactly the reviewed change: application/javascript on the base became
    text/javascript on the candidate, with the same charset parameter or none. The values are the
    ones `normalise` produces (lower case, no spaces)."""
    match = re.fullmatch(
        r"content-type: \['application/javascript(;charset=[^';]+)?'\] != \['text/javascript(;charset=[^';]+)?'\]", line)
    return match is not None and match.group(1) == match.group(2)


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


def first_asset(base_url, extension):
    """The first /assets/*.<extension> referenced by the root index.html, or None."""
    import gzip
    parts = urllib.parse.urlsplit(base_url)
    connection_class = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    connection = connection_class(parts.netloc, timeout=15)
    try:
        connection.request("GET", "/", headers={"Accept-Encoding": "gzip"})
        response = connection.getresponse()
        body = response.read()
        if response.getheader("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
    finally:
        connection.close()
    match = re.search(rf'["\'](/assets/[^"\']+\.{extension})["\']', body.decode("utf-8", "replace"))
    return match.group(1) if match else None


FIXED_PATHS = ("/favicon.svg", "/assets/does-not-exist.js", "/api/auth/me", "/actuator/health",
               "/oauth2/jwks", "/.well-known/oauth-authorization-server")


def paths(base_url, candidate_url):
    """(base path, candidate path) pairs. Each side requests the bundle its own index.html names,
    so two builds with different content hashes still compare their entry JS and CSS."""
    pairs = [("/", "/"), ("/accounts/12", "/accounts/12")]
    for extension in ("js", "css"):
        left, right = first_asset(base_url, extension), first_asset(candidate_url, extension)
        # The entry script is mandatory; a build may emit no stylesheet.
        if extension == "js" or left or right:
            pairs.append((left, right))
    pairs.extend((path, path) for path in FIXED_PATHS)
    return pairs


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
                        help="the candidate runs with HSTS_ENABLED off: accept HSTS missing on OAuth2 paths")
    args = parser.parse_args(argv)

    failures = 0
    checks = 0
    targets = paths(args.base_url, args.candidate_url)
    for extra in SCENARIOS:
        label = "https" if extra else "plain"
        for base_path, candidate_path in [*targets, ("/mcp", "/mcp")]:
            path = base_path if base_path == candidate_path else f"{base_path} vs {candidate_path}"
            checks += 1
            if base_path is None or candidate_path is None:
                failures += 1
                print(f"DIFF [{label}] {path}: an index.html references no such asset")
                continue
            if base_path == "/mcp":
                left, right = fetch_mcp(args.base_url, extra, args.mcp_key), fetch_mcp(args.candidate_url, extra, args.mcp_key)
            else:
                left, right = fetch(args.base_url, base_path, extra)[0], fetch(args.candidate_url, candidate_path, extra)[0]
            differences = diff(left, right)
            accepted = [line for line in differences
                        if is_accepted(candidate_path, line, args.candidate_hsts_disabled)]
            differences = [line for line in differences if line not in accepted]
            for line in accepted:
                print(f"accepted [{label}] {path}: {line}")
            if differences:
                failures += 1
                print(f"DIFF [{label}] {path}")
                for line in differences:
                    print(f"    {line}")
            else:
                print(f"ok   [{label}] {path} -> {left['status']}")
    print(f"{checks - failures}/{checks} paths identical")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
