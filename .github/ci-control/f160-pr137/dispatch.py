#!/usr/bin/env python3
"""One-shot, pinned F160/PR137 targeted dispatch. Never executes candidate code."""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = "pysolvesemi/Nodal"
REPO_ID = 1340301335
PR = 137
BASE = "cafd52e5b7ea0d63b1eb503d281cf801bedd6808"
BASE_TREE = "f14dc1e38e08c07243fda52e61378b95c7bd19d9"
INTEGRATION = "60d56be8dfcd0835ef6ddfa85cf9189dfdb1bb68"
INTEGRATION_TREE = "daba8506744845bb59aefd8d3442477c0809bfea"
FEATURE = "increment/160-construction-frontend-modularization"
PRIOR_FAILURE = {
    "run_id": 36530686521,
    "workflow_id": 338626156,
    "workflow_path": ".github/workflows/ci.yml",
    "run_attempt": 1,
    "head_sha": "9f2fcda2b91688191aac8210ef10edc1f71879a4",
    "tree_sha": "fccc0b2510b4f88147e23eab44da6d0ee8cf24d7",
    "head_branch": FEATURE,
    "event": "workflow_dispatch",
    "status": "completed",
    "conclusion": "failure",
    "job": {
        "id": 109283469607,
        "name": "native",
        "status": "completed",
        "conclusion": "failure",
        "steps": [
            {"number": 6, "name": "Configure, build, lint, and test native core", "status": "completed", "conclusion": "success"},
            {"number": 7, "name": "Compare Foundation 160 construction with the accepted baseline", "status": "completed", "conclusion": "failure"},
            {"number": 8, "name": "Retain Foundation 160 comparison evidence", "status": "completed", "conclusion": "success"},
        ],
    },
    "artifact": {
        "id": 11019085661,
        "name": "foundation160-parity-9f2fcda2b91688191aac8210ef10edc1f71879a4-1",
        "size_in_bytes": 13528865,
        "sha256": "46b15bde8e01b711fb08a511b97f7003d3ab13ba50f8a4499319d7e1d372ed84",
    },
}
WORKFLOW = ".github/workflows/f160-pr137-dispatch.yml"
SCRIPT = ".github/ci-control/f160-pr137/dispatch.py"
MANIFEST = ".github/ci-control/f160-pr137/manifest.json"
CONTROL_FILES = {WORKFLOW, SCRIPT, MANIFEST}
ALLOWED_WORKFLOWS = {
    ".github/workflows/ci.yml": 338626156,
    ".github/workflows/increment-26-reproducibility-contract.yml": 343773940,
    ".github/workflows/increment-36-time-waveform-operators.yml": 350704824,
    ".github/workflows/increment-37-analog-events.yml": 350854514,
    ".github/workflows/increment-38-mathematical-functions.yml": 351939507,
    ".github/workflows/increment-39-noise-operators.yml": 352225076,
    ".github/workflows/increment-40-transfer-operators.yml": 352544947,
    ".github/workflows/increment-41-analog-functions.yml": 352989730,
}
ROOT = f"/repos/{REPO}"
API_ORIGIN = "https://api.github.com"
ACTIVE = {"queued", "requested", "waiting", "pending", "in_progress"}
CONCLUSIONS = {"success", "failure", "cancelled", "timed_out", "action_required", "neutral", "skipped", "stale", "startup_failure"}
MAX_PAGES = 10  # GitHub filtered run searches expose at most 1000 results.
MAX_RESPONSE = 4 * 1024 * 1024
REQUEST_SECONDS = 20
TOTAL_SECONDS = 900
OBSERVE_SECONDS = 90
POLL_SECONDS = 3


class Closed(RuntimeError):
    """A failed invariant; callers must stop further writes."""


class Rejected(Closed):
    pass


class Uncertain(Closed):
    pass


def require(condition, message):
    if not condition:
        raise Closed(message)


def exact_keys(value, keys, label):
    require(isinstance(value, dict) and set(value) == set(keys), f"unexpected {label} fields")


def hex_digest(value, length):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{%d}" % length, value) is not None


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def decode_json(data):
    try:
        return json.loads(data, object_pairs_hook=no_duplicates)
    except (ValueError, UnicodeError) as error:
        raise Closed("invalid JSON") from error


def validate_manifest(m):
    exact_keys(m, {"schema", "phase", "repository", "repository_id", "pull_request", "base", "integration", "candidate", "default_branch", "controller", "workflows", "prior_failure"}, "manifest")
    require(type(m["schema"]) is int and m["schema"] == 1 and m["phase"] == "targeted", "unsupported schema or qualification phase")
    require(m["repository"] == REPO and m["repository_id"] == REPO_ID and m["pull_request"] == PR, "repository/PR scope differs")
    exact_keys(m["base"], {"ref", "sha", "tree"}, "base")
    require(m["base"] == {"ref": "dev", "sha": BASE, "tree": BASE_TREE}, "base pin differs from reviewed F160 base")
    exact_keys(m["integration"], {"ref", "sha", "tree"}, "integration")
    require(m["integration"] == {"ref": "dev", "sha": INTEGRATION, "tree": INTEGRATION_TREE},
            "live integration pin differs")
    exact_keys(m["candidate"], {"ref", "sha", "tree"}, "candidate")
    c = m["candidate"]
    require(c["ref"] == FEATURE and hex_digest(c["sha"], 40) and hex_digest(c["tree"], 40), "candidate must be fully pinned")
    require(c["sha"] != BASE, "candidate must advance the base")
    require(canonical(m["prior_failure"]) == canonical(PRIOR_FAILURE), "prior failure must match the exact reviewed run/job/artifact contract")
    exact_keys(m["default_branch"], {"ref", "sha", "tree"}, "default branch")
    d = m["default_branch"]
    require(d["ref"] == "main" and hex_digest(d["sha"], 40) and hex_digest(d["tree"], 40), "default-branch audit must be pinned")
    exact_keys(m["controller"], {"ref", "script_sha256", "workflow_sha256"}, "controller")
    require(m["controller"]["ref"] == f"ci-control/f160-pr137-{c['sha'][:12]}-r2",
            "controller identity differs")
    for key in ("script_sha256", "workflow_sha256"):
        require(hex_digest(m["controller"][key], 64), "controller files must be pinned after review")
    workflows = m["workflows"]
    require(isinstance(workflows, list) and 1 <= len(workflows) <= len(ALLOWED_WORKFLOWS), "bounded nonempty targeted set required")
    ids, paths = set(), set()
    for w in workflows:
        exact_keys(w, {"id", "path", "sha256", "inputs", "payload"}, "workflow")
        require(type(w["id"]) is int and w["id"] > 0 and w["id"] not in ids, "invalid or duplicate workflow ID")
        require(isinstance(w["path"], str) and re.fullmatch(r"\.github/workflows/[a-z0-9-]+\.ya?ml", w["path"]) is not None, "invalid workflow path")
        require(w["path"] not in paths and w["path"] != WORKFLOW, "duplicate or self-dispatch workflow")
        require(ALLOWED_WORKFLOWS.get(w["path"]) == w["id"], "workflow is outside the reviewed F160 targeted surface")
        require(hex_digest(w["sha256"], 64), "workflow definition must be fully pinned")
        require(w["inputs"] == {}, "only reviewed input-free workflows are supported")
        require(w["payload"] == {"ref": FEATURE}, "only the exact candidate-ref payload is allowed")
        ids.add(w["id"])
        paths.add(w["path"])
    return m


def validate_repair_scope(m):
    # The shared dispatch engine retains its adversarial two-target controls;
    # this production entry point authorizes exactly one fresh Core launch.
    require(len(m["workflows"]) == 1 and m["workflows"][0]["id"] == PRIOR_FAILURE["workflow_id"] and m["workflows"][0]["path"] == PRIOR_FAILURE["workflow_path"], "repair controller permits only one Core workflow")
    require(m["candidate"]["sha"] != PRIOR_FAILURE["head_sha"], "repair candidate must differ from the original failed head")


def bare_dispatch(content):
    """Conservative check for the reviewed targets' bare dispatch declaration."""
    lines = content.decode("utf-8").splitlines()
    top = [i for i, line in enumerate(lines) if re.fullmatch(r"(?:on|'on'|\"on\"):\s*(?:#.*)?", line)]
    require(len(top) == 1, "expected one explicit workflow event mapping")
    start = top[0] + 1
    end = next((i for i in range(start, len(lines)) if lines[i] and not lines[i][0].isspace() and not lines[i].startswith("#")), len(lines))
    hits = [i for i in range(start, end) if re.fullmatch(r"  workflow_dispatch:\s*(?:#.*)?", lines[i])]
    require(len(hits) == 1, "reviewed workflow must have one bare workflow_dispatch event")
    for line in lines[hits[0] + 1:end]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        require(len(line) - len(line.lstrip()) <= 2, "dispatch inputs or nested dispatch configuration are not allowed")
        break


class Journal:
    def __init__(self, directory):
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        self.path = directory / "ledger.jsonl"
        require(not self.path.exists(), "refusing to overwrite an existing intent ledger")
        self.stream = self.path.open("x", encoding="utf-8")
        self.previous, self.sequence = "0" * 64, 0

    def emit(self, event, **data):
        self.sequence += 1
        entry = {"sequence": self.sequence, "utc": dt.datetime.now(dt.timezone.utc).isoformat(), "event": event, "data": data, "previous_sha256": self.previous}
        self.previous = sha256(canonical(entry))
        entry["sha256"] = self.previous
        text = canonical(entry).decode()
        self.stream.write(text + "\n")
        self.stream.flush()
        os.fsync(self.stream.fileno())  # Intent is durable before the POST starts.
        print("F160_LEDGER " + text, flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None  # Never forward the job token through a redirect.


class Api:
    def __init__(self, manifest, token, *, clock=time.monotonic):
        require(isinstance(token, str) and bool(token), "runner job token is absent")
        self.m, self.token, self.clock = manifest, token, clock
        self.deadline, self.requests = clock() + TOTAL_SECONDS, 0
        self.opener = urllib.request.build_opener(NoRedirect())
        self.posts = set()

    def check_route(self, method, path, query, payload):
        require(isinstance(path, str) and path.startswith(ROOT) and "?" not in path and "#" not in path, "unexpected API destination")
        require(urllib.parse.urlsplit(API_ORIGIN + path).netloc == "api.github.com", "unexpected API host")
        ids = {w["id"] for w in self.m["workflows"]}
        if method == "POST":
            allowed = {f"{ROOT}/actions/workflows/{w['id']}/dispatches": w["payload"] for w in self.m["workflows"]}
            require(path in allowed and not query and payload == allowed[path], "write is outside exact dispatch allowlist")
            return
        require(method == "GET" and payload is None, "only GET and exact workflow dispatch POST are permitted")
        refs = {self.m[k]["ref"] for k in ("integration", "candidate", "default_branch", "controller")}
        allowed = {ROOT, f"{ROOT}/pulls/{PR}"}
        allowed |= {
            f"{ROOT}/actions/runs/{PRIOR_FAILURE['run_id']}",
            f"{ROOT}/actions/jobs/{PRIOR_FAILURE['job']['id']}",
            f"{ROOT}/actions/artifacts/{PRIOR_FAILURE['artifact']['id']}",
        }
        allowed |= {f"{ROOT}/git/ref/heads/{urllib.parse.quote(ref, safe='')}" for ref in refs}
        if path in allowed:
            require(not query, "unexpected query on identity endpoint")
            return
        if path == f"{ROOT}/pulls":
            require(query == {"head": "pysolvesemi:" + self.m["controller"]["ref"], "state": "open", "per_page": 100, "page": 1}, "unexpected controller-PR query")
            return
        if re.fullmatch(re.escape(ROOT) + r"/git/commits/[0-9a-f]{40}", path):
            require(not query, "unexpected commit query")
            return
        if re.fullmatch(re.escape(ROOT) + r"/compare/[0-9a-f]{40}\.\.\.[0-9a-f]{40}", path):
            require(not query, "unexpected comparison query")
            return
        prefix = f"{ROOT}/contents/"
        if path.startswith(prefix):
            allowed_paths = CONTROL_FILES | {w["path"] for w in self.m["workflows"]}
            require(path[len(prefix):] in allowed_paths and set(query) == {"ref"} and hex_digest(query["ref"], 40), "unexpected content read")
            return
        for workflow_id in ids:
            if path == f"{ROOT}/actions/workflows/{workflow_id}":
                require(not query, "unexpected workflow query")
                return
            if path == f"{ROOT}/actions/workflows/{workflow_id}/runs":
                same_head = {"event": "workflow_dispatch", "branch": FEATURE, "head_sha": self.m["candidate"]["sha"], "per_page": 100, "page": query.get("page")}
                branch_guard = {"branch": FEATURE, "per_page": 100, "page": query.get("page")}
                require(query in (same_head, branch_guard), "unexpected run query")
                require(type(query["page"]) is int and 1 <= query["page"] <= MAX_PAGES, "unbounded run pagination")
                return
        raise Closed("GET endpoint is outside the controller read surface")

    def request(self, method, path, *, query=None, payload=None):
        query = query or {}
        self.check_route(method, path, query, payload)
        if method == "POST":
            validate_repair_scope(self.m)
        require(self.clock() < self.deadline and self.requests < 600, "controller request/time budget exhausted")
        self.requests += 1
        if method == "POST":
            require(path not in self.posts, "automatic dispatch retry is forbidden")
            self.posts.add(path)
        url = API_ORIGIN + path + ("?" + urllib.parse.urlencode(query) if query else "")
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Nodal-F160-PR137-pinned-dispatch"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, method=method, headers=headers, data=None if payload is None else canonical(payload))
        # Socket timeouts alone do not bound a response that keeps trickling bytes.
        def expired(_signum, _frame):
            raise TimeoutError("bounded GitHub request expired")
        old_handler = signal.signal(signal.SIGALRM, expired)
        old_timer = signal.setitimer(signal.ITIMER_REAL, min(REQUEST_SECONDS, max(0.1, self.deadline - self.clock())))
        try:
            with self.opener.open(req, timeout=min(REQUEST_SECONDS, max(0.1, self.deadline - self.clock()))) as response:
                data = response.read(MAX_RESPONSE + 1)
                if len(data) > MAX_RESPONSE:
                    error_type = Uncertain if method == "POST" else Closed
                    raise error_type("API response exceeds bound")
                status = response.status
                links = response.headers.get("Link", "")
        except urllib.error.HTTPError as error:
            if method == "POST" and error.code in {400, 401, 403, 404, 405, 409, 410, 422}:
                raise Rejected(f"dispatch rejected with HTTP {error.code}; no retry") from None
            error_type = Uncertain if method == "POST" else Closed
            raise error_type(f"{method} returned HTTP {error.code}; stop and reconcile") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            error_type = Uncertain if method == "POST" else Closed
            raise error_type(f"{method} transport did not yield an authoritative result") from None
        finally:
            signal.setitimer(signal.ITIMER_REAL, *old_timer)
            signal.signal(signal.SIGALRM, old_handler)
        if method == "POST":
            if status != 204:
                raise Uncertain(f"unexpected dispatch HTTP {status}; reconcile before any further write")
            return None, links
        require(status == 200, "unexpected GET status")
        return decode_json(data), links

    def get(self, path, **query):
        return self.request("GET", path, query=query)

    def post(self, workflow):
        return self.request("POST", f"{ROOT}/actions/workflows/{workflow['id']}/dispatches", payload=workflow["payload"])


def file_bytes(api, path, ref):
    obj, _ = api.get(f"{ROOT}/contents/{path}", ref=ref)
    require(isinstance(obj, dict) and obj.get("type") == "file" and obj.get("path") == path and obj.get("encoding") == "base64", "unexpected workflow/source response")
    require(type(obj.get("size")) is int and 0 < obj["size"] <= 512 * 1024, "unexpected source size")
    try:
        data = base64.b64decode("".join(obj["content"].split()), validate=True)
    except (ValueError, TypeError, KeyError):
        raise Closed("invalid source content encoding") from None
    require(len(data) == obj["size"], "source response size differs")
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    require(blob == obj.get("sha"), "source content does not match Git blob identity")
    return data


def verify_live(api, m, control_sha, *, deep=False):
    repository, _ = api.get(ROOT)
    require(repository.get("id") == REPO_ID and repository.get("full_name") == REPO and repository.get("default_branch") == m["default_branch"]["ref"], "live repository identity differs")
    prs, links = api.get(f"{ROOT}/pulls", head="pysolvesemi:" + m["controller"]["ref"], state="open", per_page=100, page=1)
    require(prs == [] and not links, "controller branch has an open PR or ambiguous PR listing")
    for key in ("integration", "candidate", "default_branch", "controller"):
        value = m[key]
        expected = control_sha if key == "controller" else value["sha"]
        result, _ = api.get(f"{ROOT}/git/ref/heads/{urllib.parse.quote(value['ref'], safe='')}")
        require(result.get("ref") == "refs/heads/" + value["ref"] and result.get("object", {}).get("type") == "commit" and result["object"].get("sha") == expected, f"live {key} ref moved")
    pr, _ = api.get(f"{ROOT}/pulls/{PR}")
    require(pr.get("number") == PR and pr.get("state") == "open" and pr.get("merged") is False, "PR is not the intended open unmerged increment")
    for key in ("base", "head"):
        expected = m["base" if key == "base" else "candidate"]
        side = pr.get(key, {})
        require(side.get("sha") == expected["sha"] and side.get("ref") == expected["ref"] and side.get("repo", {}).get("id") == REPO_ID and side["repo"].get("full_name") == REPO, "PR ref/repository context changed")
    if not deep:
        return
    for key in ("base", "integration", "candidate", "default_branch"):
        commit, _ = api.get(f"{ROOT}/git/commits/{m[key]['sha']}")
        require(commit.get("sha") == m[key]["sha"] and commit.get("tree", {}).get("sha") == m[key]["tree"], f"{key} commit/tree pin differs")
    candidate, _ = api.get(f"{ROOT}/compare/{BASE}...{m['candidate']['sha']}")
    require(candidate.get("status") == "ahead" and candidate.get("merge_base_commit", {}).get("sha") == BASE and candidate.get("base_commit", {}).get("sha") == BASE, "candidate does not preserve audited base ancestry")
    control, _ = api.get(f"{ROOT}/git/commits/{control_sha}")
    require(control.get("sha") == control_sha and [p.get("sha") for p in control.get("parents", [])] == [INTEGRATION],
            "controller must be a direct child of live integration")
    require(not re.search(r"\[(?:skip ci|ci skip|skip actions|actions skip)\]|skip-checks:\s*true", control.get("message", ""), re.I), "controller launch commit suppresses CI")
    diff, _ = api.get(f"{ROOT}/compare/{INTEGRATION}...{control_sha}")
    require(diff.get("status") == "ahead" and diff.get("ahead_by") == 1 and diff.get("behind_by") == 0 and diff.get("total_commits") == 1, "controller ancestry/scope changed")
    require(diff.get("base_commit", {}).get("sha") == INTEGRATION and
            diff.get("merge_base_commit", {}).get("sha") == INTEGRATION,
            "controller comparison base differs")
    require([x.get("sha") for x in diff.get("commits", [])] == [control_sha], "unexpected controller commit list")
    files = diff.get("files", [])
    require(len(files) == len(CONTROL_FILES) and {f.get("filename") for f in files} == CONTROL_FILES and all(f.get("status") == "added" for f in files), "controller changes files outside its reviewed three-file scope")


def verify_workflow(api, m, workflow):
    metadata, _ = api.get(f"{ROOT}/actions/workflows/{workflow['id']}")
    require(metadata.get("id") == workflow["id"] and metadata.get("path") == workflow["path"] and metadata.get("state") == "active", "workflow ID/path/registration differs")
    data = file_bytes(api, workflow["path"], m["candidate"]["sha"])
    require(sha256(data) == workflow["sha256"], "candidate workflow definition differs from reviewed bytes")
    bare_dispatch(data)


def verify_prior_failure(api, m):
    """Bind root's reviewed artifact bytes to the unchanged original failure.

    Only metadata is read here. The pinned digest identifies the archive whose
    failure contents root already inspected; no authenticated download occurs.
    """
    p = m["prior_failure"]

    def fields(actual, expected, label):
        require(isinstance(actual, dict) and canonical({key: actual.get(key) for key in expected}) == canonical(expected), f"prior {label} metadata differs")

    def get(path):
        value, links = api.get(path)
        require(not links, "unexpected pagination on prior failure metadata")
        return value

    def check_run():
        run = get(f"{ROOT}/actions/runs/{p['run_id']}")
        expected = {key: p[key] for key in ("workflow_id", "run_attempt", "head_sha", "head_branch", "event", "status", "conclusion")}
        fields(run, {"id": p["run_id"], "path": p["workflow_path"], **expected}, "run")
        for side in ("repository", "head_repository"):
            fields(run.get(side), {"id": REPO_ID, "full_name": REPO}, "run repository")

    check_run()
    job = get(f"{ROOT}/actions/jobs/{p['job']['id']}")
    fields(job, {key: value for key, value in p["job"].items() if key != "steps"} | {
        "run_id": p["run_id"], "run_attempt": p["run_attempt"], "head_sha": p["head_sha"], "head_branch": p["head_branch"]}, "job")
    steps = job.get("steps")
    require(isinstance(steps, list) and len(steps) <= 50 and all(isinstance(step, dict) and type(step.get("number")) is int for step in steps), "prior job step inventory differs")
    require(len({step["number"] for step in steps}) == len(steps), "prior job has duplicate step identities")
    for expected in p["job"]["steps"]:
        found = [step for step in steps if step["number"] == expected["number"] or step.get("name") == expected["name"]]
        require(len(found) == 1, "prior job required step is missing or ambiguous")
        fields(found[0], expected, "job step")
    a = p["artifact"]
    artifact = get(f"{ROOT}/actions/artifacts/{a['id']}")
    fields(artifact, {key: a[key] for key in ("id", "name", "size_in_bytes")} | {"digest": "sha256:" + a["sha256"], "expired": False}, "artifact")
    fields(artifact.get("workflow_run"), {"id": p["run_id"], "repository_id": REPO_ID, "head_repository_id": REPO_ID, "head_sha": p["head_sha"], "head_branch": p["head_branch"]}, "artifact run")
    commit = get(f"{ROOT}/git/commits/{p['head_sha']}")
    fields(commit, {"sha": p["head_sha"]}, "source commit")
    fields(commit.get("tree"), {"sha": p["tree_sha"]}, "source tree")
    check_run()  # A rerun begun during the other metadata reads also closes us.


def matching_runs(api, m, workflow, *, all_branch=False):
    runs, seen = [], set()
    last_total = None
    for page in range(1, MAX_PAGES + 1):
        query = {"branch": FEATURE, "per_page": 100, "page": page}
        if not all_branch:
            query.update(event="workflow_dispatch", head_sha=m["candidate"]["sha"])
        result, link = api.get(f"{ROOT}/actions/workflows/{workflow['id']}/runs", **query)
        require(isinstance(result, dict) and type(result.get("total_count")) is int and isinstance(result.get("workflow_runs"), list), "unexpected workflow run page")
        total = result["total_count"]
        require(0 <= total <= MAX_PAGES * 100 and (last_total is None or total == last_total), "run listing changed or exceeded complete-pagination bound; stop before POST")
        last_total = total
        items = result["workflow_runs"]
        require(len(items) <= 100, "oversized workflow run page")
        for run in items:
            require(isinstance(run, dict) and type(run.get("id")) is int and run["id"] > 0 and run["id"] not in seen, "invalid or duplicate paginated run identity")
            seen.add(run["id"])
            require(run.get("workflow_id") == workflow["id"] and isinstance(run.get("event"), str) and (all_branch or run["event"] == "workflow_dispatch"), "run workflow/event differs from query")
            require(run.get("repository", {}).get("id") == REPO_ID and run["repository"].get("full_name") == REPO, "run repository differs")
            require(run.get("path") == workflow["path"], "run workflow path differs")
            require(run.get("status") in ACTIVE | {"completed"}, "unknown run status")
            require((run["status"] in ACTIVE and run.get("conclusion") is None) or (run["status"] == "completed" and run.get("conclusion") in CONCLUSIONS), "unexpected run conclusion")
            require(hex_digest(run.get("head_sha"), 40), "malformed run head")
            if all_branch or (run["head_sha"] == m["candidate"]["sha"] and run.get("head_branch") == FEATURE):
                runs.append(run)
        next_links = re.findall(r'<([^>]+)>;\s*rel="next"', link)
        require(len(next_links) <= 1, "ambiguous pagination link")
        if next_links:
            expected_query = {key: [str(value)] for key, value in query.items()}
            expected_query["page"] = [str(page + 1)]
            parsed = urllib.parse.urlsplit(next_links[0])
            require(parsed.scheme == "https" and parsed.netloc == "api.github.com" and parsed.path == f"{ROOT}/actions/workflows/{workflow['id']}/runs" and not parsed.fragment and urllib.parse.parse_qs(parsed.query, strict_parsing=True) == expected_query, "pagination redirect is outside exact run query")
            require(len(items) == 100 and page < MAX_PAGES, "incomplete or over-bound pagination")
        else:
            require(len(seen) == total, "missing run pages; refusing partial deduplication")
            return runs
    raise Closed("run pagination did not terminate")


def brief(run):
    return {k: run.get(k) for k in ("id", "run_attempt", "workflow_id", "event", "head_sha", "head_branch", "status", "conclusion", "html_url")}


def retained(runs):
    return [r for r in runs if r["status"] in ACTIVE or r["conclusion"] == "success"]


def preserve_other_active_runs(api, m, workflow):
    existing = matching_runs(api, m, workflow, all_branch=True)
    blockers = [r for r in existing if r["status"] in ACTIVE and (r["head_sha"] != m["candidate"]["sha"] or r["event"] != "workflow_dispatch")]
    require(not blockers, "other active workflow runs on candidate branch could be duplicated or cancelled by concurrency; stop before POST")


def run_dispatch(api, m, control_sha, journal, *, clock=time.monotonic, sleep=time.sleep):
    verify_live(api, m, control_sha, deep=True)
    verify_prior_failure(api, m)
    for workflow in m["workflows"]:
        verify_workflow(api, m, workflow)
    outcomes = []
    for workflow in m["workflows"]:
        verify_live(api, m, control_sha)
        verify_workflow(api, m, workflow)
        preserve_other_active_runs(api, m, workflow)
        existing = matching_runs(api, m, workflow)
        keep = retained(existing)
        if keep:
            journal.emit("retain", workflow_id=workflow["id"], runs=[brief(r) for r in keep], reason="same reviewed input-free definition, ref and head; no new POST")
            outcomes.append({"workflow_id": workflow["id"], "retained": [r["id"] for r in keep]})
            continue
        require(not existing, "same-head failed/cancelled/skipped runs require diagnosis and reviewed repair; controller will not blindly redispatch")
        # Close the expensive-validation/listing window before writing.
        verify_live(api, m, control_sha)
        verify_prior_failure(api, m)
        preserve_other_active_runs(api, m, workflow)
        existing = matching_runs(api, m, workflow)
        keep = retained(existing)
        if keep:
            journal.emit("retain_after_recheck", workflow_id=workflow["id"], runs=[brief(r) for r in keep])
            outcomes.append({"workflow_id": workflow["id"], "retained": [r["id"] for r in keep]})
            continue
        require(not existing, "new non-passing same-head run appeared; stop before dispatch")
        journal.emit("intent", workflow_id=workflow["id"], path=workflow["path"], definition_sha256=workflow["sha256"], payload=workflow["payload"], candidate_sha=m["candidate"]["sha"], candidate_tree=m["candidate"]["tree"], prior_run_ids=[], prior_failure=m["prior_failure"])
        uncertain = False
        try:
            api.post(workflow)
            journal.emit("acknowledged", workflow_id=workflow["id"], http_status=204)
        except Rejected as error:
            journal.emit("rejected", workflow_id=workflow["id"], reason=str(error))
            raise
        except Uncertain as error:
            uncertain = True
            journal.emit("uncertain", workflow_id=workflow["id"], reason=str(error), automatic_retry=False)
        until = clock() + OBSERVE_SECONDS
        observed = None
        while clock() < until:
            verify_live(api, m, control_sha)
            visible = matching_runs(api, m, workflow)
            require(len(visible) <= 1, "multiple same-head runs appeared after one intent; stop and reconcile")
            if visible:
                observed = visible[0]
                break
            sleep(min(POLL_SECONDS, max(0, until - clock())))
        require(observed is not None, "dispatch not observed within bound; uncertain intent remains open and MUST NOT be retried blindly")
        journal.emit("observed", workflow_id=workflow["id"], recovered_uncertain_response=uncertain, run=brief(observed), qualification_credit=False)
        outcomes.append({"workflow_id": workflow["id"], "observed": observed["id"]})
    journal.emit("dispatch_complete", outcomes=outcomes, qualification_credit=False)
    return outcomes


def validate_context(m, environment, event):
    c = m["controller"]["ref"]
    require(environment.get("GITHUB_REPOSITORY") == REPO and environment.get("GITHUB_EVENT_NAME") == "push" and environment.get("GITHUB_REF") == "refs/heads/" + c, "unexpected Actions launch context")
    require(environment.get("GITHUB_RUN_ATTEMPT") == "1", "controller reruns need explicit prior-intent reconciliation; automatic retries are not supported")
    require(environment.get("GITHUB_JOB") == "dispatch", "unexpected privileged job")
    control_sha = environment.get("GITHUB_SHA")
    require(hex_digest(control_sha, 40) and environment.get("GITHUB_WORKFLOW_SHA") == control_sha, "controller checkout/workflow SHA differs")
    require(environment.get("GITHUB_WORKFLOW_REF") == f"{REPO}/{WORKFLOW}@refs/heads/{c}", "controller workflow identity differs")
    require(event.get("repository", {}).get("id") == REPO_ID and event["repository"].get("full_name") == REPO and event.get("ref") == "refs/heads/" + c and event.get("after") == control_sha, "push payload identity differs")
    require(event.get("deleted") is False and event.get("forced") is False and event.get("before") in {"0" * 40, BASE}, "unexpected controller push ancestry/type")
    return control_sha


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path(MANIFEST))
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--execute", action="store_true", help="only usable in the exact controller push job")
    args = parser.parse_args(argv)
    journal = None
    try:
        manifest_bytes = args.manifest.read_bytes()
        require(len(manifest_bytes) <= 128 * 1024, "manifest exceeds bound")
        m = validate_manifest(decode_json(manifest_bytes))
        validate_repair_scope(m)
        require(sha256(Path(__file__).read_bytes()) == m["controller"]["script_sha256"], "local controller script differs from reviewed hash")
        require(sha256(Path(WORKFLOW).read_bytes()) == m["controller"]["workflow_sha256"], "local controller workflow differs from reviewed hash")
        if not args.execute:
            print("Pinned manifest and controller files validated; no network access or dispatch.")
            return 0
        require(args.ledger is not None, "durable ledger directory is required")
        event = decode_json(Path(os.environ["GITHUB_EVENT_PATH"]).read_bytes())
        control_sha = validate_context(m, os.environ, event)
        journal = Journal(args.ledger)
        (args.ledger / "manifest.json").write_bytes(manifest_bytes)
        journal.emit("start", repository=REPO, pull_request=PR, controller_sha=control_sha,
                     candidate=m["candidate"], base=m["base"], integration=m["integration"],
                     manifest_sha256=sha256(manifest_bytes),
                     script_sha256=m["controller"]["script_sha256"],
                     workflow_sha256=m["controller"]["workflow_sha256"])
        api = Api(m, os.environ.get("GITHUB_TOKEN"))
        require(file_bytes(api, MANIFEST, control_sha) == manifest_bytes, "checked-out manifest differs from controller commit")
        require(sha256(file_bytes(api, SCRIPT, control_sha)) == m["controller"]["script_sha256"], "remote controller script differs")
        require(sha256(file_bytes(api, WORKFLOW, control_sha)) == m["controller"]["workflow_sha256"], "remote controller workflow differs")
        run_dispatch(api, m, control_sha, journal)
        return 0
    except (Closed, OSError, KeyError, ValueError) as error:
        message = str(error) if isinstance(error, Closed) else f"invalid/missing local input ({type(error).__name__})"
        if journal is not None:
            journal.emit("stopped", reason=message, further_writes=False)
        print("F160-CONTROLLER-CLOSED: " + message, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
