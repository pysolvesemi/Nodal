module {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.unit"() <{dimension = "current", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Amp", symbol = "A"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadDimension"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "V", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.0 : f64, metadata = {}, parameter_kind = "real", sym_name = "I", type = f64, unit = @Amp, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %v = "nodal.parameter_ref"() <{metadata = {}, parameter = @V}> : () -> !nodal.quantity<"real", "voltage">
      %i = "nodal.parameter_ref"() <{metadata = {}, parameter = @I}> : () -> !nodal.quantity<"real", "current">
      %bad = "nodal.analog_add"(%v, %i) <{metadata = {}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
  }) : () -> ()
}
