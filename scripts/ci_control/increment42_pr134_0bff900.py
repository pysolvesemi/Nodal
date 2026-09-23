"""Allowlisted, read-validated dispatch for Nodal PR 134 checkpoint 1 only."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = 'pysolvesemi/Nodal'
PR = 134
BASE_REF = 'dev'
BASE = 'c24207e47e012d8704da5d6d8e650914b2804f28'
BASE_TREE = '2528eeac3a23563ec931e737afec03a80b7e6cd1'
REF = 'increment/42-analog-hierarchy'
HEAD = '0bff9004582df2300a040db96808b61c9382d02e'
TREE = 'ee1c455d77c0dcb4e85bb8cf835de307be3efdf9'
WORKFLOWS = (
    (338626156, '.github/workflows/ci.yml',
     '52ee1f40bd55eebc97e699bdca854ab5b96a1a98159ef0f4b7a8f2b96f0dc124'),
    (352989730, '.github/workflows/increment-41-analog-functions.yml',
     'e493d46dfd20853dbe7cf18951b24598c52e06573e1a97d3c5779f9f99e8bb6d'),
)
API = f'https://api.github.com/repos/{REPO}'
LEDGER = Path('dispatch-evidence/ledger.jsonl')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('unexpected API redirect')


def record(event: str, **fields):
    LEDGER.parent.mkdir(exist_ok=True)
    with LEDGER.open('a', encoding='utf-8') as stream:
        stream.write(json.dumps({'event': event, 'time': time.time(), **fields}, sort_keys=True) + '\n')
        stream.flush()
        os.fsync(stream.fileno())


def request(path: str, payload: dict | None = None):
    # No ref, merge, cancellation, status or repository writes exist in this controller.
    if payload is not None:
        assert path in {f'/actions/workflows/{number}/dispatches' for number, _, _ in WORKFLOWS}
        assert payload == {'ref': REF}
    else:
        assert path.startswith(('/git/ref/heads/', '/git/commits/', '/pulls/',
                                '/contents/.github/workflows/', '/actions/workflows/'))
    assert path.startswith('/') and '\\' not in path and '..' not in path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'Nodal-42-PR134-0bff900-targeted-v1',
    }, method='POST' if payload is not None else 'GET')
    with urllib.request.build_opener(NoRedirect).open(req, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def validate(definitions: bool = True):
    assert request('/git/ref/heads/' + BASE_REF)['object']['sha'] == BASE, 'base moved'
    assert request('/git/ref/heads/' + REF)['object']['sha'] == HEAD, 'candidate moved'
    assert request('/git/commits/' + BASE)['tree']['sha'] == BASE_TREE, 'base tree mismatch'
    assert request('/git/commits/' + HEAD)['tree']['sha'] == TREE, 'candidate tree mismatch'
    pr = request(f'/pulls/{PR}')
    assert pr['state'] == 'open' and not pr['merged'], 'PR is not open'
    assert pr['base']['ref'] == BASE_REF and pr['base']['sha'] == BASE, 'PR base mismatch'
    assert pr['head']['ref'] == REF and pr['head']['sha'] == HEAD, 'PR head mismatch'
    assert pr['base']['repo']['full_name'] == REPO and pr['head']['repo']['full_name'] == REPO
    if not definitions:
        return
    for number, path, digest in WORKFLOWS:
        workflow = request(f'/actions/workflows/{number}')
        assert workflow['id'] == number and workflow['path'] == path and workflow['state'] == 'active'
        content = request('/contents/' + path + '?ref=' + HEAD)
        assert content['encoding'] == 'base64'
        text = base64.b64decode(content['content']).decode('utf-8')
        assert hashlib.sha256(text.encode()).hexdigest() == digest, 'workflow definition mismatch'
        assert '\n  workflow_dispatch:' in text, 'dispatch trigger missing'


def runs(number: int):
    found = []
    branch = urllib.parse.quote(REF, safe='')
    for page in range(1, 21):
        result = request(f'/actions/workflows/{number}/runs?branch={branch}&per_page=100&page={page}')
        rows = result['workflow_runs']
        for run in rows:
            assert run['workflow_id'] == number and run['head_repository']['full_name'] == REPO
            if run['head_sha'] == HEAD and run['head_branch'] == REF:
                assert run['event'] == 'workflow_dispatch', 'unexpected same-head event/context'
                found.append(run)
            elif run['status'] != 'completed' and run['head_branch'] == REF:
                raise RuntimeError('another-head run is active; do not trigger concurrency cancellation')
        if len(rows) < 100:
            return found
    raise RuntimeError('run pagination bound reached; dispatch blocked')


def main():
    assert os.environ['GITHUB_REPOSITORY'] == REPO
    assert os.environ['GITHUB_REF'] == 'refs/heads/ci-control/nodal-42-pr134-0bff900-targeted-v1'
    record('controller-start', candidate=HEAD, tree=TREE, base=BASE, pr=PR,
           controller=os.environ['GITHUB_SHA'], attempt=os.environ['GITHUB_RUN_ATTEMPT'])
    validate()
    for number, path, digest in WORKFLOWS:
        validate()
        existing = runs(number)
        if existing:
            record('retained', workflow=number, runs=[r['id'] for r in existing])
            assert all(r['status'] != 'completed' or r['conclusion'] == 'success' for r in existing), \
                'existing failed/closed run requires diagnosis, not a duplicate dispatch'
            continue
        # After an uncertain response or controller rerun, reads/reconciliation only.
        assert os.environ['GITHUB_RUN_ATTEMPT'] == '1', 'rerun cannot POST absent uncertain dispatch'
        record('intent', workflow=number, path=path, sha256=digest, payload={'ref': REF})
        uncertain = False
        try:
            request(f'/actions/workflows/{number}/dispatches', {'ref': REF})
            record('requested', workflow=number)
        except Exception as error:
            uncertain = True
            record('uncertain-response', workflow=number, error_type=type(error).__name__,
                   http_status=getattr(error, 'code', None))
        observed = []
        for _ in range(12):
            validate(definitions=False)
            observed = runs(number)
            if observed:
                break
            time.sleep(5)
        record('observed', workflow=number, runs=[r['id'] for r in observed], uncertain=uncertain)
        assert len(observed) == 1, 'missing or ambiguous dispatch; no retry permitted'
        assert observed[0]['head_sha'] == HEAD and observed[0]['event'] == 'workflow_dispatch'
    record('dispatch-complete-not-qualification', candidate=HEAD)


if __name__ == '__main__':
    main()
