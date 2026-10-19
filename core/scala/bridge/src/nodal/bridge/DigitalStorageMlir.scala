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

  private def declaredDomain(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot
  ): KernelDomainSnapshot =
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
    selected.head

  private def domain(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot,
      symbol: (String, String) => String
  ): String = "@" + quoted(symbol("domain", declaredDomain(module, declaration).name))

  /** A port names the actual declaration, then resolves its declared requirement once. It never
    * accepts an independently asserted domain or a spelling-only memory reference.
    */
  def memoryPort(
      module: KernelModuleSnapshot,
      memory: String,
      portDomain: String,
      path: String
  ): (KernelDeclarationSnapshot, KernelDomainSnapshot) =
    val matches = module.declarations.filter(_.path == memory)
    requireValid(matches.size == 1, "memory port requires one actual owned declaration", path)
    val declaration = matches.head
    validateOwner(module, declaration)
    requireValid(
      declaration.kind == "memory",
      "memory port target is not a memory declaration",
      path
    )
    val declared = declaredDomain(module, declaration)
    requireValid(
      portDomain == declared.path,
      "memory port domain differs from its actual declaration association",
      path
    )
    val actual = if declared.kind == "required" then
      val binding = declared.binding.getOrElse(
        fail("memory port requirement has no direct local domain binding", path)
      )
      val targets = module.domains.filter(_.path == binding)
      requireValid(targets.size == 1, "memory port requirement binding is not uniquely local", path)
      targets.head
    else declared
    requireValid(
      Set("bound", "external").contains(actual.kind) && actual.binding.isEmpty &&
        actual.path.startsWith(module.path + "."),
      "memory port requires an actual direct local physical domain",
      path
    )
    declaration -> actual

  private def memoryPortAttributes(
      association: (KernelDeclarationSnapshot, KernelDomainSnapshot),
      symbol: (String, String) => String,
      metadata: String
  ): String =
    val (declaration, actual) = association
    s"memory = @${quoted(symbol("memory", declaration.name))}, " +
      s"domain = @${quoted(symbol("domain", actual.name))}, metadata = $metadata"

  def renderMemoryRead(
      association: (KernelDeclarationSnapshot, KernelDomainSnapshot),
      result: String,
      resultType: String,
      address: (String, String),
      symbol: (String, String) => String,
      metadata: String,
      sourceLocation: String
  ): String =
    val attributes = memoryPortAttributes(association, symbol, metadata)
    s"$result = \"nodal.memory_read\"(${address._1}) <{$attributes}> : (${address._2}) -> $resultType$sourceLocation"

  def renderMemoryWrite(
      association: (KernelDeclarationSnapshot, KernelDomainSnapshot),
      operands: Vector[(String, String)],
      symbol: (String, String) => String,
      metadata: String,
      sourceLocation: String
  ): String =
    val attributes = memoryPortAttributes(association, symbol, metadata)
    s"\"nodal.memory_write\"(${operands.map(_._1).mkString(", ")}) <{$attributes}> : " +
      s"(${operands.map(_._2).mkString(", ")}) -> ()$sourceLocation"

  def renderValue(
      module: KernelModuleSnapshot,
      declaration: KernelDeclarationSnapshot,
      result: String,
      nativeType: String,
      initial: Option[(String, String)],
      symbol: (String, String) => String,
      metadata: String,
      sourceLocation: String,
      exactType: Boolean
  ): String =
    validateOwner(module, declaration)
    val authoredInitial = initializer(declaration)
    requireValid(
      authoredInitial.nonEmpty == initial.nonEmpty && exactType,
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
      sourceLocation: String,
      exactType: Boolean
  ): String =
    requireValid(
      Set("continuous", "register_next").contains(kind) && exactType,
      "digital assignment requires an explicit intent and exactly matching recursive types",
      path
    )
    s"\"nodal.digital_assign\"(${target._1}, ${value._1}) <{" +
      s"kind = ${quoted(kind)}, metadata = $metadata}> : (${target._2}, ${value._2}) -> ()$sourceLocation"
