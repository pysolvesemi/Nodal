# Branch and merge policy

Nodal uses a **protected trunk** rather than a long-lived integration branch.

## Branch roles

```text
increment/<number>-<slug>
          |
          | pull request, Core CI green
          v
        main
          |
          +-- milestone tag / release
```

`main` is both the integration and release-history branch. It is locked against
direct pushes, force pushes, and deletion. All implementation changes arrive by
pull request after the required `Core CI / required` gate succeeds.

Stable releases are identified by an annotated or signed milestone tag. Keeping
release identity in tags avoids maintaining a second branch that contains the
same integration history.

## Why there is no long-lived `dev` branch

A separate `dev` branch is not needed for the current one-increment-at-a-time
workflow. It would:

- duplicate integration state;
- make contributors choose between two permanent bases;
- postpone CI and scheduled dependency workflows that GitHub reads from the
  default branch;
- create recurring `dev`-to-`main` promotion merges;
- make it less obvious which green commit is the current project baseline.

A `dev` or release-train branch may be introduced later only through an accepted
ADR if parallel incompatible trains create a demonstrated need.

## Bootstrap integration after Increment 8

The repository currently has Increments 1–8 as a linear, independently
validated stack above the original `main` roadmap commit.

The one-time bootstrap pull request should be:

```text
head: increment/8-ci-baseline
base: main
method: merge commit
```

The merge commit preserves all existing increment commits and their evidence.
Squashing this bootstrap would collapse the independently validated history into
one large commit, so the bootstrap is the only planned exception to the normal
squash policy.

## Normal increment flow

After the bootstrap merge:

1. update local `main`;
2. create `increment/<number>-<slug>` from current `main`;
3. implement exactly one roadmap increment;
4. push the increment branch;
5. open a pull request into `main`;
6. require `Core CI / required`;
7. squash-merge the increment;
8. delete the merged increment branch;
9. start the next increment from the new `main`.

This gives one reviewable commit per future increment while keeping `main`
continuously buildable.

## Main protection settings

Configure the GitHub ruleset for `main` to require:

- pull requests;
- `Core CI / required`;
- resolved review conversations;
- no force pushes;
- no branch deletion;
- squash merge for normal increment pull requests.

For a single maintainer, an approval count of zero is acceptable initially as
long as pull requests and required checks are mandatory. Increase the approval
requirement when additional maintainers join.

## Releases

Create a milestone tag only after the relevant roadmap gate is complete. The
planned early tags align with M0, M1, M2, M3, and M4 rather than every increment.

The machine-readable policy is stored in:

```text
.github/branch-policy.json
```
