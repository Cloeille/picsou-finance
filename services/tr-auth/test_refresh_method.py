"""Trade Republic moved its session refresh endpoint from POST to GET.

Upstream answers POST with 405 and accepts GET. This test pins the
outgoing method so a regression shows up before deploy.
"""

import ast
import pathlib
import unittest


def refresh_calls():
    source = pathlib.Path(__file__).with_name("main.py").read_text()
    tree = ast.parse(source)
    found = []
    for node in ast.walk(tree):
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "refresh_session":
            for child in ast.walk(node):
                if isinstance(child, ast.Await) and isinstance(child.value, ast.Call):
                    func = child.value.func
                    if isinstance(func, ast.Attribute) and func.attr in ("get", "post"):
                        for arg in child.value.args:
                            if isinstance(arg, ast.JoinedStr):
                                url = "".join(
                                    part.value for part in arg.values
                                    if isinstance(part, ast.Constant)
                                )
                                if "auth/web/refresh" in url:
                                    found.append(func.attr)
    return found


class RefreshMethodTest(unittest.TestCase):
    def test_refresh_uses_get_upstream(self):
        self.assertIn("get", refresh_calls())

    def test_refresh_no_longer_posts_upstream(self):
        self.assertNotIn("post", refresh_calls())


if __name__ == "__main__":
    unittest.main()
