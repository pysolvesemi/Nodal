package nodal.internal.testkit

import nodal.*
import utest.*

/** Reflection isolates runtime ownership tests from the independent public callback validator. */
private object MemoryKernelFixture:
  def branch(condition: Expr[Bool])(body: => Unit): Unit =
    val callbacks = Class.forName("nodal.DigitalCollectionCallback$")
    val checked = callbacks.getMethod("checkedSequential", classOf[Function0[?]])
      .invoke(callbacks.getField("MODULE$").get(()), (() => body): Function0[Unit])
    val kernel = Class.forName("nodal.ConstructionKernel$")
    try
      val _ = kernel.getMethod(
        "behavioralBranch",
        classOf[String],
        classOf[Option[?]],
        classOf[Function0[?]]
      ).invoke(kernel.getField("MODULE$").get(()), "when", Some(condition), checked)
    catch
      case failure: java.lang.reflect.InvocationTargetException =>
        scala.util.Failure[Nothing](Option(failure.getCause).getOrElse(failure)).get

enum MemoryCaptureMode derives HwEnum:
  case Idle, Active

final class MemoryCaptureSource(mode: String) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val foreign = ClockDomain.external(
    "foreign",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val input = in(UInt(32))
  val address = in(UInt(2))
  val enable = in(Bool)
  val output = out(UInt(32))
  val memory = Mem(UInt(32), 4, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val first = memory.read(address)
  mode match
    case "ordered" =>
      memory.write(address, input, 15.U(4))
      MemoryKernelFixture.branch(enable):
        memory.write(address, first, 0.U(4))
        memory.write(address, input, 1.U(1))
      memory.write(address, input, 15.U(4))
    case "branch-read" =>
      MemoryKernelFixture.branch(enable):
        val _ = memory.read(address)
    case "caught-read" =>
      val _ = scala.util.Try(MemoryKernelFixture.branch(enable):
        val _ = memory.read(address))
    case "foreign-read" => foreign { val _ = memory.read(address) }
    case "foreign-write" => foreign { memory.write(address, input, 1.U(1)) }
    case "bad-type" => memory.write(address, 1.U(31), 1.U(1))
    case "forged-read" =>
      val value = new KernelExpr[UInt](
        Vector(address, MemoryPortCapture(memory, None)),
        resultType = Some(CandidateRuntime.typeDescriptor(UInt(32))),
        operation = Some("memory_read")
      )
      ConstructionKernel.expression(value)
    case "forged-write" | "caught-forged-write" =>
      def forged(): Unit = ConstructionKernel.operation(
        "memory-write",
        memory,
        address,
        input,
        1.U(1),
        MemoryPortCapture(memory, None)
      )
      if mode == "caught-forged-write" then
        val _ = scala.util.Try(forged())
      else forged()

    case "captured-read" =>
      var state = Option.empty[Register[UInt]]
      root { state = Some(Reg.uninitialized(UInt(32))) }
      MemoryKernelFixture.branch(enable) { state.get := first }
    case "foreign-value" =>
      var state = Option.empty[Register[UInt]]
      foreign { state = Some(Reg.uninitialized(UInt(32))) }
      memory.write(address, state.get, 1.U(1))
    case "foreign-address" =>
      var state = Option.empty[Register[UInt]]
      foreign { state = Some(Reg.uninitialized(UInt(2))) }
      val _ = memory.read(state.get)
    case "foreign-mask" =>
      var state = Option.empty[Register[UInt]]
      foreign { state = Some(Reg.uninitialized(UInt(1))) }
      memory.write(address, input, state.get)
    case "foreign-guard" =>
      var state = Option.empty[Register[Bool]]
      foreign { state = Some(Reg.uninitialized(Bool)) }
      MemoryKernelFixture.branch(state.get) { memory.write(address, input, 1.U(1)) }
    case "read-to-foreign-state" =>
      var state = Option.empty[Register[UInt]]
      foreign { state = Some(Reg.uninitialized(UInt(32))) }
      state.get := first
    case _ => memory.write(address, input, 1.U(1))
  output := first

final class MemoryElementCapture[A <: Data](element: DataType[A]) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Falling,
    ResetPolicy.None,
    ResetPolarity.ActiveLow,
    100.MHz
  )
  val input = in(element)
  val output = out(element)
  val memory = Mem(element, 2, 2, ReadUnderWrite.NoChange, MemoryOrdering.Ordered, root)
  val value = memory.read(0.U(80))
  memory.write(1.U(128), input, 1.U(1))
  output := value

final class MemoryIdentityCollisionSource(late: Boolean) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val address = in(UInt(2))
  val data = in(UInt(8))
  val memory = Mem(UInt(8), 4, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  if late then memory.write(address, data, 1.U(1))
  val memory_write_0 = in(UInt(8))
  if !late then memory.write(address, data, 1.U(1))
  val memory_write_1 = memory.read(address)
  memory.write(address, memory_write_1, 1.U(1))

object Increment56MemoryCaptureTests extends TestSuite:
  private def capture(mode: String): ConstructionSnapshot =
    ConstructionKernel.inspect(new MemoryCaptureSource(mode))
  private def rejected(mode: String, code: String): Unit =
    val result = scala.util.Try(capture(mode))
    assert(result.isFailure, result.failed.get.getMessage.contains(code))
  val tests: Tests = Tests:
    test("owned typed reads and writes retain actual declaration association"):
      val snapshot = capture("ordinary")
      val memory = snapshot.modules.head.declarations.find(_.kind == "memory").get
      val read = snapshot.digitalExpressions.find(_.operation == "memory_read").get
      val write = snapshot.memoryWrites.head
      assert(read.dataType == "UInt(32)", read.operands.size == 1, read.region.isEmpty)
      assert(read.memory.contains(KernelMemoryPortSnapshot(
        memory.path,
        memory.attributes.toMap.apply("declared_domain")
      )))
      assert(write.memory == memory.path, write.domain == read.memory.get.domain)
      assert(snapshot.sourceMap.exists(_.semanticPath == write.path))
    test("memory writes share root and within-branch source chronology"):
      val snapshot = capture("ordered")
      assert(snapshot.memoryWrites.size == 4, snapshot.behavioralIfs.size == 1)
      val writes = snapshot.memoryWrites
      assert(writes.filter(_.region.isEmpty).map(_.sourceOrdinal) == Vector(0, 2))
      assert(snapshot.behavioralIfs.head.sourceOrdinal == 1)
      assert(writes.filter(_.region.nonEmpty).map(_.sourceOrdinal) == Vector(0, 1))
      assert(snapshot.behavioralIfs.head.trueArm.captures.exists(path =>
        snapshot.digitalExpressions.exists(value =>
          value.path == path && value.operation == "memory_read"
        )
      ))
    test("every storage kind keeps whole-element access including runtime Integer and Real"):
      val enumeration = EnumType[MemoryCaptureMode]()
      val structure = Struct(
        "MemoryCapturePacket",
        StructField("mode", enumeration),
        StructField("samples", Vec(UInt(8), 2)),
        StructField("count", Integer),
        StructField("level", Real)
      )
      val types: Vector[DataType[? <: Data]] = Vector(
        UInt(8),
        SInt(9),
        Bits(7),
        Bool,
        Integer,
        Real,
        enumeration,
        structure,
        Vec(structure, 2)
      )
      types.foreach: element =>
        val snapshot = ConstructionKernel.inspect(new MemoryElementCapture(element))
        val memory = snapshot.modules.head.declarations.find(_.kind == "memory").get
        val read = snapshot.digitalExpressions.find(_.operation == "memory_read").get
        assert(memory.dataType.contains(read.dataType), snapshot.memoryWrites.size == 1)
        assert(!snapshot.parameterExpressions.exists(_.path == read.path))
    test("existing reads may be captured into checked register updates"):
      val snapshot = capture("captured-read")
      assert(snapshot.digitalAssignments.count(_.kind == "register_next") == 1)
    test("branch-local read allocation rejects even when caught"):
      rejected("branch-read", "NODAL-MEMORY-056-001")
      rejected("caught-read", "NODAL-CONSTRUCTOR-LIFECYCLE-021")
    test("present foreign active domain rejects reads and writes"):
      rejected("foreign-read", "NODAL-CDC-001")
      rejected("foreign-write", "NODAL-CDC-001")
    test("write exact element types are checked at capture"):
      rejected("bad-type", "NODAL-MEMORY-056-001")
    test("generic private expression and effect routes cannot forge owned ports"):
      Vector("forged-read", "forged-write").foreach(rejected(_, "NODAL-MEMORY-056-001"))
      rejected("caught-forged-write", "NODAL-CONSTRUCTOR-LIFECYCLE-021")
    test("actual data address mask guard and read-result provenance share sampling owner"):
      Vector(
        "foreign-value",
        "foreign-address",
        "foreign-mask",
        "foreign-guard",
        "read-to-foreign-state"
      ).foreach(rejected(_, "NODAL-CDC-001"))
    test("authored names before and after writes remain distinct from allocated effect identities"):
      Vector(false, true).foreach: late =>
        val snapshot = ConstructionKernel.inspect(new MemoryIdentityCollisionSource(late))
        val paths = snapshot.memoryWrites.map(_.path)
        val values = snapshot.modules.flatMap(_.declarations.map(_.path)) ++
          snapshot.digitalExpressions.map(_.path)
        assert(paths.distinct.size == 2, !paths.exists(values.contains))
        assert(values.contains(snapshot.root + ".memory_write_0"))
        assert(values.contains(snapshot.root + ".memory_write_1"))
        val repeated = ConstructionKernel.inspect(new MemoryIdentityCollisionSource(late))
        assert(paths == repeated.memoryWrites.map(_.path))
    test("read write identities are deterministic"):
      val first = capture("ordered")
      val second = capture("ordered")
      assert(first.memoryWrites == second.memoryWrites)
      assert(first.digitalExpressions == second.digitalExpressions)
