# Design-gate policy

Design gates freeze compatibility-sensitive decisions before implementation is
allowed to depend on them.

## Required scopes

The contribution checker currently recognizes these scopes:

```text
public-api
core-library-boundary
```

A pull request touching a protected path must add or update an approved gate in
this directory. The gate file name must match `*-DG-v*.md` and contain:

```text
**Status:** Approved
**Scope:** public-api
```

or:

```text
**Status:** Approved
**Scope:** core-library-boundary
```

A gate must state the exact contract, accepted alternatives, rejected
alternatives, compatibility impact, tests, and approval evidence. Draft or
proposed documents do not authorize protected changes.

## Current public API freeze

`NodalPublicApi-DG-v0.1.md` is the controlling v0.1 public-API gate. It freezes
the source modeling API, parameterized HDL contract, backend selectors,
`Nodal.emit` entry point, compatibility policy, core-only surface, and the
versioned subset available to future library authors.

`NodalPublicApiCandidates-DG-v0.1.md` is retained only as the historical
compile-prototype decision that preceded the freeze.

Any incompatible public source change requires a new approved versioned gate
and a migration note. Public additions also require a gate because the API path
is protected.
