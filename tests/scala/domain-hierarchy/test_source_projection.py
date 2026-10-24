#!/usr/bin/env python3
"""Focused source-projection regression. Invoke python3 -I -B with an owned scratch directory."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile


def main():
    spec = importlib.util.spec_from_file_location(
        "nodal_source_projection", Path(__file__).resolve().with_name("source_projection.py"))
    assert spec is not None and spec.loader is not None
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    with tempfile.TemporaryDirectory(prefix="source-projection-", dir=sys.argv[1]) as temporary:
        root = Path(temporary).resolve()
        (root / "core/scala").mkdir(parents=True)
        (root / "docs").mkdir()
        build = root / "build.mill"
        build.write_text("// pinned test marker\n", encoding="utf-8")
        source = root / "core/scala/Source.scala"
        source.write_text("// authored test source\n", encoding="utf-8")
        digest = hashlib.sha256(build.read_bytes()).hexdigest()
        assert helper.require_source_projection(root, build_sha256=digest,
            authored_paths=[source], emitted_paths=[source],
            emitted_source='module {} loc("core/scala/Source.scala":1:1)') == root

        def rejected(label, expected, **kwargs):
            try:
                helper.require_source_projection(root, **kwargs)
            except RuntimeError as error:
                assert expected in str(error), (label, str(error))
            else:
                raise AssertionError(label + " unexpectedly accepted")

        build.unlink()
        rejected("missing build", "build.mill must be a regular file")
        build.mkdir()
        rejected("wrong build type", "build.mill must be a regular file")
        build.rmdir()
        build.write_text("// pinned test marker\n", encoding="utf-8")
        (root / "docs").rmdir()
        rejected("missing docs", "docs must be a directory")
        (root / "docs").write_text("not a directory", encoding="utf-8")
        rejected("wrong docs type", "docs must be a directory")
        (root / "docs").unlink()
        (root / "docs").mkdir()
        rejected("wrong build hash", "build.mill hash differs", build_sha256="0" * 64)
        for wrong in ("Source.scala", "foreign/Source.scala"):
            rejected("wrong source location", "emitted MLIR lacks exact authored locations",
                authored_paths=[source], emitted_paths=[source],
                emitted_source=f'module {{}} loc("{wrong}":1:1)')
        rejected("mixed foreign location", "bare or foreign author locations",
            emitted_paths=[source], emitted_source=
            'module {} loc("core/scala/Source.scala":1:1) loc("foreign/Source.scala":2:1)')
    print("PASS focused source projection markers, hash, and exact emitted author locations")


if __name__ == "__main__":
    main()
