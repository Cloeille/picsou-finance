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
# Stands in for the app: any small image with a static web server and wget works.
UPSTREAM_IMAGE = os.environ.get("PICSOU_PROXY_TEST_IMAGE", "nginx:1.29-alpine")


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


def test_compose_assigns_the_same_exact_address_to_caddy_and_nginx():
    # Any other upstream would reach Tomcat from an untrusted address and merge all TLS clients.
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
                "image": UPSTREAM_IMAGE,
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
