package nodal

/** Continuous-time numerator/denominator filter. Coefficient k has time^k units. Both arrays are
  * nonempty, static, and ordered by ascending powers of s. The normalized transfer is
  * dimensionless; its output has the input dimension.
  */
def laplaceNd(
    input: Expr[Real],
    numerator: Seq[Expr[Real]],
    denominator: Seq[Expr[Real]]
): Expr[Real] =
  AnalogTransferContract.call("laplace_nd", input, numerator, denominator, Vector.empty)

/** Sampled numerator/denominator filter with dimensionless coefficients in ascending powers of
  * z^-1. Omitted transition/start retain simulator defaults.
  */
def ziNd(
    input: Expr[Real],
    numerator: Seq[Expr[Real]],
    denominator: Seq[Expr[Real]],
    interval: Expr[Real]
): Expr[Real] =
  AnalogTransferContract.call("zi_nd", input, numerator, denominator, Vector(interval))

/** Explicit transition must be positive in the portable branch-safe profile. */
def ziNd(
    input: Expr[Real],
    numerator: Seq[Expr[Real]],
    denominator: Seq[Expr[Real]],
    interval: Expr[Real],
    transition: Expr[Real]
): Expr[Real] = AnalogTransferContract.call(
  "zi_nd",
  input,
  numerator,
  denominator,
  Vector(interval, transition)
)

/** Explicit first-transition time is nonnegative; all timing values are proven constants. */
def ziNd(
    input: Expr[Real],
    numerator: Seq[Expr[Real]],
    denominator: Seq[Expr[Real]],
    interval: Expr[Real],
    transition: Expr[Real],
    start: Expr[Real]
): Expr[Real] = AnalogTransferContract.call(
  "zi_nd",
  input,
  numerator,
  denominator,
  Vector(interval, transition, start)
)
