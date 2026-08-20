#!/usr/bin/env python3
"""Apply the staged Increment 9 implementation payload."""

from __future__ import annotations

import base64
import hashlib
import io
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PARTS = ROOT / ".github" / "increment9-payload"
EXPECTED_PARTS = tuple(PARTS / f"part-{index:02d}.txt" for index in range(6))
EXPECTED_ARCHIVE_SHA256 = "0ed240a3d17d5b62cdb524b4fddb5076aaea73192647a223a0d9e4ac8c546053"


def main() -> int:
    missing = [str(path.relative_to(ROOT)) for path in EXPECTED_PARTS if not path.is_file()]
    if missing:
        raise RuntimeError("missing payload part(s): " + ", ".join(missing))

    encoded = "".join(path.read_text(encoding="utf-8") for path in EXPECTED_PARTS)
    archive_bytes = base64.b64decode(encoded, validate=True)
    digest = hashlib.sha256(archive_bytes).hexdigest()
    if digest != EXPECTED_ARCHIVE_SHA256:
        raise RuntimeError(
            f"payload SHA-256 mismatch: observed {digest}, expected {EXPECTED_ARCHIVE_SHA256}"
        )

    root = ROOT.resolve()
    with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:gz") as archive:
        members = archive.getmembers()
        for member in members:
            if not member.isfile():
                raise RuntimeError(f"unsupported archive entry: {member.name}")
            target = (ROOT / member.name).resolve()
            target.relative_to(root)
            source = archive.extractfile(member)
            if source is None:
                raise RuntimeError(f"cannot read archive entry: {member.name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read())
            target.chmod(member.mode & 0o777)

    print(f"Increment 9 payload applied ({len(members)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
