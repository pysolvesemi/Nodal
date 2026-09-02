from pathlib import Path
import textwrap


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def insert_before_once(
    path: Path, anchor: str, block: str, present: str, label: str
) -> None:
    text = path.read_text(encoding="utf-8")
    if present in text:
        return
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    path.write_text(text.replace(anchor, block + anchor, 1), encoding="utf-8")


construction_path = Path("core/scala/api/src/nodal/AnalogProceduralConstruction.scala")
replace_once(
    construction_path,
    "    val inferredDimension = ConstructionKernel.analogDimension(expression)\n"
    "    val dimension = inferredDimension\n"
    "      .filterNot(_ == \"unknown\")\n"
    "      .orElse(reads.headOption.map(_.valueType.dimension))\n"
    "      .orElse(inferredDimension)\n"
    "      .getOrElse(\"unknown\")\n",
    "    val dimension = ConstructionKernel\n"
    "      .analogDimension(expression)\n"
    "      .getOrElse(\"unknown\")\n",
    "procedural expression dimension block",
)

runtime_path = Path("core/scala/api/src/nodal/AnalogProceduralRuntime.scala")
runtime = runtime_path.read_text(encoding="utf-8")
if runtime.count("operationOrder: Int = -1") == 0:
    runtime = runtime.replace(
        "      declarationOrder: Int\n  )\n\n  final case class AssignmentRecord(",
        "      declarationOrder: Int,\n      operationOrder: Int = -1\n  )\n\n  final case class AssignmentRecord(",
        1,
    )
    runtime = runtime.replace(
        "      analyses: Vector[String],\n      source: Option[Source]\n  )\n\n  final case class Snapshot(",
        "      analyses: Vector[String],\n      source: Option[Source],\n      operationOrder: Int = -1\n  )\n\n  final case class Snapshot(",
        1,
    )
if runtime.count("operationOrder: Int = -1") != 2:
    raise SystemExit("Scala operation-order fields were not materialized exactly twice")
if "variables.size + assignments.size" not in runtime:
    replace_old = "      val record = VariableRecord(variable, initializer, source, variables.size)\n"
    replace_new = (
        "      val record = VariableRecord(\n"
        "        variable,\n"
        "        initializer,\n"
        "        source,\n"
        "        variables.size,\n"
        "        variables.size + assignments.size\n"
        "      )\n"
    )
    if runtime.count(replace_old) != 1:
        raise SystemExit("Scala declaration operation-order anchor is not unique")
    runtime = runtime.replace(replace_old, replace_new, 1)
    assignment_old = (
        "        canonicalAnalyses.toVector.sorted,\n"
        "        source\n"
        "      )\n"
    )
    assignment_new = (
        "        canonicalAnalyses.toVector.sorted,\n"
        "        source,\n"
        "        variables.size + assignments.size\n"
        "      )\n"
    )
    if runtime.count(assignment_old) != 1:
        raise SystemExit("Scala assignment operation-order anchor is not unique")
    runtime = runtime.replace(assignment_old, assignment_new, 1)
runtime_path.write_text(runtime, encoding="utf-8")

native_path = Path("core/native/include/nodal/AnalogProceduralRuntime.h")
native = native_path.read_text(encoding="utf-8")
if native.count("std::size_t operationOrder") == 0:
    native = native.replace(
        "  std::size_t declarationOrder = 0;\n};\n\nstruct AssignmentRecord {",
        "  std::size_t declarationOrder = 0;\n"
        "  std::size_t operationOrder = 0;\n"
        "};\n\nstruct AssignmentRecord {",
        1,
    )
    native = native.replace(
        "  std::optional<Source> source;\n};\n\nstruct Snapshot {",
        "  std::optional<Source> source;\n"
        "  std::size_t operationOrder = 0;\n"
        "};\n\nstruct Snapshot {",
        1,
    )
if native.count("std::size_t operationOrder") != 2:
    raise SystemExit("native operation-order fields were not materialized exactly twice")
if "variables_.size() + assignments_.size()" not in native:
    declaration_old = (
        "    VariableRecord record{variable, initializer, source, variables_.size()};\n"
    )
    declaration_new = (
        "    VariableRecord record{variable, initializer, source, variables_.size(),\n"
        "                          variables_.size() + assignments_.size()};\n"
    )
    if native.count(declaration_old) != 1:
        raise SystemExit("native declaration operation-order anchor is not unique")
    native = native.replace(declaration_old, declaration_new, 1)
    assignment_old = (
        "    assignments_.push_back(AssignmentRecord{canonical, target, value, assignments_.size(),\n"
        "                                            scopeStack_, guard, canonicalAnalyses, source});\n"
    )
    assignment_new = (
        "    assignments_.push_back(AssignmentRecord{\n"
        "        canonical, target, value, assignments_.size(), scopeStack_, guard,\n"
        "        canonicalAnalyses, source, variables_.size() + assignments_.size()});\n"
    )
    if native.count(assignment_old) != 1:
        raise SystemExit("native assignment operation-order anchor is not unique")
    native = native.replace(assignment_old, assignment_new, 1)
native_path.write_text(native, encoding="utf-8")

evidence_path = Path("core/scala/bridge/src/nodal/bridge/AnalogProceduralEvidence.scala")
evidence = evidence_path.read_text(encoding="utf-8")
if evidence.count("operation_order") < 4:
    evidence = evidence.replace(
        "\\\"declaration_order\\\":${record.declarationOrder},\\\"source\\\"",
        "\\\"declaration_order\\\":${record.declarationOrder},\\\"operation_order\\\":${record.operationOrder},\\\"source\\\"",
        1,
    )
    evidence = evidence.replace(
        "\\\"authored_order\\\":${record.authoredOrder},\\\"scope\\\"",
        "\\\"authored_order\\\":${record.authoredOrder},\\\"operation_order\\\":${record.operationOrder},\\\"scope\\\"",
        1,
    )
    evidence = evidence.replace(
        "declaration_order = ${record.declarationOrder}\"",
        "declaration_order = ${record.declarationOrder}, operation_order = ${record.operationOrder}\"",
        1,
    )
    evidence = evidence.replace(
        "authored_order = ${record.authoredOrder}, guard =",
        "authored_order = ${record.authoredOrder}, operation_order = ${record.operationOrder}, guard =",
        1,
    )
if evidence.count("operation_order") < 4:
    raise SystemExit("procedural evidence does not retain combined operation order")
evidence_path.write_text(evidence, encoding="utf-8")

renderer_path = Path("core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala")
renderer = renderer_path.read_text(encoding="utf-8")
if renderer.count('"operation_order" -> integer(record.operationOrder)') < 4:
    renderer = renderer.replace(
        '                "declaration_order" -> integer(record.declarationOrder),\n',
        '                "declaration_order" -> integer(record.declarationOrder),\n'
        '                "operation_order" -> integer(record.operationOrder),\n',
        1,
    )
    renderer = renderer.replace(
        '                "authored_order" -> integer(record.authoredOrder),\n',
        '                "authored_order" -> integer(record.authoredOrder),\n'
        '                "operation_order" -> integer(record.operationOrder),\n',
        1,
    )
    renderer = renderer.replace(
        '        "declaration_order" -> integer(record.declarationOrder),\n',
        '        "declaration_order" -> integer(record.declarationOrder),\n'
        '        "operation_order" -> integer(record.operationOrder),\n',
        1,
    )
    renderer = renderer.replace(
        '        "authored_order" -> integer(record.authoredOrder),\n',
        '        "authored_order" -> integer(record.authoredOrder),\n'
        '        "operation_order" -> integer(record.operationOrder),\n',
        1,
    )
if "private final case class OperationSpan" not in renderer:
    helper_start = renderer.index("  private final case class OrderSpan")
    helper_end = renderer.index("\n  private def renderScopeBody(", helper_start)
    operation_helpers = textwrap.indent(
        textwrap.dedent(
            '''\
            private final case class OperationSpan(first: Int, last: Int)

            private def declarationOperationOrder(
                record: AnalogProceduralRuntime.VariableRecord
            ): Int =
              if record.operationOrder >= 0 then record.operationOrder
              else record.declarationOrder * 2

            private def assignmentOperationOrder(
                record: AnalogProceduralRuntime.AssignmentRecord
            ): Int =
              if record.operationOrder >= 0 then record.operationOrder
              else record.authoredOrder * 2 + 1

            private def scopeOperationOrders(node: ScopeNode): Vector[Int] =
              node.declarations.iterator.map(declarationOperationOrder).toVector ++
                node.assignments.iterator.map(assignmentOperationOrder).toVector ++
                node.children.valuesIterator.flatMap(child =>
                  scopeOperationOrders(child)
                ).toVector

            private def operationSpan(event: Event): OperationSpan = event match
              case DeclarationEvent(record) =>
                val order = declarationOperationOrder(record)
                OperationSpan(order, order)
              case AssignmentEvent(record) =>
                val order = assignmentOperationOrder(record)
                OperationSpan(order, order)
              case ScopeEvent(scope) =>
                val orders = scopeOperationOrders(scope)
                require(orders.nonEmpty, "procedural lexical scope contains no operations")
                OperationSpan(orders.min, orders.max)

            private def deterministicEventKey(
                event: Event
            ): (Int, Int, Int, String, Int, Int, Int, String) =
              val span = operationSpan(event)
              event.source match
                case Some(source) =>
                  (
                    span.first,
                    span.last,
                    0,
                    source.file,
                    source.line,
                    source.column,
                    event.category,
                    event.identity
                  )
                case None =>
                  (
                    span.first,
                    span.last,
                    1,
                    "",
                    Int.MaxValue,
                    Int.MaxValue,
                    event.category,
                    event.identity
                  )

            private def orderEvents(events: Vector[Event]): Vector[Event] =
              val spans = events.map(operationSpan)
              for
                leftIndex <- events.indices
                rightIndex <- (leftIndex + 1) until events.size
              do
                val left = spans(leftIndex)
                val right = spans(rightIndex)
                require(
                  left.last < right.first || right.last < left.first,
                  "procedural operation ranges overlap across lexical events"
                )
              events.sortBy(deterministicEventKey)
            '''
        ),
        "  ",
    ).rstrip()
    renderer = renderer[:helper_start] + operation_helpers + "\n" + renderer[helper_end:]
if renderer.count('"operation_order" -> integer(record.operationOrder)') < 4:
    raise SystemExit("authoritative MLIR renderer does not retain operation order")
renderer_path.write_text(renderer, encoding="utf-8")

construction_tests_path = Path(
    "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
)
insert_before_once(
    construction_tests_path,
    "final class PublicAnalogCompoundVoltage extends Module:\n",
    textwrap.dedent(
        '''\
        final class PublicAnalogCompoundReadDimensionMismatch extends Module:
          val voltage: Variable[Real] = variable(Real, 0.0.V)

          analogProcedure:
            voltage := voltage + 1.0.real

        '''
    ),
    "final class PublicAnalogCompoundReadDimensionMismatch",
    "compound-read mismatch fixture",
)
insert_before_once(
    construction_tests_path,
    '    test("public compound voltage assignment retains its physical dimension"):\n',
    textwrap.indent(
        textwrap.dedent(
            '''\
            test("public incompatible compound dimensions are rejected without read fallback"):
              val failure =
                scala.util.Try(
                  ConstructionKernel.inspect(new PublicAnalogCompoundReadDimensionMismatch)
                ).failed.get
                  .asInstanceOf[AnalogProceduralRuntime.Failure]
              assert(failure.diagnostic.code == "NODAL-ANALOG-033-013")

            '''
        ),
        "    ",
    ),
    "public incompatible compound dimensions are rejected without read fallback",
    "compound-read mismatch regression",
)

bridge_tests_path = Path(
    "core/scala/testkit/test/src/nodal/internal/testkit/ScalaToMlirBridgeTests.scala"
)
insert_before_once(
    bridge_tests_path,
    "object ScalaToMlirBridgeTests extends TestSuite:\n",
    textwrap.dedent(
        '''\
        final class BridgeProceduralInitializerDependency extends Module:
          analogProcedure:
            val source: Variable[Real] = variable(Real)
            source := 1.0.V
            val sink: Variable[Real] = variable(Real, source)
            source := 2.0.V

        '''
    ),
    "final class BridgeProceduralInitializerDependency",
    "initializer-dependency fixture",
)
insert_before_once(
    bridge_tests_path,
    '    test("snapshot insertion order does not affect the bridge"):\n',
    textwrap.indent(
        textwrap.dedent(
            '''\
            test("initializing assignments precede dependent declarations independent of provenance"):
              val snapshot = ConstructionKernel.inspect(new BridgeProceduralInitializerDependency)
              val program = snapshot.analogProcedural.head
              assert(program.variables.map(_.operationOrder) == Vector(0, 2))
              assert(program.assignments.map(_.operationOrder) == Vector(1, 3))

              val inverted = program.copy(
                variables = program.variables.map: record =>
                  val source =
                    if record.operationOrder == 0 then
                      AnalogProceduralRuntime.Source("z-helper.scala", 400, 1)
                    else AnalogProceduralRuntime.Source("a-helper.scala", 1, 1)
                  record.copy(source = Some(source)),
                assignments = program.assignments.map: record =>
                  val source =
                    if record.operationOrder == 1 then
                      AnalogProceduralRuntime.Source("z-helper.scala", 300, 1)
                    else AnalogProceduralRuntime.Source("a-helper.scala", 2, 1)
                  record.copy(source = Some(source))
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

            '''
        ),
        "    ",
    ),
    "initializing assignments precede dependent declarations independent of provenance",
    "initializer-dependency chronology regression",
)

scala_witness_path = Path(
    "examples/continuousTimeApi/src/nodal/increment33fixture/Increment33RuntimeCheck.scala"
)
scala_witness = scala_witness_path.read_text(encoding="utf-8")
if "snapshot.variables.map(_.operationOrder)" not in scala_witness:
    anchor = "    assert(snapshot.assignments.map(_.authoredOrder) == Vector(0, 1, 2, 3, 4))\n"
    block = (
        anchor
        + "    assert(snapshot.variables.map(_.operationOrder) == Vector(0, 1, 2, 7))\n"
        + "    assert(snapshot.assignments.map(_.operationOrder) == Vector(3, 4, 5, 6, 8))\n"
    )
    if scala_witness.count(anchor) != 1:
        raise SystemExit("Scala witness authored-order anchor is not unique")
    scala_witness = scala_witness.replace(anchor, block, 1)
scala_witness_path.write_text(scala_witness, encoding="utf-8")

native_witness_path = Path(
    "tests/compiler/fixtures/increment33/analog_procedural_runtime_test.cpp"
)
native_witness = native_witness_path.read_text(encoding="utf-8")
if "snapshot.variables[0].operationOrder" not in native_witness:
    anchor = (
        "  for (std::size_t index = 0; index < snapshot.assignments.size(); ++index)\n"
        "    assert(snapshot.assignments[index].authoredOrder == index);\n"
    )
    block = anchor + (
        "  assert((std::vector<std::size_t>{snapshot.variables[0].operationOrder,\n"
        "                                   snapshot.variables[1].operationOrder,\n"
        "                                   snapshot.variables[2].operationOrder,\n"
        "                                   snapshot.variables[3].operationOrder} ==\n"
        "          std::vector<std::size_t>{0, 1, 2, 7}));\n"
        "  assert((std::vector<std::size_t>{snapshot.assignments[0].operationOrder,\n"
        "                                   snapshot.assignments[1].operationOrder,\n"
        "                                   snapshot.assignments[2].operationOrder,\n"
        "                                   snapshot.assignments[3].operationOrder,\n"
        "                                   snapshot.assignments[4].operationOrder} ==\n"
        "          std::vector<std::size_t>{3, 4, 5, 6, 8}));\n"
    )
    if native_witness.count(anchor) != 1:
        raise SystemExit("native witness authored-order anchor is not unique")
    native_witness = native_witness.replace(anchor, block, 1)
native_witness_path.write_text(native_witness, encoding="utf-8")

checker_path = Path("scripts/check_increment33.py")
checker = checker_path.read_text(encoding="utf-8")
if checker.count('        "operationOrder",\n') < 2:
    positions = []
    start = 0
    token = '        "authoredOrder",\n'
    while True:
        index = checker.find(token, start)
        if index < 0:
            break
        positions.append(index)
        start = index + len(token)
    if len(positions) < 2:
        raise SystemExit("checker authored-order tokens are incomplete")
    offset = 0
    for index in positions[:2]:
        insert_at = index + offset + len(token)
        checker = checker[:insert_at] + '        "operationOrder",\n' + checker[insert_at:]
        offset += len('        "operationOrder",\n')
if "NODAL-INC33-062" not in checker:
    anchor = (
        "    require(\n"
        "        \"nested procedural scopes preserve declaration and assignment chronology\"\n"
        "        in bridge_tests,\n"
        "        \"NODAL-INC33-061: nested chronology regression is missing\",\n"
        "    )\n"
    )
    additions = anchor + (
        "    require(\n"
        "        \"public incompatible compound dimensions are rejected without read fallback\"\n"
        "        in construction_tests,\n"
        "        \"NODAL-INC33-062: compound read-dimension regression is missing\",\n"
        "    )\n"
        "    require(\n"
        "        \"initializing assignments precede dependent declarations independent of provenance\"\n"
        "        in bridge_tests,\n"
        "        \"NODAL-INC33-063: initializer dependency chronology regression is missing\",\n"
        "    )\n"
        "    require(\n"
        "        \"operationOrder\" in procedural_bridge,\n"
        "        \"NODAL-INC33-064: combined procedural operation order is not serialized\",\n"
        "    )\n"
    )
    if checker.count(anchor) != 1:
        raise SystemExit("checker nested-regression anchor is not unique")
    checker = checker.replace(anchor, additions, 1)
checker_path.write_text(checker, encoding="utf-8")

mutation_path = Path("tests/compiler/test_increment33.py")
mutations = mutation_path.read_text(encoding="utf-8")
if "test_initializer_dependency_chronology_mutation_is_rejected" not in mutations:
    anchor = '\nif __name__ == "__main__":\n'
    methods = textwrap.indent(
        textwrap.dedent(
            '''\
            def test_compound_read_dimension_regression_mutation_is_rejected(self) -> None:
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

            '''
        ),
        "    ",
    )
    if mutations.count(anchor) != 1:
        raise SystemExit("mutation insertion anchor is not unique")
    mutations = mutations.replace(anchor, "\n" + methods + anchor, 1)
mutation_path.write_text(mutations, encoding="utf-8")

required = {
    construction_path: [".analogDimension(expression)", "getOrElse(\"unknown\")"],
    runtime_path: ["operationOrder: Int = -1"],
    native_path: ["std::size_t operationOrder"],
    evidence_path: ["operation_order"],
    renderer_path: ["OperationSpan", "operation_order"],
    construction_tests_path: ["PublicAnalogCompoundReadDimensionMismatch"],
    bridge_tests_path: ["BridgeProceduralInitializerDependency"],
    checker_path: ["NODAL-INC33-064"],
    mutation_path: ["test_initializer_dependency_chronology_mutation_is_rejected"],
}
for path, tokens in required.items():
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"{path}: missing required token {token!r}")
