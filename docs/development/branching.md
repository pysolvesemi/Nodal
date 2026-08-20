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

Increments 1–8 form one linear, independently validated stack above the
original `main` roadmap commit. The one-time bootstrap therefore creates `dev`
directly at the exact validated head of `increment/8-ci-baseline`.

After Increment 8 is checked and the complete Core CI gate succeeds:

1. create `dev` from `increment/8-ci-baseline` at its exact validated commit;
2. verify that `dev` and the Increment 8 head have identical commits and trees;
3. change the GitHub default branch from `main` to `dev`;
4. enable the documented protection rules on `dev` and `main`.

No bootstrap pull request or merge commit is added. A merge would add no source
content because the history is already linear, while direct ref creation
preserves every independently validated increment commit exactly. This direct
creation is a one-time bootstrap exception only; it does not permit later
direct pushes to `dev`.

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

Core CI validates the bootstrap mode before the integration branch is created.

## Bootstrap contract evidence

The direct-ref contract was activated by commit
[`2028648`](https://github.com/pysolvesemi/Nodal/commit/20286487b758bc60c74b2c18a704770fc00c24f4)
after the complete Increment 8 validation run
[`32398325941`](https://github.com/pysolvesemi/Nodal/actions/runs/32398325941)
passed. The normal Increment 8 workflow was restored to read-only permissions
before creating `dev`.
