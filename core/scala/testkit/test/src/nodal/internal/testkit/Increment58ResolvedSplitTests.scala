package nodal.internal.testkit

import java.nio.file.{Files, Path}
import java.time.Duration
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*
import utest.*

object Increment58ResolvedSplitTests extends TestSuite:
  private def capture(mode: String) =
    ConstructionKernel.inspect(new Increment58ResolvedSplitSource(mode))
  private def split(value: KernelDigitalExpressionSnapshot): Boolean =
    Set("digital_net_write_view", "digital_net_enable_view").contains(value.operation)
  val tests = Tests:
    test("both directions preserve late symbolic types and exact contextual split intent"):
      Vector("child-driver", "parent-driver").foreach: mode =>
        val snapshot = capture(mode)
        val splits = snapshot.digitalExpressions.filter(split)
        assert(splits.nonEmpty, splits.forall(_.hierarchyDriver))
        assert(splits.filter(_.operation == "digital_net_write_view").forall(_.dataType ==
          "UInt(width)"))
        assert(splits.filter(_.operation == "digital_net_enable_view").forall(_.dataType == "Bool"))
        assert(splits.forall(value => (value.owner == snapshot.root) == (mode == "child-driver")))
    test("actual bridge emits split obligations without cloning physical nets or driver values"):
      Vector("child-driver", "parent-driver").foreach: mode =>
        val snapshot = capture(mode)
        val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
        assert(text.contains("hierarchy_driver = unit"))
        assert(text.sliding("\"nodal.resolved_net\"".length).count(_ == "\"nodal.resolved_net\"") ==
          1)
        assert(text.sliding("\"nodal.net_drive\"".length).count(_ == "\"nodal.net_drive\"") == 1)
        assert(snapshot.resolvedNets.count(_.boundary.nonEmpty) == 1)
    test("typed nested Interface level-driver and strict local split retain their existing owners"):
      val nested = ConstructionKernel.inspect(new Increment58TypedResolvedMemberSource("split"))
      assert(nested.digitalExpressions.filter(split).forall(_.hierarchyDriver))
      assert(ScalaToMlirBridge.fromSnapshot(nested).text.contains("\"nodal.member_access\""))
      val level = ConstructionKernel.inspect(new Increment58ResolvedSplitLevelSource)
      assert(level.digitalExpressions.filter(split).forall(_.hierarchyDriver))
      assert(ScalaToMlirBridge.fromSnapshot(level).text.contains("\"nodal.net_drive_level\""))
      val local = ConstructionKernel.inspect(new Increment58LocalResolvedMemberSource)
      assert(local.digitalExpressions.filter(split).nonEmpty)
      assert(local.digitalExpressions.filter(split).forall(!_.hierarchyDriver))
      assert(!ScalaToMlirBridge.fromSnapshot(local).text.contains("hierarchy_driver"))
    test("global zero release-only and multiple drivers cannot fabricate split write and enable"):
      Vector("zero", "release", "two-drivers").foreach: mode =>
        val failure = scala.util.Try(capture(mode)).failed.get
        assert(failure.isInstanceOf[ConstructionException])
        assert(failure.asInstanceOf[ConstructionException].diagnostic.code ==
          "NODAL-INTERFACE-054-012")
        assert(failure.getMessage.contains(
          if mode == "two-drivers" then "multiple conditional drivers"
          else "no write value is available"
        ))
    test("bridge independently derives local and boundary obligations and rejects forged markers"):
      val snapshot = capture("child-driver")
      val selected = snapshot.digitalExpressions.find(split).get
      val read = snapshot.digitalExpressions.find(_.operation == "digital_net_read").get
      Vector(selected.copy(hierarchyDriver = false), read.copy(hierarchyDriver = true)).foreach:
        changed =>
          val forged = snapshot.copy(digitalExpressions =
            snapshot.digitalExpressions.map(value =>
              if value.path == changed.path then changed else value
            )
          )
          val failure = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged)).failed.get
          assert(failure.isInstanceOf[BridgeException])
          assert(failure.asInstanceOf[BridgeException].diagnostic.code ==
            "NODAL-BRIDGE-054-INTERFACE")
          assert(failure.getMessage.contains("hierarchy-driver intent"))
    test(
      "canonical artifacts distinguish contextual proof intent and preserve absent local fields"
    ):
      val snapshot = capture("child-driver")
      val original = ReproducibilityContract.canonicalSnapshot(snapshot)
      val changed = snapshot.copy(digitalExpressions =
        snapshot.digitalExpressions.map(value =>
          value.copy(hierarchyDriver = false)
        )
      )
      assert(original.replaceAll("\\s", "").contains("\"hierarchy_driver\":true"))
      assert(original != ReproducibilityContract.canonicalSnapshot(changed))
      val local = ConstructionKernel.inspect(new Increment58LocalResolvedMemberSource)
      assert(!ReproducibilityContract.canonicalSnapshot(local).contains("\"hierarchy_driver\""))

object Increment58ResolvedSplitNativeTests extends TestSuite:
  val tests = Tests:
    test("actual source split obligations close both directions and retain causal feedback"):
      Vector("child-driver", "parent-driver", "interface", "level", "local", "feedback").foreach:
        mode =>
          val snapshot = ConstructionKernel.inspect:
            mode match
              case "interface" => new Increment58TypedResolvedMemberSource("split")
              case "level" => new Increment58ResolvedSplitLevelSource
              case "local" => new Increment58LocalResolvedMemberSource
              case _ => new Increment58ResolvedSplitSource(mode)
          val document = ScalaToMlirBridge.fromSnapshot(snapshot)
          val directory =
            Path.of(sys.env("NODAL_ROOT"), ".work", "f58-resolved-split", "native", mode)
          Files.createDirectories(directory)
          val _ = Files.writeString(directory.resolve("input.mlir"), document.text)
          val result = NativeCompilerClient.run(
            document,
            NativeCompilerRequest(
              Path.of(sys.env("NODAL_NODALC")).toAbsolutePath,
              Vector("--nodal-gate-default"),
              directory,
              timeout = Duration.ofSeconds(90)
            )
          )
          val _ = Files.writeString(directory.resolve("output.mlir"), result.standardOutput)
          val _ = Files.writeString(directory.resolve("diagnostics.txt"), result.standardError)
          if mode == "feedback" then
            assert(result.isInstanceOf[NativeCompilerFailure])
            assert(result.standardError.contains("NODAL-DIGITAL-055-010"))
            assert(result.standardError.contains(
              "hierarchy connection contains a causal combinational dependency cycle"
            ))
          else
            result match
              case _: NativeCompilerSuccess => ()
              case failure: NativeCompilerFailure =>
                throw new java.lang.AssertionError(
                  s"${failure.diagnostic}\n${failure.standardError}"
                )
