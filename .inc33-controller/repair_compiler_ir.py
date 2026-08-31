#!/usr/bin/env python3
"""Apply the Increment 33 lint-safe compiler-IR repair to a staged tree."""

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


runtime = "core/scala/api/src/nodal/AnalogProceduralRuntime.scala"
replace_once(
    runtime,
    """  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private final case class MutableVariable(record: VariableRecord, var initialized: Boolean)
""",
    """  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private[nodal] def reject(diagnostic: Diagnostic): Nothing =
    scala.util.Failure[Nothing](new Failure(diagnostic)).get

  private final case class MutableVariable(record: VariableRecord, var initialized: Boolean)
""",
)
replace_once(
    runtime,
    """    private def fail(code: String, message: String, path: Option[String] = None): Nothing =
      throw new Failure(Diagnostic(code, message, path))
""",
    """    private def fail(code: String, message: String, path: Option[String] = None): Nothing =
      reject(Diagnostic(code, message, path))
""",
)

construction = "core/scala/api/src/nodal/AnalogProceduralConstruction.scala"
replace_once(
    construction,
    """  private def state: State =
    var value = current.get()
    if value == null then
      value = new State
      current.set(value)
    value
""",
    """  private def state: State = Option(current.get()).getOrElse:
    val value = new State
    current.set(value)
    value
""",
)
replace_once(
    construction,
    """  private def activeModule: ModuleState =
    state.stack.lastOption.getOrElse(
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-003",
          "procedural variable construction has no active component"
        )
      )
    )
""",
    """  private def activeModule: ModuleState =
    state.stack.lastOption.getOrElse(
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-003",
          "procedural variable construction has no active component"
        )
      )
    )
""",
)
replace_once(
    construction,
    """    case other =>
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          s"unsupported procedural variable scalar kind '$other'"
        )
      )
""",
    """    case other =>
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          s"unsupported procedural variable scalar kind '$other'"
        )
      )
""",
)
replace_once(
    construction,
    "      if module.variables.get(pending.value) == null then\n",
    "      if !module.variables.containsKey(pending.value) then\n",
)
replace_once(
    construction,
    """    if module.procedureDepth == 0 then
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-008",
          "analog variable assignment requires an active procedural region"
        )
      )
""",
    """    if module.procedureDepth == 0 then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-008",
          "analog variable assignment requires an active procedural region"
        )
      )
""",
)
replace_once(
    construction,
    """        case Some((value, owner)) =>
          throw new AnalogProceduralRuntime.Failure(
            AnalogProceduralRuntime.Diagnostic(
              "NODAL-ANALOG-033-009",
              s"procedural variable belongs to component '${owner.owner}', not '${module.owner}'",
              Some(value.identity)
            )
          )
        case None =>
          throw new AnalogProceduralRuntime.Failure(
            AnalogProceduralRuntime.Diagnostic(
              "NODAL-ANALOG-033-017",
              "procedural assignment target is not registered in the active component"
            )
          )
""",
    """        case Some((value, owner)) =>
          AnalogProceduralRuntime.reject(
            AnalogProceduralRuntime.Diagnostic(
              "NODAL-ANALOG-033-009",
              s"procedural variable belongs to component '${owner.owner}', not '${module.owner}'",
              Some(value.identity)
            )
          )
        case None =>
          AnalogProceduralRuntime.reject(
            AnalogProceduralRuntime.Diagnostic(
              "NODAL-ANALOG-033-017",
              "procedural assignment target is not registered in the active component"
            )
          )
""",
)

replace_once(
    "core/scala/api/src/nodal/CandidateApi.scala",
    """  def initialValue: Expr[A] = initializer.getOrElse(
    throw new IllegalStateException("analog variable has no declaration initializer")
  )
""",
    """  def initialValue: Expr[A] = initializer.getOrElse(
    scala.util.Failure[Expr[A]](
      new IllegalStateException("analog variable has no declaration initializer")
    ).get
  )
""",
)

replace_once(
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    """  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      throw new IllegalStateException(
        "procedural module construction has no active transaction"
      )
    )
""",
    """  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      scala.util.Failure[String](
        new IllegalStateException(
          "procedural module construction has no active transaction"
        )
      ).get
    )
""",
)

gate = Path("docs/design-gates/NodalAnalogProceduralAssignment-DG-v0.1.md")
text = gate.read_text(encoding="utf-8")
for code in range(1, 20):
    short = f"- `{code:03d}` "
    full = f"- `NODAL-ANALOG-033-{code:03d}` "
    if text.count(short) != 1:
        raise SystemExit(f"design gate: expected one diagnostic bullet {short!r}")
    text = text.replace(short, full)
text = text.replace(
    "`NODAL-ANALOG-033-003` declaration outside a procedural region;",
    "`NODAL-ANALOG-033-003` procedural construction without an active component;",
)
gate.write_text(text.rstrip() + "\n", encoding="utf-8")
