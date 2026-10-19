package nodal_external

import nodal.*

enum MemoryBridgeMode derives HwEnum:
  case Idle, Run

/** Safe executable companion to the unchanged Foundation 15 compile-only memory example. */
final class Increment56MemoryBridgeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val address = in(UInt(2))
  val data = in(UInt(32))
  val mask = in(UInt(4))
  val enable = in(Bool)
  val selector = in(EnumType[MemoryBridgeMode]())
  val output = out(UInt(32))
  val memory = Mem(UInt(32), 4, 1, ReadUnderWrite.NewData, MemoryOrdering.Ordered, root)
  val read = memory.read(address)
  memory.write(address, data, mask)
  when(enable):
    memory.write(address, 17.U(32), 1.U(1))
    when(enable):
      memory.write(address, read, 0.U(1))
  root:
    fsm(FsmDefinition[MemoryBridgeMode]("memory_actions"), MemoryBridgeMode.Idle): machine =>
      machine.state(MemoryBridgeMode.Idle): current =>
        current.active { memory.write(address, 34.U(32), 1.U(1)) }
        current.onWithAction(enable)(MemoryBridgeMode.Run) {
          memory.write(address, 51.U(32), 1.U(1))
        }
      machine.state(MemoryBridgeMode.Run)(_.terminal())
  switchEnum(selector)(invalid = { memory.write(address, 68.U(32), 0.U(1)) }): cases =>
    cases.is(MemoryBridgeMode.Idle) { memory.write(address, data, mask) }
    cases.is(MemoryBridgeMode.Run) { () }
  output := read

final class Increment56MemoryWholeSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Falling,
    ResetPolicy.None,
    ResetPolarity.ActiveLow,
    50.MHz
  )
  val address = in(UInt(1))
  val enable = in(Bool)
  val boolInput = in(Bool)
  val bitsInput = in(Bits(8))
  val signedInput = in(SInt(8))
  val integerInput = in(Integer)
  val realInput = in(Real)
  val enumType = EnumType[MemoryBridgeMode]()
  val enumInput = in(enumType)
  val vectorType = Vec(Integer, 2)
  val vectorInput = in(vectorType)
  val packetType = Struct(
    "MemoryPacket",
    StructField("mode", enumType),
    StructField("samples", Vec(Real, 2)),
    StructField("count", Integer)
  )
  val packetInput = in(packetType)
  val boolMemory = Mem(Bool, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val bitsMemory = Mem(Bits(8), 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val signedMemory = Mem(SInt(8), 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val integerMemory = Mem(Integer, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val realMemory = Mem(Real, 2, 2, ReadUnderWrite.NoChange, MemoryOrdering.Ordered, root)
  val enumMemory = Mem(enumType, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val vectorMemory = Mem(vectorType, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val packetMemory = Mem(packetType, 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val boolRead = boolMemory.read(address)
  val bitsRead = bitsMemory.read(address)
  val signedRead = signedMemory.read(address)
  val integerRead = integerMemory.read(address)
  val realRead = realMemory.read(address)
  val enumRead = enumMemory.read(address)
  val vectorRead = vectorMemory.read(address)
  val packetRead = packetMemory.read(address)
  boolMemory.write(address, boolInput, 1.U(1))
  bitsMemory.write(address, bitsInput, 1.U(1))
  signedMemory.write(address, signedInput, 1.U(1))
  integerMemory.write(address, integerInput, 1.U(1))
  realMemory.write(address, realInput, 1.U(1))
  enumMemory.write(address, enumInput, 1.U(1))
  vectorMemory.write(address, vectorInput, 1.U(1))
  packetMemory.write(address, packetInput, 1.U(1))
  when(enable):
    boolMemory.write(address, boolRead, 1.U(1))
    bitsMemory.write(address, bitsRead, 1.U(1))
    signedMemory.write(address, signedRead, 1.U(1))
    integerMemory.write(address, integerRead, 1.U(1))
    realMemory.write(address, realRead, 1.U(1))
    enumMemory.write(address, enumRead, 1.U(1))
    vectorMemory.write(address, vectorRead, 1.U(1))
    packetMemory.write(address, packetRead, 1.U(1))

final class Increment56MemorySymbolicSource(unsafe: Boolean = false) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val DEPTH = param(8.integer, range = (if unsafe then 1 to 16 else 8 to 16))
  val BYTES = param(4.integer, range = 2 to 4)
  val wordType = UInt(BYTES * 8.integer)
  val address = in(UInt(3))
  val data = in(wordType)
  val mask = in(UInt(BYTES))
  val memory = Mem(wordType, DEPTH, 1, ReadUnderWrite.NewData, MemoryOrdering.Ordered, root)
  val read = memory.read(address)
  memory.write(address, data, mask)

final class Increment56MemoryFeedbackSource(latency: Int) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val address = wire(UInt(2))
  val memory = Mem(UInt(2), 4, latency, ReadUnderWrite.Undefined, MemoryOrdering.Ordered, root)
  val read = memory.read(address)
  address := read

final class Increment56MemoryOnlySource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val address = in(UInt(1))
  val data = in(UInt(8))
  val memory = Mem(UInt(8), 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val read = memory.read(address)
  memory.write(address, data, 1.U(1))

final class Increment56MemoryForeignSource extends Module:
  val first = ClockDomain.external(
    "first",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val second = ClockDomain.external(
    "second",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    50.MHz
  )
  var foreign: Option[Register[UInt]] = None
  second { foreign = Some(Reg.uninitialized(UInt(8))) }
  val memory = Mem(UInt(8), 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, first)
  first { memory.write(0.U(1), foreign.get, 1.U(1)) }

final class Increment56MemoryWideAddressSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val data = in(UInt(8))
  val memory = Mem(UInt(8), 2, 1, ReadUnderWrite.OldData, MemoryOrdering.Ordered, root)
  val read = memory.read(0.U(80))
  memory.write(1.U(128), data, 1.U(1))

/** Small semantic cases share exactly the public port construction path. */
final class Increment56MemoryPolicySource(
    latency: Int = 1,
    collision: ReadUnderWrite = ReadUnderWrite.OldData,
    ordering: MemoryOrdering = MemoryOrdering.Ordered,
    reverse: Boolean = false,
    disjoint: Boolean = false,
    zero: Boolean = false
) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.None,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val memory = Mem(UInt(16), 2, latency, collision, ordering, root)
  val firstAddress = 0.U(1)
  val secondAddress = (if disjoint then 1 else 0).U(1)
  val firstValue = 0x1122.U(16)
  val secondValue = 0x3344.U(16)
  val firstMask = 1.U(2)
  val secondMask = (if zero then 0 else 3).U(2)
  if reverse then
    memory.write(secondAddress, secondValue, secondMask)
    memory.write(firstAddress, firstValue, firstMask)
  else
    memory.write(firstAddress, firstValue, firstMask)
    memory.write(secondAddress, secondValue, secondMask)
  val read = memory.read(firstAddress)

/** Legal authored names reserve their identity even when declared after an earlier write. */
final class Increment56MemoryIdentitySource(late: Boolean) extends Module:
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
