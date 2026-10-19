package nodal.internal.testkit

import java.nio.file.{Files, Path}

import nodal.*
import nodal.internal.bridge.*
import utest.*

enum StructValueMode derives HwEnum:
  case Idle, Busy

enum StructValueOtherMode derives HwEnum:
  case Idle, Busy

final class StructValueSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val scoreWidth = param(4.integer, range = 2 to 8)
  val tagWidth = param(4.integer, range = 2 to 8)
  val payloadWidth = param(4.integer, range = 2 to 8)
  val innerCount = param(2.integer, range = 1 to 4)
  val score = StructField("score", SInt(scoreWidth))
  val tag = StructField("tag", Bits(tagWidth))
  val payload = StructField("payload", Vec(UInt(payloadWidth), innerCount, 2))
  val valid = StructField("valid", Bool)
  val mode = StructField("mode", EnumType[StructValueMode]())
  val candidate = Struct("Candidate", score, tag, payload, valid, mode)
  val validKey = candidate.member(valid)
  val modeKey = candidate.member(mode)
  val scoreKey = candidate.member(score)
  val tagKey = candidate.member(tag)
  val payloadKey = candidate.member(payload)
  val reconstructed = Struct(
    "Candidate",
    StructField("score", SInt(scoreWidth)),
    StructField("tag", Bits(tagWidth)),
    StructField("payload", Vec(UInt(payloadWidth), innerCount, 2)),
    StructField("valid", Bool),
    StructField("mode", EnumType[StructValueMode]())
  )
  val equivalentKey = reconstructed.member(StructField("score", SInt(scoreWidth)))
  val alternateScore = StructField("score", SInt(tagWidth))
  val alternateSchema = Struct("Candidate", alternateScore, tag, payload, valid, mode)
  val alternateKey = alternateSchema.member(alternateScore)
  val left = in(candidate)
  val right = in(reconstructed)
  val leftScore = left.field(equivalentKey)
  val rightScore = right.field(scoreKey)
  val actualWidthFromAlternate = left.field(alternateKey)
  val schemaOnlyWidth = StaticIntegerExpression.chooseZero(
    innerCount - 1.integer,
    StaticIntegerExpression.literal64(BigInt(Int.MaxValue) + 1),
    StaticIntegerExpression.add64(StaticIntegerExpression.ceilLog2(innerCount), 1)
  )
  val schemaOnlyScore = StructField("score", SInt(schemaOnlyWidth))
  val schemaOnlyCandidate = Struct("Candidate", schemaOnlyScore, tag, payload, valid, mode)
  val schemaOnlyRead = left.field(schemaOnlyCandidate.member(schemaOnlyScore))
  val takeLeft = leftScore >= rightScore
  val winner = select(takeLeft, left, right)
  val joined = reconstructed.value(
    scoreKey.bind(leftScore + rightScore),
    tagKey.bind(select(takeLeft, left.field(tagKey), right.field(tagKey))),
    payloadKey.bind(select(takeLeft, left.field(payloadKey), right.field(payloadKey))),
    validKey.bind(select(takeLeft, left.field(validKey), right.field(validKey))),
    modeKey.bind(select(takeLeft, left.field(modeKey), right.field(modeKey)))
  )
  val projected = joined.field(scoreKey)
  val namedJoined = joined.named("combined_record").keep("record observation")
  val namedScore = namedJoined.field(scoreKey).named("combined_score")
  val unsignedField = StructField("count", UInt(scoreWidth))
  val unsignedSchema = Struct("UnsignedRecord", unsignedField)
  val unsignedRecord = in(unsignedSchema)
  val unsignedProof = unsignedRecord.field(unsignedSchema.member(unsignedField))
    .toSigned.named("nonnegative_field").keep("unsigned field proof").toUnsigned
  val unsignedRecords = in(Vec(unsignedSchema, 2))
  val unsignedMapped = unsignedRecords.map: row =>
    row.field(unsignedSchema.member(unsignedField)).toSigned
      .named("lane_unsigned_field").keep("lane unsigned proof").toUnsigned
  val records = in(Vec(candidate, 5))
  val otherRecords = in(Vec(candidate, 5))
  val winners = records.zipWith(otherRecords): (left, right) =>
    select(left.field(scoreKey) >= right.field(scoreKey), left, right)
  val rebuilt = records.zipWith(otherRecords): (left, right) =>
    val leftScore = left.field(scoreKey)
    val rightScore = right.field(scoreKey)
    val takeLeft = leftScore >= rightScore
    candidate.value(
      scoreKey.bind(leftScore + rightScore),
      tagKey.bind(select(takeLeft, left.field(tagKey), right.field(tagKey))),
      payloadKey.bind(select(takeLeft, left.field(payloadKey), right.field(payloadKey))),
      validKey.bind(select(takeLeft, left.field(validKey), right.field(validKey))),
      modeKey.bind(select(takeLeft, left.field(modeKey), right.field(modeKey)))
    )

final class StructValueInvalidSource(mode: Int) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val width = param(4.integer, range = 2 to 8)
  val otherWidth = param(4.integer, range = 2 to 8)
  val count = param(2.integer, range = 1 to 4)
  val otherCount = param(2.integer, range = 1 to 4)
  val score = StructField("score", SInt(width))
  val tag = StructField("tag", Bits(otherWidth))
  val payload = StructField("payload", Vec(UInt(width), count))
  val schema = Struct("Candidate", score, tag, payload)
  val scoreKey = schema.member(score)
  val tagKey = schema.member(tag)
  val payloadKey = schema.member(payload)
  val input = in(schema)
  val payloadInput = in(Vec(UInt(width), otherCount))
  val sink = out(schema)
  if mode == 0 then
    val _ = schema.member(StructField("score", SInt(otherWidth)))
  if mode == 1 then
    val _ = schema.value(scoreKey.bind(input.field(scoreKey)))
  if mode == 2 then
    val _ = schema.value(
      scoreKey.bind(input.field(scoreKey)),
      scoreKey.bind(input.field(scoreKey)),
      payloadKey.bind(input.field(payloadKey))
    )
  if mode == 3 then
    val _ = schema.value(
      tagKey.bind(input.field(tagKey)),
      scoreKey.bind(input.field(scoreKey)),
      payloadKey.bind(input.field(payloadKey))
    )
  if mode == 4 then
    val _ = payloadKey.bind(payloadInput)
  if mode == 5 then
    val other = Struct("Candidate", StructField("score", UInt(width)), tag, payload)
    val _ = input.field(other.member(StructField("score", UInt(width))))
  if mode == 6 then
    val other = Struct("Candidate", tag, score, payload)
    val _ = input.field(other.member(score))
  if mode == 7 then
    val other = Struct("Other", score, tag, payload)
    val _ = input.field(other.member(score))
  if mode == 8 then
    val changed = schema.value(
      scoreKey.bind(input.field(scoreKey) + input.field(scoreKey)),
      tagKey.bind(input.field(tagKey)),
      payloadKey.bind(input.field(payloadKey))
    )
    sink := changed
  if mode == 9 then
    val other = Struct(
      "Candidate",
      score,
      tag,
      StructField("payload", Vec(UInt(width), otherCount))
    )
    val _ = input.field(other.member(score))
  if mode == 10 then
    val other = Struct("Other", score, tag, payload)
    val _ = schema.value(
      other.member(score).bind(input.field(scoreKey)),
      tagKey.bind(input.field(tagKey)),
      payloadKey.bind(input.field(payloadKey))
    )
  if mode == 11 then
    val _ = schema.member(StructField("missing", SInt(width)))
  if mode == 12 then
    val _ = schema.value(
      scoreKey.bind(input.field(scoreKey)),
      tagKey.bind(input.field(tagKey)),
      payloadKey.bind(input.field(payloadKey)),
      payloadKey.bind(input.field(payloadKey))
    )

final class StructEnumInvalidSource extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val mode = StructField("mode", EnumType[StructValueMode]())
  val other = StructField("mode", EnumType[StructValueOtherMode]())
  val schema = Struct("EnumRecord", mode)
  val input = in(schema)
  val _ = input.field(Struct("EnumRecord", other).member(other))

final class StructOpaqueValueSource(mode: Int) extends Module:
  val root = ClockDomain.external(
    "root",
    ClockEdge.Rising,
    ResetPolicy.Sync,
    ResetPolarity.ActiveHigh,
    100.MHz
  )
  val real = StructField("measure", Real)
  val integer = StructField("count", Integer)
  val tag = StructField("tag", UInt(4))
  val schema = Struct("Opaque", real, integer, tag)
  val left = in(schema)
  val right = in(schema)
  val winner = select(true.B, left, right)
  val observed = left.field(schema.member(tag))
  val realInput = in(Real)
  val integerInput = in(Integer)
  if mode == 1 then
    val _ = left.field(schema.member(real))
  if mode == 2 then
    val _ = left.field(schema.member(integer))
  if mode == 3 then
    val _ = schema.member(real).bind(realInput)
  if mode == 4 then
    val _ = schema.member(integer).bind(integerInput)

object Increment55StructValueTests extends TestSuite:
  private def rejected(snapshot: ConstructionSnapshot): Unit =
    assert(scala.util.Try(ScalaToMlirBridge.fromSnapshot(snapshot)).failed.get
      .isInstanceOf[BridgeException])

  val tests: Tests = Tests:
    test("external library uses only the typed member surface"):
      assert(nodal_external.Increment55PublicStructValues.wrongBindingRejected)
      assert(nodal_external.Increment55PublicStructValues.privateHandleRejected)

    test("whole-record winner and one shared predicate preserve actual nested value DAG"):
      val snapshot = ConstructionKernel.inspect(new StructValueSource)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      assert(snapshot.digitalCollections.size == 3)
      val record = snapshot.digitalExpressions.find(value =>
        value.region.isEmpty && value.operation == "digital_struct_make"
      ).get
      val score = snapshot.digitalExpressions.find(value =>
        value.operation == "digital_struct_get" && value.operands.exists(input =>
          snapshot.digitalExpressions.exists(producer =>
            producer.path == input && producer.operation == "digital_struct_make"
          )
        )
      ).get
      val values = snapshot.digitalExpressions.map(value => value.path -> value).toMap
      assert(record.operation == "digital_struct_make")
      assert(record.operands.size == 5)
      assert(score.dataType != "SInt(scoreWidth)")
      assert(score.dataType == values(record.operands.head).dataType)
      assert(snapshot.digitalExpressions.exists(value =>
        value.operation == "digital_struct_get" && value.dataType == "SInt(scoreWidth)" &&
          value.structure.exists(_.schemaType.startsWith("Struct(Candidate{score:SInt(tagWidth)"))
      ))
      val tag = values(record.operands(1))
      val payload = values(record.operands(2))
      assert(tag.operands.head == payload.operands.head)
      assert(values(tag.operands.head).operation == "digital_ge")
      assert(payload.dataType == "Vec(UInt(payloadWidth);innerCountx2)")
      val rebuilt = snapshot.digitalCollections.find(value =>
        values(value.yieldValue).operation == "digital_struct_make"
      ).get
      val made = values(rebuilt.yieldValue)
      assert(values(made.operands(1)).operands.head == values(made.operands(2)).operands.head)
      assert(text.contains("struct_schema = !nodal.struct<\"Candidate\""))
      assert(text.contains("operator_name = \"ceil_log2\""))
      assert(text.contains("operator_name = \"choose_zero\""))
      assert(text.contains("2147483648"))
      assert(text == ScalaToMlirBridge.lower(new StructValueSource).text)
      sys.env.get("STRUCT_STAGE_OUTPUT").foreach: directory =>
        val output = Path.of(directory)
        val _ = Files.createDirectories(output)
        val _ = Files.writeString(output.resolve("whole-record.mlir"), text)

    test("aggregate source origins aliases and receipts preserve exact schema evidence"):
      val snapshot = ConstructionKernel.inspect(new StructValueSource)
      val text = ScalaToMlirBridge.fromSnapshot(snapshot).text
      val aggregates = snapshot.digitalExpressions.filter(value =>
        DigitalAggregateContract.Operations.contains(value.operation)
      )
      assert(aggregates.nonEmpty)
      aggregates.foreach: value =>
        val source = snapshot.sourceMap.find(_.semanticPath == value.path).get.source
        assert(source.path ==
          "core/scala/testkit/test/src/nodal/internal/testkit/Increment55StructValueTests.scala")
        assert(source.line > 0, source.column > 0)
        val operation = text.linesIterator.find(line =>
          line.contains(s"semantic_path = \"${value.path}\"") &&
            line.contains(s"operator_name = \"${value.operation}\"")
        ).get
        assert(operation.contains("digital_source"))
      val aliases = snapshot.digitalExpressions.filter(_.operation == "digital_alias")
      assert(aliases.exists(_.materializationIntent.contains(
        KernelDigitalIntent.Named("combined_record")
      )))
      assert(aliases.exists(_.materializationIntent.contains(
        KernelDigitalIntent.Keep("record observation")
      )))
      assert(snapshot.digitalExpressions.exists(_.operation == "digital_to_unsigned"))
      val receipt = ReproducibilityContract.describe(snapshot)
      assert(receipt.construction.contains("\"structure\""))
      assert(receipt.construction.contains("\"schema_type\""))
      assert(receipt.construction.contains("\"intent\""))
      assert(receipt.manifest.text.contains("\"decision_status\": \"not-run\""))
      assert(!text.contains("nodal.digital.materialization"))

    test("ordered complete bindings and semantic family reject exact mismatches"):
      (0 to 12).foreach: mode =>
        val error =
          scala.util.Try(ScalaToMlirBridge.lower(new StructValueInvalidSource(mode))).failed
        assert(error.isSuccess)
      assert(scala.util.Try(ConstructionKernel.inspect(new StructEnumInvalidSource)).isFailure)

    test("opaque Real and Integer preserve whole records but standalone leaves reject"):
      val text = ScalaToMlirBridge.lower(new StructOpaqueValueSource(0)).text
      assert(text.contains("name = \"measure\", type = f64"))
      assert(text.contains("name = \"count\", type = i64"))
      val snapshot = ConstructionKernel.inspect(new StructOpaqueValueSource(0))
      val get = snapshot.digitalExpressions.find(_.operation == "digital_struct_get").get
      Vector(("Real", 0, "measure"), ("Integer", 1, "count")).foreach: (kind, ordinal, name) =>
        val forged = get.copy(
          dataType = kind,
          structure = get.structure.map(_.copy(
            ordinal = Some(ordinal),
            name = Some(name)
          ))
        )
        rejected(snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(value =>
            if value.path == get.path then forged else value
          )
        ))
      (1 to 4).foreach(mode =>
        assert(
          scala.util.Try(ConstructionKernel.inspect(new StructOpaqueValueSource(mode))).isFailure
        )
      )

    test("bridge independently rejects schema ordinal names arity and actual result widths"):
      val snapshot = ConstructionKernel.inspect(new StructValueSource)
      val get = snapshot.digitalExpressions.find(value =>
        value.operation == "digital_struct_get" && value.operands.exists(input =>
          snapshot.digitalExpressions.exists(producer =>
            producer.path == input && producer.operation == "digital_struct_make"
          )
        )
      ).get
      val make = snapshot.digitalExpressions.find(value =>
        value.region.isEmpty && value.operation == "digital_struct_make"
      ).get
      def replace(value: KernelDigitalExpressionSnapshot): ConstructionSnapshot =
        snapshot.copy(digitalExpressions =
          snapshot.digitalExpressions.map(old =>
            if old.path == value.path then value else old
          )
        )
      Vector(
        get.copy(dataType = "SInt(scoreWidth)"),
        get.copy(structure = None),
        get.copy(structure = get.structure.map(_.copy(ordinal = Some(-1)))),
        get.copy(structure = get.structure.map(_.copy(ordinal = Some(5)))),
        get.copy(structure = get.structure.map(_.copy(name = Some("tag")))),
        get.copy(structure =
          get.structure.map(value =>
            value.copy(
              schemaType = value.schemaType.replace("SInt(", "UInt(")
            )
          )
        ),
        get.copy(structure =
          get.structure.map(value =>
            value.copy(
              schemaType = value.schemaType.replace("Candidate{", "Other{")
            )
          )
        ),
        make.copy(operands = make.operands.reverse),
        make.copy(operands = make.operands.dropRight(1)),
        make.copy(operands = make.operands.updated(2, make.operands(1))),
        make.copy(structure = make.structure.map(_.copy(ordinal = Some(0))))
      ).foreach(value => rejected(replace(value)))
