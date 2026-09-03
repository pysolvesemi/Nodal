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

  private def declaration(
      identity: String,
      variable: String,
      initialized: Boolean = false,
      local: Boolean = false
  ): Statement.Declare =
    Statement.Declare(identity, variable, initialized = initialized, local = local)

  def main(args: Array[String]): Unit =
    val report = args.headOption
      .map(value => Path.of(value))
      .getOrElse(Path.of("/tmp/increment34-runtime-check.txt"))

    val conditional = AnalogControlFlowRuntime.analyze(
      Block(
        "conditional-root",
        Vector(
          declaration("conditional-value-declaration", "value"),
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
            declaration("missing-else-value-declaration", "value"),
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
          declaration("case-value-declaration", "value"),
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
            declaration("duplicate-case-value-declaration", "value"),
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
          declaration("guaranteed-loop-value-declaration", "value"),
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
            declaration("zero-trip-loop-value-declaration", "value"),
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
            declaration("continue-loop-value-declaration", "value"),
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
          declaration("static-uninitialized-declaration", "uninitialized"),
          declaration("static-value-declaration", "value"),
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
          "unreachable-unknown-root",
          Vector(
            Statement.IfThenElse(
              "unreachable-unknown-if",
              Vector(
                ConditionalBranch(
                  Condition.static(false),
                  Block(
                    "unreachable-unknown-body",
                    Vector(Statement.Read("unreachable-unknown-read", "missing"))
                  )
                )
              ),
              None
            )
          )
        )
      )

    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "escaped-local-root",
          Vector(
            Statement.Scope(
              "escaped-local-scope",
              Block(
                "escaped-local-body",
                Vector(
                  declaration(
                    "escaped-local-declaration",
                    "escaped-local",
                    initialized = true,
                    local = true
                  )
                )
              )
            ),
            Statement.Read("escaped-local-read", "escaped-local")
          )
        )
      )

    expect("NODAL-ANALOG-034-014"):
      AnalogControlFlowRuntime.analyze(
        Block(
          "forward-reference-root",
          Vector(
            Statement.Read("forward-reference-read", "future"),
            declaration("forward-reference-declaration", "future")
          )
        )
      )

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
      "nested_nonlocal=NODAL-ANALOG-034-014",
      "unreachable_unknown=NODAL-ANALOG-034-014",
      "escaped_local=NODAL-ANALOG-034-014",
      "forward_reference=NODAL-ANALOG-034-014"
    )
    Files.writeString(report, lines.mkString("", System.lineSeparator(), System.lineSeparator()))
