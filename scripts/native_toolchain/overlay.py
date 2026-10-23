"""Checksum-owned, optional CIRCT component repair on a fresh managed prefix.

The checked manifest supplies data only. Fetch locations, build commands,
component, supported host and patched output are closed in this owner.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Callable

from toolchain_lock import LockError, SHA256, lock_digest

OUTPUT = "lib/libCIRCTHW.so"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _relative(value: Any) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise LockError("overlay paths must be normalized relative paths")
    path = Path(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in value.split("/")):
        raise LockError("overlay paths must be normalized relative paths")
    return value


def load_overlay(root: Path, lock: dict[str, Any], name: str) -> dict[str, Any]:
    if not isinstance(name, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", name):
        raise LockError("invalid overlay identifier")
    directory = root.resolve() / "toolchains/patches" / name
    if not directory.resolve().is_relative_to(root.resolve() / "toolchains/patches"):
        raise LockError("overlay directory escapes checked repository")
    path = directory / "manifest.json"
    if not path.resolve().is_relative_to(directory.resolve()):
        raise LockError("overlay manifest escapes checked directory")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise LockError(f"cannot read checked overlay manifest: {exc}") from exc
    expected = {"schema", "id", "base_lock_sha256", "circt_commit", "host", "component", "base_library_sha256", "patch", "sources"}
    if not isinstance(value, dict) or set(value) != expected or value.get("schema") != 1:
        raise LockError("invalid closed overlay manifest")
    if (value["id"] != name or value["base_lock_sha256"] != lock_digest(lock) or
            value["circt_commit"] != lock["native"]["circt"]["commit"]):
        raise LockError("overlay does not match the exact locked CIRCT revision")
    if value["host"] != {"os": "linux", "arch": "x86_64"} or value["component"] != "CIRCTHW":
        raise LockError("overlay supports only the pinned Linux x86_64 CIRCTHW component")
    if not SHA256.fullmatch(str(value["base_library_sha256"])):
        raise LockError("overlay requires an exact base library checksum")
    patch = value["patch"]
    if not isinstance(patch, dict) or set(patch) != {"file", "sha256"}:
        raise LockError("invalid overlay patch identity")
    patch_path = directory / _relative(patch["file"])
    if (not patch_path.resolve().is_relative_to(directory.resolve()) or
            not SHA256.fullmatch(str(patch["sha256"])) or sha256(patch_path) != patch["sha256"]):
        raise LockError("overlay patch checksum or path mismatch")
    sources = value["sources"]
    if not isinstance(sources, list) or not 1 <= len(sources) <= 64:
        raise LockError("overlay source inventory exceeds bounded profile")
    paths: set[str] = set()
    for source in sources:
        if not isinstance(source, dict) or set(source) != {"path", "before", "after"}:
            raise LockError("invalid overlay source identity")
        relative = _relative(source["path"])
        if relative in paths or not relative.startswith(("lib/Dialect/HW/", "include/circt/Dialect/HW/", "test/Dialect/HW/", "unittests/Dialect/HW/")):
            raise LockError("overlay source path is duplicate or outside HW owner")
        paths.add(relative)
        if any(not SHA256.fullmatch(str(source[key])) for key in ("before", "after")):
            raise LockError("overlay source checksum is invalid")
    # Reject additions, deletion, rename and path escape before invoking patch.
    lines = patch_path.read_text(encoding="utf-8").splitlines()
    old = [line[6:] for line in lines if line.startswith("--- a/")]
    new = [line[6:] for line in lines if line.startswith("+++ b/")]
    all_headers = [line for line in lines if line.startswith(("--- ", "+++ "))]
    if not old or old != new or len(old) != len(set(old)) or len(all_headers) != 2 * len(old) or any(path not in paths for path in old):
        raise LockError("overlay patch may only update exact inventoried source paths")
    return value | {"manifest_sha256": sha256(path), "directory": str(directory)}


def identity(profile: dict[str, Any]) -> dict[str, str]:
    return {"id": profile["id"], "manifest_sha256": profile["manifest_sha256"],
            "patch_sha256": profile["patch"]["sha256"],
            "base_library_sha256": profile["base_library_sha256"]}


def validate_overlay(prefix: Path, root: Path, lock: dict[str, Any], value: Any) -> list[str]:
    try:
        if not isinstance(value, dict) or set(value) != {"id", "manifest_sha256", "patch_sha256", "base_library_sha256", "output_sha256", "receipt_sha256"}:
            return ["invalid installed overlay identity"]
        profile = load_overlay(root, lock, value["id"])
        if any(value[key] != expected for key, expected in identity(profile).items()):
            return ["installed overlay differs from checked repair identity"]
        output = prefix / OUTPUT
        receipt = prefix / "share/nodal/overlays" / value["id"] / "build.json"
        if output.is_symlink() or not output.is_file() or sha256(output) != value["output_sha256"]:
            return ["installed overlay library checksum mismatch"]
        if receipt.is_symlink() or not receipt.is_file() or sha256(receipt) != value["receipt_sha256"]:
            return ["installed overlay build receipt checksum mismatch"]
        return []
    except (OSError, ValueError, TypeError, KeyError, LockError) as exc:
        return [f"cannot validate installed overlay: {exc}"]


def _cmake_string(value: str) -> str:
    # Quoted CMake strings still expand variables and interpret backslashes.
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace(";", "\\;")


def _compile_component(prefix: Path, source: Path, build: Path, sources: list[str], jobs: int | None) -> tuple[list[list[str]], dict[str, Any]]:
    project = build / "project"
    project.mkdir(parents=True)
    # Compile the complete upstream component using its imported dependency
    # closure and llvm-config ABI flags. No source text or manifest command runs.
    content = r'''cmake_minimum_required(VERSION 3.20)
project(NodalCIRCTRepair LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(CIRCT REQUIRED CONFIG)
execute_process(COMMAND "${NODAL_BASE}/bin/llvm-config" --cxxflags
  OUTPUT_VARIABLE flags COMMAND_ERROR_IS_FATAL ANY OUTPUT_STRIP_TRAILING_WHITESPACE)
separate_arguments(flags NATIVE_COMMAND "${flags}")
add_library(NodalPatchedCIRCTHW SHARED
'''
    content += "".join(f'  "{_cmake_string(str(source / name))}"\n' for name in sources)
    content += r''')
get_target_property(dependencies CIRCTHW INTERFACE_LINK_LIBRARIES)
target_compile_options(NodalPatchedCIRCTHW PRIVATE ${flags})
target_link_libraries(NodalPatchedCIRCTHW PRIVATE ${dependencies})
target_link_options(NodalPatchedCIRCTHW PRIVATE -Wl,--no-undefined)
set_target_properties(NodalPatchedCIRCTHW PROPERTIES OUTPUT_NAME CIRCTHW
  BUILD_WITH_INSTALL_RPATH TRUE INSTALL_RPATH "$ORIGIN"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")
'''
    (project / "CMakeLists.txt").write_text(content, encoding="utf-8")
    commands = [["cmake", "-G", "Ninja", "-S", str(project), "-B", str(build / "native"),
                 "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_PREFIX_PATH={prefix}", f"-DNODAL_BASE={prefix}"],
                ["cmake", "--build", str(build / "native"), "--target", "NodalPatchedCIRCTHW", "--parallel", str(jobs or 2)]]
    for command in commands:
        subprocess.run(command, check=True)
    shutil.copyfile(build / "native/out/libCIRCTHW.so", prefix / OUTPUT)
    cache = (build / "native/CMakeCache.txt").read_text(encoding="utf-8")
    compiler = re.search(r"^CMAKE_CXX_COMPILER:FILEPATH=(.+)$", cache, re.MULTILINE)
    if compiler is None:
        raise LockError("overlay build lacks exact C++ compiler identity")
    tools = {}
    for name, executable in (("cxx", compiler.group(1)), ("cmake", shutil.which("cmake")), ("ninja", shutil.which("ninja")), ("llvm-config", str(prefix / "bin/llvm-config"))):
        if executable is None:
            raise LockError(f"overlay build lacks {name}")
        path = Path(executable).resolve()
        version = subprocess.run([str(path), "--version"], check=True, capture_output=True, text=True).stdout.strip()
        tools[name] = {"path": str(path), "sha256": sha256(path), "version": version}
    flags = subprocess.run([str(prefix / "bin/llvm-config"), "--cxxflags"], check=True, capture_output=True, text=True).stdout.strip()
    return commands, {"tools": tools, "llvm_cxxflags": flags, "cmake_cache_sha256": sha256(build / "native/CMakeCache.txt")}


def apply_overlay(prefix: Path, profile: dict[str, Any], stage: Path, cache: Path,
                  download: Callable, jobs: int | None) -> dict[str, str]:
    if sha256(prefix / OUTPUT) != profile["base_library_sha256"]:
        raise LockError("overlay requires the exact unmodified pinned base library")
    source = stage / "overlay-source"
    source.mkdir()
    for entry in profile["sources"]:
        relative = entry["path"]
        cached = cache / "nodal/overlay-sources" / profile["circt_commit"] / relative
        if not cached.is_file() or sha256(cached) != entry["before"]:
            download({"url": f"https://raw.githubusercontent.com/llvm/circt/{profile['circt_commit']}/{relative}",
                      "filename": Path(relative).name, "sha256": entry["before"]}, cached)
        if sha256(cached) != entry["before"]:
            raise LockError("overlay source checksum mismatch")
        destination = source / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(cached, destination)
        if sha256(destination) != entry["before"]:
            raise LockError("copied overlay source checksum mismatch")
    patch = stage / "repair.patch"
    shutil.copyfile(Path(profile["directory"]) / profile["patch"]["file"], patch)
    if sha256(patch) != profile["patch"]["sha256"]:
        raise LockError("overlay patch changed after identity validation")
    command = ["patch", "--batch", "--fuzz=0", "--forward", "-p1", "--input", str(patch)]
    subprocess.run(command, cwd=source, check=True)
    for entry in profile["sources"]:
        if sha256(source / entry["path"]) != entry["after"]:
            raise LockError(f"overlay patched source checksum mismatch: {entry['path']}")
    cmake = (source / "lib/Dialect/HW/CMakeLists.txt").read_text(encoding="utf-8")
    match = re.search(r"set\(CIRCT_HW_Sources\s+([^)]*)\)", cmake)
    if not match:
        raise LockError("overlay cannot find canonical CIRCTHW source inventory")
    names = match.group(1).split()
    paths = {entry["path"] for entry in profile["sources"]}
    if not 1 <= len(names) <= 64 or any(not re.fullmatch(r"[A-Za-z0-9_]+\.cpp", name) or f"lib/Dialect/HW/{name}" not in paths for name in names):
        raise LockError("overlay component source inventory is not closed")
    commands, build_identity = _compile_component(prefix, source, stage / "overlay-build", [f"lib/Dialect/HW/{name}" for name in names], jobs)
    receipt = prefix / "share/nodal/overlays" / profile["id"] / "build.json"
    receipt.parent.mkdir(parents=True)
    value = {"identity": identity(profile), "circt_commit": profile["circt_commit"],
             "sources": profile["sources"], "commands": [command, *commands],
             "base_lock_sha256": profile["base_lock_sha256"], "build": build_identity,
             "builder_sha256": sha256(Path(__file__)),
             "output_sha256": sha256(prefix / OUTPUT)}
    receipt.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return identity(profile) | {"output_sha256": value["output_sha256"], "receipt_sha256": sha256(receipt)}
