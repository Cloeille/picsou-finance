"""Every test gets its own profiles directory: login pacing and the pinned fingerprint are files
next to the profile, and no test may read or write the real `/data/revolut-profiles`."""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


@pytest.fixture(autouse=True)
def profiles_root(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "PROFILES_ROOT", str(tmp_path))
    return tmp_path
