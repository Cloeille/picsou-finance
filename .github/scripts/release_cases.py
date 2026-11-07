"""The release smoke cases: the parametrize ids of docker/tests/test_deploy_integration.py.

ci.yml runs one release-deployment leg per id. The ids are read from the test module
itself, without importing pytest, so the legs and the tests cannot drift.

  release_cases.py    prints `release-cases=<JSON list of ids>` for $GITHUB_OUTPUT
"""

import ast
import json
from pathlib import Path

TEST = Path(__file__).resolve().parents[2] / "docker/tests/test_deploy_integration.py"


def cases():
    for node in ast.walk(ast.parse(TEST.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Call) and ast.unparse(node.func) == "pytest.mark.parametrize":
            return [ast.literal_eval(keyword.value) for keyword in node.keywords if keyword.arg == "ids"][0]
    raise SystemExit(f"{TEST} has no parametrized ids")


if __name__ == "__main__":
    print(f"release-cases={json.dumps(cases())}")
