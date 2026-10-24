package nodal_external

import nodal.*

sealed trait Increment58SelectedInterfaceInputLink extends Interface
object Increment58SelectedInterfaceInputContract:
  val small = InterfaceMember.value("small", UInt(3))
  val wide = InterfaceMember.value("wide", Bits(5))
  val hidden = InterfaceMember.value("hidden", Bool)
  val inputRole = Role[SinkRole]("sink", RoleAccess.In("small"), RoleAccess.In("wide"))
  val extraRole = Role[SinkRole](
    "sink", RoleAccess.In("small"), RoleAccess.In("wide"), RoleAccess.In("hidden")
  )
  val outputRole = Role[SinkRole]("sink", RoleAccess.Out("small"), RoleAccess.Out("wide"))
  val definition = Interface[Increment58SelectedInterfaceInputLink](
    "SelectedInterfaceInput", small, wide
  ).withRoles(inputRole)
  val extraDefinition = Interface[Increment58SelectedInterfaceInputLink](
    "SelectedInterfaceInputExtra", small, wide, hidden
  ).withRoles(extraRole)
  val outputDefinition = Interface[Increment58SelectedInterfaceInputLink](
    "SelectedInterfaceOutput", small, wide
  ).withRoles(outputRole)

final class Increment58SelectedInterfaceInputLeaf extends Module:
  import Increment58SelectedInterfaceInputContract.*
  val required = ClockDomain.required("required")
  val incoming = interfacePort(definition, inputRole, "incoming", required)
  val smallOutput = out(UInt(3))
  val wideOutput = out(Bits(5))
  smallOutput := incoming.read(small)
  wideOutput := incoming.read(wide)

final class Increment58SelectedInterfaceInputExtraLeaf extends Module:
  import Increment58SelectedInterfaceInputContract.*
  val required = ClockDomain.required("required")
  val incoming = interfacePort(extraDefinition, extraRole, "incoming", required)
  val smallOutput = out(UInt(3))
  val wideOutput = out(Bits(5))
  smallOutput := incoming.read(small)
  wideOutput := incoming.read(wide)

final class Increment58SelectedInterfaceInputEndpointLeaf extends Module:
  import Increment58SelectedInterfaceInputContract.*
  val required = ClockDomain.required("required")
  val incoming = interfacePort(definition, inputRole, "incoming", required)
  val unreferenced = interfacePort(definition, inputRole, "unreferenced", required)
  val smallOutput = out(UInt(3))
  val wideOutput = out(Bits(5))
  smallOutput := incoming.read(small)
  wideOutput := incoming.read(wide)

final class Increment58SelectedInterfaceInputOutputLeaf extends Module:
  import Increment58SelectedInterfaceInputContract.*
  val required = ClockDomain.required("required")
  val incoming = interfacePort(outputDefinition, outputRole, "incoming", required)
  val smallOutput = out(UInt(3))
  val wideOutput = out(Bits(5))
  smallOutput := incoming.read(small)
  wideOutput := incoming.read(wide)

/** Four real parent sources, no template input drive, and both differently sized leaves observed. */
final class Increment58SelectedInterfaceInputTwo(mode: String = "two") extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  val source = if mode == "foreign" then ClockDomain.external(
    "foreign", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 80.MHz
  ) else core
  source {
    val small1 = in(UInt(3))
    core {
      val small0 = in(UInt(3))
      val wide0 = in(Bits(5))
      val wide1 = in(Bits(5))
      val leftSmall = out(UInt(3))
      val leftWide = out(Bits(5))
      val rightSmall = out(UInt(3))
      val rightWide = out(Bits(5))
      val lanes =
        for _lane <- hdlRange(0, 2) yield new Area:
          val _ = _lane
          val child = instance(new Increment58SelectedInterfaceInputLeaf).domain(core)
          val _ = child
      val a = lanes(0).child
      val b = lanes(1).child
      val first = a(_.incoming)
      if mode == "wrong-type" then first.driveMember("small", wide1)
      else first.driveMember("small", if mode == "feedback" then a(_.smallOutput) else small0)
      if mode == "observe" then first.observeMember("small")
      first.driveMember("wide", wide0)
      if mode != "missing-lane" then
        val second = b(_.incoming)
        second.driveMember("small", small1)
        second.driveMember("wide", wide1)
      if mode == "duplicate-alias" then
        val alias = lanes(0.integer).child
        alias(_.incoming).driveMember("small", small1)
      leftSmall := a(_.smallOutput)
      leftWide := a(_.wideOutput)
      if mode != "missing-lane" then
        rightSmall := b(_.smallOutput)
        rightWide := b(_.wideOutput)
      else
        rightSmall := small0
        rightWide := wide0
    }
  }

/** Statically authored topology negative: no host choice occurs in the generated constructor. */
final class Increment58SelectedInterfaceInputExtraTwo extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  core {
    val small0 = in(UInt(3))
    val small1 = in(UInt(3))
    val wide0 = in(Bits(5))
    val wide1 = in(Bits(5))
    val leftSmall = out(UInt(3))
    val leftWide = out(Bits(5))
    val rightSmall = out(UInt(3))
    val rightWide = out(Bits(5))
    val lanes =
      for _lane <- hdlRange(0, 2) yield new Area:
        val _ = _lane
        val child = instance(new Increment58SelectedInterfaceInputExtraLeaf).domain(core)
        val _ = child
    val a = lanes(0).child
    val b = lanes(1).child
    val first = a(_.incoming)
    val second = b(_.incoming)
    first.driveMember("small", small0)
    first.driveMember("wide", wide0)
    second.driveMember("small", small1)
    second.driveMember("wide", wide1)
    leftSmall := a(_.smallOutput)
    leftWide := a(_.wideOutput)
    rightSmall := b(_.smallOutput)
    rightWide := b(_.wideOutput)
  }

/** Statically authored topology negative: no host choice occurs in the generated constructor. */
final class Increment58SelectedInterfaceInputEndpointTwo extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  core {
    val small0 = in(UInt(3))
    val small1 = in(UInt(3))
    val wide0 = in(Bits(5))
    val wide1 = in(Bits(5))
    val leftSmall = out(UInt(3))
    val leftWide = out(Bits(5))
    val rightSmall = out(UInt(3))
    val rightWide = out(Bits(5))
    val lanes =
      for _lane <- hdlRange(0, 2) yield new Area:
        val _ = _lane
        val child = instance(new Increment58SelectedInterfaceInputEndpointLeaf).domain(core)
        val _ = child
    val a = lanes(0).child
    val b = lanes(1).child
    val first = a(_.incoming)
    val second = b(_.incoming)
    first.driveMember("small", small0)
    first.driveMember("wide", wide0)
    second.driveMember("small", small1)
    second.driveMember("wide", wide1)
    leftSmall := a(_.smallOutput)
    leftWide := a(_.wideOutput)
    rightSmall := b(_.smallOutput)
    rightWide := b(_.wideOutput)
  }

/** Statically authored topology negative: no host choice occurs in the generated constructor. */
final class Increment58SelectedInterfaceInputOutputTwo extends Module:
  val core = ClockDomain.external(
    "core", ClockEdge.Rising, ResetPolicy.None, ResetPolarity.ActiveHigh, 100.MHz
  )
  core {
    val small0 = in(UInt(3))
    val small1 = in(UInt(3))
    val wide0 = in(Bits(5))
    val wide1 = in(Bits(5))
    val leftSmall = out(UInt(3))
    val leftWide = out(Bits(5))
    val rightSmall = out(UInt(3))
    val rightWide = out(Bits(5))
    val lanes =
      for _lane <- hdlRange(0, 2) yield new Area:
        val _ = _lane
        val child = instance(new Increment58SelectedInterfaceInputOutputLeaf).domain(core)
        val _ = child
    val a = lanes(0).child
    val b = lanes(1).child
    val first = a(_.incoming)
    val second = b(_.incoming)
    first.driveMember("small", small0)
    first.driveMember("wide", wide0)
    second.driveMember("small", small1)
    second.driveMember("wide", wide1)
    leftSmall := a(_.smallOutput)
    leftWide := a(_.wideOutput)
    rightSmall := b(_.smallOutput)
    rightWide := b(_.wideOutput)
  }
