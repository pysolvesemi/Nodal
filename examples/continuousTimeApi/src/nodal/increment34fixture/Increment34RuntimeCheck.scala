package nodal.increment34fixture

import java.nio.file.Files
import java.nio.file.Path

import nodal.AnalogControlFlowRuntime
import nodal.AnalogControlFlowRuntime.Block
import nodal.AnalogControlFlowRuntime.CaseArm
import nodal.AnalogControlFlowRuntime.CaseLabel
import nodal.AnalogControlFlowRuntime.Condition
import nodal.AnalogControlFlowRuntime.ConditionalBranch
import nodal.AnalogControlFlowRuntime.LoopStage
import nodal.AnalogControlFlowRuntime.Selector
import nodal.AnalogControlFlowRuntime.Statement

object Increment34RuntimeCheck:
  private def expect(code: String)(body: => Unit): Unit =
    val failure = scala.util
      .Try(body)
      .failed
      .get
      .asInstanceOf[AnalogControlFlowRuntime.Failure]
    assert(failure.diagnostic.code == code)

  private def assignment(identity: String, target: String): Statement.Assign =
    Statement.Assign(identity, target)

  def main(args: Array[String]): Unit =
    val report = args.headOption
      .map(value => Path.of(value))
      .getOrElse(Path.of("/tmp/increment34-runtime-check.txt"))

    val conditional = AnalogControlFlowRuntime.analyze(
      Block(
        "conditional-root",
        Vector(
          Statement.IfThenElse(
            "conditional",
            Vector(
              ConditionalBranch(
                Condition.runtime("select"),
                Block("conditional-then", Vector(assignment("assign-then", "value")))
              )
            ),
            Some(
              Block(
                "conditional-else",
                Vector(assignment("assign-else", "value"))
              )
            )
          ),
          Statement.Read("read-after-conditional", "value")
        )
      )
    )
    assert(conditional.definitelyInitialized.contains("value"))

    expect("NODAL-ANALOG-034-004"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "missing-else-root",
          Vector(
            Statement.IfThenElse(
              "missing-else",
              Vector(
                ConditionalBranch(
                  Condition.runtime("select"),
                  Block(
                    "missing-else-then",
                    Vector(assignment("missing-else-assign", "value"))
                  )
                )
              ),
              None
            ),
            Statement.Read("missing-else-read", "value")
          )
        )
      )

    val selected = AnalogControlFlowRuntime.analyze(
      Block(
        "case-root",
        Vector(
          Statement.CaseStatement(
            "case",
            Selector.runtimeInteger("mode"),
            Vector(
              CaseArm(
                Vector(CaseLabel.Integer(0)),
                Block("case-zero", Vector(assignment("case-zero-assign", "value")))
              ),
              CaseArm(
                Vector(CaseLabel.Integer(1), CaseLabel.Integer(2)),
                Block("case-one-two", Vector(assignment("case-one-two-assign", "value")))
              )
            ),
            Some(
              Block(
                "case-default",
                Vector(assignment("case-default-assign", "value"))
              )
            )
          ),
          Statement.Read("read-after-case", "value")
        )
      )
    )
    assert(selected.definitelyInitialized.contains("value"))

    expect("NODAL-ANALOG-034-006"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "duplicate-case-root",
          Vector(
            Statement.CaseStatement(
              "duplicate-case",
              Selector.runtimeInteger("mode"),
              Vector(
                CaseArm(
                  Vector(CaseLabel.Integer(0)),
                  Block(
                    "duplicate-case-zero",
                    Vector(assignment("duplicate-case-assign-zero", "value"))
                  )
                ),
                CaseArm(
                  Vector(CaseLabel.Integer(0)),
                  Block(
                    "duplicate-case-again",
                    Vector(assignment("duplicate-case-assign-again", "value"))
                  )
                )
              ),
              None
            )
          )
        )
      )

    val guaranteedLoop = AnalogControlFlowRuntime.analyze(
      Block(
        "guaranteed-loop-root",
        Vector(
          Statement.Loop(
            "guaranteed-loop",
            LoopStage.RuntimeBounded,
            minimumIterations = 1,
            maximumIterations = 4,
            boundReads = Set.empty,
            body = Block(
              "guaranteed-loop-body",
              Vector(assignment("guaranteed-loop-assign", "value"))
            )
          ),
          Statement.Read("read-after-guaranteed-loop", "value")
        )
      )
    )
    assert(guaranteedLoop.definitelyInitialized.contains("value"))

    expect("NODAL-ANALOG-034-004"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "zero-trip-loop-root",
          Vector(
            Statement.Loop(
              "zero-trip-loop",
              LoopStage.RuntimeBounded,
              minimumIterations = 0,
              maximumIterations = 4,
              boundReads = Set.empty,
              body = Block(
                "zero-trip-loop-body",
                Vector(assignment("zero-trip-loop-assign", "value"))
              )
            ),
            Statement.Read("read-after-zero-trip-loop", "value")
          )
        )
      )

    expect("NODAL-ANALOG-034-004"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "continue-loop-root",
          Vector(
            Statement.Loop(
              "continue-loop",
              LoopStage.RuntimeBounded,
              minimumIterations = 1,
              maximumIterations = 4,
              boundReads = Set.empty,
              body = Block(
                "continue-loop-body",
                Vector(
                  Statement.IfThenElse(
                    "continue-conditional",
                    Vector(
                      ConditionalBranch(
                        Condition.runtime("skip"),
                        Block(
                          "continue-branch",
                          Vector(Statement.Continue("continue"))
                        )
                      )
                    ),
                    Some(
                      Block(
                        "continue-fallthrough",
                        Vector(assignment("continue-assignment", "value"))
                      )
                    )
                  )
                )
              )
            ),
            Statement.Read("read-after-continue-loop", "value")
          )
        )
      )

    expect("NODAL-ANALOG-034-010"):
      AnalogControlFlowRuntime.analyze(
        Block("break-root", Vector(Statement.Break("orphan-break")))
      )

    expect("NODAL-ANALOG-034-011"):
      AnalogControlFlowRuntime.analyze(
        Block("continue-root", Vector(Statement.Continue("orphan-continue")))
      )

    val staticSelection = AnalogControlFlowRuntime.analyze(
      Block(
        "static-root",
        Vector(
          Statement.IfThenElse(
            "static-if",
            Vector(
              ConditionalBranch(
                Condition.static(false),
                Block(
                  "static-dead",
                  Vector(Statement.Read("static-dead-read", "uninitialized"))
                )
              ),
              ConditionalBranch(
                Condition.static(true),
                Block(
                  "static-selected",
                  Vector(assignment("static-selected-assign", "value"))
                )
              )
            ),
            None
          ),
          Statement.Read("read-after-static", "value")
        )
      )
    )
    assert(staticSelection.definitelyInitialized.contains("value"))

    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "nested-nonlocal-root",
          Vector(
            Statement.Scope(
              "nested-nonlocal-scope",
              Block(
                "nested-nonlocal-body",
                Vector(
                  Statement.Declare(
                    "nested-nonlocal-declaration",
                    "nested-local",
                    initialized = true,
                    local = false
                  )
                )
              )
            )
          )
        )
      )

    val lines = Vector(
      s"conditional_definite=${conditional.definitelyInitialized.contains("value")}",
      s"case_definite=${selected.definitelyInitialized.contains("value")}",
      s"loop_definite=${guaranteedLoop.definitelyInitialized.contains("value")}",
      s"static_definite=${staticSelection.definitelyInitialized.contains("value")}",
      "missing_else=NODAL-ANALOG-034-004",
      "zero_trip=NODAL-ANALOG-034-004",
      "duplicate_case=NODAL-ANALOG-034-006",
      "break_scope=NODAL-ANALOG-034-010",
      "continue_scope=NODAL-ANALOG-034-011",
      "nested_nonlocal=NODAL-ANALOG-034-014"
    )
    Files.writeString(report, lines.mkString("", System.lineSeparator(), System.lineSeparator()))
