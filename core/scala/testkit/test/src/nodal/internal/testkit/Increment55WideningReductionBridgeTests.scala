package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

final class ReductionInvariantBridgeSource extends Module, ReductionClock:
  val flags = in(Vec(Bool, 5))
  val modes = in(Vec(EnumType[ReductionMode](), 5))
  val flag = flags.reduceBalancedTree((left, _) => left)
  val mode = modes.reduce((_, right) => right)

final class ReductionInnerDimensionBridgeSource extends Module, ReductionClock:
  val input = in(Vec(Vec(UInt(4), 4), 3))
  val result = input.reduceBalancedTree((left, right) => left.zipWith(right)((a, b) => a + b))

object Increment55WideningReductionBridgeTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot, text: String): Unit =
    val error = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(error.isInstanceOf[BridgeException])
    assert(error.getMessage.contains(text))

  private def collection(snapshot: ConstructionSnapshot, path: String)(
      update: KernelDigitalCollectionSnapshot => KernelDigitalCollectionSnapshot
  ): ConstructionSnapshot =
    val changed = update(snapshot.digitalCollections.find(_.path == path).get)
    snapshot.copy(
      digitalCollections =
        snapshot.digitalCollections.map(value => if value.path == path then changed else value),
      digitalExpressions = snapshot.digitalExpressions.map(value =>
        if value.path == path then
          value.copy(
            dataType = changed.dataType,
            operands = changed.inputs ++ changed.seed ++ changed.captures
          )
        else value
      )
    )

  private def expression(snapshot: ConstructionSnapshot, path: String)(
      update: KernelDigitalExpressionSnapshot => KernelDigitalExpressionSnapshot
  ): ConstructionSnapshot = snapshot.copy(digitalExpressions =
    snapshot.digitalExpressions.map(value =>
      if value.path == path then update(value) else value
    )
  )

  val tests: Tests = Tests:
    test("symbolic widening schedules raw paired max before typed value use"):
      val snapshot = ConstructionKernel.inspect(new PublicWideningSums)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 4)
      assert(document.text.contains("operator_name = \"ceil_log2\""))
      assert(document.text.contains("operator_name = \"choose_zero\""))
      val first = snapshot.digitalCollections.head
      val maximum = snapshot.parameterExpressions.find(value =>
        value.region.contains(first.path) &&
          value.operation == "static_integer_max"
      ).get
      assert(maximum.operands == first.arguments.take(2).map(_.path))
      val widened = snapshot.parameterExpressions.find(value =>
        value.region.contains(first.path) && value.operation == "analog_add" &&
          value.operands.headOption.contains(maximum.path)
      ).get
      val growthArgument = first.arguments.indexWhere(_.path == widened.operands(1))
      assert(growthArgument >= 4)
      val growth = snapshot.parameterExpressions.find(
        _.path == first.captures(growthArgument - 4)
      ).get
      assert(growth.literal.contains("1"))
      val addition = snapshot.digitalExpressions.find(value =>
        value.region.contains(first.path) && value.operation == "digital_add"
      ).get
      assert(addition.dataType == s"UInt(${widened.path})")
      val maxLine = document.text.linesIterator.find(line =>
        line.contains(s"semantic_path = \"${maximum.path}\"") &&
          line.contains("operator_name = \"max\"") &&
          line.contains("_arg0") && line.contains("_arg1")
      ).get
      val maxSsa = maxLine.trim.takeWhile(_ != ' ')
      val widthLine = document.text.linesIterator.find(line =>
        line.contains(s"semantic_path = \"${widened.path}\"") &&
          line.contains("operator_name = \"add\"")
      ).get
      assert(widthLine.contains(s"\"nodal.const_expr\"($maxSsa, "))
      assert(widthLine.contains(s"_arg$growthArgument)"))
      val additionLine = document.text.linesIterator.find(line =>
        line.contains(s"semantic_path = \"${addition.path}\"") &&
          line.contains("operator_name = \"digital_add\"")
      ).get
      assert(additionLine.contains(s"-> !nodal.digital<\"uint\", \"${widened.path}\">"))
      assert(document.text.indexOf(maxLine) < document.text.indexOf(widthLine))
      assert(document.text.indexOf(widthLine) < document.text.indexOf(additionLine))
      assert(document.text == ScalaToMlirBridge.lower(new PublicWideningSums).text)

    test("whole record graph keeps selected Bits1 invariant Bool enum and shared predicate"):
      val snapshot = ConstructionKernel.inspect(new PublicRecordReductions)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val combined = snapshot.digitalCollections(1)
      assert(combined.arguments.take(8).forall(_.dataType == "Integer"))
      assert(document.text.contains("!nodal.struct<\"Candidate\""))
      assert(document.text.contains("!nodal.enum<"))
      assert(document.text.contains("operator_name = \"digital_alias\""))
      assert(document.text.linesIterator.count(_.contains("operator_name = \"digital_ge\"")) == 2)
      assert(document.text.linesIterator.count(_.contains("operator_name = \"digital_select\"")) ==
        6)
      val omitted = collection(snapshot, combined.path)(value =>
        value.copy(arguments =
          value.arguments.updated(
            8,
            value.arguments(8).copy(dataType =
              value.arguments(8).dataType.replace(s"Bits(${combined.path}.arg3)", "Bits(1)")
            )
          )
        )
      )
      rejected(omitted, "paired width formal")

    test("all invariant signatures have zero selected width slots"):
      val snapshot = ConstructionKernel.inspect(new ReductionInvariantBridgeSource)
      assert(snapshot.digitalCollections.forall(_.arguments.size == 2))
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      assert(document.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 2)
      assert(!document.text.linesIterator.filter(_.contains("^bb0")).exists(_.contains(": i64")))

    test("reproducibility receipts retain local static regions and deterministic digests"):
      val snapshot = ConstructionKernel.inspect(new PublicNestedReduction)
      val receipt = ReproducibilityContract.describe(snapshot)
      val inventory = receipt.construction.indexOf("\"parameter_expressions\"")
      assert(inventory >= 0)
      val parameters = receipt.construction.drop(inventory)
      val local = snapshot.parameterExpressions.filter(_.region.nonEmpty)
      assert(local.nonEmpty)
      local.foreach: value =>
        val path = parameters.indexOf(s"\"path\": \"${value.path}\"")
        assert(path >= 0)
        val definition = parameters.substring(path, parameters.indexOf('}', path))
        assert(definition.contains(s"\"region\": \"${value.region.get}\""))
      val repeated = ReproducibilityContract.describe(snapshot)
      assert(receipt.construction == repeated.construction)
      assert(receipt.manifest.sha256 == repeated.manifest.sha256)
      assert(receipt.manifest.sha256.matches("[0-9a-f]{64}"))

    test("nested homogeneous body and reduction under map rebase explicit static captures"):
      val snapshot = ConstructionKernel.inspect(new PublicNestedReduction)
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val nested = snapshot.digitalCollections.find(_.kind == "zip_with").get
      assert(nested.arguments.exists(_.role == "static_capture"))
      assert(document.text.linesIterator.count(_.contains("\"nodal.collection\"")) == 4)
      val ordinal = nested.arguments.indexWhere(_.role == "static_capture")
      rejected(
        collection(snapshot, nested.path)(value =>
          value.copy(arguments =
            value.arguments.updated(ordinal, value.arguments(ordinal).copy(role = "capture"))
          )
        ),
        "static capture"
      )

    test("final width templates and static region hoists are independently rejected"):
      val snapshot = ConstructionKernel.inspect(new PublicWideningSums)
      val first = snapshot.digitalCollections.head
      rejected(
        collection(snapshot, first.path)(_.copy(dataType = "UInt(7)")),
        "exact all-legal recurrence"
      )
      rejected(
        collection(snapshot, first.path)(value =>
          value.copy(arguments =
            value.arguments.updated(
              2,
              value.arguments(2).copy(dataType = value.arguments(3).dataType)
            )
          )
        ),
        "paired width formal"
      )
      rejected(
        collection(snapshot, first.path)(value =>
          value.copy(arguments =
            value.arguments.updated(0, value.arguments(0).copy(role = "right_width:0"))
          )
        ),
        "paired width formal"
      )
      val local = snapshot.parameterExpressions.find(value =>
        value.region.contains(first.path) &&
          value.operation == "static_integer_max"
      ).get
      rejected(
        snapshot.copy(parameterExpressions =
          snapshot.parameterExpressions.map(value =>
            if value.path == local.path then value.copy(region = None) else value
          )
        ),
        "exact lexical scope"
      )

    test("equal defaults and intervals cannot replace static parameter identity"):
      val snapshot = ConstructionKernel.inspect(new PublicWideningSums)
      val module = snapshot.modules.head
      val width = module.declarations.find(_.name == "width").get
      val different = width.copy(path = module.path + ".otherWidth", name = "otherWidth")
      val changed = snapshot.copy(
        modules = snapshot.modules.updated(
          0,
          module.copy(declarations = module.declarations :+ different)
        ),
        parameterExpressions = snapshot.parameterExpressions.map(value =>
          if value.region.isEmpty && value.operation == "analog_add" &&
            value.operands.contains(width.path)
          then
            value.copy(operands =
              value.operands.map(input => if input == width.path then different.path else input)
            )
          else value
        )
      )
      rejected(changed, "exact all-legal recurrence")

    test("narrow intermediate cannot certify itself through a lawful fixed final width"):
      val snapshot = ConstructionKernel.inspect(new PublicWideningSums)
      val fixed = snapshot.digitalCollections.last
      val addition = snapshot.digitalExpressions.find(value =>
        value.region.contains(fixed.path) &&
          value.operation == "digital_add"
      ).get
      rejected(
        expression(snapshot, addition.path)(_.copy(dataType = "UInt(1)")),
        "exact kind, width"
      )

    test("first-valid later-invalid bitwise mutation checks universal contexts"):
      for balanced <- Vector(false, true) do
        val snapshot = ConstructionKernel.inspect(new ReductionCountedSource(3, balanced, "fixed"))
        val reducer = snapshot.digitalCollections.head
        val addition = snapshot.digitalExpressions.find(value =>
          value.region.contains(reducer.path) &&
            value.operation == "digital_add"
        ).get
        val changed = expression(snapshot, addition.path)(_.copy(
          operation = "digital_xor",
          dataType = reducer.arguments(2).dataType
        ))
        rejected(changed, "exact kind, width")

    test("enum literal width proof is rechecked in every reachable context"):
      for balanced <- Vector(false, true) do
        val snapshot = ConstructionKernel.inspect(new ReductionEnumDecodeSource(2, balanced))
        val first = ScalaToMlirBridge.fromSnapshot(snapshot)
        assert(first.text.contains("enum_decode"))
        val reducer = snapshot.digitalCollections.head
        val expanded = collection(snapshot, reducer.path)(_.copy(dataType = "Bits(3)"))
        val changed = expanded.copy(modules =
          expanded.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(value =>
                if reducer.inputs.contains(value.path) then
                  assert(value.dataType.contains("Vec(Bits(1);2)"))
                  value.copy(dataType = Some("Vec(Bits(1);3)"))
                else value
              )
            )
          )
        )
        rejected(changed, "enum operation has invalid exact types")

    test("formal-dependent inner dimensions cannot change after the first combine"):
      val snapshot = ConstructionKernel.inspect(new ReductionInnerDimensionBridgeSource)
      val reducer = snapshot.digitalCollections.find(_.kind == "reduce_balanced").get
      val nested = snapshot.digitalCollections.find(_.parentRegion.contains(reducer.path)).get
      assert(reducer.arguments(2).dataType == s"Vec(UInt(${reducer.path}.arg0);4)")
      // The left formal starts at four, so this forged dimension agrees initially, but the
      // next partial has width five. Inner dimensions are not selected recurrence slots.
      val changedYield = collection(snapshot, nested.path)(value =>
        value.copy(dataType = value.dataType.stripSuffix(";4)") + s";${reducer.path}.arg0)")
      )
      rejected(changedYield, "complete invariant schema")
      val changedTemplate = collection(snapshot, reducer.path)(value =>
        value.copy(arguments =
          value.arguments.updated(
            2,
            value.arguments(2).copy(dataType =
              s"Vec(UInt(${reducer.path}.arg0);${reducer.path}.arg0)"
            )
          )
        )
      )
      rejected(changedTemplate, "paired width formal")

    test("formal and typed schema resource limits reject before body scheduling"):
      val snapshot = ConstructionKernel.inspect(new ReductionCountedSource(3, true, "invariant"))
      val reducer = snapshot.digitalCollections.head
      val excess = Vector.tabulate(4099 - reducer.arguments.size)(index =>
        KernelDigitalRegionArgument(
          s"${reducer.path}.arg${reducer.arguments.size + index}",
          "Integer",
          "left_width:0"
        )
      )
      rejected(
        collection(snapshot, reducer.path)(value =>
          value.copy(arguments = value.arguments ++ excess)
        ),
        "argument count"
      )
      val nested = (0 until 130).foldLeft("UInt(4)")((element, _) => s"Vec($element;1)")
      val deep = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            declarations = module.declarations.map(value =>
              if reducer.inputs.contains(value.path) then
                value.copy(dataType = Some(s"Vec($nested;3)"))
              else value
            )
          )
        )
      )
      rejected(deep, "structural budget")

    test("bounded universal context work rejects an excessive otherwise finite signature"):
      val snapshot = ConstructionKernel.inspect(new ReductionCountedSource(3, true, "invariant"))
      val reducer = snapshot.digitalCollections.head
      val slots = 800
      def record(width: Int => String): String =
        s"Struct(Wide{${Vector.tabulate(slots)(index => s"f$index:UInt(${width(index)})").mkString(",")}})"
      val initial = record(_ => "4")
      val left = record(index => s"${reducer.path}.arg$index")
      val right = record(index => s"${reducer.path}.arg${slots + index}")
      val arguments = Vector.tabulate(2 * slots)(index =>
        KernelDigitalRegionArgument(
          s"${reducer.path}.arg$index",
          "Integer",
          if index < slots then s"left_width:$index" else s"right_width:${index - slots}"
        )
      ) ++
        Vector(
          KernelDigitalRegionArgument(s"${reducer.path}.arg${2 * slots}", left, "left"),
          KernelDigitalRegionArgument(s"${reducer.path}.arg${2 * slots + 1}", right, "right")
        )
      val changed = collection(snapshot, reducer.path)(value =>
        value.copy(
          arguments = arguments,
          captures = Vector.empty,
          yieldValue = arguments(2 * slots).path,
          dataType = initial
        )
      )
      val oversized = changed.copy(
        modules = changed.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(value =>
              if reducer.inputs.contains(value.path) then
                value.copy(dataType = Some(s"Vec($initial;1000000)"))
              else value
            )
          )
        ),
        digitalExpressions = changed.digitalExpressions.filterNot(_.region.contains(reducer.path)),
        parameterExpressions =
          changed.parameterExpressions.filterNot(_.region.contains(reducer.path))
      )
      rejected(oversized, "work budget")

    test("foreign formal capture and a leaked type identity are rejected"):
      val snapshot = ConstructionKernel.inspect(new PublicWideningSums)
      val first = snapshot.digitalCollections.head
      val second = snapshot.digitalCollections(1)
      val local = snapshot.parameterExpressions.find(value =>
        value.region.contains(first.path) &&
          value.operation == "static_integer_max"
      ).get
      val foreign = snapshot.copy(parameterExpressions =
        snapshot.parameterExpressions.map(value =>
          if value.path == local.path then
            value.copy(operands = value.operands.updated(0, second.arguments.head.path))
          else value
        )
      )
      rejected(foreign, "exact lexical scope")
      rejected(
        collection(snapshot, first.path)(_.copy(dataType = first.arguments(2).dataType)),
        "exact lexical scope"
      )

    test("retained region and static graph size do not follow logical lane count"):
      val small = ScalaToMlirBridge.lower(new ReductionCountedSource(5, true, "sum"))
      val large = ScalaToMlirBridge.lower(new ReductionCountedSource(1000000, true, "sum"))
      def count(text: String, operation: String): Int =
        text.linesIterator.count(_.contains(operation))
      assert(count(small.text, "\"nodal.collection\"") == count(large.text, "\"nodal.collection\""))
      assert(count(small.text, "\"nodal.const_expr\"") == count(large.text, "\"nodal.const_expr\""))
      assert(count(small.text, "\"nodal.digital_expr\"") ==
        count(large.text, "\"nodal.digital_expr\""))
