package nodal.internal.testkit

import nodal.*
import utest.*

object ConstructionParityProbeTests extends TestSuite:
  private def minimal: ConstructionSnapshot = ConstructionSnapshot(
    root = "root",
    modules = Vector.empty,
    interfaceAbi = Vector.empty,
    resolvedNets = Vector.empty,
    topology = Vector.empty
  )

  val tests: Tests = Tests:
    test("all immutable construction product fields are present"):
      val snapshot = minimal
      val text = ConstructionRecordJson.document(snapshot)
      snapshot.productElementNames.foreach: name =>
        assert(text.contains(ConstructionRecordJson.quote(name) + ":"))
      val changed = snapshot.copy(rootParameterBindings = Vector("gain" -> "3.0"))
      assert(text != ConstructionRecordJson.document(changed))
      val topology =
        snapshot.copy(topology = Vector(KernelTopologyEdge("root", "node-connect", "a", "b")))
      val foreign = topology.copy(topology = topology.topology.map(_.copy(owner = "foreign")))
      assert(ConstructionRecordJson.document(topology) != ConstructionRecordJson.document(foreign))

    test("late operator fields and nested source identities cannot be dropped"):
      val operator = KernelNoiseOperatorSnapshot(
        "root.noise",
        "white",
        "root",
        "thermal",
        Vector("density"),
        "current",
        Some(SourceSpan("a.scala", 1, 2, 1, 3))
      )
      val first = minimal.copy(noiseOperators = Vector(operator))
      val second = first.copy(noiseOperators =
        Vector(operator.copy(
          source = Some(SourceSpan("b.scala", 1, 2, 1, 3))
        ))
      )
      assert(ConstructionRecordJson.document(first) != ConstructionRecordJson.document(minimal))
      assert(ConstructionRecordJson.document(first) != ConstructionRecordJson.document(second))

    test("ordered sequences differ while unordered maps and sets agree"):
      assert(ConstructionRecordJson.render(Vector("a", "b")) !=
        ConstructionRecordJson.render(Vector("b", "a")))
      assert(ConstructionRecordJson.render(Map("a" -> 1, "b" -> 2)) ==
        ConstructionRecordJson.render(Map("b" -> 2, "a" -> 1)))
      assert(ConstructionRecordJson.render(Set("a", "b")) ==
        ConstructionRecordJson.render(Set("b", "a")))
      assert(ConstructionRecordJson.render(None) != ConstructionRecordJson.render(Some(None)))
      assert(ConstructionRecordJson.render(0.0) != ConstructionRecordJson.render(-0.0))

    test("unhandled values fail instead of silently rendering object identity"):
      val failure = scala.util.Try(ConstructionRecordJson.render(new Object))
      assert(failure.isFailure)
      assert(failure.failed.get.getMessage.contains("unsupported immutable record value"))

    test("fixed workload table has unique identities and preserves required scale sizes"):
      val entries = ConstructionParityProbe.cases.map(_.info)
      assert(entries.map(_.id).distinct.size == entries.size)
      val sizes = entries.filter(_.size > 0).groupBy(_.family).view.mapValues(_.map(_.size)).toMap
      assert(sizes == Map(
        "deep" -> Vector(4, 24),
        "wide" -> Vector(8, 128),
        "repeated" -> Vector(8, 256),
        "shared-dag" -> Vector(16, 256),
        "symbolic" -> Vector(8, 128)
      ))
