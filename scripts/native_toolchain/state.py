"""Managed installation state, discovery, and deterministic install plans."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from native_toolchain.overlay import identity, load_overlay, validate_overlay

from toolchain_lock import (
    MANIFEST_NAME,
    expected_install_id,
    lock_digest,
    normalize_host,
    prebuilt_asset,
    required_install_paths,
    source_asset,
)


class BootstrapError(RuntimeError):
    pass


def cache_home() -> Path:
    value = os.environ.get("XDG_CACHE_HOME")
    return Path(value).expanduser() if value else Path.home() / ".cache"


def default_prefix(lock: dict[str, Any], host: tuple[str, str] | None = None) -> Path:
    override = os.environ.get("NODAL_NATIVE_TOOLCHAIN_HOME")
    parent = Path(override).expanduser() if override else cache_home() / "nodal/toolchains"
    return parent / expected_install_id(lock, host)


def manifest_payload(
    lock: dict[str, Any], *, mode: str, host: tuple[str, str] | None = None,
    overlay: dict[str, str] | None = None,
) -> dict[str, Any]:
    os_name, arch = host or normalize_host()
    result = {
        "schema": 1,
        "lock_id": lock["lock_id"],
        "lock_sha256": lock_digest(lock),
        "install_mode": mode,
        "host": {"os": os_name, "arch": arch},
        "circt_release_tag": lock["native"]["circt"]["release_tag"],
        "circt_commit": lock["native"]["circt"]["commit"],
        "llvm_commit": lock["native"]["llvm"]["commit"],
    }
    if overlay is not None:
        result["overlay"] = overlay
    return result


def write_manifest(
    prefix: Path,
    lock: dict[str, Any],
    *,
    mode: str,
    host: tuple[str, str] | None = None,
    overlay: dict[str, str] | None = None,
) -> Path:
    path = prefix / MANIFEST_NAME
    path.write_text(
        json.dumps(manifest_payload(lock, mode=mode, host=host, overlay=overlay), indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
    )
    return path


def validate_install(
    prefix: Path,
    lock: dict[str, Any],
    *,
    host: tuple[str, str] | None = None,
    root: Path | None = None,
    required_overlay: str | None = None,
) -> list[str]:
    prefix = prefix.expanduser().resolve()
    try:
        manifest = json.loads((prefix / MANIFEST_NAME).read_text(encoding="utf-8"))
    except OSError as exc:
        return [f"missing or unreadable {MANIFEST_NAME}: {exc}"]
    except json.JSONDecodeError as exc:
        return [f"invalid {MANIFEST_NAME}: {exc}"]
    if not isinstance(manifest, dict):
        return [f"{MANIFEST_NAME} must contain a JSON object"]

    manifest_host = manifest.get("host")
    if not isinstance(manifest_host, dict):
        manifest_host = {}
    selected_host = host or normalize_host(
        str(manifest_host.get("os", "")), str(manifest_host.get("arch", ""))
    )
    expected = manifest_payload(
        lock, mode=str(manifest.get("install_mode", "")), host=selected_host
    )
    errors: list[str] = []
    for key in (
        "schema",
        "lock_id",
        "lock_sha256",
        "circt_release_tag",
        "circt_commit",
        "llvm_commit",
        "host",
    ):
        if manifest.get(key) != expected.get(key):
            errors.append(
                f"{MANIFEST_NAME} field {key!r} is {manifest.get(key)!r}, "
                f"expected {expected.get(key)!r}"
            )
    if manifest.get("install_mode") not in {"prebuilt", "source"}:
        errors.append(f"{MANIFEST_NAME} install_mode must be prebuilt or source")

    overlay = manifest.get("overlay")
    if overlay is not None:
        if manifest.get("install_mode") != "prebuilt" or selected_host != ("linux", "x86_64"):
            errors.append("managed repair requires the Linux x86_64 prebuilt profile")
        errors.extend(validate_overlay(prefix, root or Path(__file__).resolve().parents[2], lock, overlay))
    elif (prefix / "share/nodal/overlays").exists():
        errors.append("overlay receipts exist without installed overlay identity")
    if required_overlay is not None and (not isinstance(overlay, dict) or overlay.get("id") != required_overlay):
        errors.append(f"required managed overlay is missing: {required_overlay}")

    os_name = str(manifest_host.get("os", "")) or normalize_host()[0]
    for relative in required_install_paths(os_name):
        if not (prefix / relative).is_file():
            errors.append(f"required toolchain path is missing: {relative}")
    return errors


def candidate_prefixes(
    root: Path,
    lock: dict[str, Any],
    *,
    explicit: Path | None = None,
    host: tuple[str, str] | None = None,
    required_overlay: str | None = None,
) -> list[Path]:
    values: list[Path] = []
    if explicit is not None:
        values.append(explicit)
    if direct := os.environ.get("NODAL_NATIVE_TOOLCHAIN"):
        values.append(Path(direct))
    suffix = ""
    if required_overlay is not None:
        load_overlay(root, lock, required_overlay)
        suffix = "-" + required_overlay
    values.append(root / ".toolchains/native" / (expected_install_id(lock, host) + suffix))
    base = default_prefix(lock, host)
    values.append(base.with_name(base.name + suffix))

    result: list[Path] = []
    seen: set[Path] = set()
    for value in values:
        resolved = value.expanduser().resolve()
        if resolved not in seen:
            seen.add(resolved)
            result.append(resolved)
    return result


def discover(
    root: Path,
    lock: dict[str, Any],
    *,
    explicit: Path | None = None,
    host: tuple[str, str] | None = None,
    required_overlay: str | None = None,
) -> tuple[Path | None, dict[str, list[str]]]:
    rejected: dict[str, list[str]] = {}
    for prefix in candidate_prefixes(root, lock, explicit=explicit, host=host, required_overlay=required_overlay):
        if not prefix.exists():
            continue
        errors = validate_install(prefix, lock, host=host, root=root, required_overlay=required_overlay)
        if not errors:
            return prefix, rejected
        rejected[str(prefix)] = errors
    return None, rejected


def source_commands(
    lock: dict[str, Any],
    *,
    prefix: Path,
    source_root: str = "<circt-full-sources>",
    build_root: str = "<native-build>",
    jobs: int | None = None,
) -> list[list[str]]:
    settings = lock["native"]["source_build"]
    parallel = str(jobs or max(1, os.cpu_count() or 1))
    configure = [
        "cmake",
        "-G",
        settings["generator"],
        "-S",
        f"{source_root}/llvm/llvm",
        "-B",
        build_root,
        f"-DCMAKE_BUILD_TYPE={settings['build_type']}",
        f"-DCMAKE_INSTALL_PREFIX={prefix}",
        "-DLLVM_ENABLE_PROJECTS=mlir",
        "-DLLVM_TARGETS_TO_BUILD=host",
        f"-DLLVM_ENABLE_ASSERTIONS={'ON' if settings['llvm_enable_assertions'] else 'OFF'}",
        "-DLLVM_EXTERNAL_PROJECTS=circt",
        f"-DLLVM_EXTERNAL_CIRCT_SOURCE_DIR={source_root}",
        "-DCIRCT_BUILD_TOOLS=ON",
        "-DCIRCT_INCLUDE_TESTS=OFF",
        "-DCIRCT_SLANG_FRONTEND_ENABLED=OFF",
        "-DCIRCT_BINDINGS_PYTHON_ENABLED=OFF",
    ]
    build = [
        "cmake",
        "--build",
        build_root,
        "--target",
        "install",
        "--parallel",
        parallel,
    ]
    return [configure, build]


def _asset_summary(asset: dict[str, Any]) -> dict[str, Any]:
    return {name: asset[name] for name in ("id", "filename", "url", "sha256")}


def plan_install(
    root: Path,
    lock: dict[str, Any],
    *,
    mode: str,
    prefix: Path | None = None,
    host: tuple[str, str] | None = None,
    jobs: int | None = None,
    overlay: str | None = None,
) -> dict[str, Any]:
    selected_host = host or normalize_host()
    profile = load_overlay(root, lock, overlay) if overlay is not None else None
    base = default_prefix(lock, selected_host)
    if profile is not None:
        base = base.with_name(base.name + "-" + profile["id"])
    selected_prefix = (prefix or base).expanduser().resolve()
    selected_mode = mode
    commands: list[list[str]] = []

    if selected_mode == "auto":
        selected_mode = "prebuilt" if prebuilt_asset(lock, selected_host) else "source"
    if selected_mode == "prebuilt":
        asset = prebuilt_asset(lock, selected_host)
        if asset is None:
            raise BootstrapError(
                f"no prebuilt asset for {selected_host[0]}-{selected_host[1]}; use source mode"
            )
    elif selected_mode == "source":
        asset = source_asset(lock)
        commands = source_commands(lock, prefix=selected_prefix, jobs=jobs)
    else:
        raise BootstrapError(f"unsupported install mode: {mode}")

    if profile is not None and (selected_mode != "prebuilt" or selected_host != ("linux", "x86_64")):
        raise BootstrapError("managed repair requires the Linux x86_64 prebuilt profile")
    result = {
        "lock_id": lock["lock_id"],
        "host": {"os": selected_host[0], "arch": selected_host[1]},
        "mode": selected_mode,
        "prefix": str(selected_prefix),
        "asset": _asset_summary(asset),
        "commands": commands,
    }
    if profile is not None:
        result["overlay"] = identity(profile)
    return result
