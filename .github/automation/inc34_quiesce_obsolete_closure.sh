#!/usr/bin/env bash
set -euo pipefail

repo=${GITHUB_REPOSITORY}
current=${GITHUB_RUN_ID}
obsolete='[
  "Increment 34 Evidence Closure v1",
  "Increment 34 Evidence Closure v2",
  "Increment 34 Evidence Closure v3",
  "Increment 34 Finalize Closure v1",
  "Increment 34 Finalize Closure v1b",
  "Increment 34 Finalize Closure v1c",
  "Increment 34 Finalize Closure v1d",
  "Increment 34 Converge Closure v4"
]'

pr=$(gh api "repos/${repo}/pulls/111")
if [[ $(jq -r '.state' <<< "${pr}") == open ]]; then
  gh pr ready 111 --repo "${repo}" --undo || true
fi

runs=$(gh api "repos/${repo}/actions/runs?branch=automation/inc34-evidence-closure-controller-v1&per_page=100")
mapfile -t ids < <(
  jq -r --argjson obsolete "${obsolete}" --arg current "${current}" '
    .workflow_runs[]
    | select(.status != "completed")
    | select((.id|tostring) != $current)
    | select(.name as $name | $obsolete | index($name))
    | .id
  ' <<< "${runs}"
)

for id in "${ids[@]}"; do
  echo "Cancelling obsolete Increment 34 controller run ${id}"
  gh api --method POST "repos/${repo}/actions/runs/${id}/cancel" >/dev/null || true
done

sleep 15

echo "Cancelled ${#ids[@]} obsolete Increment 34 closure controller run(s)."
