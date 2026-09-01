package nodal.increment33fixture

import nodal.AnalogProceduralRuntime

object Increment33RuntimeCheck:
  private val RealVoltage =
    AnalogProceduralRuntime.ValueType(AnalogProceduralRuntime.ScalarKind.Real, "voltage")
  private val RealCurrent =
    AnalogProceduralRuntime.ValueType(AnalogProceduralRuntime.ScalarKind.Real, "current")
  private val IntegerDimensionless =
    AnalogProceduralRuntime.ValueType(
      AnalogProceduralRuntime.ScalarKind.Integer,
      "dimensionless"
    )
  private val BoolDimensionless =
    AnalogProceduralRuntime.ValueType(
      AnalogProceduralRuntime.ScalarKind.Boolean,
      "dimensionless"
    )

  private def expect(code: String)(body: => Unit): Unit =
    val failure = scala.util.Try(body).failed.get.asInstanceOf[AnalogProceduralRuntime.Failure]
    assert(failure.diagnostic.code == code)

  def main(arguments: Array[String]): Unit =
    val recorder = new AnalogProceduralRuntime.Recorder("ProceduralTop")
    var escaped: Option[AnalogProceduralRuntime.Variable] = None

    recorder.procedure:
      val previous = recorder.declare(
        "previous",
        RealVoltage,
        Some(AnalogProceduralRuntime.Value("0.0V", RealVoltage)),
        Some(AnalogProceduralRuntime.Source("Increment33RuntimeCheck.scala", 38, 7))
      )
      val scratch = recorder.declare("scratch", RealVoltage)
      val count = recorder.declare(
        "count",
        IntegerDimensionless,
        Some(AnalogProceduralRuntime.Value("0", IntegerDimensionless))
      )

      recorder.assign("capture", scratch, recorder.read(previous))
      recorder.assign(
        "update",
        previous,
        AnalogProceduralRuntime.Value("1.25V", RealVoltage),
        guard = Some(AnalogProceduralRuntime.Value("enabled", BoolDimensionless)),
        analyses = Set("dc", "transient")
      )
      recorder.assign(
        "repeat-update",
        previous,
        AnalogProceduralRuntime.Value("2.50V", RealVoltage)
      )
      recorder.assign(
        "promote-count",
        scratch,
        AnalogProceduralRuntime.Value("count-as-real", RealVoltage, Vector(count))
      )

      recorder.scope("inner"):
        val local = recorder.declare(
          "local",
          RealVoltage,
          Some(AnalogProceduralRuntime.Value("0.5V", RealVoltage))
        )
        escaped = Some(local)
        recorder.assign("inner-write", scratch, recorder.read(local))

      expect("NODAL-ANALOG-033-010"):
        recorder.assign(
          "scope-escape",
          escaped.get,
          AnalogProceduralRuntime.Value("1.0V", RealVoltage)
        )

    val snapshot = recorder.snapshot
    assert(snapshot.variables.map(_.declarationOrder) == Vector(0, 1, 2, 3))
    assert(snapshot.assignments.map(_.authoredOrder) == Vector(0, 1, 2, 3, 4))
    assert(snapshot.variables.map(_.operationOrder) == Vector(0, 1, 2, 7))
    assert(snapshot.assignments.map(_.operationOrder) == Vector(3, 4, 5, 6, 8))
    assert(snapshot.assignments.map(_.identity) == Vector(
      "ProceduralTop.capture",
      "ProceduralTop.update",
      "ProceduralTop.repeat-update",
      "ProceduralTop.promote-count",
      "ProceduralTop.inner-write"
    ))
    assert(snapshot.assignments(1).guard.nonEmpty)
    assert(snapshot.assignments(1).analyses == Vector("dc", "transient"))
    assert(snapshot.assignments(1).source.isEmpty)
    assert(snapshot.assignments(1).target.identity.endsWith(".previous"))
    assert(snapshot.assignments(2).target.identity.endsWith(".previous"))

    val readBeforeWrite = new AnalogProceduralRuntime.Recorder("ReadBeforeWrite")
    expect("NODAL-ANALOG-033-011"):
      readBeforeWrite.procedure:
        val value = readBeforeWrite.declare("uninitialized", RealVoltage)
        readBeforeWrite.read(value)

    val dimensionMismatch = new AnalogProceduralRuntime.Recorder("DimensionMismatch")
    expect("NODAL-ANALOG-033-013"):
      dimensionMismatch.procedure:
        val value = dimensionMismatch.declare("voltage", RealVoltage)
        dimensionMismatch.assign(
          "bad-dimension",
          value,
          AnalogProceduralRuntime.Value("1.0A", RealCurrent)
        )

    val crossOwnerA = new AnalogProceduralRuntime.Recorder("OwnerA")
    var foreign: Option[AnalogProceduralRuntime.Variable] = None
    crossOwnerA.procedure:
      foreign = Some(
        crossOwnerA.declare(
          "foreign",
          RealVoltage,
          Some(AnalogProceduralRuntime.Value("0.0V", RealVoltage))
        )
      )

    val crossOwnerB = new AnalogProceduralRuntime.Recorder("OwnerB")
    expect("NODAL-ANALOG-033-009"):
      crossOwnerB.procedure:
        crossOwnerB.assign(
          "foreign-write",
          foreign.get,
          AnalogProceduralRuntime.Value("1.0V", RealVoltage)
        )

    val outside = new AnalogProceduralRuntime.Recorder("Outside")
    expect("NODAL-ANALOG-033-008"):
      outside.declare("illegal", RealVoltage)

    if arguments.nonEmpty then
      java.nio.file.Files.writeString(
        java.nio.file.Path.of(arguments(0)),
        s"owner=${snapshot.owner}\nvariables=${snapshot.variables.size}\nassignments=${snapshot.assignments.size}\norder=${snapshot.assignments.map(_.identity).mkString(",")}\n"
      )

    println("Increment 33 Scala procedural runtime witness passed")
