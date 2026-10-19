package nodal.internal.testkit

import scala.compiletime.testing.typeCheckErrors

import nodal.*
import utest.*

trait DigitalCollectionClock:
  this: Module =>
  val root: ClockDomain = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )

final class DigitalCollectionMapSource extends Module, DigitalCollectionClock:
  val rows = param(2.integer, range = 1 to 1000000)
  val input = in(Vec(UInt(4), rows, 3))
  val mapped = input.map(value => value + 1.U(4))
  val narrowed = mapped.map(value => value.truncate(4))
  val selected = narrowed.at(0, 0)
  val output = out(UInt(4))
  output := selected

final class DigitalCollectionZipSource extends Module, DigitalCollectionClock:
  val left = in(Vec(UInt(4), 2, 3))
  val right = in(Vec(SInt(6), 2, 3))
  val result = left.zipWith(right)((a, b) => a.toSigned + b)

final class DigitalCollectionAggregateSource extends Module, DigitalCollectionClock:
  val flag = in(Bool)
  val record = Struct(
    "Payload",
    StructField("data", UInt(4)),
    StructField("measure", Real),
    StructField("samples", Vec(UInt(3), 2))
  )
  val input = in(Vec(record, 3))
  val result = input.map(value => select(flag, value, value))

final class DigitalCollectionNestedSource extends Module, DigitalCollectionClock:
  val increment = in(UInt(4))
  val input = in(Vec(Vec(UInt(4), 2), 3))
  val result = input.map(row => row.map(value => value + increment))
  val selected = input.map(row => row.at(1))

final class DigitalCollectionFoldScanSource extends Module, DigitalCollectionClock:
  val input = in(Vec(UInt(4), 2, 3))
  val seed = in(UInt(8))
  val folded = input.fold(seed)((accumulator, value) =>
    (accumulator - value).wrap(8).reinterpretUnsigned
  )
  val scanned = input.scan(seed)((accumulator, value) =>
    (accumulator - value).wrap(8).reinterpretUnsigned
  )

final class DigitalCollectionSymbolicSource extends Module, DigitalCollectionClock:
  val count = param(2.integer, range = 1 to 1000000)
  val width = param(4.integer, range = 2 to 8)
  val input = in(Vec(UInt(width), count))
  val seed = in(UInt(width))
  val result = input.fold(seed)((accumulator, value) => accumulator ^ value)
  val mapped = input.map(value => value + value)

final class DigitalCollectionIdentitySource extends Module, DigitalCollectionClock:
  val mode = EnumType[DigitalSourceMode]()
  val modes = in(Vec(mode, 1))
  val booleans = in(Vec(Bool, 1))
  val mappedModes = modes.map(value => value)
  val mappedBooleans = booleans.map(value => value)

final class DigitalCollectionBadDimensionsSource(symbolic: Boolean) extends Module:
  val count = param(2.integer, range = 1 to 4)
  val other = param(2.integer, range = 1 to 4)
  val left = in(if symbolic then Vec(UInt(4), count) else Vec(UInt(4), 2, 3))
  val right = in(if symbolic then Vec(UInt(4), other) else Vec(UInt(4), 3, 2))
  val result = left.zipWith(right)((a, b) => a + b)

final class DigitalCollectionBadSeedSource extends Module:
  val input = in(Vec(UInt(4), 3))
  val result = input.fold(0.U(4))((accumulator, value) => accumulator + value)

final class DigitalCollectionZipMigrationSource extends Module:
  val input = in(Vec(UInt(4), 3))
  val result = input.zip(input)

/** Direct kernel calls deliberately test runtime construction integrity independently of the
  * compiler's callback-effect attestation. These are not public host-purity witnesses.
  */
final class DigitalCollectionRuntimeSource(count: Int, mode: String) extends Module,
      DigitalCollectionClock:
  val input = in(Vec(UInt(4), count))
  val output = out(UInt(4))
  var calls = 0
  var escaped: Option[Expr[UInt]] = None
  val result = ConstructionKernel.collection("map", Vector(input), None): arguments =>
    calls += 1
    val value = arguments.head.asInstanceOf[Expr[UInt]]
    mode match
      case "declaration" => val _ = wire(UInt(4))
      case "assignment" => output := value
      case "state" => val _ = Reg(0.U(4))
      case "statement" => CandidateRuntime.statement("unknown")
      case "analog" => val _ = 1.V + 2.V
      case "unused_real" => val _ = toReal(value)
      case "unknown" => val _ = CandidateRuntime.expr[UInt](value, "external-latency")
      case "escape" | "sibling" | "selected" => escaped = Some(value)
      case "caught" =>
        try
          val _ = wire(UInt(4))
        catch case _: ConstructionException => ()
      case _ => ()
    val sum = value + 1.U(4)
    if mode == "escape_result" then escaped = Some(sum)
    if mode == "fake" then
      new KernelExpr[UInt](
        Vector.empty,
        resultType = Some(KernelTypeDescriptor("UInt", Vector(4))),
        operation = Some(DigitalCollectionContract.Argument)
      )
    else sum
  if mode == "escape" || mode == "escape_result" then
    val _ = escaped.get + 1.U(4)
  if mode == "sibling" then
    val _ = ConstructionKernel.collection("map", Vector(input), None)(_ => escaped.get)

final class DigitalCollectionForeignSource extends Module:
  val child = instance(new DigitalCollectionZipSource)
  val input = in(Vec(UInt(4), 2, 3))
  val result =
    ConstructionKernel.collection("map", Vector(input), None)(_ => child.module.left.at(0, 0))

object Increment55DigitalCollectionTests extends TestSuite:
  private def failure(body: => Any): String =
    scala.util.Try(body).failed.get.asInstanceOf[ConstructionException].diagnostic.code

  val tests: Tests = Tests:
    test("map captures typed arguments once without count-sized graph expansion"):
      val small =
        ConstructionKernel.capture(new DigitalCollectionRuntimeSource(1, "count")): module =>
          assert(module.calls == 1)
          Vector(module.result)
      val large =
        ConstructionKernel.capture(new DigitalCollectionRuntimeSource(1000000, "count")): module =>
          assert(module.calls == 1)
          Vector(module.result)
      assert(small.snapshot.digitalCollections.size == 1)
      assert(large.snapshot.digitalCollections.size == 1)
      assert(small.snapshot.digitalExpressions.size == large.snapshot.digitalExpressions.size)
      val collection = large.snapshot.digitalCollections.head
      assert(collection.arguments.map(_.path) == Vector(collection.path + ".arg0"))
      assert(collection.arguments.head.dataType == "UInt(4)")
      val body = large.snapshot.digitalExpressions.filter(_.region.contains(collection.path))
      assert(body.exists(_.operands.contains(collection.arguments.head.path)))
      assert(!body.exists(_.operation == "collection_argument"))
      assert(collection.dataType == "Vec(UInt(5);1000000)")

    test("map preserves symbolic rank and computed Vec indexing remains readable"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionMapSource)
      assert(snapshot == ConstructionKernel.inspect(new DigitalCollectionMapSource))
      assert(snapshot.digitalCollections.size == 2)
      val first = snapshot.digitalCollections.find(_.dataType.startsWith("Vec(UInt(5)")).get
      val second = snapshot.digitalCollections.find(_.dataType.startsWith("Vec(UInt(4)")).get
      assert(first.dataType.contains("rows"), first.dataType.endsWith("x3)"))
      assert(second.inputs == Vector(first.path))
      assert(snapshot.shapeIndices.exists(_.input == second.path))
      assert(snapshot.digitalAssignments.size == 1)

    test("zipWith retains both heterogeneous operands and actual right argument use"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionZipSource)
      val collection = snapshot.digitalCollections.head
      assert(collection.kind == "zip_with", collection.inputs.size == 2)
      assert(collection.arguments.map(_.dataType) == Vector("UInt(4)", "SInt(6)"))
      assert(collection.dataType == "Vec(SInt(7);2x3)")
      val addition = snapshot.digitalExpressions.find(value =>
        value.region.contains(collection.path) && value.operation == "digital_add"
      ).get
      assert(addition.operands.last == collection.arguments(1).path)
      assert(failure(ConstructionKernel.inspect(new DigitalCollectionBadDimensionsSource(false))) ==
        "NODAL-COLLECTION-055-002")
      assert(failure(ConstructionKernel.inspect(new DigitalCollectionBadDimensionsSource(true))) ==
        "NODAL-COLLECTION-055-002")

    test("whole Struct captures retain one argument and shared field dependencies"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionAggregateSource)
      val collection = snapshot.digitalCollections.head
      assert(collection.arguments.size == 2, collection.captures.size == 1)
      assert(collection.arguments.head.dataType.startsWith("Struct(Payload{"))
      assert(collection.arguments.head.dataType.contains("measure:Real"))
      val selected = snapshot.digitalExpressions.find(_.operation == "digital_select").get
      assert(selected.operands == Vector(
        collection.arguments(1).path,
        collection.arguments.head.path,
        collection.arguments.head.path
      ))

    test("nested Vec callbacks compose with explicit captures at every lexical boundary"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionNestedSource)
      val inner = snapshot.digitalCollections.find(_.parentRegion.nonEmpty).get
      val outer =
        snapshot.digitalCollections.find(value => inner.parentRegion.contains(value.path)).get
      assert(outer.arguments.head.dataType == "Vec(UInt(4);2)")
      assert(inner.inputs == Vector(outer.arguments.head.path))
      assert(outer.captures.size == 1, inner.captures == Vector(outer.arguments(1).path))
      assert(inner.arguments(1).path == inner.path + ".arg1")
      val addition = snapshot.digitalExpressions.find(value =>
        value.region.contains(inner.path) && value.operation == "digital_add"
      ).get
      assert(addition.operands == inner.arguments.map(_.path))
      assert(snapshot.shapeIndices.exists(index =>
        index.region.nonEmpty && index.input.endsWith(".arg0")
      ))
      assert(outer.dataType == "Vec(Vec(UInt(5);2);3)")

    test("fold and seed-excluded scan retain exact seed types and input dimensions"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionFoldScanSource)
      val fold = snapshot.digitalCollections.find(_.kind == "fold_left").get
      val scan = snapshot.digitalCollections.find(_.kind == "scan_left").get
      assert(fold.dataType == "UInt(8)", scan.dataType == "Vec(UInt(8);2x3)")
      Vector(fold, scan).foreach: collection =>
        assert(
          collection.seed.nonEmpty,
          collection.arguments.map(_.dataType) == Vector("UInt(8)", "UInt(4)")
        )
        val subtraction = snapshot.digitalExpressions.find(value =>
          value.region.contains(collection.path) && value.operation == "digital_sub"
        ).get
        assert(subtraction.operands == collection.arguments.map(_.path))
      assert(failure(ConstructionKernel.inspect(new DigitalCollectionBadSeedSource)) ==
        "NODAL-COLLECTION-055-003")
      val symbolic = ConstructionKernel.inspect(new DigitalCollectionSymbolicSource)
      assert(symbolic.digitalCollections.find(_.kind == "fold_left").get.dataType.contains("width"))
      assert(symbolic.parameterExpressions.exists(_.operation == "analog_add"))

    test("identity callbacks yield real typed enum and Bool arguments without surrogate values"):
      val snapshot = ConstructionKernel.inspect(new DigitalCollectionIdentitySource)
      assert(snapshot.digitalCollections.size == 2)
      assert(snapshot.enumDefinitions.size == 1)
      snapshot.digitalCollections.foreach: collection =>
        assert(collection.yieldValue == collection.arguments.head.path)
        assert(collection.captures.isEmpty)
        assert(!snapshot.digitalExpressions.exists(_.region.contains(collection.path)))

    test("computed collections retain the readonly expression capability"):
      val errors = typeCheckErrors("""
        import nodal.*
        def invalid(value: Expr[Vec[UInt]]): Unit =
          value.map(element => element).at(0) := 1.U(4)
      """)
      assert(errors.nonEmpty)

    test("hardware effects and unknown expressions reject inside pure runtime regions"):
      Vector(
        "declaration",
        "assignment",
        "state",
        "statement",
        "analog",
        "unused_real",
        "unknown"
      ).foreach: mode =>
        assert(failure(ConstructionKernel.inspect(new DigitalCollectionRuntimeSource(3, mode))) ==
          "NODAL-COLLECTION-055-006")

    test("escaped arguments sibling values and selected body handles reject"):
      Vector("escape", "escape_result", "sibling", "fake").foreach: mode =>
        assert(failure(ConstructionKernel.inspect(new DigitalCollectionRuntimeSource(3, mode))) ==
          "NODAL-COLLECTION-055-005")
      assert(failure(ConstructionKernel.inspect(new DigitalCollectionRuntimeSource(3, "caught"))) ==
        "NODAL-CONSTRUCTOR-LIFECYCLE-021")
      assert(failure(ConstructionKernel.inspect(new DigitalCollectionForeignSource)) ==
        "NODAL-COLLECTION-055-005")
      assert(failure(ConstructionKernel.capture(new DigitalCollectionRuntimeSource(3, "selected"))(
        module => Vector(module.escaped.get)
      )) == "NODAL-COLLECTION-055-005")

    test("inert zip supplies an explicit migration diagnostic"):
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new DigitalCollectionZipMigrationSource)
      ).failed.get.asInstanceOf[ConstructionException].diagnostic
      assert(failure.code == "NODAL-COLLECTION-055-004")
      assert(failure.message == "inert zip cannot represent both operands; migrate to zipWith")
      assert(failure.semanticPath.contains("DigitalCollectionZipMigrationSource.input_0"))
