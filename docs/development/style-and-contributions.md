# Style, lint, and contribution checks

Increment 9 establishes one reproducible style and policy gate for Scala,
native C++, Markdown, package ownership, and compatibility-sensitive changes.

## Locked tools

The checked lock is `toolchains/lint-lock.json`:

| Tool | Version | Purpose |
| --- | ---: | --- |
| Scalafmt | 3.11.5 | Scala formatting |
| Scalafix | 0.14.7 | Scala syntactic lint |
| clang-format | 22.1.8 | LLVM-compatible native formatting |
| clang-tidy | 22.1.8 | Conservative native static analysis |

Scala tools are resolved through the pinned Mill build. Native lint tools are
installed into an isolated Python virtual environment and accepted only when
their manifest and reported versions match the lock.

## Commands

```bash
./nodal style bootstrap
./nodal style check --base-ref origin/dev
./nodal style fix
```

The complete repository gate includes the same checks:

```bash
./nodal check --base-ref origin/dev
```

`style fix` applies only deterministic Scalafmt, Scalafix, and ClangFormat
rewrites. It does not alter Markdown structure, package ownership, pull-request
metadata, or design-gate approvals.

## Scala policy

`.scalafmt.conf` uses the Scala 3 dialect and a 100-column limit. Scalafix runs
syntactic `DisableSyntax` rules that reject `null`, `return`, `throw`, XML
literals, and finalizers. Controlled internal mutation is not globally banned;
future semantic rules require separate evidence and SemanticDB integration.

## Native policy

`.clang-format` derives from LLVM style. `.clang-tidy` starts with a conservative
set of analyzer, use-after-move, and move-const checks. Native lint runs only
after CMake has produced `compile_commands.json` for the locked compiler build.

## Markdown and package ownership

The Markdown checker enforces balanced code fences, heading hierarchy, and
valid repository-local links. The package checker keeps public packages under
`core/scala/api` and prevents API code from importing `nodal.internal`.

## Contribution and design-gate policy

`.github/change-policy.json` defines branch names, pull-request title and body
requirements, and protected path groups. Changes to the public API or the
core/library boundary require an approved, versioned design gate in the same
change. The checker operates on the Git diff against the supplied base ref and
also validates pull-request metadata when GitHub provides an event payload.
