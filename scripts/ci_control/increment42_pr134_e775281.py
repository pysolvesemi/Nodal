"""Dispatch only the pinned Increment 42 native-hierarchy targeted checks.

This controller does not execute feature source, publish source, or merge.
A successful controller is a dispatch receipt, never compiler qualification.
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
BASE = "c24207e47e012d8704da5d6d8e650914b2804f28"
BASE_TREE = "2528eeac3a23563ec931e737afec03a80b7e6cd1"
REF = "increment/42-analog-hierarchy"
HEAD = "e775281338502b4aea9885796baa5fa0908de1fd"
TREE = "07b29d5d89ed09b3b90a617210ab50aa4aed3940"
PARENT = "17394368c3ab57bcf88e7b551407690754a4b166"
CONTROL = "ci-control/nodal-42-pr134-e775281-targeted-v3"
WORKFLOWS = (
    (338626156, ".github/workflows/ci.yml",
     "52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124"),
    (342183625, ".github/workflows/increment-21-native-semantic-pipeline.yml",
     "3a26ba42e2140b3f8802a7dec8572e01f97ba2fd9fb87939beef6aa42b125931"),
    (342322970, ".github/workflows/increment-22-cross-layer-diagnostics.yml",
     "ab2e6d0e6bcc8b10ad69bc88b89b2398090346b7c13c932b3fa231bc93d53cbf"),
    (342865392, ".github/workflows/increment-23-backend-framework.yml",
     "6451dee023216644c33748ad149d4d636a95ab5c7fe5c0606d07c1eb4f812087"),
    (352989730, ".github/workflows/increment-41-analog-functions.yml",
     "70d0b2d803180a2104015e3706fb0de7b3617c52a5f46ada2b3a69c863b55ff4"),
)
PRIOR_RUNS = ((35825288843, 338626156), (35825298112, 352989730))
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
    LEDGER.parent.mkdir(exist_ok=True)
    with LEDGER.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"event": event, "time": time.time(), **fields},
                                sort_keys=True) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def request(path, payload=None):
    require(DEADLINE is None or time.monotonic() < DEADLINE, "controller deadline exceeded")
    require(path.startswith("/") and "\\" not in path and ".." not in path,
            "invalid relative repository API path")
    if payload is not None:
        allowed = {f"/actions/workflows/{number}/dispatches" for number, _, _ in WORKFLOWS}
        require(path in allowed and payload == {"ref": REF}, "write outside dispatch allowlist")
    else:
        require(path.startswith(("/git/ref/heads/", "/git/commits/", "/pulls/",
                                 "/contents/.github/workflows/", "/actions/workflows/",
                                 "/actions/runs/")), "read outside repository allowlist")
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(API + path, data=data, headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Nodal-42-PR134-e775281-targeted-v3",
    }, method="GET" if payload is None else "POST")
    with urllib.request.build_opener(NoRedirect).open(req, timeout=30) as response:
        raw = response.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "API response exceeds bounded inventory size")
        if payload is not None:
            require(response.status == 204, "unexpected dispatch response; reconcile before retry")
        return json.loads(raw) if raw else None


def validate_prior():
    # Prior targeting only establishes the starting checkpoint, not new-head test credit.
    for number, workflow in PRIOR_RUNS:
        run = request(f"/actions/runs/{number}")
        require(run["id"] == number and run["workflow_id"] == workflow,
                "prior targeting workflow identity mismatch")
        require(run["head_sha"] == PARENT and run["head_branch"] == REF
                and run["event"] == "workflow_dispatch"
                and run["status"] == "completed" and run["conclusion"] == "success",
                "prior targeting checkpoint changed")


def validate(definitions=True):
    require(request("/git/ref/heads/" + BASE_REF)["object"]["sha"] == BASE, "base moved")
    require(request("/git/ref/heads/" + REF)["object"]["sha"] == HEAD, "candidate moved")
    require(request("/git/commits/" + BASE)["tree"]["sha"] == BASE_TREE, "base tree mismatch")
    commit = request("/git/commits/" + HEAD)
    require(commit["tree"]["sha"] == TREE, "candidate tree mismatch")
    require([p["sha"] for p in commit["parents"]] == [PARENT], "candidate ancestry changed")
    pr = request(f"/pulls/{PR}")
    require(pr["state"] == "open" and not pr["merged"], "PR is no longer open")
    require(pr["base"]["ref"] == BASE_REF and pr["base"]["sha"] == BASE, "PR base mismatch")
    require(pr["head"]["ref"] == REF and pr["head"]["sha"] == HEAD, "PR head mismatch")
    require(pr["base"]["repo"]["full_name"] == REPO
            and pr["head"]["repo"]["full_name"] == REPO, "PR repository mismatch")
    if not definitions:
        return
    for number, path, digest in WORKFLOWS:
        workflow = request(f"/actions/workflows/{number}")
        require(workflow["id"] == number and workflow["path"] == path
                and workflow["state"] == "active", "workflow registration mismatch")
        content = request("/contents/" + path + "?ref=" + HEAD)
        require(content["encoding"] == "base64", "unexpected workflow encoding")
        text = base64.b64decode(content["content"]).decode("utf-8")
        require(hashlib.sha256(text.encode()).hexdigest() == digest, "workflow bytes changed")
        require("\n  workflow_dispatch:" in text, "workflow dispatch trigger missing")


def runs(number):
    require(number in {n for n, _, _ in WORKFLOWS}, "unknown workflow inventory")
    found = []
    branch = urllib.parse.quote(REF, safe="")
    for page in range(1, 21):
        result = request(f"/actions/workflows/{number}/runs?branch={branch}&per_page=100&page={page}")
        rows = result["workflow_runs"]
        for run in rows:
            require(run["workflow_id"] == number
                    and run["head_repository"]["full_name"] == REPO, "run repository/ID mismatch")
            if run["head_sha"] == HEAD and run["head_branch"] == REF:
                require(run["event"] == "workflow_dispatch", "unexpected same-head event/context")
                found.append(run)
            elif run["status"] != "completed" and run["head_branch"] == REF:
                raise RuntimeError("other-head run is active; avoid concurrency cancellation")
        if len(rows) < 100:
            return found
    raise RuntimeError("run pagination bound reached; dispatch blocked")


def dispatch_one(number, path, digest):
    validate()
    existing = runs(number)
    if existing:
        record("retained", workflow=number, runs=[r["id"] for r in existing])
        require(all(r["status"] != "completed" or r["conclusion"] == "success"
                    for r in existing), "existing unsuccessful run requires diagnosis, not duplicate")
        return
    require(os.environ["GITHUB_RUN_ATTEMPT"] == "1",
            "controller rerun cannot POST an absent uncertain dispatch")
    record("intent", workflow=number, path=path, sha256=digest, payload={"ref": REF})
    uncertain = False
    try:
        request(f"/actions/workflows/{number}/dispatches", {"ref": REF})
        record("requested", workflow=number)
    except Exception as error:
        uncertain = True
        record("uncertain-response", workflow=number, error_type=type(error).__name__,
               http_status=getattr(error, "code", None))
    observed = []
    for attempt in range(12):
        validate(definitions=False)
        observed = runs(number)
        if observed:
            break
        if attempt < 11:
            time.sleep(5)
    record("observed", workflow=number, runs=[r["id"] for r in observed], uncertain=uncertain)
    require(len(observed) == 1, "missing/ambiguous dispatch; no automatic POST retry permitted")
    require(observed[0]["head_sha"] == HEAD and observed[0]["event"] == "workflow_dispatch",
            "dispatched source/event mismatch")


def main():
    global DEADLINE
    DEADLINE = time.monotonic() + 600
    require(os.environ["GITHUB_REPOSITORY"] == REPO, "controller repository mismatch")
    require(os.environ["GITHUB_REF"] == "refs/heads/" + CONTROL, "controller branch mismatch")
    require(os.environ["GITHUB_EVENT_NAME"] == "push", "controller event mismatch")
    controller = os.environ["GITHUB_SHA"]
    require(request("/git/ref/heads/" + CONTROL)["object"]["sha"] == controller,
            "controller ref moved")
    require([p["sha"] for p in request("/git/commits/" + controller)["parents"]] == [BASE],
            "controller must start directly from pinned dev")
    record("controller-start", candidate=HEAD, tree=TREE, base=BASE, pr=PR,
           controller=controller, attempt=os.environ["GITHUB_RUN_ATTEMPT"])
    validate_prior()
    validate()
    for number, path, digest in WORKFLOWS:
        dispatch_one(number, path, digest)
    validate(definitions=False)
    record("dispatch-complete-not-qualification", candidate=HEAD)


if __name__ == "__main__":
    main()
