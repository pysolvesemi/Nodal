#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path


source = subprocess.check_output(
    [
        "git",
        "show",
        "origin/automation/inc33-p1-final-repair-v2-20260901:.github/workflows/_inc33_p1_final_repair_v2.yml",
    ],
    text=True,
)
begin_marker = "          python3 - <<'PY'\n"
end_marker = "\n          PY\n\n      - name: Install pinned native and lint toolchains"
begin = source.index(begin_marker) + len(begin_marker)
end = source.index(end_marker, begin)
lines = source[begin:end].splitlines()
script = "\n".join(
    line[10:] if line.startswith("          ") else line for line in lines
)

old_loop = """for old, new, label in renderer_substitutions:
    count = renderer.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    renderer = renderer.replace(old, new, 1)
"""
new_loop = """for old, new, label in renderer_substitutions:
    if label.startswith("IR "):
        position = renderer.rfind(old)
        if position < 0:
            raise SystemExit(f"{label}: match not found")
        renderer = renderer[:position] + new + renderer[position + len(old):]
    else:
        count = renderer.count(old)
        if count != 1:
            raise SystemExit(f"{label}: expected one match, found {count}")
        renderer = renderer.replace(old, new, 1)
"""
if script.count(old_loop) != 1:
    raise SystemExit("renderer substitution loop was not found exactly once")
script = script.replace(old_loop, new_loop, 1)

exec(
    compile(script, "increment33-final-p1-repair.py", "exec"),
    {"__name__": "__main__"},
)


def replace_region(
    path: Path,
    start_marker: str,
    end_marker: str,
    replacement: str,
    label: str,
) -> None:
    text = path.read_text(encoding="utf-8")
    start_count = text.count(start_marker)
    end_count = text.count(end_marker)
    if start_count != 1 or end_count != 1:
        raise SystemExit(
            f"{label}: marker counts are start={start_count}, end={end_count}"
        )
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


construction_tests = Path(
    "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
)
replace_region(
    construction_tests,
    '    test("public incompatible compound dimensions are rejected without read fallback"):\n',
    '    test("public compound voltage assignment retains its physical dimension"):\n',
    '''    test("public incompatible compound dimensions are rejected without read fallback"):
      val failure = scala.util
        .Try(
          ConstructionKernel.inspect(new PublicAnalogCompoundReadDimensionMismatch)
        )
        .failed
        .get
        .asInstanceOf[AnalogProceduralRuntime.Failure]
      assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

''',
    "compound read-dimension regression",
)

bridge_tests = Path(
    "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
)
replace_region(
    bridge_tests,
    '    test("initializing assignments precede dependent declarations independent of provenance"):\n',
    '    test("snapshot insertion order does not affect the bridge"):\n',
    '''    test("initializing assignments precede dependent declarations independent of provenance"):
      val snapshot = ConstructionKernel.inspect(new BridgeProceduralInitializerDependency)
      val program = snapshot.analogProcedural.head
      assert(program.variables.map(_.operationOrder) == Vector(0, 2))
      assert(program.assignments.map(_.operationOrder) == Vector(1, 3))

      val invertedVariables = program.variables.map: record =>
        val source =
          if record.operationOrder == 0 then
            AnalogProceduralRuntime.Source("z-helper.scala", 400, 1)
          else AnalogProceduralRuntime.Source("a-helper.scala", 1, 1)
        record.copy(source = Some(source))
      val invertedAssignments = program.assignments.map: record =>
        val source =
          if record.operationOrder == 1 then
            AnalogProceduralRuntime.Source("z-helper.scala", 300, 1)
          else AnalogProceduralRuntime.Source("a-helper.scala", 2, 1)
        record.copy(source = Some(source))
      val inverted = program.copy(
        variables = invertedVariables,
        assignments = invertedAssignments
      )
      val modified = snapshot.copy(
        analogProcedural = snapshot.analogProcedural.updated(0, inverted)
      )
      val rendered = AnalogProceduralMlir.renderModule(modified, program.owner).head
      val declaration0 = rendered.indexOf("operation_order = 0 : i64")
      val assignment0 = rendered.indexOf("operation_order = 1 : i64")
      val declaration1 = rendered.indexOf("operation_order = 2 : i64")
      val assignment1 = rendered.indexOf("operation_order = 3 : i64")
      assert(declaration0 >= 0)
      assert(assignment0 > declaration0)
      assert(declaration1 > assignment0)
      assert(assignment1 > declaration1)

      val document = ScalaToMlirBridge.fromSnapshot(modified)
      sys.env.get("NODAL_NODALC").foreach: executable =>
        val directory = workDirectory()
        try
          val success = NativeCompilerClient
            .run(
              document,
              NativeCompilerRequest(
                executable = Path.of(executable).toAbsolutePath,
                arguments = Vector("--mlir-print-op-generic"),
                workingDirectory = directory,
                timeout = Duration.ofSeconds(30)
              )
            )
            .asInstanceOf[NativeCompilerSuccess]
          assert(success.normalizedMlir.contains("operation_order"))
        finally delete(directory)

''',
    "initializer dependency chronology regression",
)

mutation_tests = Path("tests/compiler/test_increment33.py")
replace_region(
    mutation_tests,
    "    def test_compound_read_dimension_regression_mutation_is_rejected",
    'if __name__ == "__main__":\n',
    '''    def test_compound_read_dimension_regression_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "public incompatible compound dimensions are rejected without read fallback",
                    "compound read-dimension regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "compound read-dimension regression is missing")

    def test_initializer_dependency_chronology_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = (
                root
                / "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
            )
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "initializing assignments precede dependent declarations independent of provenance",
                    "initializer dependency chronology regression removed",
                    1,
                ),
                encoding="utf-8",
            )
            self.assert_rejected(
                root, "initializer dependency chronology regression is missing"
            )


''',
    "Increment 33 mutation regressions",
)
