#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
mkdir -p "${report}"
repo=${GITHUB_REPOSITORY}
placeholder=da785a1fbb0d8381a83d9100e2318c553102efc8

canonical_head() {
  local pr head dedicated
  pr=$(gh api "repos/${repo}/pulls/111")
  head=$(jq -r '.head.sha' <<< "${pr}")
  if [[ ${head} == "${placeholder}" ]]; then
    return 1
  fi
  dedicated=$(gh api \
    "repos/${repo}/contents/tests/compiler/fixtures/increment34/manifest.json?ref=${head}" \
    --jq '.content' 2>/dev/null | tr -d '\n' | base64 -d \
    | jq -r '.validation.dedicated_boundary_workflow_run // 0' || true)
  [[ ${dedicated} == 33732868285 ]] || return 1
  echo "${head}"
}

latest_v4_state() {
  gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100" \
    | jq -r '
        [.workflow_runs[] | select(.name == "Increment 34 Evidence Closure v4")]
        | sort_by(.id) | reverse | .[0]
        | if . == null then "missing" else (.status + ":" + (.conclusion // "")) end
      '
}

head=
for _ in $(seq 1 540); do
  if head=$(canonical_head); then
    break
  fi
  state=$(latest_v4_state)
  case "${state}" in
    queued:*|in_progress:*|waiting:*|pending:*|requested:*) sleep 20 ;;
    completed:success) sleep 10 ;;
    missing|completed:failure|completed:cancelled|completed:timed_out|completed:action_required|completed:*)
      echo "canonical closure controller state ${state}; running one guarded restart"
      rm -rf "${report}/materializer-work"
      git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
        "${report}/materializer-work"
      git -C "${report}/materializer-work" checkout \
        increment/34-evidence-closure-v1
      bash "${controller}/.github/automation/inc34_run_evidence_closure_v4.sh" \
        "${controller}" \
        "${report}/materializer-work" \
        "${report}/materializer-restart"
      head=$(canonical_head)
      break
      ;;
  esac
done

[[ -n ${head} ]] || {
  echo "Canonical Increment 34 closure head was not published." >&2
  exit 1
}

gh pr ready 111 --repo "${repo}" || true
pr=$(gh api "repos/${repo}/pulls/111")
if [[ $(jq -r '.merged' <<< "${pr}") == true ]]; then
  exec bash "${controller}/.github/automation/inc34_converge_closure_v4.sh" \
    "${controller}" "${report}/merged-audit"
fi

exec bash "${controller}/.github/automation/inc34_finalize_closure_v1.sh" \
  "${controller}" "${report}/finalizer"
