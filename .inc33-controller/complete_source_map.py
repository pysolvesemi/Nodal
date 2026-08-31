#!/usr/bin/env python3
"""Complete Increment 33 procedural source-map and round-trip coverage."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


serializer = "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
replace_once(
    serializer,
    '''  def sourceMapEntries(snapshot: ConstructionSnapshot): Vector[String] =
    snapshot.analogProcedural
      .sortBy(_.owner)
      .flatMap: program =>
        val variables = program.variables.flatMap(record =>
          record.source.map(sourceMapEntry(record.variable.identity, _))
        )
        val assignments = program.assignments.flatMap(record =>
          record.source.map(sourceMapEntry(record.identity, _))
        )
        variables ++ assignments
      .sortBy(identity)
''',
    '''  def sourceMapEntries(snapshot: ConstructionSnapshot): Vector[String] =
    snapshot.analogProcedural
      .sortBy(_.owner)
      .flatMap: program =>
        def canonicalScope(scope: Vector[String]): Vector[String] =
          if scope.headOption.contains("procedure") then scope
          else Vector("procedure") ++ scope

        val allSources =
          (program.variables.flatMap(_.source) ++ program.assignments.flatMap(_.source))
            .sortBy(source => (source.file, source.line, source.column))
        val wrappers = allSources.headOption.toVector.flatMap: source =>
          Vector(
            sourceMapEntry(s"${program.owner}.analogProcedural", source),
            sourceMapEntry(s"${program.owner}.analogProcedure", source)
          )
        val scopePaths =
          (program.variables.map(record => canonicalScope(record.variable.declarationScope)) ++
            program.assignments.map(record => canonicalScope(record.scope)))
            .flatMap(scope => (2 to scope.size).map(scope.take).toVector)
            .distinct
            .sortBy(_.mkString("."))
        val scopes = scopePaths.flatMap: scope =>
          val sources =
            (program.variables
              .filter(record => canonicalScope(record.variable.declarationScope).startsWith(scope))
              .flatMap(_.source) ++
              program.assignments
                .filter(record => canonicalScope(record.scope).startsWith(scope))
                .flatMap(_.source))
              .sortBy(source => (source.file, source.line, source.column))
          sources.headOption.map(source =>
            sourceMapEntry(s"${program.owner}.${scope.mkString(".")}", source)
          )
        val variables = program.variables.flatMap(record =>
          record.source.map(sourceMapEntry(record.variable.identity, _))
        )
        val assignments = program.assignments.flatMap(record =>
          record.source.map(sourceMapEntry(record.identity, _))
        )
        val reads = program.assignments.flatMap: record =>
          record.source.toVector.flatMap: source =>
            record.value.reads.indices.map: index =>
              sourceMapEntry(s"${record.identity}.read_$index", source)
        wrappers ++ scopes ++ variables ++ assignments ++ reads
      .distinct
      .sortBy(identity)
''',
)

tests = "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
replace_once(
    tests,
    '''  private def delete(path: Path): Unit =
''',
    '''  private def occurrences(text: String, token: String): Int =
    text.sliding(token.length).count(_ == token)

  private def delete(path: Path): Unit =
''',
)
replace_once(
    tests,
    '''      assert(first.text.contains("ScalaToMlirBridgeTests.scala"))
      assert(first.text.contains("loc(\\\""))
      assert(first.sha256 == second.sha256)
''',
    '''      assert(first.text.contains("ScalaToMlirBridgeTests.scala"))
      assert(first.text.contains("loc(\\\""))
      assert(first.sha256 == second.sha256)

      val snapshot = ConstructionKernel.inspect(new BridgeProceduralTop)
      val program = snapshot.analogProcedural.head
      assert(program.variables.forall(_.source.nonEmpty))
      assert(program.assignments.forall(_.source.nonEmpty))
      val wrapperPaths = Vector(
        s"${program.owner}.analogProcedural",
        s"${program.owner}.analogProcedure"
      )
      val variablePaths = program.variables.map(_.variable.identity)
      val assignmentPaths = program.assignments.map(_.identity)
      val readPaths = program.assignments.flatMap: record =>
        record.value.reads.indices.map(index => s"${record.identity}.read_$index")
      val authoredScopes =
        program.variables.map(_.variable.declarationScope) ++ program.assignments.map(_.scope)
      val scopePaths = authoredScopes
        .flatMap: scope =>
          val canonical =
            if scope.headOption.contains("procedure") then scope
            else Vector("procedure") ++ scope
          (2 to canonical.size).map(size =>
            s"${program.owner}.${canonical.take(size).mkString(".")}"
          )
        .distinct
      val expectedSourcePaths =
        (wrapperPaths ++ scopePaths ++ variablePaths ++ assignmentPaths ++ readPaths).distinct.sorted

      assert(readPaths.nonEmpty)
      assert(scopePaths.nonEmpty)
      assert(first.text.contains("nodal.bridge.source_map"))
      assert(
        expectedSourcePaths.forall(path =>
          occurrences(first.text, s"semantic_path = \\\"$path\\\"") >= 2
        )
      )
''',
)
replace_once(
    tests,
    '''    test("locked nodalc parses and normalizes bridge MLIR when configured"):
''',
    '''    test("locked nodalc parses procedural bridge MLIR when configured"):
      sys.env.get("NODAL_NODALC") match
        case None => assert(true)
        case Some(executable) =>
          val directory = workDirectory()
          try
            val document = ScalaToMlirBridge.lower(new BridgeProceduralTop)
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
            assert(success.normalizedMlir.contains("\\\"nodal.analog_variable\\\""))
            assert(success.normalizedMlir.contains("\\\"nodal.analog_variable_read\\\""))
            assert(success.normalizedMlir.contains("\\\"nodal.analog_assign\\\""))
            assert(success.normalizedMlir.contains("nodal.bridge.source_map"))
            assert(success.normalizedMlir.contains(".read_0"))
            assert(success.normalizedMlir.contains("authored_order"))
          finally delete(directory)

    test("locked nodalc parses and normalizes bridge MLIR when configured"):
''',
)

checker = "scripts/check_increment33.py"
replace_once(
    checker,
    '''    procedural_bridge = read_text(
        root, "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    )
''',
    '''    procedural_bridge = read_text(
        root, "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    )
    bridge_tests = read_text(
        root,
        "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
    )
''',
)
replace_once(
    checker,
    '''    require(
        "nodal.analog_variable_read" in procedural_bridge
        and "nodal.analog_assign" in procedural_bridge,
        "NODAL-INC33-040: procedural bridge operations are incomplete",
    )
''',
    '''    require(
        "nodal.analog_variable_read" in procedural_bridge
        and "nodal.analog_assign" in procedural_bridge,
        "NODAL-INC33-040: procedural bridge operations are incomplete",
    )
    for token in (
        's"${program.owner}.analogProcedural"',
        's"${program.owner}.analogProcedure"',
        'scopePaths',
        's"${record.identity}.read_$index"',
    ):
        require(
            token in procedural_bridge,
            f"NODAL-INC33-044: procedural source-map coverage is incomplete: {token!r}",
        )
    for token in (
        "expectedSourcePaths",
        "occurrences(first.text",
        "locked nodalc parses procedural bridge MLIR when configured",
        "nodal.bridge.source_map",
    ):
        require(
            token in bridge_tests,
            f"NODAL-INC33-045: procedural bridge/source-map test is missing {token!r}",
        )
''',
)

unit_tests = "tests/compiler/test_increment33.py"
replace_once(
    unit_tests,
    '''    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
''',
    '''    "core/scala/bridge/src/nodal/bridge/ScalaToMlirBridge.scala",
    "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala",
''',
)
replace_once(
    unit_tests,
    '''    def test_variable_type_diagnostic_mutation_is_rejected(self) -> None:
''',
    '''    def test_procedural_source_map_mutation_is_rejected(self) -> None:
        temporary, root = self.fixture()
        with temporary:
            path = root / "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    's"${record.identity}.read_$index"',
                    's"${record.identity}.read"',
                ),
                encoding="utf-8",
            )
            self.assert_rejected(root, "source-map coverage is incomplete")

    def test_variable_type_diagnostic_mutation_is_rejected(self) -> None:
''',
)
