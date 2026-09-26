"""Dispatch only the pinned Increment 37 and Core lifecycle checks.

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
HEAD = "e9d4534d18bbfc78ec1a8dbc76ef49d89be330b6"
TREE = "ba1e93124f4b894c1972a4a97d8ec4d1eac31406"
PARENT = "4a1e9400fec8daacfdd927e22102cacc5574657c"
PARENT_TREE = "f33a896608718ad0d1a472a4316ef2272627e408"
EVIDENCE_HEAD = "4a1e9400fec8daacfdd927e22102cacc5574657c"
PARENT_PARENT = "249cbff27116885835fd257facaeb89bc8441569"
CONTROL = "ci-control/nodal-42-pr134-e9d4534-lifecycle-v1"
CONTROL_WORKFLOW = ".github/workflows/ci-control-42-pr134-e9d4534.yml"
INVENTORY = Path("scripts/ci_control/increment42_pr134_e9d4534_inventory.json")
INVENTORY_SHA256 = "2ee84f8f97afd417e37604ee4652b11ca1f70029473a3bcb1082488e88baad38"
WORKFLOWS = (
    (350854514, '.github/workflows/increment-37-analog-events.yml',
     'd1688942b13fa071ff280a84dc67533942cb4b62834e9ec2c762e97903cadd8f', '872b0778c942ac6d74b16d295f4ca45bb8a36e7b'),
    (338626156, '.github/workflows/ci.yml',
     '18de8c738bde6197f0f55d865f15c3c1b158d38a9240bc1a0f0accb4451d54e1', '9dca0c07d90a8ed837e5204f016a8ef67ab8b27b'),
)
PRIOR_EVIDENCE = [{'run_id': 36258186895,
  'workflow_id': 338626156,
  'path': '.github/workflows/ci.yml',
  'run_attempt': 1,
  'expected_conclusion': 'success',
  'jobs': [{'id': 108448853392,
            'name': 'contracts',
            'conclusion': 'success',
            'steps': {'Validate contracts, style, and online provenance': 'success',
                      'Capture failed style repair for review': 'skipped',
                      'Preserve failed style repair evidence': 'skipped'}},
           {'id': 108448853215,
            'name': 'scala',
            'conclusion': 'success',
            'steps': {'Compile and test Scala core': 'success',
                      'Preserve constructor prototype evidence': 'success'}},
           {'id': 108448853323,
            'name': 'native',
            'conclusion': 'success',
            'steps': {'Configure, build, lint, and test native core': 'success'}},
           {'id': 108450635204,
            'name': 'required',
            'conclusion': 'success',
            'steps': {'Require every Core CI job': 'success',
                      'Publish aggregate push status': 'skipped'}}]},
 {'run_id': 36258194675,
  'workflow_id': 344284403,
  'path': '.github/workflows/increment-28-electrical-connectivity.yml',
  'run_attempt': 1,
  'expected_conclusion': 'success',
  'jobs': [{'id': 108448873943,
            'name': 'increment-28/electrical-connectivity',
            'conclusion': 'success',
            'steps': {'Validate contracts and mutation tests': 'success',
                      'Build lint and test native core': 'success',
                      'Prove deterministic topology and stable rejections': 'success'}}]}]
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
        "User-Agent": "Nodal-42-PR134-e9d4534-lifecycle-v1",
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
            and inventory["candidate_parent_tree"] == PARENT_TREE
            and inventory["evidence_head"] == EVIDENCE_HEAD
            and inventory["candidate_parent_parent"] == PARENT_PARENT
            and inventory["prior_evidence"] == PRIOR_EVIDENCE
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


def validate_prior_evidence():
    # This is diagnosis/launch sequencing evidence, never new-head qualification.
    for expected in PRIOR_EVIDENCE:
        number = expected["run_id"]
        run = request(f"/actions/runs/{number}")
        require(run["id"] == number and run["workflow_id"] == expected["workflow_id"]
                and run["path"] == expected["path"]
                and run["head_sha"] == EVIDENCE_HEAD and run["head_branch"] == REF
                and run["event"] == "workflow_dispatch"
                and run["run_attempt"] == expected["run_attempt"]
                and run["status"] == "completed" and run["conclusion"] == expected["expected_conclusion"],
                "prior evidence run identity/outcome changed or still active")
        jobs = []
        for page in range(1, 21):
            result = request(f"/actions/runs/{number}/jobs"
                             f"?filter=latest&per_page=100&page={page}")
            rows = result["jobs"]
            jobs.extend(rows)
            if len(rows) < 100:
                break
        else:
            raise RuntimeError("prior evidence job pagination bound reached")
        require(len(jobs) == len(expected["jobs"])
                and {(job["id"], job["name"]) for job in jobs}
                == {(job["id"], job["name"]) for job in expected["jobs"]},
                "prior evidence latest-attempt job set changed")
        by_id = {job["id"]: job for job in jobs}
        for witness in expected["jobs"]:
            job = by_id[witness["id"]]
            require(job["run_id"] == number and job["run_attempt"] == expected["run_attempt"]
                    and job["head_sha"] == EVIDENCE_HEAD and job["status"] == "completed"
                    and job["conclusion"] == witness["conclusion"],
                    "prior evidence job identity/outcome changed")
            steps = {step["name"]: step["conclusion"] for step in job["steps"]}
            require(all(steps.get(name) == outcome for name, outcome in witness["steps"].items()),
                    "prior evidence step outcomes changed")
        record("prior-evidence-identity-verified-not-qualification", run=number,
               jobs=[job["id"] for job in jobs], evidence_head=EVIDENCE_HEAD)


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
    parent = request("/git/commits/" + PARENT)
    require(parent["tree"]["sha"] == PARENT_TREE
            and [p["sha"] for p in parent["parents"]] == [PARENT_PARENT],
            "evidence parent ancestry/tree mismatch")
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
    validate_prior_evidence()
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
    validate_prior_evidence()
    validate_dispatch_inventory()
    for number, path, digest, blob in WORKFLOWS:
        dispatch_one(number, path, digest, blob)
    validate(definitions=False)
    record("dispatch-complete-not-qualification", candidate=HEAD)


if __name__ == "__main__":
    main()

