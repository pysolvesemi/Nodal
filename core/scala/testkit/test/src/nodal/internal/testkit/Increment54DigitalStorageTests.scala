package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration

import nodal.*
import nodal.internal.bridge.*
import utest.*

enum DigitalStorageMode derives HwEnum:
  case Idle, Active

final class DigitalStorageBridgeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(8.integer, range = 4 to 12)
  val depth = param(4.integer, range = 1 to 8)
  val modeType = EnumType[DigitalStorageMode]()
  val packet = Struct(
    "StoragePacket",
    StructField("signedSamples", Vec(SInt(width), 2, 3)),
    StructField("mode", modeType),
    StructField("count", Integer),
    StructField("level", Real)
  )
  val wireValue = wire(packet)
  val signedInput = in(Vec(SInt(width), 2, 3))
  val signedCells = wire(Vec(SInt(width), 2, 3))
  signedCells := signedInput
  val flat = signedCells.flatten
  val chosen = flat.at(1)
  val integers = wire(Vec(Integer, 2))
  val integerElement = integers.at(0)
  val reals = wire(Vec(Real, 2))
  val realElement = reals.at(0)
  val state = Reg((-2).S(8))
  val uninitialized = Reg.uninitialized(Vec(SInt(width), 2, 3))
  val modeState = Reg(enumLiteral(modeType, DigitalStorageMode.Idle))
  val stateBits = state.reinterpretUnsigned
  val modeBits = modeState.asBits
  val delayed = RegNext.uninitialized(state)
  val delayedAgain = RegNext.uninitialized(delayed)
  val memory = Mem(packet, depth, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val compoundMemory = Mem(
    SInt(width),
    depth + 1.integer,
    0,
    ReadUnderWrite.Undefined,
    MemoryOrdering.IndependentPorts,
    root
  )
  val input = in(SInt(8))
  val output = out(SInt(8))
  val selectedOutput = out(SInt(width))
  output := input
  selectedOutput := chosen
  state := input

final class DigitalStorageInvalidLatencySource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val memory = Mem(UInt(8), 2, -1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)

final class DigitalStorageCapabilitySource(reset: Boolean) extends Module:
  val invalid = if reset then wire(Vec(Reset, 2)) else wire(Vec(Clock, 2))

final class DigitalMemoryFootprintSource(real: Boolean, overflow: Boolean) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val firstOverflowingDepth = 134217728.integer * 1073741824.integer
  val depth = if overflow then firstOverflowingDepth else firstOverflowingDepth - 1.integer
  val memory =
    if real then Mem(Real, depth, 0, ReadUnderWrite.Undefined, MemoryOrdering.Ordered, root)
    else Mem(Integer, depth, 0, ReadUnderWrite.Undefined, MemoryOrdering.Ordered, root)

object Increment54DigitalStorageTests extends TestSuite:
  private def snapshot: ConstructionSnapshot =
    ConstructionKernel.inspect(new DigitalStorageBridgeSource)

  private def changed(
      value: ConstructionSnapshot,
      name: String
  )(change: KernelDeclarationSnapshot => KernelDeclarationSnapshot): ConstructionSnapshot =
    value.copy(modules =
      value.modules.map(module =>
        module.copy(declarations =
          module.declarations.map(declaration =>
            if declaration.name == name then change(declaration) else declaration
          )
        )
      )
    )

  private def rejected(value: ConstructionSnapshot): Boolean =
    scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.toOption.exists(
      _.isInstanceOf[BridgeException]
    )

  private def native(document: NodalMlirDocument, directory: Path): String =
    NativeCompilerClient.run(
      document,
      NativeCompilerRequest(
        Path.of(sys.env.getOrElse(
          "NODAL_NODALC",
          scala.util.Failure[Nothing](new java.lang.AssertionError(
            "configured nodalc is required"
          )).get
        )).toAbsolutePath,
        Vector.empty,
        directory,
        timeout = Duration.ofSeconds(90)
      )
    ) match
      case success: NativeCompilerSuccess => success.normalizedMlir
      case failure: NativeCompilerFailure =>
        scala.util.Failure[Nothing](
          new java.lang.AssertionError(s"${failure.diagnostic}\n${failure.standardError}")
        ).get

  val tests: Tests = Tests:
    test("owned wire register and memory preserve exact recursive source contracts"):
      val captured = snapshot
      val module = captured.modules.find(_.path == captured.root).get
      val state = module.declarations.find(_.name == "state").get
      val initial = state.attributes.toMap.apply("initial")
      assert(state.dataType.contains("SInt(8)"))
      Vector("delayed", "delayedAgain").foreach: name =>
        val declaration = module.declarations.find(_.name == name).get
        assert(declaration.dataType.contains("SInt(8)"))
        assert(declaration.attributes.toMap.apply("initial") == "none")
      assert(captured.digitalExpressions.exists(_.operation == "digital_reinterpret_unsigned"))
      assert(captured.digitalExpressions.exists(_.operation == "enum_as_bits"))
      assert(captured.digitalExpressions.exists(expression =>
        expression.path == initial && expression.literal.contains("-2")
      ))
      val memory = module.declarations.find(_.name == "memory").get
      assert(memory.kind == "memory", memory.dataType.exists(_.startsWith("Struct(")))
      assert(memory.attributes.toMap.apply("readUnderWrite") == "OldData")
      assert(memory.attributes.toMap.apply("ordering") == "Ordered")
      assert(memory.attributes.toMap.apply("declared_domain") == s"${captured.root}.root")
      val document = ScalaToMlirBridge.fromSnapshot(captured)
      assert(document == ScalaToMlirBridge.lower(new DigitalStorageBridgeSource))
      assert(document.text.contains("\"nodal.digital_storage\""))
      assert(document.text.contains("kind = \"register\""))
      assert(document.text.contains("\"nodal.memory\""))
      assert(document.text.contains("read_under_write = \"old_data\""))
      assert(document.text.contains("ordering = \"independent_ports\""))
      assert(document.text.contains("!nodal.struct<\"StoragePacket\""))
      assert(document.text.contains("!nodal.digital<\"sint\", \"width\">"))
      assert(document.text.contains("!nodal.enum<"))
      assert(document.text.contains("\"nodal.shape_view\""))
      assert(document.text.contains("\"nodal.shape_index\""))
      assert(document.text.contains("\"nodal.digital_assign\""))
      assert(document.text.contains("kind = \"register_next\""))
      assert(!document.text.contains("\"nodal.placeholder\""))

    test("bridge independently rejects malformed storage descriptors"):
      val captured = snapshot
      assert(rejected(changed(captured, "state")(_.copy(dataType = Some("UInt(8)")))))
      Vector("Clock", "Vec(Clock;2)", "Struct(Bad{clock:Clock})").foreach: dataType =>
        assert(rejected(changed(captured, "wireValue")(_.copy(dataType = Some(dataType)))))
      assert(rejected(changed(captured, "state")(_.copy(domain = None))))
      assert(rejected(changed(captured, "state")(declaration =>
        declaration.copy(attributes =
          declaration.attributes.map((key, value) =>
            key ->
              (if key == "initial" then s"${captured.root}.missing" else value)
          )
        )
      )))
      assert(rejected(changed(captured, "wireValue")(_.copy(domain =
        Some(s"${captured.root}.root")
      ))))
      Vector(
        "readLatency" -> "-1",
        "readUnderWrite" -> "unspecified",
        "ordering" -> "unknown",
        "depth" -> "0",
        "depth" -> "01",
        "depth" -> "+1",
        "depth" -> s"${captured.root}.input",
        "declared_domain" -> "foreign.root"
      )
        .foreach: (key, replacement) =>
          assert(rejected(changed(captured, "memory")(declaration =>
            declaration.copy(attributes =
              declaration.attributes.map((field, value) =>
                field -> (if field == key then replacement else value)
              )
            )
          )))

    test("source rejects invalid memory latency without manufacturing access semantics"):
      val result =
        scala.util.Try(ConstructionKernel.inspect(new DigitalStorageInvalidLatencySource))
      assert(result.isFailure)

    test("recursive storage capability and Real Integer footprints are proved at construction"):
      Vector(false, true).foreach: mode =>
        assert(scala.util.Try(
          ConstructionKernel.inspect(new DigitalStorageCapabilitySource(mode))
        ).failed.toOption.exists(_.isInstanceOf[ConstructionException]))
        val boundary =
          ConstructionKernel.inspect(new DigitalMemoryFootprintSource(mode, overflow = false))
        assert(boundary.modules.flatMap(_.declarations).exists(_.kind == "memory"))
        assert(scala.util.Try(
          ConstructionKernel.inspect(new DigitalMemoryFootprintSource(mode, overflow = true))
        ).failed.toOption.exists(_.isInstanceOf[ConstructionException]))

    test("source storage and real Integer projections verify and round trip natively"):
      val directory = Path.of(".work", "increment54-digital-storage").toAbsolutePath
      Files.createDirectories(directory)
      val document = ScalaToMlirBridge.lower(new DigitalStorageBridgeSource)
      Files.writeString(directory.resolve("source.mlir"), document.text)
      val normalized = native(document, directory)
      Files.writeString(directory.resolve("normalized.mlir"), normalized)
      assert(normalized.contains("nodal.digital_storage"), normalized.contains("nodal.memory"))
      assert(normalized.contains("nodal.digital_assign"))
      assert(normalized == native(document.copy(text = normalized), directory))
