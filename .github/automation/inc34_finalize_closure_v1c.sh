#!/usr/bin/env bash
set -euo pipefail

controller=${1:?controller checkout required}
report=${2:?report directory required}
mkdir -p "${report}"
repo=${GITHUB_REPOSITORY}
placeholder=da785a1fbb0d8381a83d9100e2318c553102efc8

latest_repaired_state() {
  gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100" \
    | jq -r '
        [.workflow_runs[] | select(.name == "Increment 34 Evidence Closure v2")]
        | sort_by(.id) | reverse | .[0]
        | if . == null then "missing" else (.status + ":" + (.conclusion // "")) end
      '
}

for _ in $(seq 1 480); do
  head=$(gh api "repos/${repo}/pulls/111" --jq '.head.sha')
  draft=$(gh api "repos/${repo}/pulls/111" --jq '.draft')
  if [[ ${head} != "${placeholder}" && ${draft} == false ]]; then
    break
  fi
  state=$(latest_repaired_state)
  case "${state}" in
    queued:*|in_progress:*|waiting:*|pending:*|requested:*)
      sleep 20
      ;;
    completed:success)
      sleep 10
      ;;
    missing|completed:failure|completed:cancelled|completed:timed_out|completed:action_required|completed:*)
      echo "repaired materializer state ${state}; running one guarded restart"
      rm -rf "${report}/materializer-work"
      git clone "https://x-access-token:${GH_TOKEN}@github.com/${repo}.git" \
        "${report}/materializer-work"
      git -C "${report}/materializer-work" checkout \
        increment/34-evidence-closure-v1
      bash "${controller}/.github/automation/inc34_run_evidence_closure_v2.sh" \
        "${controller}" \
        "${report}/materializer-work" \
        "${report}/materializer-restart"
      break
      ;;
  esac
done

exec bash "${controller}/.github/automation/inc34_finalize_closure_v1.sh" \
  "${controller}" "${report}/finalizer"
