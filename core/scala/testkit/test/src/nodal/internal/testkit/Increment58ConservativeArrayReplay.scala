package nodal.internal.testkit

import java.nio.file.{Files, Path}
import nodal.*
import nodal.internal.bridge.*
import nodal_external.*

/** Focused replay only; run after verified SourceProjection preflight, never as a fallback. */
object Increment58ConservativeArrayReplay:
  def main(args: Array[String]): Unit =
    require(args.length == 2 || (args.length == 3 && args(2) == "remaining-controls"), "output directory and exact authored source-relative path required")
    val directory = Path.of(args(0))
    val sourcePath = args(1)
    require(sourcePath == "core/scala/testkit/test/src/nodal_external/Increment58ConservativeArraySource.scala")
    val _ = Files.createDirectories(directory)
    val positives = Vector(
      ("extent1", 1, "direct", 2),
      ("extent3", 3, "direct", 2),
      ("reverse", 3, "reverse", 2),
      ("two-level", 3, "two-level", 4),
      ("two-instances", 3, "two-instances", 4)
    )
    positives.filterNot(_ => args.length == 3).foreach: (name, count, mode, edges) =>
      val snapshot = ConstructionKernel.inspect(new ConservativeArrayTop(count, mode))
      val connections = snapshot.digitalConnectivity.filter(_.kind == "interface-connect")
      assert(connections.size == edges)
      assert(connections.flatMap(_.member).toSet == Set("pair.p", "pair.n"))
      assert(snapshot.interfaceContracts.forall(_.dimensions == Vector(count.toString)))
      assert(snapshot.shapeIndices.isEmpty && snapshot.shapeViews.isEmpty)
      assert(snapshot.generatedRegions.isEmpty)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA).text
      assert(text.contains(sourcePath), "runtime MLIR must retain the actual authored fixture path")
      assert(text.sliding("\"nodal.connect_array\"".length)
        .count(_ == "\"nodal.connect_array\"") == edges)
      assert(!text.contains("\"nodal.interface_connect\""))
      assert(!text.contains("\"nodal.shape_index\""))
      if mode == "two-instances" then
        val root = snapshot.modules.find(_.path == snapshot.root).get
        assert(root.instances.size == 2 && root.instances.map(_.path).distinct.size == 2)
        assert(root.instances.map(_.childModule).distinct.size == 2)
        val instanceLines = text.linesIterator.filter(_.contains("\"nodal.instance\"")).toVector
        val definitionRefs = instanceLines.map(line =>
          """module = (@[^, >]+)""".r.findFirstMatchIn(line).get.group(1))
        assert(instanceLines.size == 2 && definitionRefs.distinct.size == 1)
        assert(text.sliding("\"nodal.module\"".length).count(_ == "\"nodal.module\"") == 2)
      val _ = Files.writeString(directory.resolve(name + ".mlir"), text)
      println("PASS source " + name)

    Vector("count", "role", "domain", "raw", "foreign-child", "duplicate").drop(if args.length == 3 then 3 else 0).foreach: mode =>
      val result = scala.util.Try(ConstructionKernel.inspect(new ConservativeArrayTop(3, mode)))
      assert(result.isFailure, mode + " was admitted")
      assert(result.failed.get.isInstanceOf[ConstructionException], result.failed.get)
      val message = result.failed.get.getMessage
      val cause = mode match
        case "raw" => "forwardExact requires one local and one child endpoint"
        case "foreign-child" => "immediate child's direct Interface"
        case "domain" => "one domain"
        case "role" | "duplicate" => "conservative forwarding"
        case _ => "exact interface connection"
      assert(message.contains(cause), mode + " failed at an unrelated barrier: " + message)
      println("PASS capture-causal " + mode + " " + message)
    Vector(
      "symbolic" -> (() => new ConservativeArraySymbolic),
      "generated" -> (() => new ConservativeArrayGenerated)
    ).foreach: (name, build) =>
      val result = scala.util.Try(ConstructionKernel.inspect(build()))
      assert(result.isFailure, name + " was admitted")
      assert(result.failed.get.isInstanceOf[ConstructionException], result.failed.get)
      val message = result.failed.get.getMessage
      assert(message.contains("NODAL-INTERFACE-") || message.contains("NODAL-HIERARCHY-058-INTERFACE"), message)
      println("PASS unsupported-admission " + name + " " + message)

    val original = ConstructionKernel.inspect(new ConservativeArrayTop)
    val edge = original.digitalConnectivity.find(_.kind == "interface-connect").get
    val endpoint = original.interfaceContracts.find(_.boundary.nonEmpty).get
    val forged = Vector(
      "missing-leaf" -> original.copy(digitalConnectivity = original.digitalConnectivity.filterNot(_ == edge)),
      "duplicate-leaf" -> original.copy(digitalConnectivity = original.digitalConnectivity :+
        edge.copy(path = edge.path + "_duplicate")),
      "access" -> original.copy(digitalConnectivity = original.digitalConnectivity.map(value =>
        if value == edge then value.copy(access = Some("sense")) else value)),
      "role" -> original.copy(digitalConnectivity = original.digitalConnectivity.map(value =>
        if value == edge then value.copy(endpointRole = Some("sensed")) else value)),
      "foreign-child" -> original.copy(interfaceContracts = original.interfaceContracts.map(value =>
        if value.path == endpoint.path then value.copy(boundary = value.boundary.map(
          _.copy(instance = Some("foreign")))) else value)),
      "domain" -> original.copy(interfaceContracts = original.interfaceContracts.map(value =>
        if value.path == endpoint.path then value.copy(domain = Some("foreign")) else value)),
      "count" -> original.copy(
        interfaceContracts = original.interfaceContracts.map(value =>
          if value.path == endpoint.path then value.copy(dimensions = Vector("4")) else value),
        modules = original.modules.map(module => module.copy(declarations = module.declarations.map(value =>
          if value.path == endpoint.path then value.copy(attributes = value.attributes.map((key, old) =>
            key -> (if key == "count" then "4" else old))) else value))))
    )
    forged.foreach: (name, snapshot) =>
      val result = scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot, Backend.VerilogA))
      assert(result.isFailure, "forged " + name + " was admitted")
      assert(result.failed.get.isInstanceOf[BridgeException], result.failed.get)
      println("PASS bridge-causal " + name + " " + result.failed.get.getMessage)
