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

OUTPUT = "lib/libCIRCTHW.so"  # Schema-1 compatibility.
# A manifest can select only this exact ordered catalog, never a build command.
COMPONENTS = {
    "CIRCTHW": ("lib/Dialect/HW", ("lib/Dialect/HW/", "include/circt/Dialect/HW/", "test/Dialect/HW/", "unittests/Dialect/HW/")),
    "CIRCTSVTransforms": ("lib/Dialect/SV/Transforms", ("lib/Dialect/SV/Transforms/",)),
    "CIRCTExportVerilog": ("lib/Conversion/ExportVerilog", ("lib/Conversion/ExportVerilog/",)),
    "CIRCTComb": ("lib/Dialect/Comb", ("lib/Dialect/Comb/", "include/circt/Dialect/Comb/", "test/Dialect/Comb/", "unittests/Dialect/Comb/")),
    "CIRCTDatapath": ("lib/Dialect/Datapath", ("lib/Dialect/Datapath/",)),
    "CIRCTDatapathToComb": ("lib/Conversion/DatapathToComb", ("lib/Conversion/DatapathToComb/",)),
}

CATALOGS = (
    ("CIRCTHW", "CIRCTSVTransforms", "CIRCTExportVerilog"),
    ("CIRCTHW", "CIRCTComb", "CIRCTDatapath", "CIRCTDatapathToComb", "CIRCTSVTransforms", "CIRCTExportVerilog"),
)


def _installed_headers(profile: dict[str, Any]) -> dict[str, str]:
    return {entry["path"]: entry["after"] for entry in profile["sources"]
            if entry["path"].startswith("include/") and entry["before"] != entry["after"]}


def _output_paths(profile: dict[str, Any]) -> set[str]:
    return set(_base_libraries(profile)) | set(_installed_headers(profile))


def _components(profile: dict[str, Any]) -> list[str]:
    return ["CIRCTHW"] if profile["schema"] == 1 else [entry["name"] for entry in profile["components"]]


def _base_libraries(profile: dict[str, Any]) -> dict[str, str]:
    if profile["schema"] == 1:
        return {OUTPUT: profile["base_library_sha256"]}
    return {f"lib/lib{entry['name']}.so": entry["base_library_sha256"] for entry in profile["components"]}


def _outputs(prefix: Path, profile: dict[str, Any]) -> str | dict[str, str]:
    values = {relative: sha256(prefix / relative) for relative in sorted(_output_paths(profile))}
    return values[OUTPUT] if profile["schema"] == 1 else values


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
    common = {"schema", "id", "base_lock_sha256", "circt_commit", "host", "patch", "sources"}
    if not isinstance(value, dict) or type(value.get("schema")) is not int:
        raise LockError("invalid closed overlay manifest")
    schema = value["schema"]
    expected = common | ({"component", "base_library_sha256"} if schema == 1 else {"components", "builder_sha256"})
    if schema not in (1, 2) or set(value) != expected:
        raise LockError("invalid closed overlay manifest")
    if (value["id"] != name or value["base_lock_sha256"] != lock_digest(lock) or
            value["circt_commit"] != lock["native"]["circt"]["commit"]):
        raise LockError("overlay does not match the exact locked CIRCT revision")
    if value["host"] != {"os": "linux", "arch": "x86_64"}:
        raise LockError("overlay supports only pinned Linux x86_64 components")
    if schema == 1:
        if value["component"] != "CIRCTHW":
            raise LockError("schema-1 overlay supports only the CIRCTHW component")
    else:
        if value["builder_sha256"] != sha256(Path(__file__)):
            raise LockError("overlay builder identity differs from checked recipe")
        entries = value["components"]
        if (not isinstance(entries, list) or
                any(not isinstance(entry, dict) or set(entry) != {"name", "base_library_sha256"} for entry in entries) or
                tuple(entry["name"] for entry in entries) not in CATALOGS):
            raise LockError("overlay requires the exact ordered component catalog")
    if any(not SHA256.fullmatch(str(checksum)) for checksum in _base_libraries(value).values()):
        raise LockError("overlay requires exact base library checksums")
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
    prefixes = tuple(prefix for component in _components(value) for prefix in COMPONENTS[component][1])
    paths: set[str] = set()
    for source in sources:
        if not isinstance(source, dict) or set(source) != {"path", "before", "after"}:
            raise LockError("invalid overlay source identity")
        relative = _relative(source["path"])
        if relative in paths or not relative.startswith(prefixes):
            raise LockError("overlay source path is duplicate or outside component owners")
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


def identity(profile: dict[str, Any]) -> dict[str, Any]:
    value = {"id": profile["id"], "manifest_sha256": profile["manifest_sha256"],
             "patch_sha256": profile["patch"]["sha256"]}
    if profile["schema"] == 1:
        value["base_library_sha256"] = profile["base_library_sha256"]
    else:
        value["base_libraries_sha256"] = _base_libraries(profile)
    return value


def validate_overlay(prefix: Path, root: Path, lock: dict[str, Any], value: Any) -> list[str]:
    try:
        if not isinstance(value, dict) or not isinstance(value.get("id"), str):
            return ["invalid installed overlay identity"]
        profile = load_overlay(root, lock, value["id"])
        expected = identity(profile)
        if set(value) != set(expected) | {"output_sha256", "receipt_sha256"}:
            return ["invalid installed overlay identity"]
        if any(value[key] != expected_value for key, expected_value in expected.items()):
            return ["installed overlay differs from checked repair identity"]
        outputs = value["output_sha256"]
        if profile["schema"] == 1:
            outputs = {OUTPUT: outputs}
        if not isinstance(outputs, dict) or set(outputs) != _output_paths(profile):
            return ["installed overlay output inventory mismatch"]
        for relative, checksum in _installed_headers(profile).items():
            if outputs.get(relative) != checksum:
                return ["installed overlay header identity mismatch"]
        for relative, checksum in outputs.items():
            output = prefix / relative
            if output.is_symlink() or not output.is_file() or not isinstance(checksum, str) or not SHA256.fullmatch(checksum) or sha256(output) != checksum:
                return ["installed overlay library checksum mismatch"]
        receipt = prefix / "share/nodal/overlays" / value["id"] / "build.json"
        if receipt.is_symlink() or not receipt.is_file() or sha256(receipt) != value["receipt_sha256"]:
            return ["installed overlay build receipt checksum mismatch"]
        if profile["schema"] == 2:
            build = json.loads(receipt.read_text(encoding="utf-8"))
            if (not isinstance(build, dict) or build.get("identity") != expected or
                    build.get("output_sha256") != outputs or
                    build.get("sources") != profile["sources"] or
                    build.get("circt_commit") != profile["circt_commit"] or
                    build.get("base_lock_sha256") != profile["base_lock_sha256"] or
                    not isinstance(build.get("build"), dict) or
                    set(build["build"]) != set(_components(profile)) or
                    build.get("builder_sha256") != profile["builder_sha256"]):
                return ["installed overlay build receipt identity mismatch"]
            for component_build in build["build"].values():
                if (not isinstance(component_build, dict) or
                        set(component_build) != {"tools", "llvm_cxxflags", "cmake_cache_sha256"} or
                        not isinstance(component_build["llvm_cxxflags"], str) or
                        not SHA256.fullmatch(str(component_build["cmake_cache_sha256"])) or
                        not isinstance(component_build["tools"], dict) or
                        set(component_build["tools"]) != {"cxx", "cmake", "ninja", "llvm-config"}):
                    return ["installed overlay component build identity mismatch"]
                for tool in component_build["tools"].values():
                    if (not isinstance(tool, dict) or set(tool) != {"path", "sha256", "version"} or
                            any(not isinstance(tool[key], str) or not tool[key] for key in tool) or
                            not SHA256.fullmatch(tool["sha256"])):
                        return ["installed overlay compiler/tool identity mismatch"]
        return []
    except (OSError, ValueError, TypeError, KeyError, LockError) as exc:
        return [f"cannot validate installed overlay: {exc}"]


def _cmake_string(value: str) -> str:
    # Quoted CMake strings still expand variables and interpret backslashes.
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace(";", "\\;")


def _compile_component(prefix: Path, source: Path, build: Path, sources: list[str], jobs: int | None, component: str = "CIRCTHW", overlay_headers: bool = False) -> tuple[list[list[str]], dict[str, Any]]:
    if component not in COMPONENTS:
        raise LockError("overlay build component is outside the closed catalog")
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
    content += "@SOURCES@"
    content += r''')
get_target_property(dependencies CIRCTHW INTERFACE_LINK_LIBRARIES)
target_compile_options(NodalPatchedCIRCTHW PRIVATE ${flags})
target_link_libraries(NodalPatchedCIRCTHW PRIVATE ${dependencies})
target_link_options(NodalPatchedCIRCTHW PRIVATE -Wl,--no-undefined)
set_target_properties(NodalPatchedCIRCTHW PROPERTIES OUTPUT_NAME CIRCTHW
  BUILD_WITH_INSTALL_RPATH TRUE INSTALL_RPATH "$ORIGIN"
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")
'''
    if overlay_headers:
        content += 'target_include_directories(NodalPatchedCIRCTHW BEFORE PRIVATE "${NODAL_SOURCE}/include")\n'
    content = content.replace("CIRCTHW", component).replace("@SOURCES@", "".join(f'  "{_cmake_string(str(source / name))}"\n' for name in sources))
    (project / "CMakeLists.txt").write_text(content, encoding="utf-8")
    commands = [["cmake", "-G", "Ninja", "-S", str(project), "-B", str(build / "native"),
                 "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_PREFIX_PATH={prefix}", f"-DNODAL_BASE={prefix}"] + ([f"-DNODAL_SOURCE={source}"] if overlay_headers else []),
                ["cmake", "--build", str(build / "native"), "--target", "NodalPatched" + component, "--parallel", str(jobs or 2)]]
    for command in commands:
        subprocess.run(command, check=True)
    shutil.copyfile(build / f"native/out/lib{component}.so", prefix / f"lib/lib{component}.so")
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


def _component_sources(profile: dict[str, Any], source: Path) -> dict[str, list[str]]:
    inventories = {}
    paths = {entry["path"] for entry in profile["sources"]}
    for component in _components(profile):
        directory = COMPONENTS[component][0]
        cmake = (source / directory / "CMakeLists.txt").read_text(encoding="utf-8")
        patterns = {
            "CIRCTComb": r"add_circt_dialect_library\(CIRCTComb\s+(.*?)\s+ADDITIONAL_HEADER_DIRS",
            "CIRCTDatapath": r"add_circt_dialect_library\(CIRCTDatapath\s+(.*?)\s+ADDITIONAL_HEADER_DIRS",
            "CIRCTDatapathToComb": r"add_circt_conversion_library\(CIRCTDatapathToComb\s+(.*?)\s+DEPENDS",
            "CIRCTHW": r"set\(CIRCT_HW_Sources\s+([^)]*)\)",
            "CIRCTSVTransforms": r"add_circt_dialect_library\(CIRCTSVTransforms\s+(.*?)\s+DEPENDS",
            "CIRCTExportVerilog": r"add_circt_translation_library\(CIRCTExportVerilog\s+(.*?)\s+ADDITIONAL_HEADER_DIRS",
        }
        match = re.search(patterns[component], cmake, re.DOTALL)
        if not match:
            raise LockError("overlay cannot find canonical component source inventory")
        names = match.group(1).split()
        if not 1 <= len(names) <= 64 or len(names) != len(set(names)) or any(not re.fullmatch(r"[A-Za-z0-9_]+\.cpp", name) or f"{directory}/{name}" not in paths for name in names):
            raise LockError("overlay component source inventory is not closed")
        inventories[component] = [f"{directory}/{name}" for name in names]
    return inventories


def apply_overlay(prefix: Path, profile: dict[str, Any], stage: Path, cache: Path,
                  download: Callable, jobs: int | None) -> dict[str, Any]:
    for relative, checksum in _base_libraries(profile).items():
        if sha256(prefix / relative) != checksum:
            raise LockError("overlay requires the exact unmodified pinned base libraries")
    for entry in profile["sources"]:
        if entry["path"] in _installed_headers(profile):
            header = prefix / entry["path"]
            if header.is_symlink() or not header.is_file() or sha256(header) != entry["before"]:
                raise LockError("overlay requires the exact unmodified pinned base headers")
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
    inventories = _component_sources(profile, source)
    commands: list[list[str]] = []
    builds = {}
    for component, sources in inventories.items():
        component_commands, build_identity = _compile_component(prefix, source, stage / "overlay-build" / component, sources, jobs, component, bool(_installed_headers(profile)))
        commands.extend(component_commands)
        builds[component] = build_identity
    for relative, checksum in _installed_headers(profile).items():
        shutil.copyfile(source / relative, prefix / relative)
        if sha256(prefix / relative) != checksum:
            raise LockError("installed overlay header checksum mismatch")
    build_identity = builds["CIRCTHW"] if profile["schema"] == 1 else builds
    receipt = prefix / "share/nodal/overlays" / profile["id"] / "build.json"
    receipt.parent.mkdir(parents=True)
    value = {"identity": identity(profile), "circt_commit": profile["circt_commit"],
             "sources": profile["sources"], "commands": [command, *commands],
             "base_lock_sha256": profile["base_lock_sha256"], "build": build_identity,
             "builder_sha256": sha256(Path(__file__)),
             "output_sha256": _outputs(prefix, profile)}
    receipt.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return identity(profile) | {"output_sha256": value["output_sha256"], "receipt_sha256": sha256(receipt)}
