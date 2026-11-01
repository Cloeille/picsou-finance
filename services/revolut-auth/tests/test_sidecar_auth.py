"""The Revolut app keeps the shared sidecar-key contract."""

import os
import sys
import unittest

from picsou_sidecar import contract

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


class SidecarAuthTest(contract.SidecarAuthContract):
    app = main.app


if __name__ == "__main__":
    unittest.main()
