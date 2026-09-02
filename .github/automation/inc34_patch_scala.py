#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def insert_before(text: str, marker: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    return replace_once(text, marker, addition + marker, label)


def patch_renderer(root: Path) -> None:
    path = root / "core/scala/bridge/src/nodal/bridge/AnalogProceduralMlir.scala"
    text = read(path)

    if "private def structuredInventory(" not in text:
        text = replace_once(
            text,
            "          variables ++ assignments\n",
            "          variables ++ assignments ++ structuredInventory(program)\n",
            "structured inventory call",
        )
        inventory_helpers = r'''
  private def structuredInventory(
      program: AnalogProceduralRuntime.Snapshot
  ): Vector[String] =
    program.controlFlow.toVector.flatMap: control =>
      val result = mutable.ArrayBuffer.empty[String]

      def add(kind: String, identity: String, source: Option[AnalogProceduralRuntime.Source]): Unit =
        result += dictionary(
          Vector(
            "kind" -> quoted(kind),
            "identity" -> quoted(identity),
            "owner" -> quoted(program.owner)
          ) ++ sourceFields(source)
        )

      def visitBlock(block: AnalogControlFlowRuntime.Block): Unit =
        add("control_block", block.identity, block.source)
        block.statements.foreach:
          case value: AnalogControlFlowRuntime.Statement.Declare =>
            add("control_declaration", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Assign =>
            add("control_assignment", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Read =>
            add("control_read", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Scope =>
            add("control_scope", value.identity, value.source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.IfThenElse =>
            add("control_if", value.identity, value.source)
            value.branches.zipWithIndex.foreach: (branch, index) =>
              add("control_if_condition", s"${value.identity}.condition_$index", branch.condition.source)
              visitBlock(branch.body)
            value.otherwise.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.CaseStatement =>
            add("control_case", value.identity, value.source)
            add("control_case_selector", s"${value.identity}.selector", value.selector.source)
            value.arms.foreach(arm => visitBlock(arm.body))
            value.default.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.Loop =>
            add("control_loop", value.identity, value.source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.Break =>
            add("control_break", value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Continue =>
            add("control_continue", value.identity, value.source)

      visitBlock(control.root)
      program.controlExpressions
        .sortBy(value => (value.identity, value.role))
        .foreach: expression =>
          result += dictionary(
            Vector(
              "kind" -> quoted("control_expression"),
              "identity" -> quoted(expression.identity),
              "role" -> quoted(expression.role),
              "owner" -> quoted(program.owner),
              "value" -> quoted(expression.value.rendered),
              "value_kind" -> quoted(expression.value.valueType.kind.label),
              "value_dimension" -> quoted(
                canonicalDimension(expression.value.valueType.dimension)
              ),
              "reads" -> array(expression.value.reads.map(value => quoted(value.identity)))
            ) ++ sourceFields(expression.source)
          )
      result.toVector

'''
        text = insert_before(
            text,
            "  def sourceMapEntries(snapshot: ConstructionSnapshot): Vector[String] =\n",
            inventory_helpers,
            "structured inventory helpers",
        )

    if "structuredSourceMapEntries(program)" not in text:
        text = replace_once(
            text,
            "        wrappers ++ scopes ++ variables ++ assignments ++ reads\n",
            "        wrappers ++ scopes ++ variables ++ assignments ++ reads ++\n"
            "          structuredSourceMapEntries(program)\n",
            "structured source-map call",
        )
        source_helpers = r'''
  private def structuredSourceMapEntries(
      program: AnalogProceduralRuntime.Snapshot
  ): Vector[String] =
    program.controlFlow.toVector.flatMap: control =>
      val entries = mutable.ArrayBuffer.empty[String]

      def add(path: String, source: Option[AnalogProceduralRuntime.Source]): Unit =
        source.foreach(value => entries += sourceMapEntry(path, value))

      def visitBlock(block: AnalogControlFlowRuntime.Block): Unit =
        add(block.identity, block.source)
        block.statements.foreach:
          case value: AnalogControlFlowRuntime.Statement.Declare =>
            add(value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Assign =>
            add(value.identity, value.source)
            program.controlExpressions
              .find(expression =>
                expression.identity == value.identity &&
                  expression.role == "assignment-value"
              )
              .foreach: expression =>
                expression.value.reads.indices.foreach: index =>
                  add(s"${value.identity}.read_$index", expression.source.orElse(value.source))
          case value: AnalogControlFlowRuntime.Statement.Read =>
            add(value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Scope =>
            add(value.identity, value.source)
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.IfThenElse =>
            add(value.identity, value.source)
            value.branches.zipWithIndex.foreach: (branch, index) =>
              add(s"${value.identity}.condition_$index", branch.condition.source)
              visitBlock(branch.body)
            value.otherwise.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.CaseStatement =>
            add(value.identity, value.source)
            add(s"${value.identity}.selector", value.selector.source)
            value.arms.foreach(arm => visitBlock(arm.body))
            value.default.foreach(visitBlock)
          case value: AnalogControlFlowRuntime.Statement.Loop =>
            add(value.identity, value.source)
            program.controlExpressions
              .find(expression =>
                expression.identity == value.identity && expression.role == "loop-bound"
              )
              .foreach(expression => add(s"${value.identity}.bound", expression.source))
            visitBlock(value.body)
          case value: AnalogControlFlowRuntime.Statement.Break =>
            add(value.identity, value.source)
          case value: AnalogControlFlowRuntime.Statement.Continue =>
            add(value.identity, value.source)

      visitBlock(control.root)
      entries.toVector

'''
        text = insert_before(
            text,
            "  private def sourceMapEntry(\n",
            source_helpers,
            "structured source-map helpers",
        )

    if "private def renderStructuredProgram(" not in text:
        old_header = '''  private def renderProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int
  ): String =
    val root = ScopeNode(Vector("procedure"))
'''
        new_header = '''  private def renderProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int
  ): String =
    program.controlFlow match
      case Some(control) => renderStructuredProgram(program, programIndex, control)
      case None => renderFlatProgram(program, programIndex)

  private def renderFlatProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int
  ): String =
    val root = ScopeNode(Vector("procedure"))
'''
        text = replace_once(text, old_header, new_header, "render program dispatch")

        structured_renderer = r'''
  private def renderStructuredProgram(
      program: AnalogProceduralRuntime.Snapshot,
      programIndex: Int,
      control: AnalogControlFlowConstruction.Snapshot
  ): String =
    val variables = program.variables.sortBy(_.declarationOrder)
    val variableRecords = variables.map(record => record.variable.identity -> record).toMap
    val variableHandles = variables.map(record =>
      record.variable.identity ->
        s"%procedural_${programIndex}_variable_${record.declarationOrder}"
    ).toMap
    val variableTypes = variables.map(record =>
      record.variable.identity -> variableType(record.variable)
    ).toMap
    val expressions = program.controlExpressions
      .groupBy(value => value.identity -> value.role)
      .view
      .mapValues(_.head)
      .toMap
    var readSerial = 0
    var authoredAssignmentOrder = 0

    def expression(
        identity: String,
        role: String
    ): AnalogProceduralRuntime.ControlExpressionRecord =
      expressions.getOrElse(
        identity -> role,
        throw new IllegalArgumentException(
          s"structured analog operation '$identity' is missing '$role' payload"
        )
      )

    def label(value: AnalogControlFlowRuntime.CaseLabel): String = value match
      case AnalogControlFlowRuntime.CaseLabel.Integer(number) => s"integer:$number"
      case AnalogControlFlowRuntime.CaseLabel.Boolean(boolean) => s"boolean:$boolean"

    def renderReads(
        statementIdentity: String,
        reads: Vector[AnalogProceduralRuntime.Variable],
        source: Option[AnalogProceduralRuntime.Source]
    ): (Vector[String], Vector[String], Vector[String]) =
      val lines = mutable.ArrayBuffer.empty[String]
      val values = mutable.ArrayBuffer.empty[String]
      val types = mutable.ArrayBuffer.empty[String]
      reads.zipWithIndex.foreach: (read, index) =>
        val result = s"%procedural_${programIndex}_structured_read_$readSerial"
        readSerial += 1
        val resultType = valueType(read.valueType)
        val readPath = s"$statementIdentity.read_$index"
        lines += operation(
          "nodal.analog_variable_read",
          results = Vector(result),
          operands = Vector(variableHandles(read.identity)),
          operandTypes = Vector(variableTypes(read.identity)),
          resultTypes = Vector(resultType),
          attributes = Vector(
            "read_id" -> quoted(readPath),
            "owner" -> quoted(program.owner),
            "metadata" -> metadata(readPath, source)
          ),
          source = source
        )
        values += result
        types += resultType
      (lines.toVector, values.toVector, types.toVector)

    def renderBlock(block: AnalogControlFlowRuntime.Block): String =
      block.statements.flatMap:
        case declaration: AnalogControlFlowRuntime.Statement.Declare =>
          val record = variableRecords.getOrElse(
            declaration.variable,
            throw new IllegalArgumentException(
              s"structured declaration '${declaration.variable}' has no variable record"
            )
          )
          Vector(renderDeclaration(record, program, variableHandles))

        case assignment: AnalogControlFlowRuntime.Statement.Assign =>
          val payload = expression(assignment.identity, "assignment-value")
          val (readLines, readValues, readTypes) =
            renderReads(assignment.identity, payload.value.reads, assignment.source)
          val order = authoredAssignmentOrder
          authoredAssignmentOrder += 1
          val assign = operation(
            "nodal.analog_assign",
            operands = Vector(variableHandles(assignment.target)) ++ readValues,
            operandTypes = Vector(variableTypes(assignment.target)) ++ readTypes,
            attributes = Vector(
              "statement_id" -> quoted(assignment.identity),
              "authored_order" -> integer(order),
              "operation_order" -> integer(order),
              "owner" -> quoted(program.owner),
              "value_kind" -> quoted(payload.value.valueType.kind.label),
              "value_dimension" -> quoted(
                canonicalDimension(payload.value.valueType.dimension)
              ),
              "analyses" -> array(Vector(quoted("dc"), quoted("transient"))),
              "guard_present" -> boolean(false),
              "guard_value" -> quoted(""),
              "guard_kind" -> quoted(""),
              "guard_dimension" -> quoted(""),
              "guard_reads" -> array(Vector.empty),
              "metadata" -> metadata(
                assignment.identity,
                assignment.source,
                Vector(
                  "scope" -> array(Vector(quoted(block.identity))),
                  "value" -> quoted(payload.value.rendered)
                )
              )
            ),
            source = assignment.source
          )
          readLines :+ assign

        case read: AnalogControlFlowRuntime.Statement.Read =>
          val variable = variableRecords.getOrElse(
            read.variable,
            throw new IllegalArgumentException(
              s"structured read '${read.variable}' has no variable record"
            )
          ).variable
          val result = s"%procedural_${programIndex}_structured_read_$readSerial"
          readSerial += 1
          Vector(
            operation(
              "nodal.analog_variable_read",
              results = Vector(result),
              operands = Vector(variableHandles(variable.identity)),
              operandTypes = Vector(variableTypes(variable.identity)),
              resultTypes = Vector(valueType(variable.valueType)),
              attributes = Vector(
                "read_id" -> quoted(read.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(read.identity, read.source)
              ),
              source = read.source
            )
          )

        case scope: AnalogControlFlowRuntime.Statement.Scope =>
          Vector(
            operation(
              "nodal.analog_scope",
              attributes = Vector(
                "scope_id" -> quoted(scope.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(scope.identity, scope.source)
              ),
              regions = Vector(renderBlock(scope.body)),
              source = scope.source
            )
          )

        case conditional: AnalogControlFlowRuntime.Statement.IfThenElse =>
          val arms = conditional.branches.zipWithIndex.map: (branch, index) =>
            val condition = branch.condition
            operation(
              "nodal.analog_if_arm",
              attributes = Vector(
                "arm_id" -> quoted(branch.body.identity),
                "owner" -> quoted(program.owner),
                "is_else" -> boolean(false),
                "stage" -> quoted(condition.stage.toString.toLowerCase),
                "condition_value" -> quoted(condition.rendered),
                "condition_kind" -> quoted(condition.valueType.kind.label),
                "condition_dimension" -> quoted(
                  canonicalDimension(condition.valueType.dimension)
                ),
                "condition_reads" -> array(condition.reads.toVector.sorted.map(quoted)),
                "static_value_present" -> boolean(condition.staticValue.nonEmpty),
                "static_value" -> boolean(condition.staticValue.getOrElse(false)),
                "metadata" -> metadata(
                  s"${conditional.identity}.condition_$index",
                  condition.source
                )
              ),
              regions = Vector(renderBlock(branch.body)),
              source = condition.source.orElse(branch.body.source)
            )
          val otherwise = conditional.otherwise.toVector.map: body =>
            operation(
              "nodal.analog_if_arm",
              attributes = Vector(
                "arm_id" -> quoted(body.identity),
                "owner" -> quoted(program.owner),
                "is_else" -> boolean(true),
                "stage" -> quoted("else"),
                "condition_value" -> quoted(""),
                "condition_kind" -> quoted(""),
                "condition_dimension" -> quoted(""),
                "condition_reads" -> array(Vector.empty),
                "static_value_present" -> boolean(false),
                "static_value" -> boolean(false),
                "metadata" -> metadata(body.identity, body.source)
              ),
              regions = Vector(renderBlock(body)),
              source = body.source
            )
          Vector(
            operation(
              "nodal.analog_if",
              attributes = Vector(
                "statement_id" -> quoted(conditional.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(conditional.identity, conditional.source)
              ),
              regions = Vector((arms ++ otherwise).mkString("\n")),
              source = conditional.source
            )
          )

        case selection: AnalogControlFlowRuntime.Statement.CaseStatement =>
          val selector = selection.selector
          val arms = selection.arms.map: arm =>
            operation(
              "nodal.analog_case_arm",
              attributes = Vector(
                "arm_id" -> quoted(arm.body.identity),
                "owner" -> quoted(program.owner),
                "is_default" -> boolean(false),
                "labels" -> array(arm.labels.map(value => quoted(label(value)))),
                "metadata" -> metadata(arm.body.identity, arm.body.source)
              ),
              regions = Vector(renderBlock(arm.body)),
              source = arm.body.source
            )
          val default = selection.default.toVector.map: body =>
            operation(
              "nodal.analog_case_arm",
              attributes = Vector(
                "arm_id" -> quoted(body.identity),
                "owner" -> quoted(program.owner),
                "is_default" -> boolean(true),
                "labels" -> array(Vector.empty),
                "metadata" -> metadata(body.identity, body.source)
              ),
              regions = Vector(renderBlock(body)),
              source = body.source
            )
          val staticSelector = selector.staticValue.map(label).getOrElse("")
          Vector(
            operation(
              "nodal.analog_case",
              attributes = Vector(
                "statement_id" -> quoted(selection.identity),
                "owner" -> quoted(program.owner),
                "selector_value" -> quoted(selector.rendered),
                "selector_kind" -> quoted(selector.kind.label),
                "selector_dimension" -> quoted(canonicalDimension(selector.dimension)),
                "selector_reads" -> array(selector.reads.toVector.sorted.map(quoted)),
                "static_value_present" -> boolean(selector.staticValue.nonEmpty),
                "static_value" -> quoted(staticSelector),
                "metadata" -> metadata(
                  s"${selection.identity}.selector",
                  selector.source.orElse(selection.source)
                )
              ),
              regions = Vector((arms ++ default).mkString("\n")),
              source = selection.source.orElse(selector.source)
            )
          )

        case loop: AnalogControlFlowRuntime.Statement.Loop =>
          val stage = loop.stage match
            case AnalogControlFlowRuntime.LoopStage.Static => "static"
            case AnalogControlFlowRuntime.LoopStage.RuntimeBounded => "runtime"
          val bound = expressions.get(loop.identity -> "loop-bound")
          Vector(
            operation(
              "nodal.analog_loop",
              attributes = Vector(
                "statement_id" -> quoted(loop.identity),
                "owner" -> quoted(program.owner),
                "stage" -> quoted(stage),
                "minimum_iterations" -> integer(loop.minimumIterations),
                "maximum_iterations" -> integer(loop.maximumIterations),
                "bound_value" -> quoted(
                  bound.map(_.value.rendered).getOrElse(
                    loop.staticTripCount.map(_.toString).getOrElse("")
                  )
                ),
                "bound_kind" -> quoted(loop.boundValueType.kind.label),
                "bound_dimension" -> quoted(
                  canonicalDimension(loop.boundValueType.dimension)
                ),
                "bound_reads" -> array(loop.boundReads.toVector.sorted.map(quoted)),
                "static_trip_count_present" -> boolean(loop.staticTripCount.nonEmpty),
                "static_trip_count" -> integer(loop.staticTripCount.getOrElse(0)),
                "metadata" -> metadata(loop.identity, loop.source)
              ),
              regions = Vector(renderBlock(loop.body)),
              source = loop.source
            )
          )

        case value: AnalogControlFlowRuntime.Statement.Break =>
          Vector(
            operation(
              "nodal.analog_break",
              attributes = Vector(
                "statement_id" -> quoted(value.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(value.identity, value.source)
              ),
              source = value.source
            )
          )

        case value: AnalogControlFlowRuntime.Statement.Continue =>
          Vector(
            operation(
              "nodal.analog_continue",
              attributes = Vector(
                "statement_id" -> quoted(value.identity),
                "owner" -> quoted(program.owner),
                "metadata" -> metadata(value.identity, value.source)
              ),
              source = value.source
            )
          )
      .mkString("\n")

    val allSources =
      (program.variables.flatMap(_.source) ++
        program.controlExpressions.flatMap(_.source) ++
        control.root.source.toVector)
        .sortBy(value => (value.file, value.line, value.column))
    val procedureSource = allSources.headOption
    val procedurePath = s"${program.owner}.analogProcedure"
    val procedure = operation(
      "nodal.analog_procedure",
      attributes = Vector(
        "owner" -> quoted(program.owner),
        "metadata" -> metadata(procedurePath, procedureSource)
      ),
      regions = Vector(renderBlock(control.root)),
      source = procedureSource
    )
    val analogPath = s"${program.owner}.analogProcedural"
    operation(
      "nodal.analog",
      attributes = Vector("metadata" -> metadata(analogPath, procedureSource)),
      regions = Vector(procedure),
      source = procedureSource
    )

'''
        text = insert_before(
            text,
            "  private sealed trait Event:\n",
            structured_renderer,
            "structured renderer",
        )

    write(path, text)


def patch_bridge_tests(root: Path) -> None:
    path = root / "core/scala/bridge/test/src/nodal/bridge/ScalaToMlirBridgeTests.scala"
    text = read(path)

    fixture = r'''
final class BridgeStructuredProceduralTop extends Module:
  val select: Variable[Bool] = variable(Bool, false.B)
  val mode: Variable[Integer] = variable(Integer, 0.integer)
  val iterations: Variable[Integer] = variable(Integer, 1.integer)
  val value: Variable[Real] = variable(Real)
  val sink: Variable[Real] = variable(Real, 0.0.real)

  analogProcedure:
    analogCase(mode):
      analogCaseArm(0):
        value := 1.0.real
      analogCaseDefault:
        value := 2.0.real
    analogLoop(iterations, maximumIterations = 4, minimumIterations = 1):
      analogConditional:
        analogWhen(select):
          value := 3.0.real
          analogContinue()
        analogOtherwise:
          value := 4.0.real
          analogBreak()
    sink := value

'''
    if "final class BridgeStructuredProceduralTop" not in text:
        text = insert_before(
            text,
            "object ScalaToMlirBridgeTests extends TestSuite:\n",
            fixture,
            "bridge structured fixture",
        )

    test = r'''
    test("structured analog control flow serializes without flattening"):
      val first = ScalaToMlirBridge.lower(new BridgeStructuredProceduralTop)
      val second = ScalaToMlirBridge.lower(new BridgeStructuredProceduralTop)

      assert(first == second)
      assert(first.text.contains("\"nodal.analog_if\""))
      assert(first.text.contains("\"nodal.analog_if_arm\""))
      assert(first.text.contains("\"nodal.analog_case\""))
      assert(first.text.contains("\"nodal.analog_case_arm\""))
      assert(first.text.contains("\"nodal.analog_loop\""))
      assert(first.text.contains("\"nodal.analog_break\""))
      assert(first.text.contains("\"nodal.analog_continue\""))
      assert(first.text.contains("static_trip_count_present"))
      assert(first.text.contains("minimum_iterations = 1 : i64"))
      assert(first.text.contains("maximum_iterations = 4 : i64"))
      assert(first.text.contains("nodal.bridge.analog_procedural"))
      assert(first.text.contains("semantic_path = \"BridgeStructuredProceduralTop.case_"))
      assert(first.text.contains("semantic_path = \"BridgeStructuredProceduralTop.loop_"))
      val snapshot = ConstructionKernel.inspect(new BridgeStructuredProceduralTop)
      val program = snapshot.analogProcedural.head
      assert(program.assignments.isEmpty)
      assert(program.controlFlow.nonEmpty)
      assert(program.controlExpressions.exists(_.role == "assignment-value"))
      assert(program.controlExpressions.exists(_.role == "loop-bound"))

'''
    if 'test("structured analog control flow serializes without flattening")' not in text:
        text = insert_before(
            text,
            '    test("snapshot insertion order does not affect the bridge"):\n',
            test,
            "structured bridge test",
        )

    write(path, text)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    patch_renderer(root)
    patch_bridge_tests(root)
    print("Increment 34 Scala structured MLIR patch materialized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
