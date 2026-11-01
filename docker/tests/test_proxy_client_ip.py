import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import uuid

import pytest


REPO = Path(__file__).resolve().parents[2]
NGINX_CONFIG = Path(os.environ.get(
    "PICSOU_NGINX_TEST_CONFIG", REPO / "docker" / "nginx.conf",
)).resolve()
NGINX_IMAGE = os.environ.get("PICSOU_NGINX_TEST_IMAGE", "nginx:1.29-alpine")


def docker_available():
    if shutil.which("docker") is None:
        return False
    try:
        return subprocess.run(
            ["docker", "info"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=10,
        ).returncode == 0
    except subprocess.TimeoutExpired:
        return False


pytestmark = pytest.mark.skipif(not docker_available(), reason="Docker daemon is required")


def command(args, *, check=True, env=None, timeout=30):
    result = subprocess.run(
        args, text=True, capture_output=True, check=False, timeout=timeout, env=env,
    )
    if check:
        assert result.returncode == 0, (args, result.stdout, result.stderr)
    return result


def create_test_network(name, *, internal=False, subnet_prefix=24, ip_range_prefix=None):
    private_prefixes = ("172.31", "172.28", "10.203", "10.204", "192.168")
    for attempt in range(32):
        prefix = private_prefixes[attempt % len(private_prefixes)]
        subnet = ipaddress.ip_network(f"{prefix}.{uuid.uuid4().int & 0xff}.0/{subnet_prefix}")
        args = ["docker", "network", "create", "--subnet", str(subnet)]
        if ip_range_prefix is not None:
            args.extend(["--ip-range", str(list(subnet.subnets(new_prefix=ip_range_prefix))[-1])])
        if internal:
            args.append("--internal")
        args.append(name)

        result = command(args, check=False)
        if result.returncode == 0:
            return subnet
        if "pool overlaps with other one on this address space" not in result.stderr.lower():
            assert result.returncode == 0, (args, result.stdout, result.stderr)

    pytest.fail("Could not allocate a non-overlapping Docker test subnet")


def test_docker_availability_timeout_returns_false(monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda _: "/synthetic/docker")

    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired(args[0], kwargs["timeout"])

    monkeypatch.setattr(subprocess, "run", timeout)
    assert docker_available() is False


def test_network_allocation_retries_outside_an_occupied_private_range(monkeypatch):
    attempts = []

    def create(args, **kwargs):
        subnet = ipaddress.ip_network(args[args.index("--subnet") + 1])
        attempts.append(subnet)
        overlaps = subnet.overlaps(ipaddress.ip_network("172.31.0.0/16"))
        return subprocess.CompletedProcess(
            args, 1 if overlaps else 0, "",
            "Pool overlaps with other one on this address space" if overlaps else "",
        )

    monkeypatch.setattr(f"{__name__}.command", create)
    subnet = create_test_network("synthetic-subnet-test")
    assert len(attempts) == 2
    assert not subnet.overlaps(ipaddress.ip_network("172.31.0.0/16"))


@pytest.fixture(scope="module")
def nginx_proxy(tmp_path_factory):
    suffix = uuid.uuid4().hex[:12]
    network_name = f"picsou-proxy-test-{suffix}"
    edge_name = f"picsou-nginx-test-{suffix}"
    trusted_name = f"picsou-trusted-test-{suffix}"
    untrusted_name = f"picsou-untrusted-test-{suffix}"
    snippets = tmp_path_factory.mktemp("nginx-snippets")
    backend = snippets.parent / "backend.conf"

    subnet = create_test_network(network_name)
    try:
        trusted_proxy_ip = str(subnet.network_address + 10)
        untrusted_client_ip = str(subnet.network_address + 11)

        (snippets / "picsou-hsts.conf").write_text("", encoding="utf-8")
        (snippets / "picsou-trusted-proxy.generated.conf").write_text(
            f"set_real_ip_from {trusted_proxy_ip};\n",
            encoding="utf-8",
        )
        backend.write_text(
            """server {
    listen 9090;
    location / {
        add_header X-Upstream-XFF $http_x_forwarded_for always;
        add_header X-Upstream-Real-IP $http_x_real_ip always;
        add_header X-Upstream-Port $http_x_forwarded_port always;
        return 200 "ok\\n";
    }
}
""",
            encoding="utf-8",
        )

        started = command([
            "docker", "run", "--detach", "--name", edge_name,
            "--network", network_name,
            "--volume", f"{NGINX_CONFIG}:/etc/nginx/conf.d/picsou.conf:ro",
            "--volume", f"{backend}:/etc/nginx/conf.d/backend.conf:ro",
            "--volume", f"{snippets}:/etc/nginx/snippets:ro",
            NGINX_IMAGE,
        ])
        assert started.stdout.strip()

        for name, address in (
            (trusted_name, trusted_proxy_ip),
            (untrusted_name, untrusted_client_ip),
        ):
            command([
                "docker", "run", "--detach", "--name", name,
                "--network", network_name, "--ip", address,
                NGINX_IMAGE, "sleep", "3600",
            ])

        for _ in range(30):
            running = command([
                "docker", "inspect", "--format", "{{.State.Running}}", edge_name,
            ], check=False)
            if running.stdout.strip() == "true":
                break
            time.sleep(0.1)
        else:
            pytest.fail(command(["docker", "logs", edge_name], check=False).stderr)

        yield {
            "network": network_name,
            "edge": edge_name,
            "trusted": trusted_proxy_ip,
            "untrusted": untrusted_client_ip,
            "clients": {
                trusted_proxy_ip: trusted_name,
                untrusted_client_ip: untrusted_name,
            },
        }
    finally:
        command(["docker", "rm", "--force", trusted_name, untrusted_name], check=False)
        command(["docker", "rm", "--force", edge_name], check=False)
        command(["docker", "network", "rm", network_name], check=False)


def request_through(nginx_proxy, source_ip, headers):
    args = [
        "docker", "exec", nginx_proxy["clients"][source_ip], "wget",
        "-q", "-S", "-O", "/dev/null", "-T", "2", "-t", "10",
    ]
    args.extend(f"--header={name}: {value}" for name, value in headers.items())
    args.append(f"http://{nginx_proxy['edge']}:8080/api/probe")
    result = command(args)

    response_headers = {}
    for line in result.stderr.splitlines():
        if ":" not in line:
            continue
        name, value = line.strip().split(":", 1)
        response_headers[name.lower()] = value.strip()
    return response_headers


def test_nginx_resolves_clients_only_from_the_configured_proxy(nginx_proxy):
    first = request_through(nginx_proxy, nginx_proxy["trusted"], {
        "X-Forwarded-For": "198.51.100.10",
        "X-Real-IP": "203.0.113.200",
    })
    second = request_through(nginx_proxy, nginx_proxy["trusted"], {
        "X-Forwarded-For": "198.51.100.11",
        "X-Real-IP": "203.0.113.201",
    })

    assert first["x-upstream-xff"] == "198.51.100.10"
    assert first["x-upstream-real-ip"] == "198.51.100.10"
    assert second["x-upstream-xff"] == "198.51.100.11"
    assert second["x-upstream-real-ip"] == "198.51.100.11"

    spoofed = request_through(nginx_proxy, nginx_proxy["untrusted"], {
        "X-Forwarded-For": "198.51.100.99",
        "X-Real-IP": "198.51.100.98",
    })
    assert spoofed["x-upstream-xff"] == nginx_proxy["untrusted"]
    assert spoofed["x-upstream-real-ip"] == nginx_proxy["untrusted"]


@pytest.mark.parametrize(("headers", "expected"), [
    ({"X-Forwarded-Host": "nas.test:8080"}, "8080"),
    ({"X-Forwarded-Host": "[2001:db8::1]:8443"}, "8443"),
    ({"X-Forwarded-Host": "picsou.example", "X-Forwarded-Port": "9443"}, "9443"),
    ({"X-Forwarded-Host": "picsou.example:8443", "X-Forwarded-Port": "443"}, "8443"),
])
def test_nginx_preserves_the_public_explicit_port(nginx_proxy, headers, expected):
    response = request_through(nginx_proxy, nginx_proxy["untrusted"], headers)
    assert response["x-upstream-port"] == expected


def test_nginx_leaves_the_forwarded_port_empty_for_scheme_default(nginx_proxy):
    response = request_through(nginx_proxy, nginx_proxy["untrusted"], {
        "X-Forwarded-Host": "picsou.example",
    })
    assert "x-upstream-port" not in response


def test_compose_assigns_the_same_exact_address_to_caddy_and_nginx():
    # Any other upstream would reach nginx from an untrusted address and merge all TLS clients.
    caddyfile = (REPO / "docker/Caddyfile").read_text(encoding="utf-8")
    assert re.findall(r"^\s*reverse_proxy\s+(\S+)", caddyfile, re.MULTILINE) == ["app-tls:8080"]

    proxy_ip = "172.30.254.2"
    proxy_subnet = "172.30.254.0/29"
    proxy_ip_range = "172.30.254.4/30"
    environment = {
        **os.environ,
        "APP_SIDECAR_API_KEY": "synthetic-proxy-config-test",
        "PICSOU_PROXY_IP": proxy_ip,
        "PICSOU_PROXY_IP_RANGE": proxy_ip_range,
        "PICSOU_PROXY_SUBNET": proxy_subnet,
    }
    rendered = command([
        "docker", "compose", "--project-directory", str(REPO / "docker"),
        "-f", str(REPO / "docker/docker-compose.yml"), "--profile", "tls",
        "config", "--format", "json",
    ], env=environment)
    compose = json.loads(rendered.stdout)

    assert compose["services"]["app"]["environment"]["PICSOU_PROXY_IP"] == proxy_ip
    assert compose["services"]["proxy"]["networks"]["tls-proxy"]["ipv4_address"] == proxy_ip
    assert "ipv4_address" not in compose["services"]["app"]["networks"]["tls-proxy"]
    assert compose["services"]["app"]["networks"]["tls-proxy"]["aliases"] == ["app-tls"]
    assert compose["networks"]["tls-proxy"]["internal"] is True
    assert compose["networks"]["tls-proxy"]["ipam"]["config"][0]["subnet"] == proxy_subnet
    assert compose["networks"]["tls-proxy"]["ipam"]["config"][0]["ip_range"] == proxy_ip_range


@pytest.mark.parametrize("addresses", [
    {},
    {
        "PICSOU_PROXY_SUBNET": "172.30.252.0/28",
        "PICSOU_PROXY_IP": "172.30.252.4",
        "PICSOU_PROXY_IP_RANGE": "172.30.252.8/29",
    },
], ids=["defaults", "custom-addresses"])
def test_tls_compose_starts_app_before_caddy_and_allows_concurrent_one_off_app(tmp_path, addresses):
    project = f"picsou-tls-test-{uuid.uuid4().hex[:12]}"
    environment = {
        key: value for key, value in os.environ.items()
        if key not in {"PICSOU_PROXY_SUBNET", "PICSOU_PROXY_IP", "PICSOU_PROXY_IP_RANGE"}
    }
    environment.update({
        "APP_SIDECAR_API_KEY": "synthetic-tls-startup-test",
        "PICSOU_DOMAIN": "picsou.localhost",
        **addresses,
    })
    rendered = command([
        "docker", "compose", "--project-directory", str(REPO / "docker"),
        "--env-file", os.devnull, "--project-name", project,
        "-f", str(REPO / "docker/docker-compose.yml"), "--profile", "tls",
        "config", "--format", "json",
    ], env=environment)
    compose = json.loads(rendered.stdout)
    if not addresses:
        assert compose["services"]["app"]["environment"]["PICSOU_PROXY_IP"] == "172.30.253.2"
        assert compose["services"]["proxy"]["networks"]["tls-proxy"]["ipv4_address"] == "172.30.253.2"
        assert compose["networks"]["tls-proxy"]["ipam"]["config"] == [{
            "subnet": "172.30.253.0/29", "ip_range": "172.30.253.4/30",
        }]
    upstream = tmp_path / "upstream.conf"
    upstream.write_text('server { listen 8080; return 200 "synthetic TLS upstream\\n"; }\n')
    compose["services"] = {
        "app": {
            "image": NGINX_IMAGE,
            "networks": compose["services"]["app"]["networks"],
            "volumes": [f"{upstream}:/etc/nginx/conf.d/default.conf:ro"],
        },
        "proxy": {**compose["services"]["proxy"], "ports": []},
    }
    compose_file = tmp_path / "compose.json"
    args = [
        "docker", "compose", "--project-name", project,
        "-f", str(compose_file), "--profile", "tls",
    ]
    network = compose["networks"]["tls-proxy"]["name"]
    assert command(["docker", "network", "inspect", network], check=False).returncode != 0

    reserved_network = False
    try:
        if not addresses:
            # Reserve an unused network so a running install can keep the default subnet.
            subnet = create_test_network(network, internal=True, subnet_prefix=29, ip_range_prefix=30)
            reserved_network = True
            addresses = {
                "PICSOU_PROXY_IP": str(subnet.network_address + 2),
                "PICSOU_PROXY_IP_RANGE": str(list(subnet.subnets(new_prefix=30))[-1]),
            }
            compose["networks"]["tls-proxy"] = {"name": network, "external": True}
            compose["services"]["proxy"]["networks"]["tls-proxy"]["ipv4_address"] = addresses["PICSOU_PROXY_IP"]
        compose_file.write_text(json.dumps(compose))
        command([*args, "up", "--wait", "--wait-timeout", "60", "app", "proxy"], timeout=90)
        container_ids = command([*args, "ps", "--quiet", "app", "proxy"]).stdout.split()
        containers = json.loads(command(["docker", "inspect", *container_ids]).stdout)
        actual_addresses = {
            container["Config"]["Labels"]["com.docker.compose.service"]:
                container["NetworkSettings"]["Networks"][network]["IPAddress"]
            for container in containers
        }
        migration = command([
            *args, "run", "--rm", "--no-deps", "--entrypoint", "wget", "app",
            "-q", "-O", "-", f"http://{actual_addresses['app']}:8080",
        ])
        assert migration.stdout == "synthetic TLS upstream\n"
        assert actual_addresses["proxy"] == addresses["PICSOU_PROXY_IP"]
        allocation_range = ipaddress.ip_network(addresses["PICSOU_PROXY_IP_RANGE"])
        assert ipaddress.ip_address(actual_addresses["app"]) in allocation_range
        assert ipaddress.ip_address(actual_addresses["proxy"]) not in allocation_range
        response = command([
            *args, "exec", "-T", "proxy", "wget", "-q", "-O", "-", "http://app-tls:8080",
        ])
        assert response.stdout == "synthetic TLS upstream\n"
    finally:
        try:
            if compose_file.exists():
                command([*args, "down", "--volumes", "--remove-orphans"])
        finally:
            if reserved_network:
                command(["docker", "network", "rm", network])
