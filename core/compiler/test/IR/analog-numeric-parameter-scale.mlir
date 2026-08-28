module {
  "nodal.unit"() <{dimension = "resistance", metadata = {}, native_suffix = "k", scale = 1.0e3 : f64, sym_name = "kOhm", symbol = "kOhm"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "ParameterScale"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "R", type = f64, unit = @kOhm, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %r = "nodal.parameter_ref"() <{metadata = {}, parameter = @R}> : () -> !nodal.quantity<"real", "resistance">
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %scaled = "nodal.analog_mul"(%r, %one) <{metadata = {identity = "parameter_scale"}}> : (!nodal.quantity<"real", "resistance">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "resistance">
    }) : () -> ()
  }) : () -> ()
}
