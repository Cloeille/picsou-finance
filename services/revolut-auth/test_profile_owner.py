"""The image keeps the profile owner of existing installs.

Revolut profiles live on a volume created by earlier images as UID 999. CI runs this
test inside the built image, as its user; a local run outside it is skipped.
"""

import os
import unittest


@unittest.skipUnless(os.environ.get("PICSOU_SIDECAR_BASE"), "runs inside the built image")
class ProfileOwnerTest(unittest.TestCase):
    def test_the_image_runs_as_the_legacy_profile_owner(self):
        self.assertEqual(os.getuid(), 999)
        self.assertEqual(os.environ["REVOLUT_PROFILES_DIR"], "/data/revolut-profiles")
        self.assertEqual(os.stat("/data/revolut-profiles").st_uid, 999)


if __name__ == "__main__":
    unittest.main()
