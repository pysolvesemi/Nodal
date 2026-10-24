package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.Increment58HierarchyTargetSource
import utest.*

object Increment58HierarchyTargetSourceTests extends TestSuite:
  private def capture(profile: String): ConstructionSnapshot =
    ConstructionKernel.inspect(new Increment58HierarchyTargetSource(profile))

  private def definitions(snapshot: ConstructionSnapshot): Int =
    "\"nodal.module\"".r.findAllIn(ScalaToMlirBridge.fromSnapshot(snapshot).text).size

  val tests = Tests:
    test("distinct physical clocks preserve one reusable definition"):
      val snapshot = capture("shared")
      val root = snapshot.modules.find(_.path == snapshot.root).get
      val bindings = root.instances.flatMap(_.actualBindings)
      assert(snapshot.modules.size == 3, definitions(snapshot) == 2)
      assert(bindings.size == 2, bindings.map(_.clock).distinct.size == 2)
      assert(bindings.map(_.reset).distinct.size == 2)
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)

    test("formal forwarding retains a real intermediate module"):
      val snapshot = capture("nested")
      assert(snapshot.modules.size == 4, definitions(snapshot) == 3)
      assert(snapshot.modules.map(_.instances.size).sum == 3)
      val formalClocks = snapshot.modules.flatMap(_.domains).flatMap(_.clockProjection).toSet
      assert(snapshot.modules.flatMap(_.instances).flatMap(_.actualBindings)
        .exists(binding => formalClocks(binding.clock)))
      assert(snapshot.modules.flatMap(_.declarations).count(_.kind == "register") == 2)

    test("material differences do not specialize source definitions"):
      val snapshot = capture("material")
      val root = snapshot.modules.find(_.path == snapshot.root).get
      assert(definitions(snapshot) == 2)
      assert(root.domains.map(_.edge).toSet == Set(Some("rising"), Some("falling")))
      assert(root.domains.flatMap(_.attributes.toMap.get("reset_polarity")).toSet ==
        Set("active_high", "active_low"))

    test("resetless initialized distinction is structural not guessed"):
      val snapshot = capture("resetless")
      val root = snapshot.modules.find(_.path == snapshot.root).get
      assert(definitions(snapshot) == 3)
      assert(root.instances.flatMap(_.actualBindings).count(_.reset.isEmpty) == 1)
      val states = snapshot.modules.flatMap(_.declarations).filter(_.kind == "register")
      assert(
        states.size == 2,
        states.count(_.attributes.toMap.get("initial").contains("none")) == 1
      )
