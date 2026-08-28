module {
  "nodal.unit"() <{dimension = "time^-9223372036854775808", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "ExtremeTime", symbol = "Xt"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "DimensionOverflow"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "EXTREME", type = f64, unit = @ExtremeTime, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %extreme = "nodal.parameter_ref"() <{metadata = {}, parameter = @EXTREME}> : () -> !nodal.quantity<"real", "time^-9223372036854775808">
      %bad = "nodal.analog_div"(%one, %extreme) <{metadata = {}}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "time^-9223372036854775808">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
