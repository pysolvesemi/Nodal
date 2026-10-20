package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment57HierarchyLeaf
import utest.*

final class HierarchyBridgeRepeatedBindings(resetlessSecond: Boolean = false) extends Module:
  val firstDomain = ClockDomain.external(
    "first",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val secondDomain = ClockDomain.external(
    "second",
    ClockEdge.Falling,
    if resetlessSecond then ResetPolicy.None else ResetPolicy.Sync,
    ResetPolarity.ActiveLow,
    20.MHz
  )
  val first = instance(new Increment57HierarchyLeaf(initialized = false)).domain(firstDomain)
  val second = instance(new Increment57HierarchyLeaf(initialized = false)).domain(secondDomain)
  first(_.inputPort.get) := true.B
  second(_.inputPort.get) := true.B

object Increment57HierarchyBridgeTests extends TestSuite:
  private def capture(): ConstructionSnapshot = Increment57HierarchySourceTests.capture()
  private def rejected(snapshot: ConstructionSnapshot): Unit =
    val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
    assert(failure.isInstanceOf[BridgeException], failure.getMessage.contains("NODAL-"))
  private def mapInstance(
      snapshot: ConstructionSnapshot,
      change: KernelInstanceSnapshot => KernelInstanceSnapshot
  ): ConstructionSnapshot =
    snapshot.copy(modules =
      snapshot.modules.map(module =>
        if module.path == snapshot.root then module.copy(instances = module.instances.map(change))
        else module
      )
    )
  private def mapProxy(
      snapshot: ConstructionSnapshot,
      change: KernelDeclarationSnapshot => KernelDeclarationSnapshot
  ): ConstructionSnapshot =
    snapshot.copy(modules =
      snapshot.modules.map(module =>
        module.copy(
          declarations = module.declarations.map(declaration =>
            if declaration.instancePort.nonEmpty then change(declaration) else declaration
          )
        )
      )
    )

  val tests = Tests:
    test("actual operand swaps cannot borrow matching metadata"):
      val snapshot = capture()
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(binding => binding.copy(clock = binding.reset.get))
          )
      ))
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(binding => binding.copy(reset = Some(binding.clock)))
          )
      ))
    test("same-type actual clocks and resets cannot borrow another binding"):
      val snapshot = ConstructionKernel.inspect(new HierarchyBridgeRepeatedBindings)
      val bindings = snapshot.modules.find(_.path == snapshot.root).get.instances
        .flatMap(_.actualBindings)
      assert(bindings.size == 2, bindings.map(_.clock).distinct.size == 2)
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(binding => binding.copy(clock = bindings.last.clock))
          )
      ))
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(binding => binding.copy(reset = bindings.last.reset))
          )
      ))
    test("binding coverage identity origin and dictionary are independently checked"):
      val snapshot = capture()
      rejected(mapInstance(snapshot, _.copy(actualBindings = Vector.empty)))
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(
            actualBindings = instance.actualBindings ++ instance.actualBindings
          )
      ))
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(_.copy(requirement = snapshot.root + ".foreign"))
          )
      ))
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(_.copy(actual = snapshot.root + ".foreign"))
          )
      ))
      rejected(mapInstance(
        snapshot,
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(_.copy(origin = "metadata"))
          )
      ))
      rejected(mapInstance(snapshot, _.copy(bindings = Vector.empty)))
    test("reset demand cannot be erased from actual binding"):
      rejected(mapInstance(
        capture(),
        instance =>
          instance.copy(actualBindings =
            instance.actualBindings.map(_.copy(reset = None))
          )
      ))
    test("formal projection cannot masquerade as ordinary expression or foreign value"):
      val snapshot = capture()
      val occupied = snapshot.digitalExpressions.head.path
      rejected(snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            domains = module.domains.map(domain =>
              if domain.kind == "required" then domain.copy(clockProjection = Some(occupied))
              else domain
            )
          )
        )
      ))
      rejected(snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            domains = module.domains.map(domain =>
              if domain.kind == "required" then domain.copy(edge = Some("rising")) else domain
            )
          )
        )
      ))
    test("unused requirements still cannot acquire invented physical timing"):
      val snapshot = capture()
      rejected(snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(
            domains = module.domains.map(domain =>
              if domain.kind == "required" then
                domain.copy(
                  clockProjection = None,
                  resetProjection = None,
                  edge = Some("rising"),
                  resetPolicy = Some("sync")
                )
              else domain
            )
          )
        )
      ))
    test("proxy exact direction type and immediate instance are enforced"):
      val snapshot = capture()
      rejected(mapProxy(snapshot, declaration => declaration.copy(dataType = Some("UInt(8)"))))
      rejected(mapProxy(
        snapshot,
        declaration =>
          declaration.copy(
            instancePort = declaration.instancePort.map(_.copy(direction = "output"))
          )
      ))
      rejected(mapProxy(
        snapshot,
        declaration =>
          declaration.copy(
            instancePort =
              declaration.instancePort.map(_.copy(instance = snapshot.root + ".foreign"))
          )
      ))
      rejected(mapProxy(snapshot, _.copy(instancePort = None)))
    test("child outputs are readonly and raw child captures stay foreign"):
      val snapshot = capture()
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val output = root.declarations.find(_.kind == "instance-output").get
      val rootWrite = snapshot.digitalAssignments.find(_.owner == snapshot.root).get
      rejected(snapshot.copy(digitalAssignments =
        snapshot.digitalAssignments.map(assignment =>
          if assignment.path == rootWrite.path then assignment.copy(target = output.path)
          else assignment
        )
      ))
      rejected(snapshot.copy(digitalAssignments =
        snapshot.digitalAssignments.map(assignment =>
          if assignment.path == rootWrite.path then
            assignment.copy(value = output.instancePort.get.port)
          else assignment
        )
      ))
    test("reused definitions keep local requirements across distinct physical bindings"):
      Vector(false, true).foreach: resetless =>
        val snapshot = ConstructionKernel.inspect(new HierarchyBridgeRepeatedBindings(resetless))
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        val token = "\"nodal.module\""
        assert(text.sliding(token.length).count(_ == token) == 2)
        assert(
          text.contains("origin = \"default\""),
          text.contains("nodal.instance_domain_binding")
        )
        assert(!text.contains("nodal.sequential_update"))
