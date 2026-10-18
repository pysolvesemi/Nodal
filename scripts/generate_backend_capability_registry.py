#!/usr/bin/env python3
"""Generate independent language facts and pinned executable consumer profiles.

Language admission is not a consumer support claim. The generated table contains
no MLIR types or policy callbacks; existing semantic validators own those checks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CATALOG = Path("core/compiler/backend-profiles-v0.1.json")
OPERATIONS = Path("core/compiler/include/nodal/Dialect/Nodal/NodalOps.td")
OUTPUT = Path("core/compiler/include/nodal/Support/BackendCapabilityRegistry.inc")
SCALA_OUTPUT = Path("core/integrations/src/nodal/internal/integrations/OpenVafProfiles.scala")
DOMAINS = {"neutral": "Neutral", "analog": "Analog", "digital": "Digital", "mixed": "Mixed"}
ADMISSIONS = {"direct": "Direct", "guarded": "Guarded", "normalize": "Normalize",
              "unsupported": "Unsupported"}
SUPPORT = {"supported", "conditional", "unsupported"}
OBSERVATIONS = {"compiled", "rejected", "crashed", "simulated"}
CONSUMERS = {
    "OpenVAF": (r"[0-9]+\.[0-9]+\.[0-9]+", "openvaf", "openvaf"),
    "OpenVAF-reloaded": (r"[0-9]{8}-[0-9]+-g[0-9a-f]+", "openvaf-reloaded", "OpenVAF-reloaded"),
}
COMPILE_ARGUMENTS = ["--target", "x86_64-unknown-linux", "--target_cpu", "generic",
                     "--interface", "OSDI", "-O", "3"]


class CatalogError(ValueError):
    """A malformed or unresolved catalog cannot generate admission facts."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise CatalogError(message)


def records(value: Any, label: str, *, empty: bool = False) -> list[dict[str, Any]]:
    require(isinstance(value, list) and (empty or bool(value)), f"{label}: expected list")
    require(all(isinstance(entry, dict) for entry in value), f"{label}: expected objects")
    return value


def text(value: Any, label: str) -> str:
    require(isinstance(value, str) and bool(value.strip()), f"{label}: expected nonempty string")
    return value


def strings(value: Any, label: str, *, empty: bool = False) -> list[str]:
    require(isinstance(value, list) and (empty or bool(value)), f"{label}: expected string list")
    for item in value:
        text(item, label)
    require(len(set(value)) == len(value), f"{label}: duplicate reference")
    return value


def index(entries: list[dict[str, Any]], key: str, label: str) -> dict[str, dict[str, Any]]:
    result = {}
    for entry in entries:
        identity = text(entry.get(key), f"{label}.{key}")
        require(identity not in result, f"{label}: duplicate {key} {identity}")
        result[identity] = entry
    return result


def reference(root: Path, value: str, label: str, *, symbol: bool = False) -> None:
    filename, separator, fragment = value.partition("#")
    relative = Path(filename)
    require(not relative.is_absolute() and ".." not in relative.parts,
            f"{label}: reference must be repository-relative: {value}")
    path = root / relative
    require(path.is_file(), f"{label}: missing reference {value}")
    if separator:
        require(bool(fragment), f"{label}: empty reference fragment {value}")
        if symbol:
            require(re.search(r"\b" + re.escape(fragment) + r"\b",
                              path.read_text(encoding="utf-8")) is not None,
                    f"{label}: unknown validator {value}")


def known_operations(root: Path) -> set[str]:
    """Read concrete TableGen defs, including the analog binary/event subclasses."""
    source = (root / OPERATIONS).read_text(encoding="utf-8")
    names = re.findall(r'def\s+Nodal_\w+Op\s*:\s*Nodal_\w*Op<"([^"]+)"', source)
    require(bool(names) and len(set(names)) == len(names), "TableGen operation inventory is invalid")
    return {"nodal." + name for name in names}


def validate_consumers(catalog: dict[str, Any]) -> dict[str, dict[str, Any]]:
    consumers = catalog.get("consumer_profiles")
    require(isinstance(consumers, dict), "consumer_profiles: expected object")
    require(set(consumers) == {"schema_version", "profile_version", "profiles"},
            "consumer_profiles: unknown or missing fields")
    require(type(consumers.get("schema_version")) is int and consumers["schema_version"] == 1,
            "consumer_profiles: unsupported schema_version")
    require(consumers.get("profile_version") == "1",
            "consumer_profiles: unsupported profile_version")
    profiles = index(records(consumers.get("profiles"), "consumer_profiles.profiles"),
                     "id", "consumer_profiles.profiles")
    for identity, profile in profiles.items():
        require(set(profile) == {"id", "consumer", "version", "version_stdout",
                                 "executable_sha256", "platform", "compile_arguments"},
                f"{identity}: unknown or missing consumer profile fields")
        consumer = profile["consumer"]
        require(isinstance(consumer, str) and consumer in CONSUMERS,
                f"{identity}: unsupported consumer")
        pattern, prefix, version_prefix = CONSUMERS[consumer]
        version = profile["version"]
        require(isinstance(version, str) and re.fullmatch(pattern, version) is not None,
                f"{identity}: invalid consumer version")
        require(profile["version_stdout"] == version_prefix + " " + version,
                f"{identity}: version stdout does not match consumer version")
        require(profile["platform"] == "linux-x86_64", f"{identity}: unsupported platform")
        release = version.split("-")[0]
        require(identity == prefix + "-" + release + "-" + profile["platform"],
                f"{identity}: invalid consumer profile id")
        digest = profile["executable_sha256"]
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                f"{identity}: invalid executable_sha256")
        require(profile["compile_arguments"] == COMPILE_ARGUMENTS,
                f"{identity}: unsupported compile_arguments")
    return profiles


def observation_profile(observation: dict[str, Any],
                        profiles: dict[str, dict[str, Any]]) -> dict[str, Any]:
    matching = [profile for profile in profiles.values()
                if (profile["consumer"], profile["version"], profile["executable_sha256"]) ==
                (observation["consumer"], observation["version"], observation["tool_sha256"])]
    require(len(matching) == 1, f"{observation['id']}: unknown observed consumer tool identity")
    return matching[0]


def validate_catalog(catalog: dict[str, Any], root: Path = ROOT) -> dict[str, Any]:
    require(isinstance(catalog, dict), "catalog: expected object")
    require(type(catalog.get("schema")) is int and catalog["schema"] == 1,
            "catalog: unsupported legacy schema")
    require(catalog.get("version") == "0.1", "catalog: unsupported legacy version")
    consumers = validate_consumers(catalog)
    profiles = index(records(catalog.get("profiles"), "profiles"), "id", "profiles")
    inventory = catalog.get("feature_inventory")
    require(isinstance(inventory, dict), "feature_inventory: expected object")
    require(type(inventory.get("schema_version")) is int and inventory["schema_version"] == 1,
            "feature_inventory: unsupported schema_version")
    require(inventory.get("inventory_version") == "1",
            "feature_inventory: unsupported inventory_version")
    require(inventory.get("unknown_operation_policy") == "reject",
            "feature_inventory: unknown operations must reject")
    for field in ("scope", "domain_semantics", "consumer_observation_policy"):
        text(inventory.get(field), "feature_inventory." + field)
    semantics = inventory.get("admission_semantics")
    require(isinstance(semantics, dict) and set(semantics) == set(ADMISSIONS),
            "feature_inventory: admission semantics must cover the closed enum")
    for admission in ADMISSIONS:
        text(semantics[admission], "admission_semantics." + admission)

    features = index(records(inventory.get("features"), "features"), "id", "features")
    for identity, feature in features.items():
        require(re.fullmatch(r"[a-z][a-z0-9-]*", identity) is not None,
                f"features: invalid id {identity}")
        text(feature.get("summary"), identity + ".summary")
        strings(feature.get("constraints"), identity + ".constraints")
        for ref in strings(feature.get("validation_references"), identity + ".validators"):
            reference(root, ref, identity, symbol=True)
        for ref in strings(feature.get("documentation"), identity + ".documentation"):
            reference(root, ref, identity)
        for ref in strings(feature.get("registry_references", []), identity + ".registries", empty=True):
            filename, separator, section = ref.partition("#")
            require(filename == "analog-functions-v1.json" and separator and
                    section in {"functions", "analyses", "contexts"},
                    f"{identity}: unsupported registry reference {ref}")
            registry = json.loads((root / "core/compiler" / filename).read_text(encoding="utf-8"))
            require(registry.get("schema_version") == 1 and registry.get("registry_version") == "1",
                    f"{identity}: unsupported analog registry version")
            index(records(registry.get(section), ref), "id", ref)

    operations = index(records(inventory.get("operations"), "operations"), "operation", "operations")
    used_features = set()
    for name, operation in operations.items():
        require(re.fullmatch(r"nodal\.[a-z][a-z0-9_]*", name) is not None,
                f"operations: invalid operation {name}")
        require(isinstance(operation.get("feature"), str) and operation["feature"] in features, f"{name}: unknown feature reference")
        require(isinstance(operation.get("domain"), str) and operation["domain"] in DOMAINS, f"{name}: invalid domain")
        require(isinstance(operation.get("admission"), str) and operation["admission"] in ADMISSIONS, f"{name}: invalid admission")
        used_features.add(operation["feature"])
    require(used_features == set(features), "features: every feature must own an operation")
    known = known_operations(root)
    require(set(operations) == known,
            "operations: TableGen inventory mismatch; missing=" + ",".join(sorted(known - set(operations))) +
            "; unknown=" + ",".join(sorted(set(operations) - known)))

    languages = index(records(inventory.get("language_profiles"), "language_profiles"),
                      "id", "language_profiles")
    require(set(languages) == set(profiles), "language_profiles: profile references do not match profiles")
    for identity, language in languages.items():
        require(language.get("profile_version") == catalog["version"],
                f"{identity}: profile version mismatch")
        require(isinstance(language.get("file_extension"), str) and
                language["file_extension"] in {".va", ".vams"},
                f"{identity}: unsupported file extension")
        text(language.get("implementation_scope"), identity + ".implementation_scope")
        feature_support = index(records(language.get("features"), identity + ".features"),
                                "feature", identity + ".features")
        require(set(feature_support) == set(features), f"{identity}: incomplete or unknown feature references")
        for name, entry in feature_support.items():
            require(isinstance(entry.get("support"), str) and entry["support"] in SUPPORT, f"{identity}/{name}: invalid support")
            admissions = {op["admission"] for op in operations.values() if op["feature"] == name}
            require((entry["support"] == "unsupported") == (admissions == {"unsupported"}),
                    f"{identity}/{name}: support disagrees with operation admission")
            require(entry["support"] != "supported" or
                    not admissions.intersection({"guarded", "normalize"}),
                    f"{identity}/{name}: guarded or normalized admission requires conditional support")

    observations = index(records(inventory.get("consumer_observations"), "consumer_observations", empty=True),
                         "id", "consumer_observations")
    for identity, observation in observations.items():
        require(re.fullmatch(r"[a-z][a-z0-9-]*", identity) is not None,
                f"consumer_observations: invalid id {identity}")
        for field in ("consumer", "version"):
            text(observation.get(field), identity + "." + field)
        require(isinstance(observation.get("profile"), str) and observation["profile"] in profiles, f"{identity}: unknown observed profile")
        require(isinstance(observation.get("result"), str) and observation["result"] in OBSERVATIONS, f"{identity}: invalid observation result")
        observed = strings(observation.get("features"), identity + ".features")
        require(set(observed) <= set(features), f"{identity}: unknown observed feature")
        witness = text(observation.get("witness"), identity + ".witness")
        reference(root, witness, identity + ".witness")
        selection = text(observation.get("selection"), identity + ".selection")
        require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", selection) is not None and
                re.search(r"\b" + re.escape(selection) + r"\b",
                          (root / witness).read_text(encoding="utf-8")) is not None,
                f"{identity}: selection is not present in the witness")
        text(observation.get("model"), identity + ".model")
        for field in ("tool_sha256", "model_sha256"):
            digest = observation.get(field)
            require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                    f"{identity}: invalid {field}")
        observation_profile(observation, consumers)
        exit_code = observation.get("exit_code")
        require(type(exit_code) is int, f"{identity}: exit_code must be an integer")
        require((exit_code == 0) == (observation["result"] in {"compiled", "simulated"}),
                f"{identity}: result disagrees with exit_code")
        text(observation.get("output_format"), identity + ".output_format")
        require(type(observation.get("output_produced")) is bool,
                f"{identity}: output_produced must be a boolean")
        text(observation.get("detail"), identity + ".detail")
        for ref in strings(observation.get("evidence"), identity + ".evidence"):
            reference(root, ref, identity + ".evidence")
    return inventory


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        require(key not in result, f"JSON: duplicate key {key}")
        result[key] = value
    return result


def outputs(root: Path = ROOT) -> dict[Path, str]:
    catalog_bytes = (root / CATALOG).read_bytes()
    catalog = json.loads(catalog_bytes.decode("utf-8"),
                         object_pairs_hook=reject_duplicate_keys)
    inventory = validate_catalog(catalog, root)
    lines = ["// Generated by scripts/generate_backend_capability_registry.py. Do not edit.",
             "// Source: core/compiler/backend-profiles-v0.1.json, feature inventory v1.",
             "// Sorted by operation name; admission never replaces semantic validation.",
             "static constexpr BackendOperationDescriptor backendOperationEntries[] = {"]
    for operation in sorted(inventory["operations"], key=lambda entry: entry["operation"]):
        name = json.dumps(operation["operation"])
        feature = json.dumps(operation["feature"])
        domain = DOMAINS[operation["domain"]]
        admission = ADMISSIONS[operation["admission"]]
        lines += [f"    {{{name}, {feature},",
                  f"     BackendOperationDomain::{domain}, BackendOperationAdmission::{admission}}},"]
    lines += ["};", ""]
    return {OUTPUT: "\n".join(lines), SCALA_OUTPUT: consumer_output(catalog, catalog_bytes)}


def consumer_output(catalog: dict[str, Any], catalog_bytes: bytes) -> str:
    """The exact catalog bytes identify provenance; no generated output enters its hash."""
    profiles = {profile["id"]: profile for profile in catalog["consumer_profiles"]["profiles"]}
    observations = {identity: [] for identity in profiles}
    for observation in catalog["feature_inventory"]["consumer_observations"]:
        profile = observation_profile(observation, profiles)
        observations[profile["id"]].append(observation)
    digest = hashlib.sha256(catalog_bytes).hexdigest()
    lines = ["package nodal.internal.integrations", "",
             "// Generated by scripts/generate_backend_capability_registry.py. Do not edit.",
             "// Source: core/compiler/backend-profiles-v0.1.json, consumer profiles v1.",
             "// Historical observations never replace the current invocation outcome.",
             "private[nodal] object OpenVafProfiles:",
             "  val all: Vector[OpenVafProfile] = Vector("]
    for position, (identity, profile) in enumerate(sorted(profiles.items())):
        lines += ["    OpenVafProfile(",
                  "      id = " + json.dumps(identity) + ",",
                  "      version = " + json.dumps(profile["version_stdout"]) + ",",
                  "      executableSha256 = " + json.dumps(profile["executable_sha256"]) + ",",
                  "      platform = " + json.dumps(profile["platform"]) + ",",
                  "      compileArguments = Vector("]
        lines += ["        " + json.dumps(argument) +
                  ("," if i + 1 < len(profile["compile_arguments"]) else "")
                  for i, argument in enumerate(profile["compile_arguments"])]
        lines += ["      ),", "      catalogSha256 = " + json.dumps(digest) + ","]
        entries = sorted(observations[identity], key=lambda entry: entry["id"])
        lines += ["      observations = Vector(" if entries else "      observations = Vector.empty"]
        for i, observation in enumerate(entries):
            lines += ["        OpenVafObservation(",
                      "          id = " + json.dumps(observation["id"]) + ",",
                      "          modelSha256 = " + json.dumps(observation["model_sha256"]) + ",",
                      "          executableSha256 = " + json.dumps(observation["tool_sha256"]) + ",",
                      "          version = " + json.dumps(profile["version_stdout"]) + ",",
                      "          outcome = " + json.dumps(observation["result"]) + ",",
                      "          description =",
                      "            " + json.dumps(observation["detail"]),
                      "        )" + ("," if i + 1 < len(entries) else "")]
        if entries:
            lines += ["      )"]
        lines += ["    )" + ("," if position + 1 < len(profiles) else "")]
    lines += ["  )", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Reject a stale generated table without writing")
    parser.add_argument("--root", type=Path, default=ROOT, help="Repository root (defaults to this checkout)")
    args = parser.parse_args()
    try:
        for name, content in outputs(args.root).items():
            path = args.root / name
            if args.check:
                if not path.is_file() or path.read_bytes() != content.encode("utf-8"):
                    raise CatalogError(f"stale backend capability registry: {name}")
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
    except (CatalogError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"backend capability registry: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
