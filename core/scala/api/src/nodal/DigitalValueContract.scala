package nodal

/** Exact digital scalar facts shared by construction and later typed consumers. */
private[nodal] object DigitalValueContract:
  def fail(number: Int, message: String): Nothing =
    scala.util.Failure[Nothing](
      new ConstructionException(KernelDiagnostic(f"NODAL-DIGITAL-054-$number%03d", message))
    ).get

  def validateLiteral(kind: String, value: BigInt, width: Long): Unit =
    if width <= 0 then fail(1, "digital width must be positive")
    val required = kind match
      case "UInt" | "Bits" =>
        if value < 0 then fail(2, "unsigned digital literal cannot be negative")
        math.max(1, value.bitLength).toLong
      case "SInt" => value.bitLength.toLong + 1L
      case _ => fail(3, s"unsupported digital literal kind '$kind'")
    if required > width then
      fail(2, s"$kind literal $value does not fit width $width")
