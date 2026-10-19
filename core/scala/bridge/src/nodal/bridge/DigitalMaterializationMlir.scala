package nodal.internal.bridge

import nodal.*

/** Serialization of authored facts only. Native DigitalMaterializationModel owns decisions. */
private[nodal] object DigitalMaterializationMlir:
  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-BRIDGE-055-MATERIALIZATION", message, Some(path)))
    ).get

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""

  private def dictionary(fields: Vector[(String, String)]): String =
    fields.sortBy(_._1).map((key, value) => s"$key = $value").mkString("{", ", ", "}")

  def intent(value: KernelDigitalIntent): String =
    val (kind, payload) = value match
      case KernelDigitalIntent.Named(name) => "named" -> name
      case KernelDigitalIntent.Keep(reason) => "keep" -> reason
    dictionary(Vector("kind" -> quoted(kind), "value" -> quoted(payload)))

  def validate(value: KernelDigitalExpressionSnapshot): Unit =
    if (value.operation == DigitalMaterializationContract.Operation) !=
        value.materializationIntent.nonEmpty
    then fail("one typed intent is required exactly on a digital alias", value.path)
    value.materializationIntent.foreach: authored =>
      try DigitalMaterializationContract.validate(authored)
      catch
        case error: ConstructionException => fail(error.diagnostic.message, value.path)
    if value.materializationIntent.nonEmpty &&
      (value.operands.size != 1 || value.literal.nonEmpty || value.enumIdentity.nonEmpty ||
        value.caseIds.nonEmpty)
    then
      fail(
        "digital alias requires one actual input without literal or enum operation facts",
        value.path
      )

  final class Plan(snapshot: ConstructionSnapshot):
    snapshot.digitalExpressions.foreach(validate)
    private val byPath = snapshot.digitalExpressions.map(value => value.path -> value).toMap
    private val names = snapshot.names.filter(_.category == "expression").groupBy(_.semanticPath)
    if names.values.exists(_.size != 1) then
      fail("digital source names require one existing canonical name record", snapshot.root)

    def contains(path: String): Boolean = byPath.contains(path)

    def quality: String =
      val temporaries = snapshot.emitQuality.temporaries match
        case TemporaryPolicy.InlineSafe => "inline-safe"
        case TemporaryPolicy.Readable => "readable"
        case TemporaryPolicy.Debug => "debug"
      val naming = snapshot.emitQuality.naming match
        case NamingPolicy.Semantic => "semantic"
        case NamingPolicy.SourcePreferred => "source-preferred"
        case NamingPolicy.DebugStable => "debug-stable"
      dictionary(Vector(
        "version" -> "1 : i64",
        "temporaries" -> quoted(temporaries),
        "naming" -> quoted(naming)
      ))

    private def candidate(value: KernelDigitalExpressionSnapshot): Boolean =
      (value.operation == DigitalMaterializationContract.Operation ||
        DigitalCombinationalContract.Operations.contains(value.operation) ||
        DigitalAggregateContract.Operations.contains(value.operation) ||
        DigitalScalarContract.Operations.contains(value.operation) ||
        DigitalCombinationalContract.ReachableBooleanOperations.contains(value.operation) ||
        value.operation == DigitalCollectionContract.Operation ||
        value.operation == "structural_if" ||
        (value.operation.startsWith("enum_") && value.operation != "enum_literal")) &&
        value.operation != "digital_to_real"

    def metadata(path: String): Vector[(String, String)] = byPath.get(path).toVector.flatMap:
      expression =>
        if !candidate(expression) then Vector.empty
        else
          val authored = expression.materializationIntent.toVector.map(value =>
            "digital_intent" -> intent(value)
          )
          val source = names.get(path).flatMap(_.headOption).map: record =>
            val selected = expression.materializationIntent match
              case Some(KernelDigitalIntent.Named(name)) => name
              case _ => record.name
            val provenance = expression.materializationIntent match
              case Some(KernelDigitalIntent.Named(_)) =>
                if record.provenance != "explicit" then
                  fail("named alias requires explicit canonical name provenance", path)
                "explicit"
              case _ => record.provenance
            val aliases = (record.aliases ++ Option.when(selected != record.name)(record.name))
              .distinct.sorted
            if aliases.size > 128 ||
              !Set("explicit", "scala-declaration", "source-digest", "sink-affinity", "shaped-view")
                .contains(provenance)
            then fail("source aliases or provenance exceed the closed digital profile", path)
            (Vector(selected) ++ aliases).foreach: name =>
              try DigitalMaterializationContract.validate(KernelDigitalIntent.Named(name))
              catch
                case error: ConstructionException => fail(error.diagnostic.message, path)
            "digital_source" -> dictionary(Vector(
              "name" -> quoted(selected),
              "provenance" -> quoted(provenance),
              "aliases" -> aliases.map(quoted).mkString("[", ", ", "]")
            ))
          authored ++ source
