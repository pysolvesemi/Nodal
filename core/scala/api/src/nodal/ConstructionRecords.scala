package nodal

import scala.collection.mutable

private[nodal] enum KernelSignalKind(val label: String):
  case Parameter extends KernelSignalKind("parameter")
  case Input extends KernelSignalKind("input")
  case Output extends KernelSignalKind("output")
  case Wire extends KernelSignalKind("wire")
  case Variable extends KernelSignalKind("variable")
  case Register extends KernelSignalKind("register")
  case Memory extends KernelSignalKind("memory")
  case AnalogInput extends KernelSignalKind("analog-input")
  case AnalogOutput extends KernelSignalKind("analog-output")
  case AnalogInout extends KernelSignalKind("analog-inout")
  case AnalogNode extends KernelSignalKind("analog-node")
  case AnalogNodeArray extends KernelSignalKind("analog-node-array")
  case InterfacePort extends KernelSignalKind("interface-port")
  case InterfaceArray extends KernelSignalKind("interface-array")
  case DigitalInout extends KernelSignalKind("digital-inout")
  case ConservativeTerminal extends KernelSignalKind("conservative-terminal")
  case AnalogSignal extends KernelSignalKind("analog-signal")

private[nodal] enum KernelDomainKind(val label: String):
  case External extends KernelDomainKind("external")
  case Bound extends KernelDomainKind("bound")
  case Required extends KernelDomainKind("required")
  case Generated extends KernelDomainKind("generated")

private[nodal] final case class KernelTypeDescriptor(
    kind: String,
    arguments: Vector[Any] = Vector.empty
)

private[nodal] trait KernelDescribedType:
  def kernelDescriptor: KernelTypeDescriptor

private[nodal] final case class KernelDiagnostic(
    code: String,
    message: String,
    semanticPath: Option[String] = None
):
  override def toString: String = semanticPath match
    case Some(path) => s"$code: $message [$path]"
    case None => s"$code: $message"

private[nodal] final class ConstructionException(val diagnostic: KernelDiagnostic)
    extends IllegalArgumentException(diagnostic.toString)

private[nodal] final case class KernelDomainSnapshot(
    path: String,
    name: String,
    kind: String,
    binding: Option[String],
    edge: Option[String] = None,
    resetPolicy: Option[String] = None,
    attributes: Vector[(String, String)] = Vector.empty
)

private[nodal] final case class KernelDeclarationSnapshot(
    path: String,
    kind: String,
    name: String,
    dataType: Option[String],
    domain: Option[String],
    attributes: Vector[(String, String)],
    conservativeDimensions: Vector[String] = Vector.empty,
    generatedScope: Option[KernelGeneratedScope] = None
)

private[nodal] final case class KernelGeneratedScope(owner: String, induction: String)

private[nodal] final case class KernelInstanceSnapshot(
    path: String,
    childModule: String,
    lexicalDomain: Option[String],
    bindings: Vector[(String, String)],
    parameterBindings: Vector[(String, String)] = Vector.empty,
    generatedScope: Option[KernelGeneratedScope] = None
)

private[nodal] final case class KernelInstanceArrayRootSnapshot(
    path: String,
    owner: String,
    instance: String,
    port: String,
    dimensions: Vector[String],
    generatedScope: Option[KernelGeneratedScope] = None
)

private[nodal] final case class KernelConservativeArrayBindingSnapshot(
    path: String,
    owner: String,
    left: String,
    right: String,
    generatedScope: Option[KernelGeneratedScope] = None
)

private[nodal] final case class KernelParameterExpressionSnapshot(
    path: String,
    owner: String,
    operation: String,
    operands: Vector[String],
    dataType: String,
    literal: Option[String],
    unit: Option[String],
    region: Option[String] = None
)

private[nodal] final case class KernelStructOperationSnapshot(
    schemaType: String,
    ordinal: Option[Int],
    name: Option[String]
)

private[nodal] final case class KernelDigitalExpressionSnapshot(
    path: String,
    owner: String,
    operation: String,
    operands: Vector[String],
    dataType: String,
    literal: Option[String],
    enumIdentity: Option[String],
    caseIds: Vector[String],
    region: Option[String] = None,
    materializationIntent: Option[KernelDigitalIntent] = None,
    structure: Option[KernelStructOperationSnapshot] = None
)

private[nodal] final case class KernelDigitalRegionArgument(
    path: String,
    dataType: String,
    role: String
)

private[nodal] final case class KernelDigitalCollectionSnapshot(
    path: String,
    owner: String,
    parentRegion: Option[String],
    kind: String,
    inputs: Vector[String],
    seed: Option[String],
    captures: Vector[String],
    arguments: Vector[KernelDigitalRegionArgument],
    yieldValue: String,
    dataType: String
)

private[nodal] final case class KernelDigitalAssignmentSnapshot(
    path: String,
    owner: String,
    target: String,
    value: String,
    kind: String,
    region: Option[String] = None,
    sourceOrdinal: Int = 0
)

private[nodal] final case class KernelDigitalLoopSnapshot(
    path: String,
    owner: String,
    lower: String,
    upperExclusive: String,
    step: String,
    maximum: Option[Int],
    maximumTripCount: Int,
    captures: Vector[String],
    arguments: Vector[KernelDigitalRegionArgument],
    sourceOrdinal: Int,
    generatedRegion: Option[String] = None
)

private[nodal] final case class KernelIterationIndexSnapshot(
    path: String,
    owner: String,
    region: String,
    operation: String,
    operands: Vector[String]
)

private[nodal] final case class KernelGeneratedRegionSnapshot(
    path: String,
    owner: String,
    parent: Option[String],
    induction: String,
    lower: String,
    upperExclusive: String,
    step: String,
    maximum: Option[Int],
    maximumTripCount: Int,
    declarations: Vector[String]
)

private[nodal] final case class KernelShapeIndexSnapshot(
    path: String,
    owner: String,
    input: String,
    indices: Vector[String],
    resultDimensions: Vector[String] = Vector.empty,
    sourceUses: Vector[String] = Vector.empty,
    generatedScope: Option[KernelGeneratedScope] = None,
    region: Option[String] = None
)

private[nodal] final case class KernelShapeSliceSnapshot(
    axis: Int,
    lower: String,
    upperExclusive: String
)

private[nodal] final case class KernelShapeViewSnapshot(
    path: String,
    owner: String,
    input: String,
    dimensions: Vector[String],
    slice: Option[KernelShapeSliceSnapshot] = None,
    generatedScope: Option[KernelGeneratedScope] = None,
    region: Option[String] = None
)

private[nodal] final case class KernelModuleSnapshot(
    path: String,
    className: String,
    domains: Vector[KernelDomainSnapshot],
    declarations: Vector[KernelDeclarationSnapshot],
    instances: Vector[KernelInstanceSnapshot]
)

private[nodal] final case class KernelResolvedNetSnapshot(
    path: String,
    dataType: String,
    mode: String,
    placement: String,
    profile: String,
    operations: Vector[String]
)

private[nodal] final case class KernelTopologyEdge(
    owner: String,
    kind: String,
    left: String,
    right: String,
    generatedScope: Option[KernelGeneratedScope] = None,
    path: Option[String] = None
)

private[nodal] final case class KernelAnalogExpressionSnapshot(
    path: String,
    operation: String,
    operands: Vector[String],
    literal: Option[String],
    unit: Option[String]
)

private[nodal] final case class KernelAnalogContributionSnapshot(
    path: String,
    target: String,
    value: String,
    kind: String,
    generatedOwner: Option[String] = None,
    generatedInduction: Option[String] = None
)

private[nodal] final case class KernelAnalogRegionSnapshot(
    path: String,
    module: String,
    expressions: Vector[KernelAnalogExpressionSnapshot],
    contributions: Vector[KernelAnalogContributionSnapshot],
    generatedOwner: Option[String] = None,
    generatedInduction: Option[String] = None
)

private[nodal] final case class KernelContinuousOperatorSnapshot(
    path: String,
    operation: String,
    owner: String,
    context: String,
    input: String,
    initialCondition: Option[String],
    inputDimension: String,
    resultDimension: String,
    stateId: Option[String],
    initialization: String,
    analyses: Vector[String],
    source: Option[SourceSpan],
    generatedOwner: Option[String] = None,
    generatedInduction: Option[String] = None
)

private[nodal] final case class KernelWaveformOperatorSnapshot(
    path: String,
    operation: String,
    owner: String,
    context: String,
    operands: Vector[String],
    operandDimensions: Vector[String],
    resultDimension: String,
    inputContinuity: String,
    outputContinuity: String,
    stateId: Option[String],
    analyses: Vector[String],
    source: Option[SourceSpan],
    generatedOwner: Option[String] = None,
    generatedInduction: Option[String] = None
)

private[nodal] final case class KernelTransferOperatorSnapshot(
    path: String,
    kind: String,
    owner: String,
    numeratorSize: Int,
    denominatorSize: Int,
    operands: Vector[String],
    resultDimension: String,
    source: Option[SourceSpan],
    generatedOwner: Option[String] = None,
    generatedInduction: Option[String] = None
)

private[nodal] final case class KernelNoiseOperatorSnapshot(
    path: String,
    kind: String,
    owner: String,
    label: String,
    operands: Vector[String],
    resultDimension: String,
    source: Option[SourceSpan],
    generatedOwner: Option[String] = None,
    generatedInduction: Option[String] = None
)

private[nodal] final case class KernelWaiverSnapshot(
    kind: String,
    id: String,
    reason: String,
    relation: String,
    semanticPath: String,
    sourceValue: Option[String],
    destinationDomain: Option[String],
    source: Option[SourceSpan]
)

private[nodal] final case class ConstructionSnapshot(
    root: String,
    rootParameterBindings: Vector[(String, String)] = Vector.empty,
    parameterExpressions: Vector[KernelParameterExpressionSnapshot] = Vector.empty,
    modules: Vector[KernelModuleSnapshot],
    interfaceAbi: Vector[InterfaceAbiEntry],
    resolvedNets: Vector[KernelResolvedNetSnapshot],
    topology: Vector[KernelTopologyEdge],
    names: Vector[KernelNameSnapshot] = Vector.empty,
    origins: Vector[KernelOriginSnapshot] = Vector.empty,
    generatedNames: Vector[KernelGeneratedNameSnapshot] = Vector.empty,
    sourceMap: Vector[SourceMapEntry] = Vector.empty,
    analogRegions: Vector[KernelAnalogRegionSnapshot] = Vector.empty,
    continuousOperators: Vector[KernelContinuousOperatorSnapshot] = Vector.empty,
    waveformOperators: Vector[KernelWaveformOperatorSnapshot] = Vector.empty,
    noiseOperators: Vector[KernelNoiseOperatorSnapshot] = Vector.empty,
    transferOperators: Vector[KernelTransferOperatorSnapshot] = Vector.empty,
    analogFunctions: Vector[AnalogUserFunctionRuntime.Snapshot] = Vector.empty,
    analogSemantics: AnalogEquationRuntime.Snapshot =
      AnalogEquationRuntime.Snapshot(Vector.empty, Vector.empty),
    analogProcedural: Vector[AnalogProceduralRuntime.Snapshot] = Vector.empty,
    waivers: Vector[KernelWaiverSnapshot] = Vector.empty,
    generatedRegions: Vector[KernelGeneratedRegionSnapshot] = Vector.empty,
    shapeIndices: Vector[KernelShapeIndexSnapshot] = Vector.empty,
    shapeViews: Vector[KernelShapeViewSnapshot] = Vector.empty,
    conservativeArrayBindings: Vector[KernelConservativeArrayBindingSnapshot] = Vector.empty,
    instanceArrayRoots: Vector[KernelInstanceArrayRootSnapshot] = Vector.empty,
    enumDefinitions: Vector[ResolvedEnumAbi] = Vector.empty,
    digitalExpressions: Vector[KernelDigitalExpressionSnapshot] = Vector.empty,
    interfaceContracts: Vector[KernelInterfaceSnapshot] = Vector.empty,
    digitalConnectivity: Vector[KernelDigitalConnectivitySnapshot] = Vector.empty,
    digitalAssignments: Vector[KernelDigitalAssignmentSnapshot] = Vector.empty,
    digitalCollections: Vector[KernelDigitalCollectionSnapshot] = Vector.empty,
    digitalLoops: Vector[KernelDigitalLoopSnapshot] = Vector.empty,
    iterationIndices: Vector[KernelIterationIndexSnapshot] = Vector.empty,
    emitQuality: EmitQuality = EmitQuality()
)

/** Immutable selected facts. Operand tags prevent authored strings from posing as references. */
private[nodal] enum KernelSelectedOperand:
  case Reference(path: String)
  case Literal(kind: String, value: String)
  case Opaque(kind: String)

private[nodal] final case class KernelSelectedReference(
    path: String,
    owner: String,
    kind: String,
    dataType: Option[String],
    operation: Option[String] = None,
    operands: Vector[KernelSelectedOperand] = Vector.empty
)

private[nodal] final case class KernelSelectedCapture(
    emission: Emission,
    snapshot: ConstructionSnapshot,
    references: Vector[KernelSelectedReference],
    operationKinds: Vector[String] = Vector.empty
)

private final case class DomainRef(module: Long, index: Int)
private final case class DeclarationRef(module: Long, index: Int)
private final case class ExpressionRef(module: Long, index: Int)

private final case class DomainRecord(
    reference: DomainRef,
    domain: ClockDomain,
    name: String,
    kind: KernelDomainKind
)

private final case class DeclarationRecord(
    reference: DeclarationRef,
    value: AnyRef,
    kind: KernelSignalKind,
    dataType: Option[DataType[? <: Data]],
    explicitName: Option[String],
    domainCandidate: Option[ClockDomain],
    attributes: Vector[(String, Any)],
    conservativeDimensions: Vector[Dimension] = Vector.empty
)

private final class GeneratedRegionRecord(
    val owner: Long,
    val ordinal: Int,
    val parentOrdinal: Option[Int],
    val induction: ExpressionRef,
    val lower: Any,
    val upperExclusive: Any,
    val step: Any,
    val maximum: Option[Int],
    val maximumTripCount: Int
):
  val declarations: mutable.ArrayBuffer[DeclarationRef] = mutable.ArrayBuffer.empty

private final case class ConservativeArrayBindingRecord(
    left: DeclarationRef | ExpressionRef,
    right: DeclarationRef | ExpressionRef,
    source: Option[SourceSpan]
)

private[nodal] sealed trait NodeArrayShapeIntent:
  def rank: Int
  def knownDimensions: Option[Vector[Dimension]]

private[nodal] object NodeArrayShapeIntent:
  final case class Declared(dimensions: Vector[Dimension]) extends NodeArrayShapeIntent:
    def rank: Int = dimensions.size
    def knownDimensions: Option[Vector[Dimension]] = Some(dimensions)
  final case class InstancePort(instance: Instance[?], port: NodeArray[?])
      extends NodeArrayShapeIntent:
    def rank: Int = port.shapeIntent.rank
    def knownDimensions: Option[Vector[Dimension]] = None
  final case class Transform(
      projection: NodeArrayProjection,
      rank: Int,
      knownDimensions: Option[Vector[Dimension]]
  ) extends NodeArrayShapeIntent

private[nodal] final case class NodeArrayProjection(
    input: NodeArray[?],
    indices: Vector[Dimension] = Vector.empty,
    slice: Option[ShapeSliceSelection] = None,
    reshape: Vector[Dimension] = Vector.empty,
    flatten: Boolean = false
)

private final class InstanceArrayRootRecord(
    val value: NodeArray[?],
    val reference: ExpressionRef,
    val instanceOrdinal: Int,
    val port: DeclarationRef
):
  var dimensions: Option[Vector[Dimension]] = None

private final case class DeferredNodeArrayRecord(
    value: NodeArray[?] | Node[?],
    reference: ExpressionRef,
    input: NodeArray[?],
    projection: Option[NodeArrayProjection],
    indices: Vector[Dimension] = Vector.empty
)

private final case class ConservativeSelectionKey(
    array: DeclarationRef | ExpressionRef,
    region: StructuralShapeCoordinates.Region
)

private final case class ShapeIndexRecord(
    reference: ExpressionRef,
    input: DeclarationRef | ExpressionRef,
    indices: Vector[Int | Expr[Integer]],
    resultDimensions: Vector[Any],
    conservative: Option[ConservativeSelectionKey] = None,
    sourceUses: Vector[(ExpressionRef, Vector[Dimension])] = Vector.empty,
    generatedScope: Option[GeneratedRegionRecord] = None
)

private final case class ShapeSliceSelection(
    axis: Int,
    lower: Dimension,
    upperExclusive: Dimension
)

private final case class ShapeViewRecord(
    reference: ExpressionRef,
    input: DeclarationRef | ExpressionRef,
    dimensions: Vector[Dimension],
    slice: Option[ShapeSliceSelection] = None,
    generatedScope: Option[GeneratedRegionRecord] = None
)

private final class InstanceRecord(
    val ordinal: Int,
    val child: Long,
    val lexicalDomain: Option[ClockDomain],
    val captured: Boolean,
    val generatedScope: Option[GeneratedRegionRecord] = None
):
  var defaultBinding: Option[ClockDomain] = None
  val namedBindings: mutable.ArrayBuffer[(ClockDomain, ClockDomain)] = mutable.ArrayBuffer.empty
  val parameterOverrides: mutable.ArrayBuffer[(Any, Any)] = mutable.ArrayBuffer.empty

private final class ModuleRecord(
    val handle: Long,
    val className: String,
    val parentAtConstruction: Option[Long]
):
  val domains: mutable.ArrayBuffer[DomainRecord] = mutable.ArrayBuffer.empty
  val declarations: mutable.ArrayBuffer[DeclarationRecord] = mutable.ArrayBuffer.empty
  val instances: mutable.ArrayBuffer[InstanceRecord] = mutable.ArrayBuffer.empty
  val generatedRegions: mutable.ArrayBuffer[GeneratedRegionRecord] = mutable.ArrayBuffer.empty
  var expressionCount: Int = 0
  var scaffoldExpressionCount: Int = 0
  var attached: Boolean = parentAtConstruction.isEmpty

private final case class Operation(
    owner: Long,
    kind: String,
    values: Vector[Any],
    generatedScope: Option[GeneratedRegionRecord] = None,
    source: Option[SourceSpan] = None,
    digitalRegion: Option[DigitalValueRegionRecord] = None,
    sourceOrdinal: Int = 0
)

/** Mutable lexical capture state owned exclusively by ConstructionSession. */
private final class DigitalValueRegionRecord(
    val reference: ExpressionRef,
    val parent: Option[DigitalValueRegionRecord],
    val kind: String,
    val inputs: Vector[Expr[?]],
    val seed: Option[Expr[?]],
    val loop: Option[DigitalLoopHeader] = None
):
  def isLoop: Boolean = loop.nonEmpty
  val arguments: mutable.ArrayBuffer[(KernelExpr[?], String)] = mutable.ArrayBuffer.empty
  val captures: mutable.LinkedHashMap[DeclarationRef | ExpressionRef, (Expr[?], KernelExpr[?])] =
    mutable.LinkedHashMap.empty
  var expressionCount: Int = 0
  var assignmentCount: Int = 0
  var yielded: Option[Expr[?]] = None
  var result: Option[KernelExpr[?]] = None

private final class AnalogRegionRecord(
    val module: Long,
    val ordinal: Int,
    val generatedOwnership: Option[(String, String)]
):
  val expressions: mutable.ArrayBuffer[ExpressionRef] = mutable.ArrayBuffer.empty
  val contributions: mutable.ArrayBuffer[(Any, Any)] = mutable.ArrayBuffer.empty

private final case class AnalogSemanticContext(
    module: Long,
    kind: AnalogEquationRuntime.RegionKind
)

private final case class ContinuousOperatorRecord(
    reference: ExpressionRef,
    operation: String,
    input: Expr[Real],
    initialCondition: Option[Expr[Real]],
    context: String,
    inputDimension: AnalogDimension,
    resultDimension: AnalogDimension
)

/** A domain and effect position attached to the shared lexical value owner. */
private final case class DigitalLoopHeader(
    lower: Dimension,
    upperExclusive: Dimension,
    step: Dimension,
    maximum: Option[Int],
    domain: IterationDomain.Structural,
    sourceOrdinal: Int,
    generated: Option[GeneratedRegionRecord] = None
)
