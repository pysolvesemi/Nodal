# Retained coherent-source regression fixtures

These seven files are byte-exact copies of the accepted coherent-source
regressions. Their placement here is new; no relocated execution or maintained
automation acceptance is claimed. Source locations and derived origin IDs can
change when compiling these paths. They are not inputs to a whole-directory
compile: the two ordering source sets intentionally reuse helper names.

The historical source engine was the 108-file coherent source snapshot; the
query compiler was `d59b274b`. Rebuild its API, plugin and bridge, using the
pinned Scala 3.8.4 compiler/runtime and required frontend/integrations classes.
Use `dotty.tools.dotc.Main`, `-color:never -Wunused:all -Werror -Ycheck:all`,
`-Xplugin:<plugin.jar>` and absolute API/bridge/shared/runtime classpaths.
No current binaries or private scratch installation are required by these
source files. A maintained driver still needs focused relocation acceptance.

## Checked-empty initialization

Compile `checked-empty-initialization/{Probe,Cases,Replay}.scala` together.
Create an isolated runtime working directory containing
`.work/f58-empty-initialized-storage`; the replay deliberately writes there.
Run `nodal.internal.testkit.EmptyInitializedReplay <mode>` with absolute
classpaths. The retained historical outcomes are:

- Success: `empty`, `attained`, `parameter`, `attainedfit`, `signed`, `nested`.
- Refusal: `negative`, `zero`, `badoperand`, `runtime`, `unbounded`,
  `attainedbad`, `attainedzero`, `numeric`, `foreign`, `bridge-negative`,
  `bridge-malformed`, `bridge-canonical`, `bridge-owner`.

These 19 outcomes are separate from other source group counts.

## Guard-qualified ordinal ordering

Compile `SelectedGuardedSource.scala` and `SelectedGuardedReplay.scala` as one
set, and `OrdinalControlsSource.scala` and `OrdinalControlsReplay.scala` as a
second set, with separate output directories. Run the corresponding
`nodal.internal.testkit.SelectedGuardedReplay` or
`nodal.internal.testkit.OrdinalControlsReplay` with a mode and an absolute
output MLIR path.

The historical successful recipe uses `guarded`, `strict`, `inclusive` as
successes and `weak`, `zero`, `foreign`, `or` as nonzero NODAL-diagnostic
refusals. In particular, the weak `or` control is not the independent stronger
Or proof profile. Do not combine the two source sets or treat `or` as positive.
