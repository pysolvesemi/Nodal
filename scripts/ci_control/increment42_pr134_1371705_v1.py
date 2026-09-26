"""Dispatch only the five pinned Increment 42 shared-static repair checks.

The controller executes only its reviewed script, never feature source.
It cannot publish source, rerun, cancel, merge, or launch full CI.
Its ledger is a dispatch receipt, never compiler qualification.
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
PR = 134
BASE_REF = "dev"
BASE = "c3ea7cbf0432de7143a08b4431d51708c307d67e"
BASE_TREE = "6edbf959d28104a1de62623e8b7e31170232bd64"
REF = "increment/42-analog-hierarchy"
HEAD = "137170547bdd68ad8388c36a31b95df86f98b3fa"
TREE = "e9c8f853f6251558cafb47a9940f8c63b138b8d1"
PARENT = "650a6710dc7d05082a9117339b0d4b86ce1ba3ab"
CONTROL = "ci-control/nodal-42-pr134-1371705-static-v1"
CONTROL_WORKFLOW = ".github/workflows/ci-control-42-pr134-1371705.yml"
INVENTORY = Path("scripts/ci_control/increment42_pr134_1371705_inventory.json")
INVENTORY_SHA256 = "1a4dab2b9a52774318cb0e20d3090a7458dd5ee628d8f43bfbcc965eb50780bc"
WORKFLOWS = (
    (338626156, '.github/workflows/ci.yml',
     '52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124', 'db695c972d0fb077cd355cf01d2c204afb6de40f'),
    (344342524, '.github/workflows/increment-29-parameters-units.yml',
     'f69d4c5e57791839e3654292d90c6cbea92760797914d93c564abe04b75f16b4', 'b05c9bac999a55b824584287502bd24b5395f026'),
    (350704824, '.github/workflows/increment-36-time-waveform-operators.yml',
     'a812d0e0b4b29330f05f14273b80b3d7da67850255ba2d2214351800beb66006', '2b04c8b29ae55c6cd63756cb33a96c10aa29ff6f'),
    (351939507, '.github/workflows/increment-38-mathematical-functions.yml',
     'dd20eb590e8b709e7686948f39055ae2c24a39293586602349778277feb883c0', '6c2ab4ecf2efcc5639f8ff88745961f9f817bfe5'),
    (352989730, '.github/workflows/increment-41-analog-functions.yml',
     '70d0b2d803180a2104015e3706fb0de7b3617c52a5f46ada2b3a69c863b55ff4', 'a037327d04463825d4584ddacd186f31becc0d17'),
)
PARENT_FAILURE_RUN = 36220692427
PARENT_FAILURE_JOB = 108345268733
API = f"https://api.github.com/repos/{REPO}"
LEDGER = Path("dispatch-evidence/ledger.jsonl")
MAX_BYTES = 16 * 1024 * 1024
DEADLINE = None


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("unexpected API redirect")


def record(event, **fields):
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    for directory in (LEDGER.parent.parent, LEDGER.parent):
        descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    with LEDGER.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"event": event, "time": time.time(), **fields},
                                sort_keys=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    descriptor = os.open(LEDGER.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def request(path, payload=None):
    require(DEADLINE is None or time.monotonic() < DEADLINE,
            "controller deadline exceeded")
    require(path.startswith("/") and "\\" not in path and ".." not in path,
            "invalid relative repository API path")
    if payload is not None:
        allowed = {f"/actions/workflows/{number}/dispatches" for number, _, _, _ in WORKFLOWS}
        require(path in allowed and payload == {"ref": REF},
                "write outside dispatch allowlist")
    else:
        require(path.startswith(("/git/ref/heads/", "/git/commits/", "/pulls/",
                                 "/contents/.github/workflows/", "/actions/workflows/",
                                 "/actions/runs/")),
                "read outside repository allowlist")
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(API + path, data=data, headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Nodal-42-PR134-1371705-static-v1",
    }, method="GET" if payload is None else "POST")
    timeout = 30 if DEADLINE is None else min(30, max(0.1, DEADLINE - time.monotonic()))
    with urllib.request.build_opener(NoRedirect).open(req, timeout=timeout) as response:
        raw = response.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "API response exceeds bounded inventory size")
        if payload is not None:
            require(response.status == 204,
                    "unexpected dispatch response; reconcile before retry")
        return json.loads(raw) if raw else None


def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def validate_trigger_isolation():
    require(INVENTORY.is_file() and not INVENTORY.is_symlink(),
            "controller inventory is not a regular file")
    inventory_bytes = INVENTORY.read_bytes()
    require(hashlib.sha256(inventory_bytes).hexdigest() == INVENTORY_SHA256,
            "controller inventory bytes changed")
    inventory = json.loads(inventory_bytes)
    require(inventory["repository"] == REPO and inventory["pr"] == PR
            and inventory["base"] == BASE and inventory["base_tree"] == BASE_TREE
            and inventory["candidate"] == HEAD and inventory["candidate_tree"] == TREE
            and inventory["candidate_parent"] == PARENT
            and inventory["controller_branch"] == CONTROL
            and inventory["controller_workflow"] == CONTROL_WORKFLOW,
            "controller inventory identity mismatch")
    expected = inventory["dev_workflows"]
    require(len(expected) == 43, "inherited trigger inventory count changed")
    directory = Path(".github/workflows")
    require(directory.is_dir() and not directory.is_symlink(),
            "workflow directory is not a real directory")
    actual = {str(path) for path in directory.iterdir()}
    require(actual == set(expected) | {CONTROL_WORKFLOW},
            "unexpected or missing controller-checkout workflow")
    for path, digest in expected.items():
        source = Path(path)
        require(source.is_file() and not source.is_symlink(),
                "inherited workflow is not a regular file")
        data = source.read_bytes()
        require(git_blob(data) == digest["blob"]
                and hashlib.sha256(data).hexdigest() == digest["sha256"],
                "inherited workflow trigger bytes changed: " + path)
    own = Path(CONTROL_WORKFLOW)
    require(own.is_file() and not own.is_symlink(),
            "controller workflow is not a regular file")
    require(hashlib.sha256(own.read_bytes()).hexdigest()
            == inventory["controller_workflow_sha256"],
            "controller workflow trigger bytes changed")
    declared = [(item["id"], item["path"], item["sha256"], item["blob"])
                for item in inventory["candidate_workflows"]]
    require(declared == list(WORKFLOWS)
            and all(item["payload"] == {"ref": REF}
                    for item in inventory["candidate_workflows"]),
            "candidate dispatch inventory mismatch")
    record("trigger-isolation-verified", inherited_workflows=len(expected),
           workflow=CONTROL_WORKFLOW, inventory_sha256=INVENTORY_SHA256)


def validate_parent_failure():
    run = request(f"/actions/runs/{PARENT_FAILURE_RUN}")
    require(run["id"] == PARENT_FAILURE_RUN and run["workflow_id"] == 344342524
            and run["head_sha"] == PARENT and run["head_branch"] == REF
            and run["event"] == "workflow_dispatch" and run["run_attempt"] == 1
            and run["status"] == "completed" and run["conclusion"] == "failure",
            "parent failure run identity/outcome changed")
    jobs = []
    for page in range(1, 21):
        result = request(f"/actions/runs/{PARENT_FAILURE_RUN}/jobs"
                         f"?filter=latest&per_page=100&page={page}")
        rows = result["jobs"]
        jobs.extend(rows)
        if len(rows) < 100:
            break
    else:
        raise RuntimeError("parent failure job pagination bound reached")
    require(len(jobs) == 1, "parent failure latest-attempt job set changed")
    job = jobs[0]
    require(job["id"] == PARENT_FAILURE_JOB and job["run_id"] == PARENT_FAILURE_RUN
            and job["name"] == "increment-29/parameters-units"
            and job["status"] == "completed" and job["conclusion"] == "failure",
            "parent failure job identity/outcome changed")
    steps = {step["name"]: step["conclusion"] for step in job["steps"]}
    require(steps.get("Validate contracts and mutation tests") == "failure"
            and steps.get("Build lint and test native core") == "skipped",
            "parent Scalafix failure step shape changed")
    record("parent-failure-identity-verified", run=PARENT_FAILURE_RUN,
           job=PARENT_FAILURE_JOB, candidate=PARENT,
           failed_step="Validate contracts and mutation tests")


def validate(definitions=True):
    require(request("/git/ref/heads/" + BASE_REF)["object"]["sha"] == BASE,
            "base moved")
    require(request("/git/ref/heads/" + REF)["object"]["sha"] == HEAD,
            "candidate moved")
    require(request("/git/commits/" + BASE)["tree"]["sha"] == BASE_TREE,
            "base tree mismatch")
    commit = request("/git/commits/" + HEAD)
    require(commit["tree"]["sha"] == TREE, "candidate tree mismatch")
    require([p["sha"] for p in commit["parents"]] == [PARENT],
            "candidate ancestry changed")
    pr = request(f"/pulls/{PR}")
    require(pr["state"] == "open" and not pr["merged"] and pr["draft"] is True,
            "PR must remain open, draft, and unmerged")
    require(pr["base"]["ref"] == BASE_REF and pr["base"]["sha"] == BASE,
            "PR base mismatch")
    require(pr["head"]["ref"] == REF and pr["head"]["sha"] == HEAD,
            "PR head mismatch")
    require(pr["base"]["repo"]["full_name"] == REPO
            and pr["head"]["repo"]["full_name"] == REPO,
            "PR repository mismatch")
    if not definitions:
        return
    for number, path, digest, blob in WORKFLOWS:
        workflow = request(f"/actions/workflows/{number}")
        require(workflow["id"] == number and workflow["path"] == path
                and workflow["state"] == "active",
                "workflow registration mismatch")
        content = request("/contents/" + path + "?ref=" + HEAD)
        require(content["encoding"] == "base64" and content["sha"] == blob,
                "unexpected workflow encoding/blob")
        data = base64.b64decode(content["content"])
        require(git_blob(data) == blob, "workflow blob content mismatch")
        text = data.decode("utf-8")
        require(hashlib.sha256(text.encode()).hexdigest() == digest,
                "workflow bytes changed")
        require("\n  workflow_dispatch:" in text,
                "workflow dispatch trigger missing")


def runs(number):
    require(number in {n for n, _, _, _ in WORKFLOWS}, "unknown workflow inventory")
    found = []
    branch = urllib.parse.quote(REF, safe="")
    for page in range(1, 21):
        result = request(
            f"/actions/workflows/{number}/runs?branch={branch}&per_page=100&page={page}")
        rows = result["workflow_runs"]
        for run in rows:
            require(run["workflow_id"] == number
                    and run["head_repository"]["full_name"] == REPO,
                    "run repository/ID mismatch")
            if run["head_sha"] == HEAD and run["head_branch"] == REF:
                require(run["event"] == "workflow_dispatch",
                        "unexpected same-head event/context")
                found.append(run)
            elif run["status"] != "completed" and run["head_branch"] == REF:
                raise RuntimeError(
                    "other-head run is active; avoid concurrency cancellation")
        if len(rows) < 100:
            return found
    raise RuntimeError("run pagination bound reached; dispatch blocked")


def validate_dispatch_inventory():
    # Inventory every target before the first POST so a later target's old run
    # cannot cause a partial launch and concurrency cancellation.
    for number, _, _, _ in WORKFLOWS:
        existing = runs(number)
        require(all(run["status"] != "completed" or run["conclusion"] == "success"
                    for run in existing),
                "existing unsuccessful run requires diagnosis, not duplicate")
        record("pre-dispatch-inventory", workflow=number,
               runs=[run["id"] for run in existing])


def dispatch_one(number, path, digest, blob):
    validate()
    existing = runs(number)
    if existing:
        record("retained", workflow=number, runs=[r["id"] for r in existing])
        require(all(r["status"] != "completed" or r["conclusion"] == "success"
                    for r in existing),
                "existing unsuccessful run requires diagnosis, not duplicate")
        return
    require(os.environ["GITHUB_RUN_ATTEMPT"] == "1",
            "controller rerun cannot POST an absent uncertain dispatch")
    record("intent", workflow=number, path=path, sha256=digest, blob=blob,
           payload={"ref": REF})
    uncertain = False
    try:
        request(f"/actions/workflows/{number}/dispatches", {"ref": REF})
        record("requested", workflow=number)
    except Exception as error:
        uncertain = True
        record("uncertain-response", workflow=number,
               error_type=type(error).__name__,
               http_status=getattr(error, "code", None))
    observed = []
    for attempt in range(12):
        validate(definitions=False)
        observed = runs(number)
        if observed:
            break
        if attempt < 11:
            time.sleep(5)
    record("observed", workflow=number, runs=[r["id"] for r in observed],
           uncertain=uncertain)
    require(len(observed) == 1,
            "missing/ambiguous dispatch; no automatic POST retry permitted")
    require(observed[0]["head_sha"] == HEAD
            and observed[0]["event"] == "workflow_dispatch"
            and observed[0]["head_branch"] == REF
            and observed[0]["workflow_id"] == number,
            "dispatched source/event mismatch")


def main():
    global DEADLINE
    DEADLINE = time.monotonic() + 600
    require(os.environ["GITHUB_REPOSITORY"] == REPO,
            "controller repository mismatch")
    require(os.environ["GITHUB_REF"] == "refs/heads/" + CONTROL,
            "controller branch mismatch")
    require(os.environ["GITHUB_EVENT_NAME"] == "push",
            "controller event mismatch")
    controller = os.environ["GITHUB_SHA"]
    require(request("/git/ref/heads/" + CONTROL)["object"]["sha"] == controller,
            "controller ref moved")
    require([p["sha"] for p in request("/git/commits/" + controller)["parents"]] == [BASE],
            "controller must start directly from pinned dev")
    record("controller-start", candidate=HEAD, tree=TREE, base=BASE, pr=PR,
            parent=PARENT, controller=controller,
           attempt=os.environ["GITHUB_RUN_ATTEMPT"])
    validate_trigger_isolation()
    validate()
    validate_parent_failure()
    validate_dispatch_inventory()
    for number, path, digest, blob in WORKFLOWS:
        dispatch_one(number, path, digest, blob)
    validate(definitions=False)
    record("dispatch-complete-not-qualification", candidate=HEAD)


if __name__ == "__main__":
    main()

