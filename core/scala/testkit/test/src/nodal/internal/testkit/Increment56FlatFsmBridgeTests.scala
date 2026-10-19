package nodal.internal.testkit

import nodal.*
import nodal.internal.bridge.*
import utest.*

object Increment56FlatFsmBridgeTests extends TestSuite:
  private def sample: ConstructionSnapshot =
    ConstructionKernel.inspect(new nodal_external.Increment56FlatFsmBridgeSource)
  private def rejected(value: ConstructionSnapshot, diagnostic: String): Unit =
    val failure =
      scala.util.Try(ScalaToMlirBridge.fromSnapshot(value)).failed.get.asInstanceOf[BridgeException]
    assert(failure.getMessage.contains(diagnostic))
  val tests = Tests:
    test("public plugin machine retains raw guards actions observations and exhaustive switch"):
      val captured = sample
      val machine = captured.flatFsms.head
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(text.contains("\"nodal.flat_fsm\""), text.contains("\"nodal.enum_switch\""))
      assert(
        text.contains("case_ids = ["),
        text.contains("action_capture_counts = array<i32: 1, 2, 1, 1>")
      )
      assert(text.contains(machine.path + ".next"), text.contains(machine.path + ".taken"))
      assert(!text.contains("\"nodal.flat_fsm_next\""), !text.contains("\"nodal.flat_fsm_taken\""))
      assert(captured.digitalAssignments.filter(_.region.isEmpty).exists(_.sourceOrdinal >
        machine.sourceOrdinal))
      assert(text == ScalaToMlirBridge.fromSnapshot(captured).text)
    test("canonical enum ABI remains stable across local policies and zero action ingress"):
      val variants =
        Vector(FsmEncoding.Compact, FsmEncoding.OneHot, FsmEncoding.Gray, FsmEncoding.Auto)
          .map(value =>
            ConstructionKernel.inspect(new nodal_external.Increment56FlatEncodingSource(value))
          ) :+
          ConstructionKernel.inspect(new nodal_external.Increment56FlatEncodingSource(
            FsmEncoding.Custom,
            true
          ))
      assert(variants.map(_.enumDefinitions.head.hash).distinct.size == 1)
      variants.foreach: captured =>
        val text = ScalaToMlirBridge.fromSnapshot(captured).text
        assert(text.contains("action_capture_counts = array<i32>"))
        assert(captured.flatFsms.head.actions.isEmpty)
      assert(variants.last.flatFsms.head.localWidth == 4)
      assert(variants.last.flatFsms.head.localCodes.map(_.code) == Vector("3", "7", "9"))
    test("nested actions share typed conditional switch and context scheduling"):
      val captured = ConstructionKernel.inspect(new nodal_external.Increment56NestedFsmSource)
      val text = ScalaToMlirBridge.fromSnapshot(captured).text
      assert(
        text.contains("nodal.behavioral_if"),
        text.contains("nodal.behavioral_predicate"),
        text.contains("nodal.enum_switch")
      )
      assert(captured.behavioralIfs.forall(_.root == captured.flatFsms.head.path))
      assert(captured.enumSwitches.forall(_.root == captured.flatFsms.head.path))
      assert(captured.flatFsms.head.actions.exists(_.arguments.exists(_.role == "static_capture")))
    test("canonical child definition reuse retains one identical machine definition"):
      val text = ScalaToMlirBridge.lower(new nodal_external.Increment56RepeatedFsmSource).text
      assert(text.sliding("\"nodal.flat_fsm\"".length).count(_ == "\"nodal.flat_fsm\"") == 1)
    test("machine identity storage domain and result forgeries reject"):
      val value = sample
      val machine = value.flatFsms.head
      rejected(
        value.copy(flatFsms = Vector(machine.copy(parentRegion = Some(machine.actions.head.path)))),
        "direct physical-domain"
      )
      rejected(
        value.copy(flatFsms =
          Vector(machine.copy(storage =
            value.modules.head.declarations.find(_.name == "input").get.path
          ))
        ),
        "whole local Bits register"
      )
      rejected(
        value.copy(flatFsms = Vector(machine.copy(domain = machine.owner + ".absent"))),
        "physical-domain"
      )
      rejected(
        value.copy(flatFsms = Vector(machine.copy(nextLocal = machine.path + ".bad"))),
        "canonical next"
      )
      rejected(value.copy(flatFsms = Vector.empty), "no actual machine owner")
    test("machine inventory encoding and source order forgeries reject"):
      val value = sample
      val machine = value.flatFsms.head
      rejected(
        value.copy(flatFsms = Vector(machine.copy(states = machine.states.dropRight(1)))),
        "case and state inventories"
      )
      rejected(
        value.copy(flatFsms = Vector(machine.copy(localCodes = machine.localCodes.reverse))),
        "case and state inventories"
      )
      rejected(
        value.copy(flatFsms =
          Vector(machine.copy(localCodes =
            machine.localCodes.updated(0, machine.localCodes.head.copy(code = "000"))
          ))
        ),
        "canonical unsigned decimal"
      )
      rejected(
        value.copy(flatFsms = Vector(machine.copy(resolvedEncoding = "auto"))),
        "encoding policy"
      )
      rejected(
        value.copy(flatFsms = Vector(machine.copy(actions = machine.actions.reverse))),
        "source order"
      )
      rejected(
        value.copy(flatFsms =
          Vector(machine.copy(states =
            machine.states.updated(0, machine.states.head.copy(terminal = true))
          ))
        ),
        "terminal/dead-end"
      )
    test("action context role capture root and lexical escape forgeries reject"):
      val value = sample
      val machine = value.flatFsms.head
      val action = machine.actions.head
      def changed(next: KernelFlatFsmAction): ConstructionSnapshot =
        value.copy(flatFsms = Vector(machine.copy(actions = machine.actions.updated(0, next))))
      rejected(
        changed(action.copy(arguments =
          action.arguments.updated(0, action.arguments.head.copy(role = "capture"))
        )),
        "canonical identity, role"
      )
      rejected(
        changed(action.copy(arguments =
          action.arguments.updated(0, action.arguments.head.copy(dataType = "Bits(3)"))
        )),
        "canonical identity, role"
      )
      rejected(changed(action.copy(captures = Vector.empty)), "context prefix")
      rejected(
        changed(action.copy(
          captures = Vector(machine.nextLocal),
          arguments = action.arguments.updated(
            3,
            action.arguments(3).copy(dataType = s"Bits(${machine.localWidth})")
          )
        )),
        "forward dependency"
      )

      rejected(changed(action.copy(transitionOrdinal = Some(4))), "phase and owner")
      val assignment = value.digitalAssignments.find(_.region.contains(action.path)).get
      rejected(
        value.copy(digitalAssignments =
          value.digitalAssignments.map(current =>
            if current == assignment then current.copy(value = machine.storage) else current
          )
        ),
        "exact lexical scope"
      )
    test("guard ABI projection forgery and actual forward feedback reject"):
      val value = sample
      val machine = value.flatFsms.head
      val edge = machine.transitions.head
      rejected(
        value.copy(flatFsms =
          Vector(machine.copy(transitions =
            machine.transitions.updated(0, edge.copy(guard = machine.storage))
          ))
        ),
        "actual Bool guard"
      )
      rejected(
        value.copy(flatFsms =
          Vector(machine.copy(transitions =
            machine.transitions.updated(0, edge.copy(guard = machine.transitionTaken))
          ))
        ),
        "forward dependency"
      )
      rejected(
        value.copy(digitalExpressions =
          value.digitalExpressions.map(expression =>
            if expression.path == machine.nextLocal then expression.copy(dataType = "Bits(7)")
            else expression
          )
        ),
        "projection requires"
      )
      val switch = value.enumSwitches.head
      val selector = value.modules.head.declarations.find(_.path == switch.selector).get
      rejected(
        value.copy(modules =
          value.modules.map(module =>
            module.copy(declarations =
              module.declarations.map(declaration =>
                if declaration == selector then declaration.copy(dataType = Some("Bool"))
                else declaration
              )
            )
          )
        ),
        "exact canonical enum ABI"
      )
    test("switch exhaustive invalid arm and root forgeries reject"):
      val value = sample
      val switch = value.enumSwitches.head
      rejected(
        value.copy(enumSwitches = Vector(switch.copy(caseIds = switch.caseIds.dropRight(1)))),
        "exhaustive unique cases"
      )
      rejected(
        value.copy(enumSwitches =
          Vector(switch.copy(caseIds = Vector.fill(3)(switch.caseIds.head)))
        ),
        "exhaustive unique cases"
      )
      rejected(
        value.copy(enumSwitches = Vector(switch.copy(root = switch.path + ".bad"))),
        "outer update root"
      )
      rejected(
        value.copy(enumSwitches =
          Vector(switch.copy(invalidArm =
            switch.invalidArm.copy(yieldValue = Some(switch.selector))
          ))
        ),
        "without yield"
      )
      rejected(
        value.copy(enumSwitches = Vector(switch.copy(invalidArm = switch.arms.head))),
        "duplicate or excessive"
      )

    test("sequential Integer and Real leaves preserve runtime versus static capture roles"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56ScalarSequentialSource)
      val text = ScalaToMlirBridge.fromSnapshot(value).text
      assert(text.contains("(i64, i64) -> ()"), text.contains("(f64, f64) -> ()"))
      val machine = value.flatFsms.head
      val action = machine.actions.head
      val index = action.arguments.indexWhere(_.dataType == "Integer")
      assert(index >= 3, action.arguments(index).role == "capture")
      rejected(
        value.copy(flatFsms =
          Vector(machine.copy(actions =
            Vector(action.copy(arguments =
              action.arguments.updated(index, action.arguments(index).copy(role = "static_capture"))
            ))
          ))
        ),
        "canonical identity, role"
      )

    test("bounded many-state graph retains balanced observations and deterministic transport"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56ManyStateSource)
      val machine = value.flatFsms.head
      val first = ScalaToMlirBridge.fromSnapshot(value)
      val second = ScalaToMlirBridge.lower(new nodal_external.Increment56ManyStateSource)
      assert(machine.states.size == 32, machine.transitions.size == 31, machine.localWidth == 5)
      assert(value.digitalExpressions.size < 512)
      assert(first.text == second.text)
      val provenance = ReproducibilityContract.describe(value)
      assert(provenance.construction.contains("flat_fsms"))
      assert(provenance.construction.contains("transition_taken"))
      assert(provenance.construction.contains(machine.path))

    test("definition reuse preserves distinct guard edges and quoted authored metadata"):
      val value = ConstructionKernel.inspect(new nodal_external.Increment56RepeatedFsmSource)
      val machine = value.flatFsms.last
      val guard = value.digitalExpressions.find(expression =>
        expression.owner == machine.owner &&
          expression.operation == "bool_or" && expression.region.isEmpty
      ).get.copy(
        path = machine.owner + ".distinct_guard",
        operation = "bool_not",
        operands = Vector(machine.transitions.head.guard)
      )
      val altered = value.copy(
        digitalExpressions = value.digitalExpressions :+ guard,
        flatFsms = value.flatFsms.updated(
          value.flatFsms.size - 1,
          machine.copy(transitions =
            machine.transitions.updated(0, machine.transitions.head.copy(guard = guard.path))
          )
        )
      )
      val distinct = ScalaToMlirBridge.fromSnapshot(altered).text
      assert(distinct.sliding("\"nodal.flat_fsm\"".length).count(_ == "\"nodal.flat_fsm\"") == 2)
      val quoted =
        ScalaToMlirBridge.lower(new nodal_external.Increment56QuotedDefinitionsSource).text
      val alias = "operator_name = \"digital_alias\""
      assert(quoted.sliding(alias.length).count(_ == alias) == 2)
      assert(
        quoted.contains("retain %digital_value_0, ({ metadata"),
        quoted.contains("retain %digital_value_1, ({ metadata")
      )
