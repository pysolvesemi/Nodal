package nodal.internal.testkit

import nodal.*
import nodal_external.*
import utest.*

final class ReductionCountedSource(count: Int, balanced: Boolean, mode: String) extends Module,
      ReductionClock:
  val input = in(Vec(UInt(4), count))
  var calls = 0
  val result = ConstructionKernel.collection(
    if balanced then "reduce_balanced" else "reduce_left",
    Vector(input),
    None
  ): arguments =>
    calls += 1
    val left = arguments(0).asInstanceOf[Expr[UInt]]
    val right = arguments(1).asInstanceOf[Expr[UInt]]
    mode match
      case "later-bitwise" => (left ^ right).wrap(8)
      case "later-extend" => (left + right).extend(6)
      case "fixed" => (left + right).wrap(8)
      case "product" => left * right
      case "invariant" => left ^ right
      case "nested-reducer" =>
        ConstructionKernel.collection("reduce_left", Vector(input), None)(values => values.head)
      case _ => left + right

final class ReductionEnumDecodeSource(count: Int, balanced: Boolean) extends Module, ReductionClock:
  val input = in(Vec(Bits(1), count))
  val result = ConstructionKernel.collection(
    if balanced then "reduce_balanced" else "reduce_left",
    Vector(input),
    None
  ): arguments =>
    val left = arguments(0).asInstanceOf[Expr[Bits]]
    val right = arguments(1).asInstanceOf[Expr[Bits]]
    val _ = decodeEnum(left, EnumType[ReductionMode](), ReductionMode.Idle)
    left.concat(right)

private object ReductionInvocationProbe:
  var calls = 0

final class ReductionOversizedSource(count: Int = 2) extends Module:
  val fields = Vector.tabulate(2049)(index => StructField(s"f$index", UInt(1)))
  val schema = Struct("Oversized", fields*)
  val input = in(Vec(schema, count))
  var called = false
  val result = ConstructionKernel.collection("reduce_left", Vector(input), None): arguments =>
    called = true
    ReductionInvocationProbe.calls += 1
    arguments.head

final class ReductionProofBudgetSource extends Module:
  val count = param(1000000.integer, range = 1 to 1000000)
  val schema =
    Struct("ProofBudget", Vector.tabulate(1024)(index => StructField(s"f$index", UInt(1)))*)
  val input = in(Vec(schema, count))
  val result = ConstructionKernel.collection("reduce_balanced", Vector(input), None): arguments =>
    ReductionInvocationProbe.calls += 1
    arguments.head

final class ReductionDeepSchemaSource extends Module:
  val schema = (0 until 130).foldLeft(UInt(1).asInstanceOf[DataType[Data]])((element, _) =>
    Vec(element, 1).asInstanceOf[DataType[Data]]
  )
  val input = in(Vec(schema, 2))
  val result = ConstructionKernel.collection("reduce_left", Vector(input), None): arguments =>
    ReductionInvocationProbe.calls += 1
    arguments.head

final class ReductionChangingInnerSource extends Module:
  val input = in(Vec(Vec(UInt(4), 4), 3))
  val result = ConstructionKernel.collection("reduce_left", Vector(input), None): arguments =>
    val left = arguments(0).asInstanceOf[Expr[Vec[UInt]]]
    val right = arguments(1).asInstanceOf[Expr[Vec[UInt]]]
    val element = CandidateRuntime.typeDescriptor(CandidateRuntime.expressionDataType(left).get)
      .arguments.head.asInstanceOf[DataType[?]]
    val width = CandidateRuntime.typeDescriptor(element).arguments.head.asInstanceOf[Expr[Integer]]
    left.zipWith(right)((a, b) => a + b).reshape(width)

final class ReductionRetainedShapeSource(count: Int, balanced: Boolean, mode: String)
    extends Module:
  val input = in(Vec(Vec(UInt(4), 8), count))
  val result = ConstructionKernel.collection(
    if balanced then "reduce_balanced" else "reduce_left",
    Vector(input),
    None
  ): arguments =>
    val left = arguments(0).asInstanceOf[Expr[Vec[UInt]]]
    val right = arguments(1).asInstanceOf[Expr[Vec[UInt]]]
    val element = CandidateRuntime.typeDescriptor(CandidateRuntime.expressionDataType(left).get)
      .arguments.head.asInstanceOf[DataType[?]]
    val width = CandidateRuntime.typeDescriptor(element).arguments.head.asInstanceOf[Expr[Integer]]
    mode match
      case "index" => val _ = left.at(width)
      case "slice" => val _ = left.slice(width, 8)
      case _ => val _ = left.slice(0, width).zipWith(right.slice(0, 4))((a, b) => a + b)
    left.zipWith(right)((a, b) => a + b)

object Increment55WideningReductionTests extends TestSuite:
  private def rejected(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.message

  val tests: Tests = Tests:
    test("frozen reduce is actual left and balanced API is additive"):
      val snapshot = ConstructionKernel.inspect(new PublicWideningSums)
      assert(snapshot.digitalCollections.map(_.kind) ==
        Vector("reduce_left", "reduce_balanced", "reduce_balanced", "reduce_balanced"))
      assert(snapshot.digitalCollections.forall(_.arguments.take(2).map(_.dataType) ==
        Vector("Integer", "Integer")))
      val first = snapshot.digitalCollections.head
      assert(first.arguments.take(4).map(_.role) ==
        Vector("left_width:0", "right_width:0", "left", "right"))
      val maximum = snapshot.parameterExpressions.find(value =>
        value.region.contains(first.path) && value.operation == "static_integer_max"
      ).get
      assert(maximum.operands == first.arguments.take(2).map(_.path))
      val widened = snapshot.parameterExpressions.find(value =>
        value.region.contains(first.path) &&
          value.operation == "analog_add" && value.operands.headOption.contains(maximum.path)
      ).get
      val growthArgument = first.arguments.indexWhere(_.path == widened.operands(1))
      assert(growthArgument >= 4)
      val growth =
        snapshot.parameterExpressions.find(_.path == first.captures(growthArgument - 4)).get
      assert(growth.literal.contains("1"))
      val addition = snapshot.digitalExpressions.find(value =>
        value.region.contains(first.path) &&
          value.operation == "digital_add"
      ).get
      assert(addition.dataType == s"UInt(${widened.path})")
      val actualSource = snapshot.sourceMap.find(_.semanticPath == addition.path).get
      assert(actualSource.source.path.nonEmpty, actualSource.source.line > 0)
      val inputSource = snapshot.sourceMap.find(_.semanticPath == "PublicWideningSums.input").get
      assert(inputSource.source.path.endsWith("Increment55PublicWideningReductions.scala"))
      assert(snapshot == ConstructionKernel.inspect(new PublicWideningSums))
      val _ =
        rejected(ConstructionKernel.inspect(new ReductionCountedSource(3, false, "later-bitwise")))
      val afterFailure = ConstructionKernel.inspect(new PublicWideningSums)
      assert(snapshot == afterFailure)
      assert(afterFailure.sourceMap.find(_.semanticPath == addition.path).contains(actualSource))

    test("singleton bypass and one template independent of lane count"):
      val singleton =
        ConstructionKernel.capture(new ReductionCountedSource(1, true, "sum")): module =>
          assert(module.calls == 0)
          Vector(module.result)
      assert(singleton.snapshot.digitalCollections.isEmpty)
      val small = ConstructionKernel.capture(new ReductionCountedSource(5, true, "sum")): module =>
        assert(module.calls == 1)
        Vector(module.result)
      val large =
        ConstructionKernel.capture(new ReductionCountedSource(1000000, true, "sum")): module =>
          assert(module.calls == 1)
          Vector(module.result)
      assert(small.snapshot.digitalExpressions.size == large.snapshot.digitalExpressions.size)
      assert(small.snapshot.names.size == large.snapshot.names.size)
      assert(small.snapshot.origins.size == large.snapshot.origins.size)
      assert(small.snapshot.generatedNames.size == large.snapshot.generatedNames.size)
      assert(small.snapshot.sourceMap.size == large.snapshot.sourceMap.size)
      assert(large.snapshot.digitalCollections.size == 1)

    test("later-context bitwise and narrowing failures are not hidden by fixed final width"):
      for balanced <- Vector(false, true) do
        assert(rejected(ConstructionKernel.inspect(new ReductionCountedSource(
          3,
          balanced,
          "later-bitwise"
        ))).contains("every reachable context"))
        assert(rejected(ConstructionKernel.inspect(new ReductionCountedSource(
          10,
          balanced,
          "later-extend"
        ))).contains("reachable reduction context"))
        val fixed = ConstructionKernel.inspect(new ReductionCountedSource(5, balanced, "fixed"))
        assert(fixed.digitalCollections.size == 1)
        val firstDecode = ConstructionKernel.inspect(new ReductionEnumDecodeSource(2, balanced))
        assert(firstDecode.digitalExpressions.exists(_.operation == "enum_decode"))
        assert(rejected(ConstructionKernel.inspect(new ReductionEnumDecodeSource(
          3,
          balanced
        ))).contains("enum representation width is not exact"))

    test("whole records preserve four width slots Bool enum and shared predicate"):
      val snapshot = ConstructionKernel.inspect(new PublicRecordReductions)
      assert(snapshot.digitalCollections.size == 2)
      val combined = snapshot.digitalCollections(1)
      assert(combined.arguments.size - combined.captures.size == 10)
      assert(combined.arguments.take(4).map(_.role) ==
        Vector("left_width:0", "left_width:1", "left_width:2", "left_width:3"))
      val body = snapshot.digitalExpressions.filter(_.region.contains(combined.path))
      val predicates = body.filter(_.operation == "digital_ge")
      val selections = body.filter(_.operation == "digital_select")
      assert(predicates.size == 1, selections.size == 5)
      assert(selections.forall(_.operands.head == predicates.head.path))
      assert(body.count(_.operation == "digital_struct_make") == 1)
      assert(snapshot.digitalExpressions.exists(_.operation == "digital_alias"))

    test("ordinary nested collections retain explicit static captures"):
      val snapshot = ConstructionKernel.inspect(new PublicNestedReduction)
      val nested = snapshot.digitalCollections.find(_.kind == "zip_with").get
      assert(nested.arguments.exists(_.role == "static_capture"))
      assert(nested.parentRegion.nonEmpty)

    test("unsupported reducer ancestry and oversized signatures fail before callback"):
      assert(rejected(ConstructionKernel.inspect(new ReductionCountedSource(
        2,
        false,
        "nested-reducer"
      ))).contains("reducer-in-reducer"))
      ReductionInvocationProbe.calls = 0
      assert(rejected(ConstructionKernel.inspect(new ReductionOversizedSource)).contains("4098"))
      assert(ReductionInvocationProbe.calls == 0)
      val singleton = ConstructionKernel.inspect(new ReductionOversizedSource(1))
      assert(singleton.digitalCollections.isEmpty, ReductionInvocationProbe.calls == 0)

    test("proof work and schema depth reject before callback allocation"):
      ReductionInvocationProbe.calls = 0
      assert(rejected(
        ConstructionKernel.inspect(new ReductionProofBudgetSource)
      ).contains("analysis-work budget"))
      assert(ReductionInvocationProbe.calls == 0)
      val depth = rejected(ConstructionKernel.inspect(new ReductionDeepSchemaSource))
      assert(depth.contains("depth") || depth.contains("structural budget"))
      assert(ReductionInvocationProbe.calls == 0)

    test("geometry includes odd carries and maximum signed cardinality without lanes"):
      val five = DigitalReductionContract.contexts(5, 5, balanced = true)
      import DigitalReductionContract.SizeRange
      assert(five.contains(SizeRange(1, 1) -> SizeRange(1, 1)))
      assert(five.contains(SizeRange(2, 2) -> SizeRange(2, 2)))
      assert(five.contains(SizeRange(4, 4) -> SizeRange(1, 1)))
      assert(DigitalReductionContract.contexts(1, Long.MaxValue, balanced = true).size <= 189)

    test("exact singleton fixed growth and product widths for odd and even counts"):
      for
        count <- Vector(1, 2, 3, 5, 6); balanced <- Vector(false, true);
        mode <- Vector("sum", "product", "fixed", "invariant")
      do
        val captured =
          ConstructionKernel.capture(new ReductionCountedSource(count, balanced, mode))(module =>
            Vector(module.result)
          )
        val snapshot = captured.snapshot
        val actual = if count == 1 then 4L
        else
          val collection = snapshot.digitalCollections.head
          val token = collection.dataType.stripPrefix("UInt(").stripSuffix(")")
          StructuralParameterBinding.defaultInteger(snapshot, collection.owner, token)
        val expected = if count == 1 then 4L
        else
          mode match
            case "fixed" => 8L
            case "invariant" => 4L
            case "product" => 4L * count
            case _ => 4L + (if balanced then BigInt(count - 1).bitLength else count - 1)
        assert(actual == expected)

    test("first-context equality cannot change the invariant inner shape later"):
      val diagnostic = rejected(ConstructionKernel.inspect(new ReductionChangingInnerSource))
      assert(diagnostic.contains("shape"))

    test("unused retained shapes and nested zip contracts are checked in later contexts"):
      for balanced <- Vector(false, true); mode <- Vector("index", "slice", "zip") do
        val first = ConstructionKernel.inspect(new ReductionRetainedShapeSource(2, balanced, mode))
        assert(first.digitalCollections.nonEmpty)
        val count = if mode == "zip" then 3 else if balanced then 33 else 7
        val diagnostic = rejected(ConstructionKernel.inspect(new ReductionRetainedShapeSource(
          count,
          balanced,
          mode
        )))
        assert(if mode == "index" then diagnostic.contains("not in bounds")
        else if mode == "zip" then diagnostic.contains("exactly equal dimensions")
        else diagnostic.contains("shape slice"))

    test("independent default-equal coupled slots and default-only legal callbacks reject"):
      assert(rejected(
        ConstructionKernel.inspect(new PublicCoupledReduction)
      ).contains("unsupported coupled"))
      assert(rejected(
        ConstructionKernel.inspect(new PublicBadSymbolicReduction)
      ).contains("every reachable context"))
