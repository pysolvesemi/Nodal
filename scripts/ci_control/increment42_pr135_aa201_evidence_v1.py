"""Dispatch only the two pinned Increment 42 evidence-closure checks.

The controller executes only this reviewed script, never candidate source. It
cannot publish source, rerun, cancel, merge, or launch full CI. Its ledger is a
dispatch receipt, never qualification evidence.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request

REPO = "pysolvesemi/Nodal"
PR = 135
BASE_REF = "dev"
BASE = "98085f79aeaef3a5c7eeabfda462afa7299cbaf7"
BASE_TREE = "e9b74f0a8963ecec76b7e68406e104beda08bf04"
REF = "evidence/increment-42-analog-hierarchy-closure"
HEAD = "aa201f415a1d139948d24351c86862078cd101d2"
TREE = "e785dcb4d061642573cdcba6b00cf5680573140e"
PARENT = BASE
EVIDENCE_HEAD = "fa921f95cc6aad9b1cb86e33edc7f09e53a7f4e4"
EVIDENCE_TREE = BASE_TREE
CONTROL = "ci-control/nodal-42-pr135-aa201-evidence-v1"
CONTROL_WORKFLOW = ".github/workflows/ci-control-42-pr135-aa201-evidence.yml"
INVENTORY = Path(
    "scripts/ci_control/increment42_pr135_aa201_evidence_inventory.json"
)
INVENTORY_SHA256 = "79e08e60c8b203a742bdb990a255a8aac74bc9179bdc6d86a74ea7601b79e04b"
WORKFLOWS = (
    (
        338626156,
        ".github/workflows/ci.yml",
        "18de8c738bde6197f0f55d865f15c3c1b158d38a9240bc1a0f0accb4451d54e1",
        "9dca0c07d90a8ed837e5204f016a8ef67ab8b27b",
    ),
    (
        352989730,
        ".github/workflows/increment-41-analog-functions.yml",
        "0cfecfd99f61cdc5752be18bf3fb996a445b38d597c3ab5a93f96c43fef56b07",
        "f92763766d31ee2cc9172d6736d8b709dbbac03f",
    ),
)
API = f"https://api.github.com/repos/{REPO}"
LEDGER = Path("dispatch-evidence/ledger.jsonl")
SUMMARY = Path("dispatch-evidence/summary.json")
MAX_BYTES = 16 * 1024 * 1024
DEADLINE: float | None = None


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("unexpected API redirect")


def record(event: str, **fields: object) -> None:
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    with LEDGER.open("a", encoding="utf-8") as stream:
        stream.write(
            json.dumps(
                {"event": event, "time": time.time(), **fields}, sort_keys=True
            )
            + "\n"
        )
        stream.flush()
        os.fsync(stream.fileno())


def request(path: str, payload: dict[str, str] | None = None):
    require(DEADLINE is None or time.monotonic() < DEADLINE, "deadline exceeded")
    require(path.startswith("/") and "\\" not in path and ".." not in path, "bad API path")
    if payload is not None:
        allowed = {f"/actions/workflows/{number}/dispatches" for number, *_ in WORKFLOWS}
        require(path in allowed and payload == {"ref": REF}, "write outside allowlist")
    else:
        require(
            path.startswith(
                (
                    "/git/ref/heads/",
                    "/git/commits/",
                    "/pulls/",
                    "/contents/.github/workflows/",
                    "/actions/workflows/",
                    "/actions/runs/",
                )
            ),
            "read outside allowlist",
        )
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        API + path,
        data=data,
        headers={
            "Authorization": "Bearer " + os.environ["GH_TOKEN"],
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "Nodal-42-PR135-aa201-evidence-v1",
        },
        method="GET" if payload is None else "POST",
    )
    timeout = 30 if DEADLINE is None else min(30, max(0.1, DEADLINE - time.monotonic()))
    with urllib.request.build_opener(NoRedirect).open(req, timeout=timeout) as response:
        raw = response.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "API response exceeds bound")
        if payload is not None:
            require(response.status == 204, "uncertain dispatch response")
        return json.loads(raw) if raw else None


def git_blob(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def load_inventory() -> dict[str, object]:
    require(INVENTORY.is_file() and not INVENTORY.is_symlink(), "bad inventory file")
    raw = INVENTORY.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == INVENTORY_SHA256, "inventory changed")
    return json.loads(raw)


def validate_trigger_isolation(inventory: dict[str, object]) -> None:
    require(
        inventory["repository"] == REPO
        and inventory["pr"] == PR
        and inventory["base"] == BASE
        and inventory["base_tree"] == BASE_TREE
        and inventory["candidate"] == HEAD
        and inventory["candidate_tree"] == TREE
        and inventory["candidate_parent"] == PARENT
        and inventory["qualified_implementation_head"] == EVIDENCE_HEAD
        and inventory["qualified_implementation_tree"] == EVIDENCE_TREE
        and inventory["controller_branch"] == CONTROL
        and inventory["controller_workflow"] == CONTROL_WORKFLOW,
        "inventory identity mismatch",
    )
    expected = inventory["dev_workflows"]
    require(isinstance(expected, dict) and len(expected) == 43, "workflow inventory changed")
    directory = Path(".github/workflows")
    actual = {str(path) for path in directory.iterdir()}
    require(actual == set(expected) | {CONTROL_WORKFLOW}, "unexpected workflow file")
    for path, digest in expected.items():
        source = Path(path)
        require(source.is_file() and not source.is_symlink(), "bad inherited workflow")
        data = source.read_bytes()
        require(
            git_blob(data) == digest["blob"]
            and hashlib.sha256(data).hexdigest() == digest["sha256"],
            "inherited workflow bytes changed: " + path,
        )
    own = Path(CONTROL_WORKFLOW)
    require(own.is_file() and not own.is_symlink(), "bad controller workflow")
    require(
        hashlib.sha256(own.read_bytes()).hexdigest()
        == inventory["controller_workflow_sha256"],
        "controller workflow changed",
    )
    declared = [
        (item["id"], item["path"], item["sha256"], item["blob"])
        for item in inventory["candidate_workflows"]
    ]
    require(declared == list(WORKFLOWS), "dispatch inventory changed")
    require(
        all(item["payload"] == {"ref": REF} for item in inventory["candidate_workflows"]),
        "dispatch payload changed",
    )
    record("trigger-isolation-verified", inherited_workflows=len(expected))


def validate_prior_evidence(inventory: dict[str, object]) -> None:
    for expected in inventory["prior_evidence"]:
        number = expected["run_id"]
        run = request(f"/actions/runs/{number}")
        require(
            run["id"] == number
            and run["workflow_id"] == expected["workflow_id"]
            and run["path"] == expected["path"]
            and run["head_sha"] == EVIDENCE_HEAD
            and run["event"] == expected["event"]
            and run["run_attempt"] == expected["run_attempt"]
            and run["status"] == "completed"
            and run["conclusion"] == expected["expected_conclusion"],
            "prior evidence identity changed",
        )
        jobs = []
        for page in range(1, 21):
            result = request(
                f"/actions/runs/{number}/jobs?filter=latest&per_page=100&page={page}"
            )
            jobs.extend(result["jobs"])
            if len(result["jobs"]) < 100:
                break
        else:
            raise RuntimeError("job pagination bound reached")
        require(
            {(job["id"], job["name"]) for job in jobs}
            == {(job["id"], job["name"]) for job in expected["jobs"]},
            "prior job set changed",
        )
        by_id = {job["id"]: job for job in jobs}
        for witness in expected["jobs"]:
            job = by_id[witness["id"]]
            require(
                job["run_id"] == number
                and job["run_attempt"] == expected["run_attempt"]
                and job["head_sha"] == EVIDENCE_HEAD
                and job["status"] == "completed"
                and job["conclusion"] == witness["conclusion"],
                "prior job identity changed",
            )
            steps = {step["name"]: step["conclusion"] for step in job["steps"]}
            require(
                all(steps.get(name) == outcome for name, outcome in witness["steps"].items()),
                "prior step evidence changed",
            )
        record("prior-evidence-verified-not-qualification", run=number)


def validate(definitions: bool = True) -> None:
    require(request("/git/ref/heads/" + BASE_REF)["object"]["sha"] == BASE, "base moved")
    require(request("/git/ref/heads/" + REF)["object"]["sha"] == HEAD, "candidate moved")
    require(request("/git/commits/" + BASE)["tree"]["sha"] == BASE_TREE, "base tree changed")
    commit = request("/git/commits/" + HEAD)
    require(commit["tree"]["sha"] == TREE, "candidate tree changed")
    require([parent["sha"] for parent in commit["parents"]] == [PARENT], "candidate ancestry changed")
    pull = request(f"/pulls/{PR}")
    require(pull["state"] == "open" and not pull["merged"] and pull["draft"], "closure PR state changed")
    require(
        pull["base"]["ref"] == BASE_REF
        and pull["base"]["sha"] == BASE
        and pull["head"]["ref"] == REF
        and pull["head"]["sha"] == HEAD,
        "closure PR refs changed",
    )
    if not definitions:
        return
    for number, path, digest, blob in WORKFLOWS:
        workflow = request(f"/actions/workflows/{number}")
        require(
            workflow["id"] == number
            and workflow["path"] == path
            and workflow["state"] == "active",
            "workflow registration changed",
        )
        content = request("/contents/" + path + "?ref=" + urllib.parse.quote(HEAD))
        require(content["encoding"] == "base64" and content["sha"] == blob, "workflow blob changed")
        data = base64.b64decode(content["content"])
        require(git_blob(data) == blob and hashlib.sha256(data).hexdigest() == digest, "workflow bytes changed")
        require("\n  workflow_dispatch:" in data.decode(), "workflow_dispatch missing")


def runs(number: int) -> list[dict[str, object]]:
    require(number in {entry[0] for entry in WORKFLOWS}, "unknown workflow")
    found = []
    branch = urllib.parse.quote(REF, safe="")
    for page in range(1, 21):
        result = request(
            f"/actions/workflows/{number}/runs?branch={branch}&per_page=100&page={page}"
        )
        rows = result["workflow_runs"]
        for run in rows:
            require(run["workflow_id"] == number, "run workflow mismatch")
            if run["head_sha"] == HEAD and run["head_branch"] == REF:
                require(run["event"] == "workflow_dispatch", "unexpected same-head event")
                found.append(run)
            elif run["head_branch"] == REF and run["status"] != "completed":
                raise RuntimeError("other-head candidate run active")
        if len(rows) < 100:
            return found
    raise RuntimeError("run pagination bound reached")


def reusable(found: list[dict[str, object]]) -> dict[str, object] | None:
    require(len(found) <= 1, "multiple exact-head dispatch runs")
    if not found:
        return None
    run = found[0]
    if run["status"] != "completed" or run["conclusion"] == "success":
        return run
    raise RuntimeError("exact-head run already failed; publish a repair")


def dispatch(number: int) -> dict[str, object]:
    found = runs(number)
    existing = reusable(found)
    if existing is not None:
        record("existing-run-retained", workflow=number, run=existing["id"], status=existing["status"])
        return existing
    record("dispatch-intent", workflow=number, ref=REF, head=HEAD)
    try:
        request(f"/actions/workflows/{number}/dispatches", {"ref": REF})
        record("dispatch-request-certain", workflow=number)
    except Exception as error:
        record("dispatch-response-uncertain", workflow=number, error=type(error).__name__)
        for _ in range(12):
            observed = reusable(runs(number))
            if observed is not None:
                record("uncertain-dispatch-reconciled", workflow=number, run=observed["id"])
                return observed
            time.sleep(5)
        raise RuntimeError("dispatch uncertainty unresolved; no retry") from error
    for _ in range(24):
        observed = reusable(runs(number))
        if observed is not None:
            record("dispatch-observed", workflow=number, run=observed["id"], status=observed["status"])
            return observed
        time.sleep(5)
    raise RuntimeError("certain dispatch not observed within bound")


def finish() -> None:
    raw = LEDGER.read_bytes()
    summary = {
        "schema": 1,
        "candidate": HEAD,
        "candidate_tree": TREE,
        "controller": CONTROL,
        "ledger_sha256": hashlib.sha256(raw).hexdigest(),
        "events": len(raw.splitlines()),
    }
    SUMMARY.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")


def main() -> None:
    global DEADLINE
    DEADLINE = time.monotonic() + 12 * 60
    inventory = load_inventory()
    validate_trigger_isolation(inventory)
    validate_prior_evidence(inventory)
    observed = []
    for number, *_ in WORKFLOWS:
        validate()
        run = dispatch(number)
        observed.append({"workflow": number, "run": run["id"]})
        validate()
    record("controller-complete", observed=observed)
    finish()


if __name__ == "__main__":
    main()
