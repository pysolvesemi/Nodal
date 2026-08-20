# Branch and merge policy

Nodal uses a protected integration branch and a protected milestone-release
branch:

- `dev` is the protected integration branch.
- `main` is the protected milestone-release branch.

## Branch roles

```text
increment/<number>-<slug>
          |
          | pull request, Core CI green, squash merge
          v
         dev                         default development branch
          |
          | milestone promotion pull request, Core CI green
          v
        main                         locked release branch
          |
          +-- annotated or signed milestone tag
```

- `dev` is the continuously integrated development branch and should become the
  repository default branch after the bootstrap integration.
- `main` is the milestone-release branch. It changes only through a promotion
  pull request whose source is `dev`.
- Both branches reject direct pushes, force pushes, and deletion.

## Bootstrap integration after Increment 8

The repository currently has Increments 1–8 as a linear, independently
validated stack above the original `main` roadmap commit.

After Increment 8 Core CI succeeds, perform the one-time bootstrap:

1. create `dev` from the current `main` commit;
2. open one pull request with head `increment/8-ci-baseline` and base `dev`;
3. require `Core CI / required`;
4. merge with a merge commit;
5. change the GitHub default branch from `main` to `dev`;
6. enable the documented protection rules on both branches.

The merge commit preserves all existing increment commits and their evidence.
Squashing this bootstrap would collapse the independently validated history into
one large commit, so the bootstrap is the only exception to the normal
increment squash policy.

No bootstrap branch, pull request, or merge is created merely by documenting
this policy. Integration is a separate authorized repository action.

## Normal increment flow

After bootstrap:

1. update local `dev`;
2. create `increment/<number>-<slug>` from current `dev`;
3. implement exactly one roadmap increment;
4. push the increment branch;
5. open a pull request into `dev`;
6. require `Core CI / required`;
7. squash-merge the increment;
8. delete the merged increment branch;
9. start the next increment from the new `dev`.

This gives one reviewable integration commit per future increment while keeping
`dev` continuously buildable.

## Milestone promotion flow

Promote `dev` to `main` only when a roadmap milestone is complete:

| Milestone | Promotion gate |
| --- | --- |
| M0 — Foundation | after Increment 12 |
| M1 — First vertical slice | after Increment 23 |
| M2 — Analog preview | after Increment 50 |
| M3 — AMS preview | after Increment 66 |
| M4 — Scalable core release | after Increment 77 |

A promotion uses a pull request from `dev` to `main`, requires the same Core CI
gate, and uses a merge commit. After merge, create the corresponding annotated
or signed milestone tag. Hotfix branches are out of scope until the first
release exists.

## Protection settings

Configure the GitHub ruleset for `dev` to require:

- pull requests;
- `Core CI / required`;
- resolved review conversations;
- no force pushes;
- no branch deletion;
- squash merge for normal increment pull requests.

Configure `main` with the same restrictions, plus the policy that the pull
request source is `dev` and promotion occurs only at a completed milestone.

For a single maintainer, an approval count of zero is acceptable initially as
long as pull requests and required checks are mandatory. Increase the approval
requirement when additional maintainers join.

## Machine-readable policy

The checked contract is stored in:

```text
.github/branch-policy.json
```
