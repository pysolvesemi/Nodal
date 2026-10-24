package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import scala.compiletime.testing.typeCheckErrors
import utest.*
object Increment58ResolvedArrayTests extends TestSuite:
  private def capture(mode: String) = Increment58ResolvedArrayReplay.capture(mode)
  private def rejection(mode: String, code: String, reason: String): Unit =
    val failure = scala.util.Try(capture(mode)).failed.get
    assert(failure.isInstanceOf[ConstructionException])
    assert(failure.asInstanceOf[ConstructionException].diagnostic.code == code)
    assert(failure.getMessage.contains(reason))
  private final class OriginFixture(
      kind: String,
      foreign: Option[InterfaceArray[Increment58ResolvedArrayLink, SourceRole]] = None
  ) extends Module:
    val core = ClockDomain.external(
      "core",
      ClockEdge.Rising,
      ResetPolicy.None,
      ResetPolarity.ActiveHigh,
      100.MHz
    )
    val pad = InterfaceMember.digitalInout("pad", UInt(4), DriveMode.pushPull)
    val definition = Interface[Increment58ResolvedArrayLink](
      "ArrayOrigin",
      pad
    ).withRoles(Increment58ResolvedArrayRoles.drive)
    val driven = interfaceArray(definition, Increment58ResolvedArrayRoles.drive, 3, "driven", core)
    val original = foreign.getOrElse(driven)
    val actualField = original.definition.members.head.asInstanceOf[InterfaceMember.DigitalResolved[
      UInt,
      DriveMode.PushPull
    ]]
    val changedDefinition = if kind == "definition" then
      Interface[Increment58ResolvedArrayLink](
        "OtherOrigin",
        actualField
      ).withRoles(Increment58ResolvedArrayRoles.drive)
    else original.definition
    val changedDomain = if kind == "domain" then
      ClockDomain.external(
        "other",
        ClockEdge.Rising,
        ResetPolicy.None,
        ResetPolarity.ActiveHigh,
        100.MHz
      )
    else original.domain
    val view = new InterfaceArray(
      changedDefinition,
      monitor,
      if kind == "count" then 2 else 3,
      "forged",
      changedDomain,
      exported = false,
      origin = Some(original)
    )
    val output = out(Vec(UInt(4), 3))
    output := view.resolved(actualField).read
  val tests = Tests:
    test("whole array read conditional drive and release retain one actual member carrier"):
      Vector("read", "drive", "release").foreach: mode =>
        val snapshot = capture(mode)
        assert(snapshot.resolvedNets.forall(_.member.nonEmpty))
        assert(snapshot.resolvedNets.forall(_.dataType == "Vec(UInt(4);count)"))
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.contains("!nodal.shaped<\"count\", !nodal.resolved<"))
        assert(!text.contains("\"nodal.resolved_net\""))
        assert(!text.contains("\"nodal.net_boundary\""))
    test("late child width and count seal both data and pointwise enable geometry"):
      val snapshot = capture("child")
      val local = snapshot.resolvedNets.filter(_.path.startsWith(snapshot.root + "."))
      assert(local.size == 2, local.forall(_.dataType == "Vec(UInt(width);count)"))
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(text.contains("\"nodal.interface_boundary\""))
      assert(text.contains("!nodal.shaped<\"count\", !nodal.bits<1>>"))
    test("readonly monitor split exposes original write enable and reconstructed aliases"):
      Vector("split", "split-alias").foreach: mode =>
        val snapshot = capture(mode)
        val projections =
          snapshot.digitalExpressions.filter(value => value.operation.startsWith("digital_net_"))
        assert(projections.exists(value =>
          value.operation == "digital_net_enable_view" && value.dataType == "Vec(Bool;count)"
        ))
        assert(projections.exists(value =>
          value.operation == "digital_net_write_view" && value.dataType == "Vec(UInt(4);count)"
        ))
        assert(snapshot.digitalConnectivity.count(_.kind == "inout-drive") == 1)
        assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("\"nodal.net_split\""))
    test("open drain source and exact family pass-through keep their original modes"):
      Vector("low", "high", "pass").foreach: mode =>
        val text = ScalaToMlirBridge.fromSnapshot(capture(mode)).text
        assert(text.contains(if mode == "pass" then "\"nodal.net_pass_through\""
        else "\"nodal.net_drive_level\""))
    test("shape ownership role and unique driver failures remain causal"):
      Vector(
        "wrong-enable",
        "wrong-rank"
      ).foreach(rejection(_, "NODAL-INTERFACE-054-008", "exact owned payload type"))
      rejection("child-wrong-type", "NODAL-COLLECTION-055-005", "exactly matching recursive types")
      rejection("wrong-field", "NODAL-INTERFACE-054-003", "exact declared field witness")
      rejection("read-drive", "NODAL-INTERFACE-054-003", "drive-only")
      Vector(
        "drive-connect",
        "monitor-drive"
      ).foreach(rejection(_, "NODAL-INTERFACE-054-003", "permit drive"))
      Vector(
        "split-empty",
        "released-split"
      ).foreach(rejection(_, "NODAL-INTERFACE-054-012", "no write value"))
      rejection("multiple-split", "NODAL-INTERFACE-054-012", "multiple conditional drivers")
      rejection("pass-shape", "NODAL-INTERFACE-054-008", "exact type")
      rejection("pass-domain", "NODAL-INTERFACE-054-006", "domain ownership")
    test("public scalar broadcast standalone arrays and mode laundering remain unavailable"):
      assert(typeCheckErrors("""import nodal.*
        def invalid(v: ResolvedDigitalArray[UInt, DriveMode.PushPull], d: Expr[Vec[UInt]], e: Expr[Bool]) = v.drive(d, e)
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        def invalid(v: ResolvedDigitalArray[UInt, DriveMode.OpenDrain], d: Expr[Vec[UInt]], e: Expr[Vec[Bool]]) = v.drive(d, e)
      """).nonEmpty)
      assert(typeCheckErrors("""import nodal.*
        val invalid = digitalInout(Vec(UInt(4), 3), DriveMode.pushPull, InoutPlacement.TopLevelPin, ResolutionProfile.PortableBoundaryOnly, "bad")
      """).nonEmpty)
    test("bridge independently refuses forged origin rights and exact result geometry"):
      val snapshot = capture("split")
      val read = snapshot.digitalExpressions.find(_.operation == "digital_net_enable_view").get
      val changed = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.map(value =>
          if value.path == read.path then value.copy(dataType = "Bool") else value
        )
      )
      val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(changed)).failed.get
      assert(failure.isInstanceOf[BridgeException])
      val selected = snapshot.resolvedNets.find(_.member.exists(_.role == "monitor")).get
      val forged = snapshot.copy(resolvedNets =
        snapshot.resolvedNets.map(value =>
          if value.path == selected.path then value.copy(member = None) else value
        )
      )
      val originFailure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
      assert(originFailure.isInstanceOf[BridgeException])
    test("monitor views cannot change actual definition domain shape or construction origin"):
      Vector("definition", "domain", "count").foreach: kind =>
        val failure = scala.util.Try(ConstructionKernel.inspect(new OriginFixture(kind))).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        val expected =
          if kind == "count" then "NODAL-INTERFACE-054-008" else "NODAL-INTERFACE-054-005"
        assert(failure.asInstanceOf[ConstructionException].diagnostic.code == expected)
      var saved: Option[InterfaceArray[Increment58ResolvedArrayLink, SourceRole]] = None
      val _ = ConstructionKernel.inspect:
        val module = new OriginFixture("keep")
        saved = Some(module.driven)
        module
      val foreign =
        scala.util.Try(ConstructionKernel.inspect(new OriginFixture("keep", saved))).failed.get
      assert(foreign.isInstanceOf[ConstructionException])
      assert(foreign.asInstanceOf[ConstructionException].diagnostic.code ==
        "NODAL-INTERFACE-054-007")
