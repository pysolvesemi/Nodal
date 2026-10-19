package nodal.internal.bridge

import nodal.*

/** Exact declaration transport. Type/shape proofs stay with the shared bridge and native owners;
  * this helper never expands lanes, chooses a memory implementation or invents access timing.
  */
private[nodal] object DigitalStorageMlir:
  private def fail(message: String, path: String): Nothing =
    scala.util.Failure[Nothing](
      new BridgeException(BridgeDiagnostic("NODAL-BRIDGE-054-STORAGE", message, Some(path)))
    ).get

  private def requireValid(condition: Boolean, message: String, path: String): Unit =
    if !condition then fail(message, path)

  private def quoted(value: String): String =
    val escaped = value.flatMap:
      case '\\' => "\\\\"
      case '"' => "\\\""
      case character if character.isControl => f"\\${character.toInt & 0xff}%02X"
      case character => character.toString
    s"\"$escaped\""

  private def attributes(declaration: KernelDeclarationSnapshot): Map[String, String] =
    requireValid(
      declaration.attributes.map(_._1).distinct.size == declaration.attributes.size,
      "digital storage has duplicate contract attributes",
      declaration.path
    )
    declaration.attributes.toMap

  def initializer(declaration: KernelDeclarationSnapshot): Option[String] =
    val fields = attributes(declaration)
    declaration.kind match
      case "wire" =>
        requireValid(
          !fields.contains("initial"),
          "structural wire cannot carry a state initializer",
          declaration.path
        )
        None
      case "register" =>
        val value = fields.getOrElse(
          "initial",
          fail("register initializer contract is absent", declaration.path)
        )
        requireValid(
          value.nonEmpty && value == value.trim,
          "register initializer identity is empty or noncanonical",
          declaration.path
        )
        Option.unless(value == "none")(value)
      case _ => None

  private def validateOwner(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot
  ): Unit =
    requireValid(
      declaration.path.startsWith(module.path + ".") &&
        module.declarations.contains(declaration) && declaration.generatedScope.isEmpty &&
        declaration.name.nonEmpty && declaration.dataType.nonEmpty,
      "digital storage requires an exact type and direct module ownership",
      declaration.path
    )

  private def domain(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot,
      symbol: (String, String) => String
  ): String =
    val actual = declaration.domain.getOrElse(
      fail("stateful storage has no clock/reset domain", declaration.path)
    )
    val matching = module.domains.filter(value =>
      value.path == actual || value.binding.contains(actual)
    )
    val selected = attributes(declaration).get("declared_domain") match
      case Some(path) => matching.filter(_.path == path)
      case None => matching
    requireValid(
      selected.size == 1,
      "stateful storage domain must identify exactly one owned local domain or requirement",
      declaration.path
    )
    "@" + quoted(symbol("domain", selected.head.name))

  def renderValue(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot,
      result: String,
      nativeType: String,
      initial: Option[(String, String)],
      symbol: (String, String) => String,
      metadata: String,
      sourceLocation: String
  ): String =
    validateOwner(module, declaration)
    val authoredInitial = initializer(declaration)
    requireValid(
      authoredInitial.nonEmpty == initial.nonEmpty && initial.forall(_._2 == nativeType),
      "register initializer must retain the exact declared recursive type",
      declaration.path
    )
    val domainAttribute = declaration.kind match
      case "wire" =>
        requireValid(
          declaration.domain.isEmpty && !attributes(declaration).contains("declared_domain"),
          "structural wire cannot carry a state domain",
          declaration.path
        )
        ""
      case "register" => s", domain = ${domain(module, declaration, symbol)}"
      case _ => fail("value storage must be wire or register", declaration.path)
    val operands = initial.toVector
    s"$result = \"nodal.digital_storage\"(${operands.map(_._1).mkString(", ")}) <{" +
      s"name = ${quoted(symbol("storage", declaration.name))}, " +
      s"kind = ${quoted(declaration.kind)}$domainAttribute, metadata = $metadata}> : " +
      s"(${operands.map(_._2).mkString(", ")}) -> $nativeType$sourceLocation"

  def depth(declaration: KernelDeclarationSnapshot): String =
    attributes(declaration).getOrElse(
      "depth",
      fail("memory depth contract is absent", declaration.path)
    )

  def renderMemory(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot,
      nativeElementType: String,
      nativeDepth: String,
      symbol: (String, String) => String,
      metadata: String,
      sourceLocation: String
  ): String =
    validateOwner(module, declaration)
    requireValid(
      declaration.kind == "memory",
      "expected an explicit memory declaration",
      declaration.path
    )
    val fields = attributes(declaration)
    requireValid(
      !fields.contains("initial") && nativeDepth.nonEmpty && depth(declaration).nonEmpty,
      "memory requires an explicit depth and cannot carry a register initializer",
      declaration.path
    )
    val latency = fields.get("readLatency").flatMap(_.toIntOption).filter(_ >= 0).getOrElse(
      fail("memory read latency must be a nonnegative Integer", declaration.path)
    )
    val collision = fields.get("readUnderWrite") match
      case Some("OldData") => "old_data"
      case Some("NewData") => "new_data"
      case Some("NoChange") => "no_change"
      case Some("Undefined") => "undefined"
      case _ => fail("memory read-under-write policy is absent or unknown", declaration.path)
    val ordering = fields.get("ordering") match
      case Some("Ordered") => "ordered"
      case Some("IndependentPorts") => "independent_ports"
      case _ => fail("memory ordering policy is absent or unknown", declaration.path)
    "\"nodal.memory\"() <{" +
      s"sym_name = ${quoted(symbol("memory", declaration.name))}, " +
      s"element_type = $nativeElementType, depth = ${quoted(nativeDepth)}, " +
      s"read_latency = $latency : i64, read_under_write = ${quoted(collision)}, " +
      s"ordering = ${quoted(ordering)}, domain = ${domain(module, declaration, symbol)}, " +
      s"metadata = $metadata}> : () -> ()$sourceLocation"

  def renderAssignment(
      path: String,
      kind: String,
      target: (String, String),
      value: (String, String),
      metadata: String,
      sourceLocation: String
  ): String =
    requireValid(
      Set("continuous", "register_next").contains(kind) && target._2 == value._2,
      "digital assignment requires an explicit intent and exactly matching recursive types",
      path
    )
    s"\"nodal.digital_assign\"(${target._1}, ${value._1}) <{" +
      s"kind = ${quoted(kind)}, metadata = $metadata}> : (${target._2}, ${value._2}) -> ()$sourceLocation"
