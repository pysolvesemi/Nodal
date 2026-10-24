# Focused shape anchor mapper contracts

This bundle preserves a pinned-formatting-only copy of the accepted map-controls.cpp
and thirteen byte-identical corrected native inputs. The formatted source was
compiled with unchanged flags and include closure into an anonymous memory-backed
object; its SHA256 exactly matched the accepted probe object. It follows the existing tests/compiler/fixtures/increment58 probe
convention; there was no tracked tests/native directory. It is not a new compiler
qualification suite and has no CMake/CI integration.

Run with an explicitly supplied EXISTING mapper probe:

    python3 -B run_mapper.py --probe /absolute/path/to/mapper-probe --receipt /new/path/results.json

The executable must implement `map-controls.cpp`, NOT the backend/adapter probe.
The runner executes the original fixture/mode argv pairs, checks all 1,796
assertions, zero exit statuses, and exact accepted stdout/stderr hashes. It checks
source/fixture hashes and writes one compact receipt without copying executables,
libraries, or full logs. It never compiles, links, installs, or invokes consumers.
A future intentionally changed contract needs reviewed expectation updates rather
than weakening failures. The caller supplies any shared build/execution lock.

## Scope and limitations

The C++ source deliberately parses with whole-program verification disabled. These
are authentic relation/projection API controls, not proof that every raw fixture
is accepted by the full-module verifier or backend. Cases include old/new mapping,
nonunit attainable coordinates, out-of-image refusal, one-way scalar anchor,
foreign/malformed relation/projection facts, fresh/nested scope refusal, singleton,
reverse scalar-to-lane refusal, bounded work, and unchanged output/IR on failure.
The capture case is a corrected authored HardwareLoop with explicit captures and
valid header provenance. It expects Invalid at exact operand validation, including
zero work: its resolved module-root projection is not the actual captured operand.
It does NOT claim supported capture remapping. Mapper state0/1 successes allocate
no partition states and do NOT demonstrate connectivity state-ceiling enforcement.
That separate graph-budget test is outside this thirteen-case preserved bundle.

Only corrected accepted fixtures are included. The earlier malformed capture,
initial null-parent query harness crashes, recursive diagnostic printer, unrelated
predecessor fixtures, and later array-anchor controls are NOT included. Fixture
origins in cases.json distinguish ten byte-identical preexisting native inputs
from three explicitly authored controls; none is misrepresented as fresh Scala
output. `raw-*` names and unsupported-boundary inputs retain that limitation.

## Rebuilding separately when authorized

Compile map-controls.cpp with a matching Nodal compiler implementation exposing
mapStaticShapeSameOccurrencePoint and mapStaticShapeSameOccurrenceOrInvariantPoint.
Use the matching generated Nodal headers and MLIR/CIRCT include/library closure,
C++17, the same C++ standard-library ABI, and the compiler build's visibility,
exception and RTTI configuration. The accepted build used release -O3 -DNDEBUG,
-fPIC, -ffunction-sections, -fdata-sections, -fno-exceptions and -fno-rtti, with
_GLIBCXX_USE_CXX11_ABI=1. Link the matching Nodal dialect/shape/connectivity
implementation and its LLVM/MLIR/CIRCT dependencies. A backend adapter executable
cannot substitute for this test main.

Build only when separately authorized, in a fresh output directory. Redirect all
object, dependency, linker-dependency and temporary outputs there. Audit matching
source/header/library revisions rather than mixing an arbitrary installed
compiler with another build's libraries. This runner requires an existing probe;
it deliberately offers no installation, rebuild, or consumer pipeline. Existing
CIRCT dependencies are needed, but no CIRCT repair, firtool, check.py or SV consumer
execution is involved. Historical build provenance belongs in ignored local
receipts, not the maintained test bundle.
