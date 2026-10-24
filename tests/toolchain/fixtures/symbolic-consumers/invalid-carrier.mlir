module { hw.module @Invalid(in %a : f64, out result : i8) { %r = "comb.concat"(%a) : (f64) -> i8
 hw.output %r : i8
}}