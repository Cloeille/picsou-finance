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


@pytest.mark.parametrize("custom_addresses", [False, True], ids=["defaults", "custom-addresses"])
def test_tls_compose_starts_app_before_caddy_and_allows_concurrent_one_off_app(tmp_path, custom_addresses):
    project = f"picsou-tls-test-{uuid.uuid4().hex[:12]}"
    environment = {
        key: value for key, value in os.environ.items()
        if key not in {"PICSOU_PROXY_SUBNET", "PICSOU_PROXY_IP", "PICSOU_PROXY_IP_RANGE"}
    }
    environment.update({
        "APP_SIDECAR_API_KEY": "synthetic-tls-startup-test",
        "PICSOU_DOMAIN": "picsou.localhost",
    })
    network = f"{project}_tls-proxy"
    compose_file = tmp_path / "compose.json"
    args = [
        "docker", "compose", "--project-name", project,
        "-f", str(compose_file), "--profile", "tls",
    ]
    reserved_network = False
    try:
        # Reserve before rendering so custom values prove Compose interpolation too.
        prefix = 28 if custom_addresses else 29
        subnet = create_test_network(network, internal=True, subnet_prefix=prefix, ip_range_prefix=prefix + 1)
        reserved_network = True
        addresses = {
            "PICSOU_PROXY_SUBNET": str(subnet),
            "PICSOU_PROXY_IP": str(subnet.network_address + (4 if custom_addresses else 2)),
            "PICSOU_PROXY_IP_RANGE": str(list(subnet.subnets(new_prefix=prefix + 1))[-1]),
        }
        if custom_addresses:
            environment.update(addresses)
        rendered = command([
            "docker", "compose", "--project-directory", str(REPO / "docker"),
            "--env-file", os.devnull, "--project-name", project,
            "-f", str(REPO / "docker/docker-compose.yml"), "--profile", "tls",
            "config", "--format", "json",
        ], env=environment)
        compose = json.loads(rendered.stdout)
        expected = addresses if custom_addresses else {
            "PICSOU_PROXY_SUBNET": "172.30.253.0/29",
            "PICSOU_PROXY_IP": "172.30.253.2",
            "PICSOU_PROXY_IP_RANGE": "172.30.253.4/30",
        }
        assert compose["networks"]["tls-proxy"]["name"] == network
        assert compose["services"]["app"]["environment"]["PICSOU_PROXY_IP"] == expected["PICSOU_PROXY_IP"]
        assert compose["services"]["proxy"]["networks"]["tls-proxy"]["ipv4_address"] == expected["PICSOU_PROXY_IP"]
        assert compose["networks"]["tls-proxy"]["ipam"]["config"] == [{
            "subnet": expected["PICSOU_PROXY_SUBNET"], "ip_range": expected["PICSOU_PROXY_IP_RANGE"],
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
        # Both cases run on the reserved bridge; defaults were checked above.
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


def test_split_compose_isolates_the_exact_frontend_proxy_address(tmp_path):
    compose_file = tmp_path / "docker-compose.yml"
    compose_file.write_text(
        (REPO / "docker-compose.yml").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (tmp_path / ".env").write_text(
        "POSTGRES_DB=picsou\n"
        "POSTGRES_USER=picsou\n"
        "POSTGRES_PASSWORD=synthetic-compose-test\n"
        "APP_SIDECAR_API_KEY=synthetic-compose-test\n",
        encoding="utf-8",
    )

    rendered = command([
        "docker", "compose", "--project-directory", str(tmp_path),
        "-f", str(compose_file), "config", "--format", "json",
    ])
    compose = json.loads(rendered.stdout)
    services = compose["services"]

    assert services["backend"]["environment"]["SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES"] == (
        r"172\.30\.254\.2"
    )
    assert services["frontend"]["networks"]["picsou-ingress"]["ipv4_address"] == "172.30.254.2"
    assert services["backend"]["networks"]["picsou-ingress"]["ipv4_address"] == "172.30.254.3"
    assert services["backend"]["networks"]["picsou-ingress"]["aliases"] == ["ingress-backend"]
    assert "picsou-net" in services["frontend"]["networks"]
    assert compose["networks"]["picsou-ingress"]["internal"] is True
    assert compose["networks"]["picsou-ingress"]["ipam"]["config"][0]["subnet"] == (
        "172.30.254.0/29"
    )
    assert {
        name for name, service in services.items()
        if "picsou-ingress" in service.get("networks", {})
    } == {"backend", "frontend"}


@pytest.fixture(scope="module")
def split_frontend_proxy(tmp_path_factory):
    suffix = uuid.uuid4().hex[:12]
    general_network = f"picsou-split-general-{suffix}"
    ingress_network = f"picsou-split-ingress-{suffix}"
    backend_name = f"picsou-split-backend-{suffix}"
    frontend_name = f"picsou-split-frontend-{suffix}"
    client_name = f"picsou-split-client-{suffix}"
    config_dir = tmp_path_factory.mktemp("split-nginx")
    backend_config = config_dir / "backend.conf"
    backend_config.write_text(
        """server {
    listen 8080;
    location / {
        add_header X-Upstream-Peer $remote_addr always;
        add_header X-Upstream-XFF $http_x_forwarded_for always;
        return 200 "ok\\n";
    }
}
""",
        encoding="utf-8",
    )

    created_networks = []
    try:
        create_test_network(general_network)
        created_networks.append(general_network)
        ingress_subnet = create_test_network(ingress_network, internal=True)
        created_networks.append(ingress_network)
        frontend_ingress_ip = str(ingress_subnet.network_address + 2)
        backend_ingress_ip = str(ingress_subnet.network_address + 3)

        command([
            "docker", "run", "--detach", "--name", backend_name,
            "--network", ingress_network, "--ip", backend_ingress_ip,
            "--network-alias", "ingress-backend",
            "--volume", f"{backend_config}:/etc/nginx/conf.d/default.conf:ro",
            NGINX_IMAGE,
        ])
        command([
            "docker", "run", "--detach", "--name", frontend_name,
            "--network", general_network, "--publish", "127.0.0.1::8080",
            "--volume", f"{REPO / 'frontend/nginx.conf'}:/etc/nginx/conf.d/default.conf:ro",
            "--volume",
            f"{REPO / 'frontend/picsou-realip.conf'}:/etc/nginx/conf.d/00-picsou-realip.conf:ro",
            NGINX_IMAGE, "sleep", "3600",
        ])
        command([
            "docker", "network", "connect", "--ip", frontend_ingress_ip,
            ingress_network, frontend_name,
        ])
        command(["docker", "exec", frontend_name, "nginx"])
        command([
            "docker", "run", "--detach", "--name", client_name,
            "--network", general_network, NGINX_IMAGE, "sleep", "3600",
        ])
        client_ip = command([
            "docker", "inspect", "--format",
            f"{{{{(index .NetworkSettings.Networks \"{general_network}\").IPAddress}}}}",
            client_name,
        ]).stdout.strip()
        published = command(["docker", "port", frontend_name, "8080/tcp"]).stdout.strip()

        yield {
            "backend": backend_name,
            "frontend": frontend_name,
            "client": client_name,
            "client_ip": client_ip,
            "frontend_ingress_ip": frontend_ingress_ip,
            "published_port": int(published.rsplit(":", 1)[1]),
        }
    finally:
        command(
            ["docker", "rm", "--force", client_name, frontend_name, backend_name],
            check=False,
        )
        if created_networks:
            command(["docker", "network", "rm", *reversed(created_networks)], check=False)


def test_split_frontend_uses_fixed_ingress_peer_and_keeps_published_port_reachable(
    split_frontend_proxy,
):
    container_response = command([
        "docker", "exec", split_frontend_proxy["client"], "wget",
        "-q", "-S", "-O", "/dev/null", "-T", "2", "-t", "10",
        "--header=X-Forwarded-For: 198.51.100.99",
        f"http://{split_frontend_proxy['frontend']}:8080/api/probe",
    ])
    container_headers = {}
    for line in container_response.stderr.splitlines():
        if ":" in line:
            name, value = line.strip().split(":", 1)
            container_headers[name.lower()] = value.strip()

    assert container_headers["x-upstream-peer"] == split_frontend_proxy["frontend_ingress_ip"]
    assert container_headers["x-upstream-xff"] == split_frontend_proxy["client_ip"]

    request = urllib.request.Request(
        f"http://127.0.0.1:{split_frontend_proxy['published_port']}/api/probe",
        headers={"X-Forwarded-For": "198.51.100.98"},
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status == 200
        assert response.headers["X-Upstream-Peer"] == split_frontend_proxy["frontend_ingress_ip"]
        assert response.headers["X-Upstream-XFF"] != "198.51.100.98"
