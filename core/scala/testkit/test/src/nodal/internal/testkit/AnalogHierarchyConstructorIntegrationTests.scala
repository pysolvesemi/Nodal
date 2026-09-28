package nodal.internal.testkit

import nodal.*

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import utest.*

// These fixtures deliberately use the approved literal Param constructor spelling.
final class ConstructorGain(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val internal: Node[Electrical.type] = node(Electrical)

final class ConstructorTop(topGain: Param[Real] = 4.0) extends Module:
  def parameter: Param[Real] = topGain
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val amp: ConstructorGain = new ConstructorGain(gain = topGain)
  val another: ConstructorGain = new ConstructorGain(gain = topGain)
  val afterChildren: Param[Real] = param(7.0.real)
  vin <> amp.vin
  amp.vout <> vout

final class ConstructorOmittedAndEqualTop extends Module:
  val omitted: ConstructorGain = new ConstructorGain
  val explicitEqual: ConstructorGain = new ConstructorGain(gain = 2.0)

final class ConstructorAutomaticFormTop extends Module:
  val parentGain: Param[Real] = param(4.0.real)
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val amp: ConstructorGain = new ConstructorGain(gain = parentGain)
  vin <> amp.vin
  amp.vout <> vout

final class ConstructorExplicitFormTop extends Module:
  val parentGain: Param[Real] = param(4.0.real)
  val vin: Node[Electrical.type] = in(Electrical)
  val vout: Node[Electrical.type] = out(Electrical)
  val amp: Instance[ConstructorGain] = instance(new ConstructorGain)
  amp.param(_.parameter, parentGain)
  connect(vin, amp(_.vin))
  connect(amp(_.vout), vout)

final class ConstructorHandleTop extends Module:
  val childModule: ConstructorGain = new ConstructorGain
  val child: Instance[ConstructorGain] = instance(childModule)
  val sameChild: Instance[ConstructorGain] = instance(childModule)
  child.param(_.parameter, 5.0.real)

final class ConstructorClockedChild extends Module:
  val core: ClockDomain = ClockDomain.required("core")
  core:
    val state = Reg(0.U(8))
    state := 1.U(8)

final class ConstructorAutomaticDomainTop extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )
  root:
    val child = new ConstructorClockedChild
    val _ = child.core

final class ConstructorExplicitDomainTop extends Module:
  val root: ClockDomain = ClockDomain.external(
    "root",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )
  root:
    val childModule = new ConstructorClockedChild
    val child = instance(childModule)
    child.domain(root)

final class ConstructorDuplicateTop extends Module:
  val parentGain: Param[Real] = param(4.0.real)
  val amp: ConstructorGain = new ConstructorGain(gain = parentGain)
  val handle: Instance[ConstructorGain] = instance(amp)
  handle.param(_.parameter, 5.0.real)

final class ConstructorAliasDuplicateTop extends Module:
  val amp: ConstructorGain = new ConstructorGain
  val first: Instance[ConstructorGain] = instance(amp)
  val second: Instance[ConstructorGain] = instance(amp)
  first.param(_.parameter, 4.0.real)
  second.param(_.parameter, 5.0.real)

final class ConstructorForeignTargetTop extends Module:
  val left: Instance[ConstructorGain] = instance(new ConstructorGain)
  val right: ConstructorGain = new ConstructorGain
  left.param(_ => right.parameter, 4.0.real)

final class ConstructorSiblingActualTop extends Module:
  val left: ConstructorGain = new ConstructorGain
  val right: ConstructorGain = new ConstructorGain(gain = left.parameter)

// Host callback and generic constructors retain the established explicit profile.
final class ConstructorOwnerVisitor(action: () => Unit) extends Module:
  action()

final class ConstructorWrongOverrideOwnerTop extends Module:
  val child: Instance[ConstructorGain] = instance(new ConstructorGain)
  val visitor: Instance[ConstructorOwnerVisitor] = instance(new ConstructorOwnerVisitor(() =>
    val _ = child.param(_.parameter, 4.0.real)
  ))

final class ConstructorForeignHandleVisitor(child: ConstructorGain) extends Module:
  val handle: Instance[ConstructorGain] = instance(child)

final class ConstructorForeignHandleTop extends Module:
  val child: ConstructorGain = new ConstructorGain
  val visitor: Instance[ConstructorForeignHandleVisitor] =
    instance(new ConstructorForeignHandleVisitor(child))

final class ConstructorInternalEndpointTop extends Module:
  val pin: Node[Electrical.type] = in(Electrical)
  val child: ConstructorGain = new ConstructorGain
  pin <> child.internal

final class ConstructorDetachedEndpointTop(detached: Node[Electrical.type]) extends Module:
  val pin: Node[Electrical.type] = in(Electrical)
  pin <> detached

final class ConstructorPair(left: Param[Real] = 2.0, right: Param[Real] = 3.0) extends Module:
  def leftParameter: Param[Real] = left
  def rightParameter: Param[Real] = right

object ConstructorFactories:
  def gain(actual: Param[Real]): ConstructorGain = new ConstructorGain(gain = actual)

final class ConstructorFactoryTop(log: ArrayBuffer[String]) extends Module:
  val parentLeft: Param[Real] = param(4.0.real)
  val parentRight: Param[Real] = param(6.0.real)
  private def mark(label: String, value: Param[Real]): Param[Real] =
    log += label
    value
  val pair: ConstructorPair = new ConstructorPair(
    right = mark("right", parentRight),
    left = mark("left", parentLeft)
  )
  val factory: ConstructorGain = ConstructorFactories.gain(mark("factory", parentLeft))
  val afterChildren: Param[Real] = param(8.0.real)

final class ConstructorZeroChild extends Module:
  val pin: Node[Electrical.type] = in(Electrical)

final class ConstructorZeroTop extends Module:
  val pin: Node[Electrical.type] = in(Electrical)
  val child: ConstructorZeroChild = new ConstructorZeroChild
  val afterChild: Param[Real] = param(9.0.real)
  pin <> child.pin

final class ConstructorLegacyHost(val width: Int = 8) extends Module:
  val value: Param[UInt] = param(0.U(width))

final class ConstructorLegacyGeneric[A](val tag: A) extends Module:
  val gain: Param[Real] = param(2.0.real)

final class ConstructorLegacyTop(log: ArrayBuffer[String]) extends Module:
  private def width: Int =
    log += "host-width"
    12
  val host: Instance[ConstructorLegacyHost] = instance(new ConstructorLegacyHost(width))
  val generic: Instance[ConstructorLegacyGeneric[String]] =
    instance(new ConstructorLegacyGeneric[String]("host-tag"))
  val afterChildren: Param[Real] = param(9.0.real)

final class ConstructorLegacyUnattachedTop extends Module:
  val child: ConstructorLegacyHost = new ConstructorLegacyHost

final class ConstructorLegacyDoubleAttachTop extends Module:
  val child: ConstructorLegacyHost = new ConstructorLegacyHost
  val first: Instance[ConstructorLegacyHost] = instance(child)
  val second: Instance[ConstructorLegacyHost] = instance(child)

final class ConstructorReplicationTop extends Module:
  val empty: IndexedSeq[ConstructorGain] = (0 until 0).map(_ => new ConstructorGain)
  val singleton: IndexedSeq[ConstructorGain] = (0 until 1).map(_ => new ConstructorGain)
  val boundary: IndexedSeq[ConstructorGain] = (0 until 4).map(_ => new ConstructorGain)
  val vector: Vector[ConstructorGain] =
    Vector.tabulate(2)(_ => new ConstructorGain)
  val list: List[ConstructorGain] =
    List.tabulate(2)(_ => new ConstructorGain)
  val core: ClockDomain = ClockDomain.external(
    "core",
    edge = ClockEdge.Rising,
    reset = ResetPolicy.Sync,
    resetPolarity = ResetPolarity.ActiveHigh,
    frequency = 100.MHz
  )
  val states: Vector[Register[UInt]] =
    var captured = Vector.empty[Register[UInt]]
    core:
      captured = Vector.tabulate(4)(_ => Reg(0.U(8)))
    captured
  core:
    for index <- 0 until 4 do states(index) := (index + 1).U(8)

final class ConstructorInvalidReplicationTop extends Module:
  val childModule: ConstructorLegacyHost = new ConstructorLegacyHost
  val repeated: Vector[Instance[ConstructorLegacyHost]] =
    Vector.tabulate(2)(_ => instance(childModule))

final class ConstructorFailingGain(gain: Param[Real] = 2.0) extends Module:
  def parameter: Param[Real] = gain
  val begun: Param[Real] = param(11.0.real)
  scala.util.Failure[Unit](new IllegalStateException("constructor-body-failure")).get

final class ConstructorCaughtBodyFailureTop(log: ArrayBuffer[String]) extends Module:
  val parentGain: Param[Real] = param(4.0.real)
  private val attempted = Try(new ConstructorFailingGain(gain = parentGain))
  log += attempted.failed.get.getMessage

final class ConstructorCaughtArgumentFailureTop(log: ArrayBuffer[String]) extends Module:
  val parentGain: Param[Real] = param(4.0.real)
  private def first: Param[Real] =
    val _ = ConstructorFactories.gain(parentGain)
    log += "first-argument-child"
    parentGain
  private def second: Param[Real] =
    log += "second-argument-failure"
    scala.util.Failure[Param[Real]](new IllegalStateException("constructor-argument-failure")).get
  private val attempted = Try(new ConstructorPair(left = first, right = second))
  log += attempted.failed.get.getMessage

object AnalogHierarchyConstructorIntegrationTests extends TestSuite:
  private def failure(top: => Module): ConstructionException =
    Try(ConstructionKernel.inspect(top)).failed.get.asInstanceOf[ConstructionException]

  private def root(snapshot: ConstructionSnapshot): KernelModuleSnapshot =
    snapshot.modules.find(_.path == snapshot.root).get

  private def child(snapshot: ConstructionSnapshot, name: String): KernelModuleSnapshot =
    snapshot.modules.find(_.path == s"${snapshot.root}.$name").get

  private def declaration(module: KernelModuleSnapshot, name: String): KernelDeclarationSnapshot =
    module.declarations.find(_.name == name).get

  private def relative(snapshot: ConstructionSnapshot, value: String): String =
    value.replace(snapshot.root, "$root")

  val tests: Tests = Tests:
    test("constructor child owns a fresh declaration and retains its authored literal default"):
      var captured: Option[ConstructorTop] = None
      val snapshot = ConstructionKernel.inspect:
        val top = new ConstructorTop
        captured = Some(top)
        top
      val top = captured.get
      assert(top.amp.parameter ne top.parameter)
      assert(top.another.parameter ne top.parameter)
      assert(top.amp.parameter ne top.another.parameter)
      val parent = root(snapshot)
      val parentParameter = declaration(parent, "topGain")
      assert(parentParameter.attributes.toMap.get("default").contains("4.0"))
      assert(parent.declarations.count(_.kind == "parameter") == 2)
      assert(declaration(parent, "afterChildren").attributes.toMap.get("default").contains("7.0"))
      assert(parent.instances.size == 2)
      Vector("amp", "another").foreach: name =>
        val module = child(snapshot, name)
        val parameter = declaration(module, "gain")
        assert(module.declarations.count(_.kind == "parameter") == 1)
        assert(parameter.dataType.contains("Real"))
        assert(parameter.attributes.toMap.get("default").contains("2.0"))
        val instance = parent.instances.find(_.childModule == module.path).get
        assert(instance.parameterBindings == Vector("gain" -> parentParameter.path))
      assert(snapshot.topology.count(_.kind == "node-connect") == 2)

    test("root constructor actual remains distinct from the authored declaration default"):
      val snapshot = ConstructionKernel.inspect(new ConstructorTop(topGain = 6.0))
      val parent = root(snapshot)
      assert(declaration(parent, "topGain").attributes.toMap.get("default").contains("4.0"))
      assert(snapshot.rootParameterBindings == Vector("topGain" -> "6.0"))
      assert(parent.instances.map(_.parameterBindings) == Vector.fill(2)(
        Vector("gain" -> declaration(parent, "topGain").path)
      ))

    test("omitted default and explicit equal literal remain distinct binding cases"):
      val snapshot = ConstructionKernel.inspect(new ConstructorOmittedAndEqualTop)
      val instances = root(snapshot).instances
      val omitted = child(snapshot, "omitted")
      val explicitEqual = child(snapshot, "explicitEqual")
      assert(instances.find(_.childModule == omitted.path).get.parameterBindings.isEmpty)
      assert(instances.find(_.childModule == explicitEqual.path).get.parameterBindings ==
        Vector("gain" -> "2.0"))
      Vector(omitted, explicitEqual).foreach: module =>
        assert(declaration(module, "gain").attributes.toMap.get("default").contains("2.0"))

    test("automatic and explicit forms share canonical hierarchy and connection semantics"):
      val automatic = ConstructionKernel.inspect(new ConstructorAutomaticFormTop)
      val explicit = ConstructionKernel.inspect(new ConstructorExplicitFormTop)
      val snapshots = Vector(automatic, explicit)
      assert(snapshots.map(snapshot => root(snapshot).instances.size) == Vector(1, 1))
      val parameters = snapshots.map: snapshot =>
        child(snapshot, "amp").declarations.map: value =>
          (value.kind, value.name, value.dataType, value.attributes)
      assert(parameters.distinct.size == 1)
      val bindings = snapshots.map: snapshot =>
        root(snapshot).instances.head.parameterBindings.map: (name, value) =>
          name -> relative(snapshot, value)
      assert(bindings == Vector.fill(2)(Vector("gain" -> "$root.parentGain")))
      val topology = snapshots.map: snapshot =>
        snapshot.topology.map: edge =>
          (edge.kind, relative(snapshot, edge.left), relative(snapshot, edge.right))
      assert(topology.distinct.size == 1)

    test("explicit retrieval returns the canonical handle and preserves the first explicit name"):
      var captured: Option[ConstructorHandleTop] = None
      val snapshot = ConstructionKernel.inspect:
        val top = new ConstructorHandleTop
        captured = Some(top)
        top
      assert(captured.get.child eq captured.get.sameChild)
      assert(root(snapshot).instances.size == 1)
      assert(snapshot.modules.map(_.path) ==
        Vector("ConstructorHandleTop", "ConstructorHandleTop.child"))
      assert(root(snapshot).instances.head.parameterBindings == Vector("gain" -> "5.0"))
      assert(snapshot.names.exists(entry =>
        entry.semanticPath == "ConstructorHandleTop.child" && entry.name == "child"
      ))

    test("automatic zero argument allocation and explicit handle keep lexical domains"):
      val snapshots = Vector(
        ConstructionKernel.inspect(new ConstructorAutomaticDomainTop),
        ConstructionKernel.inspect(new ConstructorExplicitDomainTop)
      )
      snapshots.foreach: snapshot =>
        assert(root(snapshot).instances.size == 1)
        val module = child(snapshot, "child")
        assert(module.domains.exists(domain =>
          domain.name == "core" && domain.binding.contains(s"${snapshot.root}.root")
        ))
        assert(module.declarations.exists(value =>
          value.kind == "register" && value.domain.contains(s"${snapshot.root}.root")
        ))
        assert(root(snapshot).instances.head.lexicalDomain.contains(s"${snapshot.root}.root"))

    test("constructor actual and explicit override cannot bind the same target twice"):
      assert(failure(new ConstructorDuplicateTop).diagnostic.code == "NODAL-HIERARCHY-030")
      assert(failure(new ConstructorAliasDuplicateTop).diagnostic.code == "NODAL-HIERARCHY-030")

    test("foreign child selector and sibling owned actual retain exact ownership checks"):
      assert(failure(new ConstructorForeignTargetTop).diagnostic.code ==
        "NODAL-PARAMETER-BINDING-017")
      assert(failure(new ConstructorSiblingActualTop).diagnostic.code == "NODAL-HIERARCHY-031")

    test("only the current owning parent can mutate or retrieve a captured child handle"):
      assert(failure(new ConstructorWrongOverrideOwnerTop).diagnostic.code ==
        "NODAL-PARAMETER-BINDING-020")
      val _ = failure(new ConstructorForeignHandleTop)
      assert(ConstructionKernel.inspect(new ConstructorZeroTop).modules.size == 2)

    test("automatic attachment does not expose internal endpoints"):
      assert(failure(new ConstructorInternalEndpointTop).diagnostic.code == "NODAL-HIERARCHY-040")

    test("an endpoint retained from a completed transaction remains detached"):
      var detached: Option[Node[Electrical.type]] = None
      val _ = ConstructionKernel.inspect:
        val top = new ConstructorZeroTop
        detached = Some(top.child.pin)
        top
      assert(failure(new ConstructorDetachedEndpointTop(detached.get)).diagnostic.code ==
        "NODAL-HIERARCHY-038")

    test("factory allocation and reversed named arguments preserve evaluation order and count"):
      val log = ArrayBuffer.empty[String]
      val snapshot = ConstructionKernel.inspect(new ConstructorFactoryTop(log))
      assert(log.toVector == Vector("right", "left", "factory"))
      val parent = root(snapshot)
      assert(parent.instances.size == 2)
      assert(parent.declarations.count(_.kind == "parameter") == 3)
      val pair = child(snapshot, "pair")
      val pairBindings = parent.instances.find(_.childModule == pair.path).get.parameterBindings
      assert(pairBindings.toMap == Map(
        "left" -> declaration(parent, "parentLeft").path,
        "right" -> declaration(parent, "parentRight").path
      ))
      assert(declaration(pair, "left").attributes.toMap.get("default").contains("2.0"))
      assert(declaration(pair, "right").attributes.toMap.get("default").contains("3.0"))
      val factory = child(snapshot, "factory")
      assert(parent.instances.find(_.childModule == factory.path).get.parameterBindings ==
        Vector("gain" -> declaration(parent, "parentLeft").path))

    test("automatic zero argument child finishes before subsequent parent declarations"):
      val snapshot = ConstructionKernel.inspect(new ConstructorZeroTop)
      assert(snapshot.modules.map(_.path) ==
        Vector("ConstructorZeroTop", "ConstructorZeroTop.child"))
      assert(root(snapshot).instances.size == 1)
      assert(declaration(root(snapshot), "afterChild").attributes.toMap.get("default")
        .contains("9.0"))
      assert(child(snapshot, "child").declarations.map(_.name) == Vector("pin"))
      assert(snapshot.topology.count(_.kind == "node-connect") == 1)

    test("legacy host and generic constructors retain explicit semantics and host arguments"):
      val log = ArrayBuffer.empty[String]
      var captured: Option[ConstructorLegacyTop] = None
      val snapshot = ConstructionKernel.inspect:
        val top = new ConstructorLegacyTop(log)
        captured = Some(top)
        top
      assert(log.toVector == Vector("host-width"))
      assert(captured.get.host(_.width) == 12)
      assert(captured.get.generic(_.tag) == "host-tag")
      assert(root(snapshot).instances.size == 2)
      assert(declaration(child(snapshot, "host"), "value").dataType.contains("UInt(12)"))
      assert(declaration(child(snapshot, "generic"), "gain").attributes.toMap.get("default")
        .contains("2.0"))
      assert(root(snapshot).instances.forall(_.parameterBindings.isEmpty))

    test("legacy unattached and double attachment guards remain active"):
      assert(failure(new ConstructorLegacyUnattachedTop).diagnostic.code == "NODAL-LIFECYCLE-017")
      assert(failure(new ConstructorLegacyDoubleAttachTop).diagnostic.code == "NODAL-HIERARCHY-017")

    test("fixed Scala ranges and strict collections retain stable indexed identities"):
      val first = ConstructionKernel.inspect(new ConstructorReplicationTop)
      val second = ConstructionKernel.inspect(new ConstructorReplicationTop)
      assert(first == second)
      val expected = Vector(
        "singleton_0",
        "boundary_0",
        "boundary_1",
        "boundary_2",
        "boundary_3",
        "vector_0",
        "vector_1",
        "list_0",
        "list_1"
      )
      assert(root(first).instances.map(_.childModule) ==
        expected.map(name => s"${first.root}.$name"))
      assert(root(first).declarations.filter(_.kind == "register").map(_.name) ==
        Vector("states_0", "states_1", "states_2", "states_3"))
      assert(first.modules.map(_.path).toSet ==
        (first.root +: expected.map(name => s"${first.root}.$name")).toSet)
      expected.foreach: name =>
        val path = s"${first.root}.$name"
        assert(first.names.exists(entry =>
          entry.semanticPath == path && entry.name == name &&
            entry.provenance == "scala-declaration"
        ))
        assert(first.sourceMap.exists(entry =>
          entry.semanticPath == path &&
            entry.source.path.endsWith("AnalogHierarchyConstructorIntegrationTests.scala") &&
            entry.source.line > 0 && entry.source.column > 0
        ))

    test("invalid fixed replication cannot attach one legacy child at multiple indices"):
      assert(failure(new ConstructorInvalidReplicationTop).diagnostic.code ==
        "NODAL-HIERARCHY-017")

    test("automatic hierarchy source identities are deterministic and mapped"):
      val first = ConstructionKernel.inspect(new ConstructorTop)
      val second = ConstructionKernel.inspect(new ConstructorTop)
      assert(first == second)
      Vector("amp", "another").foreach: name =>
        val path = s"${first.root}.$name"
        assert(first.names.exists(entry => entry.semanticPath == path && entry.name == name))
        assert(first.sourceMap.exists(entry =>
          entry.semanticPath == path &&
            entry.source.path.endsWith("AnalogHierarchyConstructorIntegrationTests.scala") &&
            entry.source.line > 0 && entry.source.column > 0
        ))

    // A constructor that entered Module.begin may have touched several canonical owners;
    // catching it cannot make the transaction publishable.
    test("caught constructor body failure poisons the transaction and a fresh session succeeds"):
      val log = ArrayBuffer.empty[String]
      val rejected = Try(ConstructionKernel.inspect(new ConstructorCaughtBodyFailureTop(log)))
      assert(log.toVector == Vector("constructor-body-failure"))
      assert(rejected.isFailure)
      val recovered = ConstructionKernel.inspect(new ConstructorTop)
      assert(recovered == ConstructionKernel.inspect(new ConstructorTop))
      assert(recovered.modules.size == 3)

    // Argument evaluation stays outside the pending allocation. Earlier construction effects
    // still poison the transaction when a later argument fails.
    test("caught argument failure poisons earlier effects and leaves a fresh session clean"):
      val log = ArrayBuffer.empty[String]
      val rejected = Try(ConstructionKernel.inspect(new ConstructorCaughtArgumentFailureTop(log)))
      assert(log.toVector == Vector(
        "first-argument-child",
        "second-argument-failure",
        "constructor-argument-failure"
      ))
      assert(rejected.isFailure)
      val recovered = ConstructionKernel.inspect(new ConstructorZeroTop)
      assert(recovered == ConstructionKernel.inspect(new ConstructorZeroTop))
      assert(recovered.modules.size == 2)
