from pathlib import Path
import re
import unittest


COMPOSE_FILE = Path(__file__).resolve().parents[1] / "docker-compose.yml"


def service_block(service):
    compose = COMPOSE_FILE.read_text(encoding="utf-8")
    match = re.search(
        rf"(?ms)^  {re.escape(service)}:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)",
        compose,
    )
    if match is None:
        raise AssertionError(f"Compose service {service!r} not found")
    return match.group(1)


class CaddyTlsConfigTest(unittest.TestCase):
    def test_default_sni_uses_the_configured_site_name(self):
        caddyfile = (COMPOSE_FILE.parent / "Caddyfile").read_text(encoding="utf-8")
        self.assertRegex(
            caddyfile,
            r"(?ms)^\{\s+default_sni \{\$PICSOU_DOMAIN:picsou\.localhost\}\s+\}",
        )
        self.assertIn("{$PICSOU_DOMAIN:picsou.localhost} {", caddyfile)


class ComposePasswordTest(unittest.TestCase):
    @staticmethod
    def compose_password_for(service):
        password_match = re.search(r"(?m)^      POSTGRES_PASSWORD: (.+)$", service_block(service))
        if password_match is None:
            raise AssertionError(f"POSTGRES_PASSWORD not set for Compose service {service!r}")
        return password_match.group(1)

    def test_compose_app_and_database_receive_the_same_password_expression(self):
        self.assertEqual(
            self.compose_password_for("app"),
            self.compose_password_for("db"),
        )

    def test_compose_password_fallback_covers_unset_and_empty_values(self):
        expression = self.compose_password_for("app")
        self.assertEqual(expression, "${POSTGRES_PASSWORD:-picsou}")
        for configured_password in (None, ""):
            resolved = configured_password or expression.partition(":-")[2].removesuffix("}")
            self.assertEqual(resolved, "picsou")


class ComposeDataPermissionsTest(unittest.TestCase):
    def test_app_waits_for_the_one_shot_data_permissions_service(self):
        app = service_block("app")
        self.assertRegex(app, r"(?m)^      data-permissions:\n        condition: service_completed_successfully$")

    def test_data_permissions_chowns_the_data_volume_to_the_app_user(self):
        block = service_block("data-permissions")
        self.assertIn('user: "0:0"', block)
        self.assertIn('restart: "no"', block)
        self.assertIn("picsou_data:/data", block)
        self.assertRegex(block, r"entrypoint: \[\"chown\", \"-R\", \"1000:1000\", \"/data\"\]")


if __name__ == "__main__":
    unittest.main()
