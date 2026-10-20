package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment57ResetSourceTests extends TestSuite:
  private def capture(mode: String = "ordinary") = ConstructionKernel.inspect(
    new Increment57ResetSource(ClockEdge.Rising, ResetPolarity.ActiveHigh, mode)
  )
  val tests = Tests:
    test("declared boundary remains distinct from effective release"):
      val s = capture()
      val d = s.modules.head.domains.find(_.name == "root").get
      assert(d.attributes.toMap.apply("reset_source") !=
        d.attributes.toMap.apply("effective_reset_source"))
      assert(s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 2)
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.reset_sync"))
    test("explicit cascades retain actual producer edges"):
      val s = capture("cascade")
      assert(s.digitalExpressions.count(_.reset.exists(_.kind == "sync")) == 3)
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("stages = 3 : i64"))
    test("provider safety is explicit retained destination fact"):
      val s = capture("external")
      assert(s.digitalExpressions.exists(_.reset.exists(_.release == "external")))
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("release_domain"))
    test("mixed roots preserve independent typed boundaries"):
      val s = ConstructionKernel.inspect(new Increment57MixedResetSource)
      assert(s.digitalExpressions.exists(_.reset.exists(_.kind == "combine")))
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.reset_combine"))
    test("owned reset producers cannot be forged"):
      val s = capture()
      val e = s.digitalExpressions.find(_.reset.exists(_.kind == "sync")).get
      val bad = s.copy(digitalExpressions =
        s.digitalExpressions.map(x => if x.path == e.path then x.copy(reset = None) else x)
      )
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("missing physical clock cannot erase resolved no-state domain"):
      val s = ConstructionKernel.inspect(
        new Increment57NoStateResetSource(ResetPolicy.AsyncExternalRelease)
      )
      val root = s.modules.head
      val bad = s.copy(modules =
        s.modules.map(m =>
          if m.path == root.path then
            m.copy(domains =
              m.domains.map(d =>
                d.copy(attributes = d.attributes.filterNot(_._1 == "clock_source"))
              )
            )
          else m
        )
      )
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("foreign same-leaf parent is rejected"):
      val s = capture()
      val bad = s.copy(modules =
        s.modules.map(m =>
          m.copy(domains =
            m.domains.map(d =>
              d.copy(attributes = d.attributes.map {
                case ("clock_parent", p) => "clock_parent" -> ("foreign." + p)
                case x => x
              })
            )
          )
        )
      )
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("raw asynchronous release rejected without state"):
      val s = ConstructionKernel.inspect(new Increment57NoStateResetSource(ResetPolicy.Async))
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(s)).isFailure)
    test("contradictory roots and independent release reconvergence rejected at source"):
      for (mode, message) <-
          Vector("polarity" -> "contradictory polarities", "reconvergence" -> "reconverge")
      do
        val result =
          scala.util.Try(ConstructionKernel.inspect(new Increment57InvalidResetSource(mode)))
        assert(result.failed.toOption.exists(_.getMessage.contains(message)))
    test("malformed typed reset contracts rejected"):
      val s = capture()
      val e = s.digitalExpressions.find(_.reset.exists(_.kind == "sync")).get
      val r = e.reset.get
      for changed <- Vector(
          r.copy(kind = "invalid"),
          r.copy(polarity = "invalid"),
          r.copy(polarity = "active_low"),
          r.copy(release = "external"),
          r.copy(stages = 1)
        )
      do
        val bad = s.copy(digitalExpressions =
          s.digitalExpressions.map(x =>
            if x.path == e.path then x.copy(reset = Some(changed)) else x
          )
        )
        assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("no-state clock requires actual correctly typed owned input"):
      val s = ConstructionKernel.inspect(
        new Increment57NoStateResetSource(ResetPolicy.AsyncExternalRelease)
      )
      val domain = s.modules.head.domains.head
      for path <- Vector("foreign.clock", domain.attributes.toMap.apply("reset_source")) do
        val bad = s.copy(modules =
          s.modules.map(m =>
            m.copy(domains =
              m.domains.map(d =>
                d.copy(attributes = d.attributes.map {
                  case ("clock_source", _) => "clock_source" -> path
                  case x => x
                })
              )
            )
          )
        )
        assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("explicit aliases share actual physical clock identity"):
      val s = ConstructionKernel.inspect(new Increment57AliasedClockSource)
      assert(ScalaToMlirBridge.fromSnapshot(s).text.contains("nodal.local_domain_binding"))
    test("missing or unknown no-state reset policy cannot erase binding"):
      val s = ConstructionKernel.inspect(
        new Increment57NoStateResetSource(ResetPolicy.AsyncExternalRelease)
      )
      for policy <- Vector(None, Some("unknown")) do
        val bad = s.copy(modules =
          s.modules.map(m => m.copy(domains = m.domains.map(_.copy(resetPolicy = policy))))
        )
        assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("fixed shaped physical control elements retain actual projections"):
      for mode <- Vector("single", "combine", "matrix", "slice") do
        val captured = ConstructionKernel.inspect(new Increment57ShapedResetSource(mode))
        val text = ScalaToMlirBridge.fromSnapshot(captured).text
        assert(
          text.contains("nodal.shape_index"),
          text.contains("nodal.local_domain_binding"),
          text.contains("nodal.reset_sync")
        )
    test("shaped aliases preserve physical polarity and release lineage"):
      for (conflict, message) <- Vector(true -> "contradictory polarities", false -> "reconverge")
      do
        val result = scala.util.Try(
          ConstructionKernel.inspect(new Increment57ShapedResetAliasSource(conflict))
        )
        assert(result.failed.toOption.exists(_.getMessage.contains(message)))
    test("forged shaped control selector rejects before execution"):
      val captured = ConstructionKernel.inspect(new Increment57ShapedResetSource("single"))
      val index = captured.shapeIndices.head
      for changed <- Vector(
          index.copy(indices = Vector("2")),
          index.copy(indices = Vector("param.selector")),
          index.copy(owner = "foreign"),
          index.copy(region = Some("foreign.region"))
        )
      do
        val bad = captured.copy(shapeIndices =
          captured.shapeIndices.map(value => if value.path == index.path then changed else value)
        )
        assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(bad)).isFailure)
    test("distinct shaped Sync elements retain independent actual receiving clocks"):
      val captured = ConstructionKernel.inspect(new Increment57ShapedSyncSource(false))
      assert(ScalaToMlirBridge.fromSnapshot(captured).text.contains("nodal.reset_boundary"))
      val shared = ConstructionKernel.inspect(new Increment57ShapedSyncSource(true))
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(shared)).isFailure)
      val boundary = captured.digitalExpressions.find(_.reset.exists(_.release == "sync")).get
      val other = captured.modules.head.domains.find(d =>
        !boundary.reset.get.destination.contains(d.path)
      ).get
      val retargeted = captured.copy(digitalExpressions =
        captured.digitalExpressions.map(value =>
          if value.path == boundary.path then
            value.copy(reset = value.reset.map(_.copy(destination = Some(other.path))))
          else value
        )
      )
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(retargeted)).isFailure)

    test("all-Sync composition preserves assertion policy without erasing receiving clocks"):
      val captured = ConstructionKernel.inspect(new Increment57SyncCombineSource("same"))
      val child = captured.modules.head.domains.find(_.name == "child").get
      assert(child.resetPolicy.contains("sync"))
      assert(ScalaToMlirBridge.fromSnapshot(captured).text.contains("nodal.reset_combine"))
      val different = ConstructionKernel.inspect(new Increment57SyncCombineSource("different"))
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(different)).isFailure)
    test("mixed assertion composition requires explicit synchronization"):
      val mixed = ConstructionKernel.inspect(new Increment57SyncCombineSource("mixed"))
      assert(mixed.modules.head.domains.find(_.name == "child").get.resetPolicy.contains("async"))
      assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(mixed)).isFailure)
      val synchronized = ConstructionKernel.inspect(new Increment57SyncCombineSource("mixed_sync"))
      assert(ScalaToMlirBridge.fromSnapshot(synchronized).text.contains("nodal.reset_sync"))
