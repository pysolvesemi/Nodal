# Bounded C2a construction controls

Focused bounded C2a prerequisite coverage. The maintained runner freshly compiles all 12 fixture sources and executes the four groups below; this is not exhaustive Foundation58 qualification.

Invoke python3 -I -B run_reduction_construction_c2a.py with --repository-root, --source-root, --compiler-classpath, --runtime-classpath, --api-jar, repeated --bridge-jar in precedence order, --java, --output-root, and positive --timeout. Optional --api-support-jar repeats after the primary API. Runtime/compile dependency order is primary API, support API JARs, bridge JARs, runtime JARs. Compiler classpath is separate. All inputs must be explicit JARs; there is no resolution or API build.

The caller prepares one coherent --source-root (NODAL_WORKSPACE), including reviewed production API/bridge source mappings and each unchanged fixture at manifest source_path. No mixed live worktree is allowed. Fixture bytes in both maintained and lookup trees are verified before/after commands. JARs and other inputs are hashed; the runner does not assemble or certify production source coherence.

--output-root must be fresh, git-ignored, and inside the repository. It contains fixture classes, command/log/input/result receipts, and isolated tmp/home/cache directories. Inherited Java/workspace environment overrides are sanitized. 235 MiB free is required before each command; this is not continuous disk monitoring. The caller must enforce its stage cap and bound output allocation.

Groups: original15 (original singleton controls), default6 (all six accounting controls, no poison-only argument), naming5 (five naming groups), publication3 (three publication groups). Repeat --suite NAME or use --suite all. Default is all four. The selected fixture dependency union is freshly compiled once, then groups execute in separate JVMs. No retained fixture classes or frontend plugin.

These are trusted package-internal controls with private inspection and deliberate poison tests. Public pending-outer callbacks remain off. C2b provenance/context/final-name ordering and C2c multi-element callback scheduling remain open, not admitted. Charged budgets measure structural work, not CPU-time guarantees. No CI integration, native compiler, firtool, check.py, external/index/production action is included.
