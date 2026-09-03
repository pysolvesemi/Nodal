#!/usr/bin/env bash
set -euo pipefail

repo=${GITHUB_REPOSITORY}
current=${GITHUB_RUN_ID}
keep='[
  "Increment 34 Evidence Closure v5",
  "Increment 34 Canonical Closure Convergence v6",
  "Increment 34 Quiesce Obsolete Closure Controllers v3"
]'

pr=$(gh api "repos/${repo}/pulls/111")
if [[ $(jq -r '.state' <<< "${pr}") == open ]]; then
  gh pr ready 111 --repo "${repo}" --undo || true
fi

runs=$(gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100")
mapfile -t ids < <(
  jq -r --argjson keep "${keep}" --arg current "${current}" '
    .workflow_runs[]
    | select(.status != "completed")
    | select((.id|tostring) != $current)
    | select(.name | startswith("Increment 34"))
    | select(.name as $name | ($keep | index($name)) == null)
    | .id
  ' <<< "${runs}"
)
for id in "${ids[@]}"; do
  gh api --method POST "repos/${repo}/actions/runs/${id}/cancel" >/dev/null || true
done
sleep 20
echo "Cancelled ${#ids[@]} obsolete Increment 34 finalizer run(s)."
