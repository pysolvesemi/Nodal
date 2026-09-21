# Nodal project instructions

These instructions apply to the whole repository. A more specific `AGENTS.md`
may narrow them for its subtree. A direct user instruction for the current task
takes precedence. Read [CONTRIBUTING.md](CONTRIBUTING.md) before implementing or
completing an increment.

## Targeted CI before full CI

For every increment, sub-increment, repair and accepted-evidence closure, qualify
newly affected or repaired source with targeted CI before starting full CI.
Local tests are useful repair evidence, not a substitute for remote qualification.
Use the existing increment branch and PR against `dev` unless the task specifies
otherwise; do not start another increment or touch `main` implicitly.

1. Inspect the live target, candidate, PR and repository instructions. Publish a
   clean candidate with `[skip ci]` to prevent automatic broad push/PR CI during
   the targeted phase. Record the full candidate SHA, tree SHA and target SHA.
2. Determine the affected workflow set from changed source, tests, generated
   artifacts, manifests, toolchain pins, formal registries, source-review
   contracts and prior CI failures. Record workflow IDs, paths, definition
   hashes, required inputs, jobs and matrix lanes; names alone are not stable
   workflow identities. Include shared gates and affected predecessor coverage.
3. Confirm each workflow supports `workflow_dispatch`, is registered/dispatchable
   under GitHub's rules, and executes the intended checks for that event/ref.
   A successful dispatch cannot compensate for PR-only jobs silently skipping.
   Do not change the default branch or modify `main` to make dispatch available.
4. Launch each affected workflow on the exact candidate branch using the
   browserless route below. Do not duplicate queued, running or successful runs
   for the same workflow, head, inputs and qualification context. Verify the
   resulting workflow ID, event, ref, head and actual checkout/tree identity.
5. Treat a workflow as passing only after every applicable job and matrix lane
   succeeds. Enumerate all run, latest-attempt job, check and status pages, and
   inspect actual logs/artifacts. Missing lanes, zero-job greens, pending work,
   cancellations, timeouts, skipped applicable jobs and old-head successes are
   not passing evidence. Record legitimate non-applicable skips without giving
   them test credit. A prospective test-merge SHA is not an actual merge.
6. After the first targeted launch, immediately enable hourly monitoring as
   specified below. Repair failures and repeat only the affected targeted set
   until all targeted requirements pass on the intended candidate.
7. Only then start one applicable full-CI qualification on that exact candidate.
   Inventory the complete required workflow/job set, including Core CI and
   applicable increment/compatibility workflows; Core CI alone is not the full
   set when other gates apply. Reuse qualifying same-head targeted runs and
   launch only missing full-phase requirements through approved repository
   triggers. Verify branch-protection-required contexts as well as test results.
8. If full CI fails, return to failed-first diagnosis and targeted repair rather
   than repeatedly relaunching the full matrix. After repair targeting is green,
   complete full qualification for the repaired head. Keep successful independent
   same-head runs; results from a preceding SHA do not qualify a new source head.

Rerunning an existing job preserves the original run's source SHA/ref. Use a
specific-job or failed-job rerun only for a diagnosed unchanged-source transient
failure. After any source, test, workflow, manifest, registry or review-contract
repair, publish the corrected commit and dispatch fresh runs on the new head.
New job IDs can represent copied successes from an earlier attempt; verify step
execution times and checkout identities instead of assuming a fresh rebuild.

Skip annotations suppress push/PR triggers, not explicit dispatches, and can
leave required PR contexts pending. They never authorize bypassing protection,
posting fabricated success statuses or counting absent checks as passed. Plan
an approved full-CI route that supplies every required context. If a required
workflow/context cannot be qualified through available authorized mechanisms,
record the precise blocker; do not silently run broad CI during targeted repair.

## Browserless targeted dispatch

Do not require the user to run GitHub CLI commands. Do not depend on a browser,
devbox or external MCP server when an authorized repository route is available.
Use this launch order:

1. Use a connected GitHub `workflow_dispatch` operation when it is exposed.
2. Otherwise use the standing authorization below for a narrowly scoped,
   increment-specific repository dispatcher. Tool unavailability is not a reason
   to ask again for this permission. This does not override a safety denial,
   repository restriction or genuinely missing access.

### Standing authorization for a repository dispatcher

The agent may publish and trigger a controller on a dedicated non-target,
non-feature control branch, starting from the current integration target. This
is a targeted-CI mechanism, not a source publisher or a separate increment.
The controller may use the runner's automatic `${{ github.token }}` to call the
Actions workflow-dispatch REST endpoint. No personal token is required.

The controller must:

- Have a unique branch and controller identity bound to the increment, PR,
  target and candidate SHA. Never repurpose one pinned to different work.
- Have an exact `push` branch filter and path filters limited to its own workflow
  and reviewed controller files. Request only `contents: read` and
  `actions: write`; no personal token, extracted credentials, browser session,
  default-branch change, feature-trigger modification or unrelated workflow edit.
- Carry an explicit allowlist for repository, PR, base ref/SHA, candidate
  ref/SHA/tree, workflow IDs/definition hashes and exact dispatch payloads/inputs.
  Validate live identities, original failure evidence when applicable and
  workflow definitions before dispatch; recheck identities between dispatches.
- Use reads for validation and permit only the allowlisted write operation
  `POST /repos/OWNER/REPO/actions/workflows/WORKFLOW_ID/dispatches`, normally
  with `{"ref":"CANDIDATE_BRANCH"}`. Reject unexpected inputs or destinations.
- Enumerate existing same-workflow, same-head dispatch runs, with matching
  inputs/context, and retain queued, running or successful runs. Record intent
  before POST, reconcile an uncertain response before retrying, and retain an
  auditable ledger of requested and observed runs without logging tokens.
- Fail closed on ref movement or unexpected data, use bounded timeouts and
  serialize dispatch attempts. Do not execute candidate-controlled source or
  arbitrary scripts in the controller's privileged job.
- Dispatch only the affected workflow set. It must not update source refs,
  rerun old heads, launch full CI, merge, cancel unrelated work, broaden triggers
  or alter protections. Keep controller commits off feature/integration branches.

Before publication, inspect every workflow that can match the controller push.
That push must launch only the intended controller; otherwise select a safe
isolated route or stop without dispatching. In Nodal, Core CI matches
`increment/**`, so a controller branch must not use that prefix. An isolated
`ci-control/...` name is only a candidate until all live trigger filters have
been checked. The controller-launch commit must NOT contain `[skip ci]`.

After launch, verify each resulting run has the allowlisted workflow ID, event
`workflow_dispatch`, candidate branch and exact head SHA. An actor such as
`github-actions[bot]` is expected with the job token; neither that actor nor a
green controller job is qualification evidence. Only the dispatched checks count.

## Hourly monitoring until increment closure

Immediately after launching targeted CI for an increment, create, re-enable or
update one hourly continuation for that increment using the available scheduling
tool (`RRULE:FREQ=HOURLY`). Do not wait for targeted CI to finish or ask for this
standing authorization again. Reuse an existing task instead of creating
competing monitors. Confirm scheduling actually succeeded before reporting it
as enabled; report an unavailable scheduler without claiming background work.

Keep the task enabled through targeted repairs, full CI, review, merge and any
separate accepted-evidence closure. A green targeted run, green full matrix or
merged implementation PR is not alone a reason to disable it.

Each execution must:

1. Read live refs, PR/review state, the durable increment checkpoint and every
   applicable latest-attempt workflow/job/check/status page. Avoid competing
   writes with another resume; recheck refs before publishing or merging.
2. Inspect actual failed logs and artifacts. Repair generic causes within the
   authorized increment, run proportional local checks, publish a clean
   `[skip ci]` checkpoint, and dispatch only failed/newly affected targeting on
   the repaired head. Diagnose infrastructure failures, including rate limits,
   before bounded retries; never disable provenance or tests to make them pass.
3. Advance from targeted to full CI only when all targeted requirements pass;
   return full-CI failures to the same repair loop. Leave unrelated and
   still-running jobs intact, and do not restart successful independent checks.
4. Once all required full-CI checks and review pass on the exact final head,
   perform the already-authorized verified merge with post-merge CI suppressed
   as described below. Continue monitoring until acceptance records, roadmap
   and any required closure PR are complete, not merely until code is merged.
5. Update the durable checkpoint with the current phase, repo/increment/PR,
   candidate/base/tree SHAs, affected/full workflow inventories, run/attempt IDs,
   failures, fixes, evidence, merge identity and remaining closure obligations.
   Stay quiet for unchanged queued/running work; report meaningful failures,
   published repairs, blockers, merge verification and accepted completion.

Disable the hourly task only after the increment is legitimately closed:
required final-head qualification and review are complete, the actual merge is
verified, required evidence/closure records are integrated, the authoritative
roadmap and manifest agree, and the completion demonstration/report is delivered.
Keep it enabled while any of these remain outstanding, unless the user explicitly
pauses/cancels it. A persistent blocker must be reported, not treated as closure;
do not hammer failing services or repeatedly publish the same checkpoint.

## Verified merge with post-merge CI suppressed

Effective 2026-09-21, suppress duplicate post-merge CI for fully qualified,
identical-tree increment and closure merges. This replaces older generic
instructions to rerun the same suite after every merge; it does not remove
pre-merge qualification, review, source integrity or accepted-evidence closure.

- Require all applicable targeted/full-CI checks and review on the final head.
  Re-read candidate and target SHAs, required contexts and mergeability just
  before merging. Target movement, unresolved review or a different prospective
  tree requires reconciliation and qualification, not an unchecked merge.
- Preserve Nodal's documented **squash merge into `dev`** policy. Supply the
  verified `expected_head_sha` and put `[skip ci]` in the actual squash-merge
  commit message. Do not add an untested feature commit merely to carry the
  annotation, rewrite feature history or bypass protected-branch requirements.
- Verify GitHub's actual merged flag, merge commit, parent/target relationship,
  final target ref, commit message and tree. The merge tree must equal the
  qualified candidate tree. Retain these identities with pre-merge CI evidence;
  a prospective PR test-merge SHA or a successful merge request is insufficient.
- Check that the intended post-merge push workflows were suppressed. Do not
  dispatch duplicate post-merge CI. `[skip ci]` does not suppress every event
  type; inspect live `pull_request_target`, `workflow_run` and other relevant
  triggers before relying on it. Do not globally disable workflows or cancel
  unrelated runs to manufacture suppression. Report unexpected triggered runs
  and inspect them without counting a failed applicable check as success.
- Record `post_merge_ci: skipped` with reason `qualified-identical-tree-merge`,
  actual merge/tree identities and references to the executed qualification.
  Never invent a post-merge run ID, report skipped checks as executed/passed, or
  reuse old-head results as if they ran on the merge commit.
- Preserve historical accepted-evidence records, hashes and genuine post-merge
  results. Do not rewrite them for this policy. New records must distinguish
  executed qualification from merge verification and suppression; an old schema
  that requires a new post-merge run needs an explicit reviewed schema evolution,
  not dummy run IDs or weakened historical guards.
- Keep any required separate evidence-closure PR in scope. Validate its changed
  checks/docs using targeted-first/full-CI qualification, review and the same
  verified merge/suppression rule. Complete the roadmap and manifest according
  to CONTRIBUTING; do not delete unfinished acceptance tasks to claim closure.

If merge-tree verification fails or a mandatory check remains unsatisfied, keep
the increment open and monitoring active. Diagnose and qualify the discrepancy;
never classify it as a successful CI-skipped merge.

## Failure handling and publication

Preserve authoritative source ancestry and evidence. Do not rebase, force-push
or rewrite published candidate history; the final reviewed squash merge above
is the Nodal integration policy, not permission to relabel tests from other
commits. Never weaken tests, simulations, formal proofs, mutation controls,
source reviews, timeouts or workflow matrices merely to obtain a pass.

For a user-authorized documentation-only instruction/roadmap update with CI
explicitly waived, use the existing requested branch and `[skip ci]`; do not
create a new branch, PR, controller or monitor merely to exercise these rules.
That exception is not a waiver for compiler changes or increment-closure gates.

## Standing increment completion rule

Whenever an increment or sub-increment in any roadmap track is completed and
marked `[x]`, follow
[Increment completion demonstrations](CONTRIBUTING.md#increment-completion-demonstrations)
in the user-facing completion report: show a concrete Nodal Scala example and
its corresponding actual generated Verilog-* output when applicable. Identify
the backend, relevant source/output paths, and what the example demonstrates.
Do not substitute hypothetical output or a test-status summary for the demo.

When the increment does not affect generated Verilog-*, explicitly state:

> The current increment does not affect generated Verilog-*.

Add a brief reason, and show a Scala example only when it is useful and
applicable. This includes documentation-only increments; it does not waive any
existing acceptance, validation, or merge requirements for implementation work.

## GitHub CI mechanics references

- [Workflow dispatch and job-token triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)
- [Dispatch event requirements](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)
- [Rerun source identity](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs)
- [Commit-message skip annotations](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)
