package nodal.internal.testkit

import nodal.*
import utest.*

object Increment58StructuralBoundaryTests extends TestSuite:
  private def fixture =
    val source = ConstructionKernel.inspect(new Increment43DependentArrayBinding)
    val root = source.instanceArrayRoots.head
    val snapshot = source.copy(modules =
      source.modules.map(module =>
        module.copy(declarations =
          module.declarations.map(declaration =>
            if declaration.path == root.port then
              declaration.copy(
                kind = "interface-array",
                attributes = declaration.attributes :+
                  ("count" -> declaration.conservativeDimensions.head),
                conservativeDimensions = Vector.empty
              )
            else declaration
          )
        )
      )
    )
    (snapshot, root)

  private def rejected(body: => Any): Unit =
    val failure = scala.util.Try(body).failed.get
    assert(failure.isInstanceOf[IllegalArgumentException])

  val tests = Tests:
    test("one immutable occurrence reuses its validated substitution context"):
      val captured = fixture
      val snapshot = captured._1
      val root = captured._2
      val queries = new StructuralParameterBinding.InstanceQueries(snapshot)
      val first = queries.query(root.owner, root.instance)
      assert(first eq queries.query(root.owner, root.instance))
      val original = queries.resolveInterfaceDimensions(root.owner, root.instance, root.port)
      val repeated = queries.resolveInterfaceDimensions(root.owner, root.instance, root.port)
      assert(original == repeated)
      (1000L until 1256L).foreach(value => { val _ = first.literal(value) })
      val afterUnrelatedFacts =
        queries.resolveInterfaceDimensions(root.owner, root.instance, root.port)
      assert(afterUnrelatedFacts == original)
      rejected:
        new StructuralParameterBinding.InstanceQueries(
          snapshot,
          Some(new StructuralParameterBinding.Query(snapshot.copy()))
        ).query(root.owner, root.instance)

    test("Interface count uses finalized dependent child substitution"):
      val captured = fixture
      val snapshot = captured._1
      val root = captured._2
      val resolved = StructuralParameterBinding.resolveInterfaceDimensions(
        snapshot,
        root.owner,
        root.instance,
        root.port
      )
      assert(resolved.dimensions.size == 1)
      assert(resolved.nodes(resolved.dimensions.head).bounds.lower == 3)
      assert(resolved.nodes(resolved.dimensions.head).bounds.upper == 6)
      StructuralParameterBinding.validateInterfaceDimensions(
        snapshot,
        root.owner,
        root.instance,
        root.port,
        root.dimensions
      )

    test("equal default is not exact symbolic count proof"):
      val captured = fixture
      val snapshot = captured._1
      val root = captured._2
      rejected:
        StructuralParameterBinding.validateInterfaceDimensions(
          snapshot,
          root.owner,
          root.instance,
          root.port,
          Vector("3")
        )

    test("rank and exact child declaration are independently checked"):
      val captured = fixture
      val snapshot = captured._1
      val root = captured._2
      rejected:
        StructuralParameterBinding.validateInterfaceDimensions(
          snapshot,
          root.owner,
          root.instance,
          root.port,
          root.dimensions ++ root.dimensions
        )
      rejected:
        StructuralParameterBinding.resolveInterfaceDimensions(
          snapshot,
          root.owner,
          root.instance,
          root.owner + ".taps"
        )
      rejected:
        StructuralParameterBinding.resolveInterfaceDimensions(
          snapshot,
          root.owner,
          root.instance + "_forged",
          root.port
        )

    test("duplicate count and generated occurrence need their own proof"):
      val captured = fixture
      val snapshot = captured._1
      val root = captured._2
      val duplicate = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(declarations =
            module.declarations.map(declaration =>
              if declaration.path == root.port then
                declaration.copy(attributes = declaration.attributes :+ ("count" -> "3"))
              else declaration
            )
          )
        )
      )
      rejected:
        StructuralParameterBinding.resolveInterfaceDimensions(
          duplicate,
          root.owner,
          root.instance,
          root.port
        )
      val generated = snapshot.copy(modules =
        snapshot.modules.map(module =>
          module.copy(instances =
            module.instances.map(instance =>
              instance.copy(generatedScope = Some(KernelGeneratedScope("forged", "index")))
            )
          )
        )
      )
      rejected:
        StructuralParameterBinding.resolveInterfaceDimensions(
          generated,
          root.owner,
          root.instance,
          root.port
        )
