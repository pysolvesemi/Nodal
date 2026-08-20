"""Verified release download, safe extraction, and native installation."""

from __future__ import annotations

import hashlib
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path
from typing import Any, Iterable

from toolchain_lock import required_install_paths, version_at_least
from native_toolchain.state import (
    BootstrapError,
    cache_home,
    plan_install,
    source_commands,
    validate_install,
    write_manifest,
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(asset: dict[str, Any], destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".partial")
    request = urllib.request.Request(
        asset["url"], headers={"User-Agent": "Nodal-native-toolchain-bootstrap/1"}
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open(
            "wb"
        ) as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    actual = sha256(temporary)
    if actual != asset["sha256"]:
        temporary.unlink(missing_ok=True)
        raise BootstrapError(
            f"SHA-256 mismatch for {asset['filename']}: expected {asset['sha256']}, found {actual}"
        )
    temporary.replace(destination)
    return destination


def _validate_tar_members(archive: tarfile.TarFile, destination: Path) -> None:
    destination = destination.resolve()
    for member in archive.getmembers():
        target = (destination / member.name).resolve()
        if not target.is_relative_to(destination):
            raise BootstrapError(f"archive member escapes extraction root: {member.name}")
        if member.isdev():
            raise BootstrapError(f"archive contains a device entry: {member.name}")
        if member.issym() or member.islnk():
            link = Path(member.linkname)
            if link.is_absolute():
                raise BootstrapError(f"archive contains absolute link: {member.name}")
            if not (target.parent / link).resolve().is_relative_to(destination):
                raise BootstrapError(f"archive link escapes extraction root: {member.name}")


def safe_extract(archive_path: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive_path, mode="r:*") as archive:
        _validate_tar_members(archive, destination)
        archive.extractall(destination)


def _find_prebuilt_root(stage: Path, os_name: str) -> Path:
    required = required_install_paths(os_name)
    candidates = [stage, *(p for p in stage.iterdir() if p.is_dir() and p.name != "__MACOSX")]
    candidates.extend(path.parent.parent for path in stage.rglob("bin/circt-opt*"))
    seen: set[Path] = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate not in seen and all((candidate / item).is_file() for item in required):
            return candidate
        seen.add(candidate)
    raise BootstrapError("prebuilt archive lacks the required LLVM/MLIR/CIRCT layout")


def _find_source_root(stage: Path) -> Path:
    candidates = [stage, *(p for p in stage.iterdir() if p.is_dir() and p.name != "__MACOSX")]
    candidates.extend(path.parent for path in stage.rglob("CMakeLists.txt"))
    for candidate in candidates:
        if (candidate / "CMakeLists.txt").is_file() and (
            candidate / "llvm/llvm/CMakeLists.txt"
        ).is_file():
            return candidate.resolve()
    raise BootstrapError("source archive lacks a CIRCT root with bundled LLVM")


def _tool_version(command: str) -> str:
    try:
        completed = subprocess.run(
            [command, "--version"], check=True, capture_output=True, text=True
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise BootstrapError(f"cannot run required host tool {command}: {exc}") from exc
    text = (completed.stdout or completed.stderr).strip()
    if not text:
        raise BootstrapError(f"{command} --version returned no output")
    return text


def check_source_host_tools(lock: dict[str, Any]) -> dict[str, str]:
    requirements = lock["native"]["requirements"]
    cmake = _tool_version("cmake").splitlines()[0].split()[-1]
    ninja = _tool_version("ninja").splitlines()[0].strip()
    for name, actual, required in (
        ("CMake", cmake, requirements["cmake_minimum"]),
        ("Ninja", ninja, requirements["ninja_minimum"]),
    ):
        if not version_at_least(actual, required):
            raise BootstrapError(f"{name} {actual} is older than {required}")
    return {"cmake": cmake, "ninja": ninja}


def _run(commands: Iterable[list[str]]) -> None:
    for command in commands:
        subprocess.run(command, check=True)


def install(
    root: Path,
    lock: dict[str, Any],
    *,
    mode: str,
    prefix: Path | None = None,
    host: tuple[str, str] | None = None,
    jobs: int | None = None,
    force: bool = False,
    dry_run: bool = False,
) -> dict[str, Any]:
    plan = plan_install(root, lock, mode=mode, prefix=prefix, host=host, jobs=jobs)
    if dry_run:
        return plan

    selected_prefix = Path(plan["prefix"])
    if selected_prefix.exists():
        if not force:
            errors = validate_install(selected_prefix, lock, host=host)
            if not errors:
                return plan | {"status": "already-installed"}
            raise BootstrapError(
                f"destination is not the locked toolchain: {selected_prefix}; use --force"
            )
        shutil.rmtree(selected_prefix)

    asset = plan["asset"]
    archive_path = cache_home() / "nodal/downloads" / lock["lock_id"] / asset["filename"]
    if not archive_path.is_file() or sha256(archive_path) != asset["sha256"]:
        download(asset, archive_path)

    selected_prefix.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f"{lock['lock_id']}-", dir=str(selected_prefix.parent)
    ) as temporary:
        stage = Path(temporary) / "extract"
        safe_extract(archive_path, stage)
        install_host = (plan["host"]["os"], plan["host"]["arch"])
        if plan["mode"] == "prebuilt":
            shutil.copytree(
                _find_prebuilt_root(stage, plan["host"]["os"]),
                selected_prefix,
                symlinks=True,
            )
        else:
            check_source_host_tools(lock)
            commands = source_commands(
                lock,
                prefix=selected_prefix,
                source_root=str(_find_source_root(stage)),
                build_root=str(Path(temporary) / "build"),
                jobs=jobs,
            )
            _run(commands)
        write_manifest(selected_prefix, lock, mode=plan["mode"], host=install_host)

    errors = validate_install(
        selected_prefix,
        lock,
        host=(plan["host"]["os"], plan["host"]["arch"]),
    )
    if errors:
        raise BootstrapError("installed toolchain failed validation:\n  - " + "\n  - ".join(errors))
    return plan | {"status": "installed"}
