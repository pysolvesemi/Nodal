package nodal.internal.testkit
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

object ReplayForgedParentForwarding:
  def main(args: Array[String]): Unit =
    val snapshot = ConstructionKernel.inspect(new SelectedLiteralParentReference)
    val owner = snapshot.modules.find(_.className == "SelectedLiteralParentReference").get
    val parent = owner.declarations.find(_.name == "parentGain").get
    val instance = owner.instances.head
    val child = snapshot.modules.find(_.path == instance.childModule).get
    def ownerChange(change: KernelModuleSnapshot => KernelModuleSnapshot): ConstructionSnapshot =
      snapshot.copy(modules =
        snapshot.modules.map(module =>
          if module.path == owner.path then change(module) else module
        )
      )
    def actual(value: String): ConstructionSnapshot = ownerChange(module =>
      module.copy(instances =
        module.instances.map(valueInstance =>
          valueInstance.copy(parameterBindings =
            valueInstance.parameterBindings.map((name, old) =>
              name -> (if name == "gain" then value else old)
            )
          )
        )
      )
    )
    def declaration(change: KernelDeclarationSnapshot => KernelDeclarationSnapshot)
        : ConstructionSnapshot =
      ownerChange(module =>
        module.copy(declarations =
          module.declarations.map(value =>
            if value.path == parent.path then change(value) else value
          )
        )
      )
    def attribute(key: String, value: String): ConstructionSnapshot = declaration(parameter =>
      parameter.copy(attributes = parameter.attributes.filterNot(_._1 == key) :+ (key -> value))
    )
    val foreign = child.copy(
      path = "Foreign",
      className = "Foreign",
      domains = Vector.empty,
      declarations = Vector(parent.copy(path = "Foreign.parentGain")),
      instances = Vector.empty
    )
    val foreignActual = actual("Foreign.parentGain")
    // Keep the exact apparent parent path but move its only declaration out of the owner's inventory.
    val moved = snapshot.copy(modules =
      snapshot.modules.map(module =>
        if module.path == owner.path then
          module.copy(declarations = module.declarations.filterNot(_.path == parent.path))
        else if module.path == child.path then
          module.copy(declarations = module.declarations :+ parent)
        else module
      )
    )
    val duplicates = Vector(
      "duplicate-symbolic" -> Vector("gain" -> parent.path, "gain" -> parent.path),
      "equal-literal-symbolic" -> Vector("gain" -> "5.0", "gain" -> parent.path),
      "unequal-symbolic-literal" -> Vector("gain" -> parent.path, "gain" -> "6.0")
    ).map: (name, bindings) =>
      name -> ownerChange(module =>
        module.copy(instances =
          module.instances.map(value =>
            value.copy(parameterBindings = bindings)
          )
        )
      )
    val duplicateAttributes = Vector("unit" -> "V", "classification" -> "structural").flatMap:
      (key, hidden) =>
        Vector(false, true).map: reverse =>
          val valid = parent.attributes.find(_._1 == key).map(_._2).getOrElse(if key == "unit" then
            ""
          else "ordinary")
          val pair = Vector(key -> valid, key -> hidden)
          s"duplicate-parent-$key-$reverse" -> declaration(value =>
            value.copy(attributes =
              value.attributes.filterNot(_._1 == key) ++ (if reverse then pair.reverse else pair)
            )
          )
    val expressionPath = owner.path + ".forwardingExpression"
    val expressionActual = actual(expressionPath)
    val expression = expressionActual.copy(parameterExpressions =
      expressionActual.parameterExpressions :+ KernelParameterExpressionSnapshot(
        expressionPath,
        owner.path,
        "add",
        Vector(parent.path, parent.path),
        "Real",
        None,
        None
      )
    )
    val cases = Vector(
      "unknown-expression-opcode" -> expression,
      "foreign-same-name-same-default" ->
        foreignActual.copy(modules = foreignActual.modules :+ foreign),
      "child-owned-actual" -> actual(child.declarations.find(_.name == "gain").get.path),
      "parent-path-in-child-inventory" -> moved,
      "unregistered-parent-path" -> actual(owner.path + ".missingGain"),
      "generated-local-parent" -> declaration(_.copy(generatedScope = instance.generatedScope)),
      "wrong-parent-integer" -> declaration(value =>
        value.copy(
          dataType = Some("Integer"),
          attributes = value.attributes.filterNot(_._1 == "default") :+ ("default" -> "5")
        )
      ),
      "wrong-parent-bool" -> declaration(value =>
        value.copy(
          dataType = Some("Bool"),
          attributes = value.attributes.filterNot(_._1 == "default") :+ ("default" -> "true")
        )
      ),
      "wrong-parent-kind" -> declaration(_.copy(kind = "analog-node")),
      "parent-unit" -> attribute("unit", "V"),
      "parent-structural" -> attribute("classification", "structural"),
      "unknown-formal" -> ownerChange(module =>
        module.copy(instances =
          module.instances.map(value =>
            value.copy(parameterBindings = Vector("notGain" -> parent.path))
          )
        )
      ),
      "duplicate-parent-declaration" -> ownerChange(module =>
        module.copy(declarations = module.declarations :+ parent)
      )
    ) ++ duplicates ++ duplicateAttributes
    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    cases.foreach: (name, forged) =>
      try
        val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(forged, Backend.VerilogA).text)
        assert(result.isFailure, name)
        val interface = "NODAL-BRIDGE-054-INTERFACE"
        val expected = name match
          // Physical control resolution checks all declaration identities before Interface admission.
          case "duplicate-parent-declaration" =>
            Vector(
              "NODAL-BRIDGE-056-SEQUENTIAL: physical controls require unique typed root and projection identities"
            )
          // Global declaration-scope validation precedes selected Interface admission.
          case "generated-local-parent" =>
            Vector(
              "NODAL-BRIDGE-043: generated declaration scope is only supported on local conservative arrays"
            )
          case "wrong-parent-integer" | "wrong-parent-bool" =>
            Vector(interface, "NODAL-BRIDGE-034: symbolic parameter binding type")
          case "parent-structural" =>
            Vector(
              interface,
              "NODAL-BRIDGE-043: structural parameters currently require Integer type"
            )
          case value if value.startsWith("duplicate-parent-classification-") =>
            Vector(
              interface,
              "NODAL-BRIDGE-043: structural parameters currently require Integer type"
            )
          case "unknown-formal" => Vector(interface, "NODAL-BRIDGE-030: unknown child parameter")
          case "unregistered-parent-path" =>
            Vector(interface, "NODAL-BRIDGE-021: unsupported real parameter default")
          case "wrong-parent-kind" =>
            Vector(
              interface,
              "NODAL-BRIDGE-014: conservative declaration lacks discipline identity"
            )
          case _ => Vector(interface)
        val diagnostic = result.failed.get.toString
        assert(expected.exists(diagnostic.contains), name + ": " + diagnostic)
        println("PASS " + name + ": " + result.failed.get.toString)
      catch
        case failure: AssertionError =>
          failures += failure.getMessage
          println("FAIL " + name + ": " + failure.getMessage)
    assert(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text.nonEmpty)
    assert(failures.isEmpty, failures.mkString("\n"))
    println(
      "PASS exact-parent forged refusals and recovery; pure literal duplicate transport remains separate"
    )
