"""The Caisse d'Epargne app keeps the shared sidecar-key contract."""

import unittest

from picsou_sidecar import contract

import main


class SidecarAuthTest(contract.SidecarAuthContract):
    app = main.app


if __name__ == "__main__":
    unittest.main()
