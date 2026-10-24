"""Small shared preflight for an explicit construction source-root projection.

This checks source discovery inputs, not compiler freshness or semantic validity.
Load by explicit file path when invoking a runner with python3 -I -B.
"""
import hashlib
from pathlib import Path
import re


def require_source_projection(root, *, build_sha256=None, authored_paths=(),
                              emitted_source=None, emitted_paths=()):
    """Validate markers and optional authored-file/MLIR-location provenance.

    authored_paths and emitted_paths accept absolute or root-relative file paths.
    emitted_source is actual MLIR text, never a filename. emitted_paths names the
    subset expected to contribute locations, not replay-only Scala entry points.
    Returns the resolved root; failures raise RuntimeError before replay proceeds.
    """
    root = Path(root).resolve()

    def require(condition, message):
        if not condition:
            raise RuntimeError("source projection: " + message)

    build = root / "build.mill"
    require(build.is_file() and not build.is_symlink(),
            "build.mill must be a regular file")
    for name in ("core/scala", "docs"):
        marker = root / name
        require(marker.is_dir() and not marker.is_symlink(),
                name + " must be a directory")
    if build_sha256 is not None:
        require(hashlib.sha256(build.read_bytes()).hexdigest() == build_sha256,
                "build.mill hash differs from the pinned source")

    def authored(value):
        path = Path(value)
        path = path if path.is_absolute() else root / path
        require(path.is_file() and not path.is_symlink(),
                "authored path must be a regular file: " + str(path))
        path = path.resolve()
        require(path.is_relative_to(root), "authored path escapes the source root")
        return path.relative_to(root).as_posix()

    for value in authored_paths:
        authored(value)
    expected = {authored(value) for value in emitted_paths}
    if emitted_source is not None:
        require(isinstance(emitted_source, str), "emitted_source must be actual MLIR text")
        require(bool(expected), "emitted MLIR requires explicit expected author paths")
        locations = set(re.findall(r'\bloc\("([^"\r\n]+)":\d+:\d+\)', emitted_source))
        require(expected <= locations,
                "emitted MLIR lacks exact authored locations: " + str(sorted(expected - locations)))
        names = {Path(value).name for value in expected}
        foreign = {value for value in locations
                   if Path(value).name in names and value not in expected}
        require(not foreign,
                "emitted MLIR contains bare or foreign author locations: " + str(sorted(foreign)))
    else:
        require(not expected, "expected emitted paths require actual MLIR text")
    return root
