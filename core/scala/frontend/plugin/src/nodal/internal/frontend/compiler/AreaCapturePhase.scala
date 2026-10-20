package nodal.internal.frontend.compiler

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.UniqueName
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

import scala.collection.mutable

/** Lexical lifecycle only; the shared callback phase continues to own hardware effect validation.
  */
private[compiler] final class AreaCapturePhase extends PluginPhase:
  val phaseName = "nodalAreaCapture"
  override val runsAfter = Set("nodalConstructorCapture")
  override val runsBefore = Set("nodalDigitalCollectionCallbacks", "pickler")

  private var scanned = false
  private val parentApplications = mutable.HashSet.empty[(Symbol, String, Int, Int)]
  private def areaClass(using Context): ClassSymbol = requiredClass("nodal.Area")
  private def schemaClass(using Context): ClassSymbol = requiredClass("nodal.AreaCaptureSchema")
  private def area(tpe: Type)(using Context): Boolean =
    tpe.widen.dealias.derivesFrom(areaClass)
  private def string(value: String)(using Context): Tree = Literal(Constant(value))
  private def number(value: Int)(using Context): Tree = Literal(Constant(value))
  private def runtime(name: String)(using Context): Tree =
    ref(requiredMethod("nodal.AreaCaptureRuntime." + name))
  private def fail(code: String, message: String, tree: Tree)(using Context): Unit =
    report.error(s"NODAL-AREA-058-$code: $message", tree.srcPos)

  override def prepareForUnit(tree: Tree)(using Context): Context =
    if !scanned then
      scanned = true
      val base = ctx
      base.run.nn.units.foreach: unit =>
        given Context = base.fresh.setCompilationUnit(unit)
        val scan = new TreeTraverser:
          override def traverse(current: Tree)(using Context): Unit =
            current match
              case definition: TypeDef
                  if definition.symbol.isClass &&
                    definition.symbol != areaClass && definition.symbol.derivesFrom(areaClass) =>
                val cls = definition.symbol.asClass
                def parentApplication(parent: Tree): Unit = parent match
                  case application: Apply =>
                    if application.fun.symbol.isConstructor then
                      parentApplications +=
                        ((
                          application.fun.symbol,
                          ctx.source.file.path,
                          application.span.start,
                          application.span.end
                        ))
                    parentApplication(application.fun)
                  case TypeApply(function, _) => parentApplication(function)
                  case _ => ()
                definition.rhs match
                  case template: Template => template.parents.foreach(parentApplication)
                  case _ => ()
                if cls.hasAnnotation(schemaClass) then
                  fail("FORGED", "source must not declare compiler-owned Area metadata", definition)
                else
                  cls.addAnnotation(Annotation(
                    schemaClass,
                    List(number(1)),
                    definition.span
                  ))
              case reference: RefTree
                  if reference.symbol.exists &&
                    reference.symbol.owner.fullName.toString == "nodal.AreaCaptureRuntime" =>
                fail(
                  "FORGED",
                  "source must not call compiler-owned Area capture methods",
                  reference
                )
              case _ => ()
            traverseChildren(current)
        scan.traverse(unit.tpdTree)
    ctx

  private def capturedClass(cls: Symbol, tree: Tree)(using Context): Boolean =
    val bases =
      cls.info.baseClasses.filter(value => value != areaClass && value.derivesFrom(areaClass))
    bases.forall: base =>
      val valid = base.getAnnotation(schemaClass).exists: annotation =>
        annotation.argumentConstant(0).exists(_.value == 1)
      if !valid then
        fail(
          "MISSING",
          "Area class or base requires matching compiler-owned capture metadata",
          tree
        )
      valid

  private def guarded(body: Tree)(using Context): Tree =
    val thunk = Lambda(MethodType(Nil)(_ => Nil, _ => body.tpe), _ => body)
    runtime("guard").appliedToType(body.tpe)
      .appliedToArgs(List(string(ctx.source.file.path + ":" + body.span.start), thunk))

  override def transformApply(tree: Apply)(using Context): Tree = tree.fun match
    case selection: Select
        if selection.qualifier.isInstanceOf[New] &&
          selection.symbol.isConstructor && selection.symbol.owner.derivesFrom(areaClass) =>
      val cls = selection.symbol.owner
      if cls == areaClass ||
        parentApplications((
          selection.symbol,
          ctx.source.file.path,
          tree.span.start,
          tree.span.end
        )) ||
        !capturedClass(cls, tree)
      then tree
      else
        val arguments = tree.args.map(argument =>
          SyntheticValDef(UniqueName.fresh(termName("areaActual")), argument)
        )
        val allocation = cpy.Apply(tree)(tree.fun, arguments.map(value => ref(value.symbol)))
        val thunk = Lambda(MethodType(Nil)(_ => Nil, _ => tree.tpe), _ => allocation)
        val position = tree.srcPos.sourcePos
        val wrapped = runtime("allocate").appliedToType(tree.tpe).appliedToArgs(List(
          string(ctx.source.file.path),
          number(position.startLine + 1),
          number(position.startColumn + 1),
          number(position.endLine + 1),
          number(position.endColumn + 1),
          thunk
        ))
        guarded(Block(arguments, wrapped).withSpan(tree.span)).withSpan(tree.span)
    case _ => tree

  /** Named-argument temporaries belong to the same failure transaction as allocation. */
  override def transformBlock(tree: Block)(using Context): Tree =
    val guardedResult = tree.expr match
      case Apply(TypeApply(function, _), _) =>
        function.symbol == requiredMethod("nodal.AreaCaptureRuntime.guard")
      case Apply(function, _) =>
        function.symbol == requiredMethod("nodal.AreaCaptureRuntime.guard")
      case _ => false
    if tree.stats.nonEmpty && guardedResult then guarded(tree).withSpan(tree.span)
    else tree

  private def hardware(tpe: Type)(using Context): Boolean =
    val value = tpe.widen.dealias
    List(
      "nodal.Expr",
      "nodal.Instance",
      "nodal.Module",
      "nodal.Area",
      "nodal.ClockDomain",
      "nodal.InterfacePort",
      "nodal.InterfaceArray",
      "nodal.DigitalInout",
      "nodal.Node",
      "nodal.NodeArray"
    ).exists(name => value.derivesFrom(requiredClass(name)))

  private def bindingPriority(owner: Symbol)(using Context): Int =
    if owner.isClass then 2
    else
      var enclosing = owner
      while enclosing.exists && !enclosing.isClass &&
        !(enclosing.is(Method) && !enclosing.is(Synthetic))
      do enclosing = enclosing.owner
      if enclosing.exists && enclosing.isConstructor then 1 else 0

  override def transformValDef(tree: ValDef)(using Context): Tree =
    if tree.rhs.isEmpty || tree.symbol.is(Synthetic) then tree
    else
      val owner = tree.symbol.owner
      val areaField = owner.isClass && owner.derivesFrom(areaClass) && owner != areaClass
      if areaField && hardware(tree.tpt.tpe) && tree.symbol.isOneOf(Mutable | Lazy) then
        fail("MEMBER", "hardware Area members must be immutable eager vals", tree)
        tree
      else
        val bound = if area(tree.tpt.tpe) then
          runtime("bind").appliedToType(tree.tpt.tpe)
            .appliedToArgs(List(
              string(tree.name.toString),
              number(bindingPriority(owner)),
              tree.rhs
            ))
        else tree.rhs
        cpy.ValDef(tree)(rhs = bound)

  /** Capture the actual initialized field, including private anonymous members returned through a
    * generic helper. The field read is semantic capture, not an artificial unused-warning
    * exemption.
    */
  override def transformTemplate(tree: Template)(using Context): Tree =
    val cls = tree.symbol.owner
    if !cls.isClass || cls == areaClass || !cls.derivesFrom(areaClass) then tree
    else
      val body = tree.body.flatMap:
        case field: ValDef
            if !field.rhs.isEmpty && !field.symbol.isOneOf(Synthetic | Mutable | Lazy) =>
          val captured = runtime("member").appliedToType(field.tpt.tpe).appliedToArgs(List(
            This(cls.asClass),
            string(field.name.toString),
            ref(field.symbol)
          ))
          List(field, captured)
        case other => List(other)
      cpy.Template(tree)(body = body)
