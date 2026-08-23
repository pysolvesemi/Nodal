from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_increment15.py"


class Increment15FreezeTests(unittest.TestCase):
  def test_repository_matches_v03_freeze(self) -> None:
    completed = subprocess.run(
      [sys.executable, str(CHECKER), "--root", str(ROOT)],
      cwd=ROOT,
      check=False,
      text=True,
      stdout=subprocess.PIPE,
      stderr=subprocess.STDOUT,
    )
    self.assertEqual(completed.returncode, 0, completed.stdout)


if __name__ == "__main__":
  unittest.main()
