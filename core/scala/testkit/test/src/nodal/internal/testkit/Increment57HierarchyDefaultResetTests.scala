package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import utest.*

/** No lexical ClockDomain scope: state must use the exact single local requirement default. */
final class HierarchyDefaultResetLeaf extends Module:
  val required = ClockDomain.required("required")
  val input = in(Bool)
  val output = out(Bool)
  val state = Reg(false.B)
  state := input
  output := state

final class HierarchyDefaultResetSource(binding: String, resetless: Boolean = false) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    if resetless then ResetPolicy.None else ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val child = instance(new HierarchyDefaultResetLeaf)
  if binding == "default" then child.domain(root)
  else if binding == "named" then child.domain(_.required, root)
  child(_.input) := true.B
  val output = out(Bool)
  output := child(_.output)

object Increment57HierarchyDefaultResetTests extends TestSuite:
  val tests = Tests:
    test("initialized default child retains local state and reset-bearing actual binding"):
      Vector("default", "named", "inherited").foreach: kind =>
        val snapshot = ConstructionKernel.inspect(new HierarchyDefaultResetSource(kind))
        val child = snapshot.modules.find(_.className == "HierarchyDefaultResetLeaf").get
        val required = child.domains.head
        val state = child.declarations.find(_.kind == "register").get
        val actual = snapshot.modules.flatMap(_.instances).flatMap(_.actualBindings).head
        assert(state.domain.contains(required.path), state.resolvedDomain == required.binding)
        assert(actual.origin == kind, actual.reset.nonEmpty)
        assert(required.resetProjection.forall(_.startsWith(child.path + ".")))
        assert(ScalaToMlirBridge.fromSnapshot(snapshot).text.contains("register_next"))
    test("initialized default child cannot discard its required actual reset"):
      Vector("default", "named", "inherited").foreach: kind =>
        assert(scala.util.Try(ConstructionKernel.inspect(
          new HierarchyDefaultResetSource(kind, resetless = true)
        )).isFailure)
    test("configured native admits initialized default child through actual reset binding"):
      sys.env.get("NODAL_NODALC").foreach: executable =>
        Vector("default", "named", "inherited").foreach: kind =>
          val document = ScalaToMlirBridge.lower(new HierarchyDefaultResetSource(kind))
          val directory = Files.createDirectories(Path.of(".work", "hierarchy-default-reset", kind))
          NativeCompilerClient.run(
            document,
            NativeCompilerRequest(
              Path.of(executable).toAbsolutePath,
              arguments = Vector("--mlir-print-op-generic"),
              workingDirectory = directory.toAbsolutePath,
              timeout = Duration.ofSeconds(30)
            )
          ) match
            case _: NativeCompilerSuccess => ()
            case failure: NativeCompilerFailure =>
              scala.Predef.assert(false, s"${failure.diagnostic}\n${failure.standardError}")

object Increment57HierarchyDefaultResetReplay:
  def main(args: Array[String]): Unit =
    val directory = Files.createDirectories(Path.of(args(0)))
    Vector("default", "named", "inherited").foreach: kind =>
      val snapshot = ConstructionKernel.inspect(new HierarchyDefaultResetSource(kind))
      val requirement = snapshot.modules.flatMap(_.domains).find(_.kind == "required").get
      val document = ScalaToMlirBridge.fromSnapshot(snapshot)
      val _ = Files.writeString(directory.resolve(kind + ".mlir"), document.text)
      println(s"$kind requirement_reset_projection=${requirement.resetProjection.nonEmpty}")
