package nodal.internal.testkit
import nodal.*
import nodal_external.*
import nodal.internal.bridge.ReproducibilityContract
import utest.*
import scala.collection.mutable
object Increment58ReductionCalls:
  var count = 0
final class Increment58PendingReductionCountedSource(
    count: Int = 4,
    invalid: Boolean = false,
    mutate: String = ""
) extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(Bool, count))
  val lanes =
    for i <- hdlRange(0, 3) yield new Area:
      private val width = i + 1.integer
      val stored = wire(Vec(SInt(width), count))
      stored := input.map(bit => select(bit, (-1).S(width), 0.S(width)))
  val chosen = lanes(1).stored
  val result = ConstructionKernel.collection("reduce_left", Vector(chosen), None): args =>
    Increment58ReductionCalls.count += 1
    val left = args(0).asInstanceOf[Expr[SInt]]
    val right = args(1).asInstanceOf[Expr[SInt]]
    if invalid then
      val _ = left ^ right
    left + right
  if mutate == "sibling" then
    val _ = ConstructionKernel.collection("reduce_left", Vector(chosen), None)(_.head)
  if mutate.nonEmpty then Increment58PendingReductionControls.mutate(mutate)
object Increment58PendingReductionControls extends TestSuite:
  val tests = Tests:
    test("pending reduction preserves exact transfer and callback lifecycle"):
      run(java.nio.file.Path.of(".work/increment58-pending-reduction-tests"))
  def mutate(mode: String): Unit =
    val active = ConstructionKernel.getClass.getDeclaredMethod("active")
    active.setAccessible(true)
    val session = active.invoke(ConstructionKernel).asInstanceOf[Option[AnyRef]].get
    val field = session.getClass.getDeclaredFields.find(_.getName.endsWith("collectionRegions")).get
    field.setAccessible(true)
    val regions = field.get(session).asInstanceOf[mutable.Map[Any, AnyRef]]
    val region =
      regions.values.find(value => value.getClass.getMethod("kind").invoke(value) == "reduce_left")
    val actual = region.get
    val args =
      actual.getClass.getMethod("arguments").invoke(actual).asInstanceOf[mutable.ArrayBuffer[(
          AnyRef,
          String
      )]]
    mode match
      case "role" => args(0) = args(0)._1 -> "right_width:0"
      case "position" =>
        val first = args(0); args(0) = args(1); args(1) = first
      case "extra" => args += args.last
      case "capture" => args += (args.last._1 -> "capture")
      case "yield" =>
        val setter = actual.getClass.getMethods.find(_.getName == "yielded_$eq").get
        val _ = setter.invoke(actual, Some(args(2)._1))
      case "sibling" =>
        val other = regions.values.find(value =>
          (value ne actual) && value.getClass.getMethod("kind").invoke(value) == "reduce_left"
        ).get
        val foreign =
          other.getClass.getMethod("arguments").invoke(other).asInstanceOf[mutable.ArrayBuffer[(
              AnyRef,
              String
          )]]
        args(2) = foreign(2)
      case _ => ()
  def main(args: Array[String]): Unit = run(java.nio.file.Path.of(args.head))
  private def run(output: java.nio.file.Path): Unit =
    val _ = java.nio.file.Files.createDirectories(output)
    var observed = 0
    def check(name: String)(body: => Unit): Unit =
      body; observed += 1; println("PASS " + name)
    def reject(expected: String)(body: => Any): Unit =
      val error = scala.util.Try(body).failed.get
      Predef.assert(error.getMessage.contains(expected), error)
    Vector[(String, () => Module)](
      "left" -> (() => new Increment58PendingReductionSource),
      "tree" -> (() => new Increment58PendingReductionSource("tree")),
      "invariant" -> (() => new Increment58PendingReductionSource("invariant")),
      "capture" -> (() => new Increment58PendingReductionCaptureSource),
      "nested" -> (() => new Increment58PendingReductionNestedSource),
      "packet" -> (() => new Increment58PendingReductionPacketSource)
    ).foreach: (name, source) =>
      check(name):
        val snapshot = ConstructionKernel.inspect(source())
        val _ = java.nio.file.Files.writeString(
          output.resolve(name + ".json"),
          ReproducibilityContract.canonicalSnapshot(snapshot)
        )
        val _ = java.nio.file.Files.writeString(
          output.resolve(name + ".mlir"),
          nodal.internal.bridge.ScalaToMlirBridge.fromSnapshot(snapshot).text
        )
    check("independent schema retains distinct original width tokens"):
      val snapshot = ConstructionKernel.inspect(new Increment58RegionalSchemaCaptureSource)
      val constructs = snapshot.digitalExpressions.filter(_.operation == "digital_struct_make")
      Predef.assert(constructs.size == 2 &&
        constructs.forall(value => value.structure.get.schemaType != value.dataType))
      val _ = java.nio.file.Files.writeString(
        output.resolve("schema.mlir"),
        nodal.internal.bridge.ScalaToMlirBridge.fromSnapshot(snapshot).text
      )
    check("sibling schema token cannot acquire local capture authority"):
      val snapshot = ConstructionKernel.inspect(new Increment58RegionalSchemaCaptureSource)
      val constructs = snapshot.digitalExpressions.filter(_.operation == "digital_struct_make")
      val first = constructs.head
      val sibling = constructs.last
      val forged = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.map(value =>
          if value.path == first.path then value.copy(structure = sibling.structure) else value
        )
      )
      val error =
        scala.util.Try(nodal.internal.bridge.ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      Predef.assert(
        error.getMessage.contains("escaped local width identity outside its exact lexical scope"),
        error
      )
    check("singleton never invokes callback"):
      Increment58ReductionCalls.count = 0
      val snapshot = ConstructionKernel.inspect(new Increment58PendingReductionCountedSource(1))
      Predef.assert(Increment58ReductionCalls.count == 0 &&
        !snapshot.digitalCollections.exists(_.kind == "reduce_left"))
      val _ = java.nio.file.Files.writeString(
        output.resolve("singleton.mlir"),
        nodal.internal.bridge.ScalaToMlirBridge.fromSnapshot(snapshot).text
      )
    check("complete and pending singleton have exact equal final element and geometry"):
      Increment58ReductionCalls.count = 0
      var pending = Option.empty[Increment58PendingReductionCountedSource]
      val delayedSnapshot = ConstructionKernel.inspect {
        val module = new Increment58PendingReductionCountedSource(1)
        pending = Some(module)
        module
      }
      var eager = Option.empty[Increment58CompleteReductionSingletonSource]
      val eagerSnapshot = ConstructionKernel.inspect {
        val module = new Increment58CompleteReductionSingletonSource
        eager = Some(module)
        module
      }
      val delayedType =
        CandidateRuntime.expressionDataType(pending.get.result).map(CandidateRuntime.typeDescriptor)
      val eagerType =
        CandidateRuntime.expressionDataType(eager.get.result).map(CandidateRuntime.typeDescriptor)
      Predef.assert(delayedType == eagerType &&
        delayedType.contains(KernelTypeDescriptor("SInt", Vector(2))))
      val delayedIndex = delayedSnapshot.shapeIndices.find(_.path.endsWith(".result")).get
      val eagerIndex = eagerSnapshot.shapeIndices.find(_.path.endsWith(".result")).get
      Predef.assert(delayedIndex.indices == eagerIndex.indices &&
        delayedIndex.resultDimensions == eagerIndex.resultDimensions)
      Predef.assert(Increment58ReductionCalls.count == 0)
    check("multicount invokes callback exactly once"):
      Increment58ReductionCalls.count = 0
      val _ = ConstructionKernel.inspect(new Increment58PendingReductionCountedSource)
      Predef.assert(Increment58ReductionCalls.count == 1)
    check("unused later-context operation rejects"):
      reject("every reachable context") {
        ConstructionKernel.inspect(new Increment58PendingReductionCountedSource(invalid = true))
      }
    check("wrong final output width"):
      reject("exactly matching recursive types") {
        ConstructionKernel.inspect(new Increment58PendingReductionSource(wrong = true))
      }
    check("pending outer geometry remains explicit"):
      reject("pending reduction outer geometry") {
        ConstructionKernel.inspect(new Increment58PendingReductionGeometrySource)
      }
    Vector("role", "position", "extra", "capture", "sibling").foreach: mode =>
      check("mutated formal " + mode):
        reject("reduction formal role or position") {
          ConstructionKernel.inspect(new Increment58PendingReductionCountedSource(mutate = mode))
        }
    check("mutated original yield cannot refresh the registered intent"):
      reject("exact registered inputs, captures or yield") {
        ConstructionKernel.inspect(new Increment58PendingReductionCountedSource(mutate = "yield"))
      }
    check("singleton bad index still rejects"):
      reject("index") { ConstructionKernel.inspect(new Increment58PendingReductionIndexSource) }
    check("zero outer count does not invoke callback"):
      Increment58ReductionCalls.count = 0
      val failure = scala.util.Try(
        ConstructionKernel.inspect(new Increment58PendingReductionCountedSource(0))
      ).failed.get
      Predef.assert(failure.isInstanceOf[ConstructionException] &&
        Increment58ReductionCalls.count == 0)
    check("wrong parent snapshot retains lexical refusal"):
      val snapshot = ConstructionKernel.inspect(new Increment58PendingReductionNestedSource)
      val nested = snapshot.digitalCollections.find(value => value.kind == "zip_with").get
      val forged = snapshot.copy(digitalCollections =
        snapshot.digitalCollections.map(value =>
          if value.path == nested.path then value.copy(parentRegion = None) else value
        )
      )
      val error =
        scala.util.Try(nodal.internal.bridge.ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      Predef.assert(error.getMessage.contains("matching ordinary digital result record"), error)
    check("forged collection operand inventory remains rejected"):
      val snapshot = ConstructionKernel.inspect(new Increment58PendingReductionSource)
      val reduced = snapshot.digitalCollections.find(_.kind == "reduce_left").get
      val forged = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.map(value =>
          if value.path == reduced.path then value.copy(operands = value.operands.dropRight(1))
          else value
        )
      )
      val error =
        scala.util.Try(nodal.internal.bridge.ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      Predef.assert(error.getMessage.contains("matching ordinary digital result record"), error)
    check("foreign input cannot acquire reduction ownership"):
      reject("another Module") { ConstructionKernel.inspect(new Increment58ReductionForeignUse) }
    check("same JVM failure recovery"):
      Increment58ReductionCalls.count = 0
      val _ = ConstructionKernel.inspect(new Increment58PendingReductionCountedSource)
      Predef.assert(Increment58ReductionCalls.count == 1)
    println(s"observations=$observed")

final class Increment58ReductionForeignInput extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(SInt(2), 4))
final class Increment58ReductionForeignUse extends Module:
  val foreign = new Increment58ReductionForeignInput
  val result = ConstructionKernel.collection("reduce_left", Vector(foreign.input), None)(_.head)

final class Increment58CompleteReductionSingletonSource extends Module:
  val core = ClockDomain.external(
    "core",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(Vec(SInt(2), 1))
  val result = ConstructionKernel.collection("reduce_left", Vector(input), None): args =>
    Increment58ReductionCalls.count += 1
    args(0).asInstanceOf[Expr[SInt]] + args(1).asInstanceOf[Expr[SInt]]
