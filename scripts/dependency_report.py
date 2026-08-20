#!/usr/bin/env python3
"""Generate a report-only view of Nodal compiler and toolchain update candidates."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
STABLE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
CIRCT_TAG = re.compile(r"^firtool-([0-9]+\.[0-9]+\.[0-9]+)$")

SCALA_METADATA = (
    "https://repo.maven.apache.org/maven2/"
    "org/scala-lang/scala3-compiler_3/maven-metadata.xml"
)
MILL_METADATA = (
    "https://repo.maven.apache.org/maven2/"
    "com/lihaoyi/mill-dist-native-linux-amd64/maven-metadata.xml"
)
UTEST_METADATA = (
    "https://repo.maven.apache.org/maven2/"
    "com/lihaoyi/utest_3/maven-metadata.xml"
)
CIRCT_RELEASES = "https://api.github.com/repos/llvm/circt/releases?per_page=100"


class DependencyReportError(RuntimeError):
    pass


@dataclass(frozen=True)
class Candidate:
    dependency: str
    current: str
    candidate: str | None
    status: str
    source: str
    note: str = ""

    def as_dict(self) -> dict[str, str | None]:
        return {
            "dependency": self.dependency,
            "current": self.current,
            "candidate": self.candidate,
            "status": self.status,
            "source": self.source,
            "note": self.note,
        }


Fetcher = Callable[[str], str]


def version_key(value: str) -> tuple[int, int, int]:
    if not STABLE_VERSION.fullmatch(value):
        raise ValueError(f"not a stable three-part version: {value!r}")
    return tuple(int(part) for part in value.split("."))  # type: ignore[return-value]


def fetch_text(url: str) -> str:
    headers = {
        "Accept": "application/json, application/xml, text/xml;q=0.9, */*;q=0.1",
        "User-Agent": "Nodal-dependency-report/1",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = f"Bearer {token}"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read().decode("utf-8")
    except (OSError, UnicodeError, urllib.error.URLError) as exc:
        raise DependencyReportError(f"cannot read {url}: {exc}") from exc


def latest_maven_version(payload: str) -> str:
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exc:
        raise DependencyReportError(f"invalid Maven metadata: {exc}") from exc
    versions = {
        (element.text or "").strip()
        for element in root.findall("./versioning/versions/version")
    }
    stable = sorted(
        (value for value in versions if STABLE_VERSION.fullmatch(value)),
        key=version_key,
    )
    if not stable:
        raise DependencyReportError("Maven metadata contains no stable three-part version")
    return stable[-1]


def latest_circt_release(payload: str) -> str:
    try:
        releases = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise DependencyReportError(f"invalid CIRCT release response: {exc}") from exc
    if not isinstance(releases, list):
        raise DependencyReportError("CIRCT release response must be a JSON array")
    versions: list[str] = []
    for release in releases:
        if not isinstance(release, dict):
            continue
        if release.get("draft") or release.get("prerelease"):
            continue
        tag = str(release.get("tag_name", ""))
        match = CIRCT_TAG.fullmatch(tag)
        if match:
            versions.append(match.group(1))
    if not versions:
        raise DependencyReportError("CIRCT releases contain no stable firtool tag")
    return "firtool-" + max(versions, key=version_key)


def classify(current: str, candidate: str) -> str:
    current_version = current.removeprefix("firtool-")
    candidate_version = candidate.removeprefix("firtool-")
    return "update-available" if version_key(candidate_version) > version_key(current_version) else "current"


def _online_candidate(
    *,
    dependency: str,
    current: str,
    source: str,
    loader: Callable[[str], str],
    fetcher: Fetcher,
    note: str = "",
) -> Candidate:
    try:
        candidate = loader(fetcher(source))
    except DependencyReportError as exc:
        return Candidate(dependency, current, None, "error", source, str(exc))
    return Candidate(
        dependency,
        current,
        candidate,
        classify(current, candidate),
        source,
        note,
    )


def build_report(
    lock: dict,
    *,
    online: bool,
    fetcher: Fetcher = fetch_text,
    generated_at: dt.datetime | None = None,
) -> dict:
    scala = lock.get("scala")
    native = lock.get("native")
    if not isinstance(scala, dict) or not isinstance(native, dict):
        raise DependencyReportError("toolchains/lock.json lacks scala/native sections")
    circt = native.get("circt")
    requirements = native.get("requirements")
    if not isinstance(circt, dict) or not isinstance(requirements, dict):
        raise DependencyReportError("toolchains/lock.json lacks CIRCT/requirements sections")

    current = {
        "Scala 3": str(scala.get("version", "")),
        "Mill": str(scala.get("mill_version", "")),
        "uTest": str(scala.get("utest_version", "")),
        "CIRCT": str(circt.get("release_tag", "")),
    }
    for name, value in current.items():
        normalized = value.removeprefix("firtool-")
        if not STABLE_VERSION.fullmatch(normalized):
            raise DependencyReportError(f"{name} pin is not a stable three-part version: {value!r}")

    if online:
        candidates = [
            _online_candidate(
                dependency="Scala 3",
                current=current["Scala 3"],
                source=SCALA_METADATA,
                loader=latest_maven_version,
                fetcher=fetcher,
                note="Frontend compiler update; requires the normal Nodal compatibility review.",
            ),
            _online_candidate(
                dependency="Mill",
                current=current["Mill"],
                source=MILL_METADATA,
                loader=latest_maven_version,
                fetcher=fetcher,
                note="Build-tool update; validate the repository wrapper and managed JDK.",
            ),
            _online_candidate(
                dependency="uTest",
                current=current["uTest"],
                source=UTEST_METADATA,
                loader=latest_maven_version,
                fetcher=fetcher,
                note="Test dependency update.",
            ),
            _online_candidate(
                dependency="CIRCT",
                current=current["CIRCT"],
                source=CIRCT_RELEASES,
                loader=latest_circt_release,
                fetcher=fetcher,
                note=(
                    "Candidate only: derive the exact LLVM submodule revision, regenerate "
                    "checksums, and rerun native compatibility validation before changing the lock."
                ),
            ),
        ]
    else:
        candidates = [
            Candidate(name, value, None, "not-checked", source)
            for name, value, source in (
                ("Scala 3", current["Scala 3"], SCALA_METADATA),
                ("Mill", current["Mill"], MILL_METADATA),
                ("uTest", current["uTest"], UTEST_METADATA),
                ("CIRCT", current["CIRCT"], CIRCT_RELEASES),
            )
        ]

    candidates.extend(
        [
            Candidate(
                "JDK",
                str(scala.get("jvm", "")),
                None,
                "manual-review",
                "toolchains/lock.json",
                "JDK major changes are policy decisions and are never proposed automatically.",
            ),
            Candidate(
                "CMake minimum",
                str(requirements.get("cmake_minimum", "")),
                None,
                "minimum-policy",
                "toolchains/lock.json",
            ),
            Candidate(
                "Ninja minimum",
                str(requirements.get("ninja_minimum", "")),
                None,
                "minimum-policy",
                "toolchains/lock.json",
            ),
        ]
    )

    now = generated_at or dt.datetime.now(dt.timezone.utc)
    return {
        "schema": 1,
        "generated_at": now.isoformat().replace("+00:00", "Z"),
        "mode": "online" if online else "offline",
        "policy": (
            "Report only. This command never edits source files, toolchain locks, "
            "branches, pull requests, or releases."
        ),
        "updates_available": any(item.status == "update-available" for item in candidates),
        "errors": [item.note for item in candidates if item.status == "error"],
        "dependencies": [item.as_dict() for item in candidates],
    }


def render_markdown(report: dict) -> str:
    lines = [
        "# Nodal dependency report",
        "",
        f"Generated: `{report['generated_at']}`",
        "",
        report["policy"],
        "",
        "| Dependency | Current | Candidate | Status |",
        "| --- | --- | --- | --- |",
    ]
    for item in report["dependencies"]:
        candidate = item["candidate"] or "—"
        lines.append(
            f"| {item['dependency']} | `{item['current']}` | `{candidate}` | "
            f"`{item['status']}` |"
        )
    lines.extend(["", "## Review notes", ""])
    notes = [item for item in report["dependencies"] if item.get("note")]
    if notes:
        for item in notes:
            lines.append(f"- **{item['dependency']}:** {item['note']}")
    else:
        lines.append("- No additional notes.")
    lines.extend(
        [
            "",
            "## Upgrade rule",
            "",
            "Candidates are informational. A maintainer must update the checked lock, "
            "derive compatible LLVM/CIRCT revisions, regenerate checksums, and pass "
            "the complete Nodal CI pipeline. The report workflow has no content-write "
            "permission and cannot apply an upgrade.",
            "",
        ]
    )
    return "\n".join(lines)


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def _write_github_output(path: Path, report: dict) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(
            "updates_available="
            + ("true" if report["updates_available"] else "false")
            + "\n"
        )
        stream.write("report_errors=" + ("true" if report["errors"] else "false") + "\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--online", action="store_true")
    mode.add_argument("--offline", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--json", dest="json_path", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)

    try:
        lock = json.loads(
            (args.root.resolve() / "toolchains/lock.json").read_text(encoding="utf-8")
        )
        report = build_report(lock, online=bool(args.online))
    except (OSError, json.JSONDecodeError, DependencyReportError) as exc:
        print(f"NODAL-CI-DEPENDENCY-001: {exc}", file=sys.stderr)
        return 1

    markdown = render_markdown(report)
    if args.markdown:
        _write(args.markdown, markdown)
    else:
        print(markdown, end="")
    if args.json_path:
        _write(args.json_path, json.dumps(report, indent=2, sort_keys=True) + "\n")
    if args.github_output:
        _write_github_output(args.github_output, report)

    if report["errors"]:
        for error in report["errors"]:
            print(f"NODAL-CI-DEPENDENCY-002: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
