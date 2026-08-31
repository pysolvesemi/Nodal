#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    file.write_text(text.replace(old, new), encoding="utf-8")


replace_once(
    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    '''  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private final case class MutableVariable(record: VariableRecord, var initialized: Boolean)
''',
    '''  final class Failure(val diagnostic: Diagnostic)
      extends IllegalArgumentException(diagnostic.toString)

  private[nodal] def reject(diagnostic: Diagnostic): Nothing =
    scala.util.Failure[Nothing](new Failure(diagnostic)).get

  private final case class MutableVariable(record: VariableRecord, var initialized: Boolean)
''',
    "runtime rejection helper",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralRuntime.scala",
    '''    private def fail(code: String, message: String, path: Option[String] = None): Nothing =
      throw new Failure(Diagnostic(code, message, path))
''',
    '''    private def fail(code: String, message: String, path: Option[String] = None): Nothing =
      reject(Diagnostic(code, message, path))
''',
    "runtime fail implementation",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''  private def state: State =
    var value = current.get()
    if value == null then
      value = new State
      current.set(value)
    value
''',
    '''  private def state: State =
    Option(current.get()).getOrElse:
      val value = new State
      current.set(value)
      value
''',
    "thread-local state initialization",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''  private def activeModule: ModuleState =
    state.stack.lastOption.getOrElse(
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-003",
          "procedural variable construction has no active component"
        )
      )
    )
''',
    '''  private def activeModule: ModuleState =
    state.stack.lastOption.getOrElse(
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-003",
          "procedural variable construction has no active component"
        )
      )
    )
''',
    "active module rejection",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''    case other =>
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          s"unsupported procedural variable scalar kind '$other'"
        )
      )
''',
    '''    case other =>
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-019",
          s"unsupported procedural variable scalar kind '$other'"
        )
      )
''',
    "unsupported scalar-kind rejection",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''      if module.variables.get(pending.value) == null then
''',
    '''      if !module.variables.containsKey(pending.value) then
''',
    "identity-map membership",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''    if module.procedureDepth == 0 then
      throw new AnalogProceduralRuntime.Failure(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-008",
          "analog variable assignment requires an active procedural region"
        )
      )
''',
    '''    if module.procedureDepth == 0 then
      AnalogProceduralRuntime.reject(
        AnalogProceduralRuntime.Diagnostic(
          "NODAL-ANALOG-033-008",
          "analog variable assignment requires an active procedural region"
        )
      )
''',
    "assignment-region rejection",
)

replace_once(
    "core/scala/api/src/nodal/AnalogProceduralConstruction.scala",
    '''        case Some((value, owner)) =>
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
''',
    '''        case Some((value, owner)) =>
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
''',
    "assignment ownership rejection",
)

replace_once(
    "core/scala/api/src/nodal/CandidateApi.scala",
    '''  def initialValue: Expr[A] = initializer.getOrElse(
    throw new IllegalStateException("analog variable has no declaration initializer")
  )
''',
    '''  def initialValue: Expr[A] = initializer.getOrElse(
    scala.util.Failure[Expr[A]](
      new IllegalStateException("analog variable has no declaration initializer")
    ).get
  )
''',
    "public variable initializer rejection",
)

replace_once(
    "core/scala/api/src/nodal/ElaborationConstructionKernel.scala",
    '''  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      throw new IllegalStateException(
        "procedural module construction has no active transaction"
      )
    )
''',
    '''  def currentModulePath: String = active
    .map(_.currentModulePath)
    .getOrElse(
      scala.util.Failure[String](
        new IllegalStateException(
          "procedural module construction has no active transaction"
        )
      ).get
    )
''',
    "construction transaction rejection",
)
