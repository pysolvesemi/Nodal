package nodal

import scala.annotation.targetName

/** Explicit control-flow group for ordered analog procedural code.
  *
  * The group body may contain `analogWhen`, `analogElseWhen`, and one optional
  * `analogOtherwise`. Conditions remain first-class runtime expressions unless an
  * `analogStaticWhen` form is selected explicitly.
  */
def analogConditional(body: => Unit): Unit =
  AnalogProceduralConstruction.conditional(body)

def analogWhen(condition: Expr[Bool])(body: => Unit): Unit =
  AnalogProceduralConstruction.conditionalBranch(condition, first = true)(body)

def analogElseWhen(condition: Expr[Bool])(body: => Unit): Unit =
  AnalogProceduralConstruction.conditionalBranch(condition, first = false)(body)

def analogStaticWhen(condition: Boolean)(body: => Unit): Unit =
  AnalogProceduralConstruction.staticConditionalBranch(condition, first = true)(body)

def analogStaticElseWhen(condition: Boolean)(body: => Unit): Unit =
  AnalogProceduralConstruction.staticConditionalBranch(condition, first = false)(body)

def analogOtherwise(body: => Unit): Unit =
  AnalogProceduralConstruction.conditionalOtherwise(body)

/** Exact, non-fall-through case selection over a runtime integer expression. */
def analogCase(selector: Expr[Integer])(body: => Unit): Unit =
  AnalogProceduralConstruction.integerCase(selector)(body)

/** Exact, non-fall-through case selection over a runtime Boolean expression. */
@targetName("analogBooleanCase")
def analogCase(selector: Expr[Bool])(body: => Unit): Unit =
  AnalogProceduralConstruction.booleanCase(selector)(body)

/** Exact, non-fall-through case selection over a compile-time integer. */
def analogStaticCase(selector: Int)(body: => Unit): Unit =
  AnalogProceduralConstruction.staticIntegerCase(selector)(body)

/** Exact, non-fall-through case selection over a compile-time Boolean. */
def analogStaticCase(selector: Boolean)(body: => Unit): Unit =
  AnalogProceduralConstruction.staticBooleanCase(selector)(body)

def analogCaseArm(first: Int, rest: Int*)(body: => Unit): Unit =
  AnalogProceduralConstruction.integerCaseArm((first +: rest).toVector)(body)

def analogCaseArm(first: Boolean, rest: Boolean*)(body: => Unit): Unit =
  AnalogProceduralConstruction.booleanCaseArm((first +: rest).toVector)(body)

def analogCaseDefault(body: => Unit): Unit =
  AnalogProceduralConstruction.caseDefault(body)

/** Exact compile-time repetition. The body remains a retained static loop until later legalization. */
def analogRepeat(iterations: Int)(body: => Unit): Unit =
  AnalogProceduralConstruction.staticLoop(iterations)(body)

/** Runtime loop with an explicit finite envelope.
  *
  * `minimumIterations` states the guaranteed lower bound used by definite-assignment analysis.
  * `maximumIterations` is mandatory and prevents hidden unbounded iteration or inferred latency.
  */
def analogLoop(
    iterations: Expr[Integer],
    maximumIterations: Int,
    minimumIterations: Int = 0
)(body: => Unit): Unit =
  AnalogProceduralConstruction.runtimeLoop(
    iterations,
    minimumIterations,
    maximumIterations
  )(body)

def analogBreak(): Unit = AnalogProceduralConstruction.breakStatement()

def analogContinue(): Unit = AnalogProceduralConstruction.continueStatement()
