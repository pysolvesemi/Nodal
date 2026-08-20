package nodal.bootstrap.testkit

import nodal.bootstrap.api.*
import nodal.bootstrap.integrations.ToolStatus
import utest.*

object BootstrapSmokeTests extends TestSuite:
  def tests = Tests:
    test("module graph"):
      val plan = BootstrapFixture.plan
      assert(plan.module.name.value == BootstrapFixture.moduleName)
      assert(plan.tool == ToolStatus.Unavailable("simulator integration is deferred"))

    test("deterministic textual boundary"):
      val first = BootstrapFixture.plan.payload.value
      val second = BootstrapFixture.plan.payload.value
      assert(first == second)
      assert(first == "module attributes {nodal.bootstrap.module = \"rc_smoke\"}\n")
      assert(BootstrapFixture.report.endsWith(first.trim))

    test("managed JDK"):
      assert(Runtime.version().feature() == 25)
