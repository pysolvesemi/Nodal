#!/usr/bin/env python3
"""Allow source capture while a child module awaits its immediate Instance attachment."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


kernel = "core/scala/api/src/nodal/ElaborationConstructionKernel.scala"
replace_once(
    kernel,
    '''  private def provisionalModulePath(handle: Long): String =
    val record = records(handle)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = records(parentHandle)
        val instance = parent.instances.find(_.child == handle).getOrElse(
          fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
        )
        s"${provisionalModulePath(parentHandle)}.${record.className}_${instance.ordinal}"
''',
    '''  private def provisionalModulePath(handle: Long): String =
    val record = records(handle)
    record.parentAtConstruction match
      case None => record.className
      case Some(parentHandle) =>
        val parent = records(parentHandle)
        val ordinal = parent.instances
          .find(_.child == handle)
          .map(_.ordinal)
          .orElse:
            if !record.attached && moduleStack.exists(_.handle == handle) then
              Some(parent.instances.size)
            else None
          .getOrElse(
            fail("NODAL-HIERARCHY-020", "child Module has no Instance record")
          )
        s"${provisionalModulePath(parentHandle)}.${record.className}_$ordinal"
''',
)

tests = "core/scala/testkit/test/src/nodal/AnalogProceduralConstructionTests.scala"
replace_once(
    tests,
    '''final class PublicAnalogCrossOwner extends Module:
  val childModule = new PublicAnalogChild
  val child = instance(childModule)

  analogProcedure:
    childModule.local := 1.0.V

object AnalogProceduralConstructionTests extends TestSuite:
''',
    '''final class PublicAnalogCrossOwner extends Module:
  val childModule = new PublicAnalogChild
  val child = instance(childModule)

  analogProcedure:
    childModule.local := 1.0.V

final class PublicAnalogNestedChild extends Module:
  val local: Variable[Real] = variable(Real, 0.0.V)

  analogProcedure:
    local := 1.0.V

final class PublicAnalogParentWithChild extends Module:
  val childModule = new PublicAnalogNestedChild
  val child = instance(childModule)

object AnalogProceduralConstructionTests extends TestSuite:
''',
)
replace_once(
    tests,
    '''    test("public cross-component variable assignment is rejected"):
''',
    '''    test("child procedural source capture uses its provisional instance path"):
      val snapshot = ConstructionKernel.inspect(new PublicAnalogParentWithChild)
      val procedural = snapshot.analogProcedural.find(_.assignments.nonEmpty).get
      assert(
        procedural.owner ==
          "PublicAnalogParentWithChild.PublicAnalogNestedChild_0"
      )
      assert(
        procedural.assignments.head.identity ==
          "PublicAnalogParentWithChild.PublicAnalogNestedChild_0.statement_0"
      )

    test("public cross-component variable assignment is rejected"):
''',
)
