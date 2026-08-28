module attributes {
  nodal.target.profile = "analog"
} {
  "nodal.unit"() <{dimension = "voltage", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Volt", symbol = "V"}> : () -> ()
  "nodal.unit"() <{dimension = "current", metadata = {}, native_suffix = "", scale = 1.0 : f64, sym_name = "Amp", symbol = "A"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "AnalogNumericTyping"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = 1.5 : f64, metadata = {}, parameter_kind = "real", sym_name = "V1", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 2.5 : f64, metadata = {}, parameter_kind = "real", sym_name = "V2", type = f64, unit = @Volt, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = 0.25 : f64, metadata = {}, parameter_kind = "real", sym_name = "I1", type = f64, unit = @Amp, variability = "fixed"}> : () -> ()
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {typing = "increment30"}}> ({
    ^bb0:
      %v1 = "nodal.parameter_ref"() <{metadata = {}, parameter = @V1}> : () -> !nodal.quantity<"real", "voltage">
      %v2 = "nodal.parameter_ref"() <{metadata = {}, parameter = @V2}> : () -> !nodal.quantity<"real", "voltage">
      %i1 = "nodal.parameter_ref"() <{metadata = {}, parameter = @I1}> : () -> !nodal.quantity<"real", "current">
      %enable = "nodal.parameter_ref"() <{metadata = {}, parameter = @ENABLE}> : () -> i1
      %sum = "nodal.analog_add"(%v1, %v2) <{metadata = {identity = "sum"}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %power = "nodal.analog_mul"(%sum, %i1) <{metadata = {identity = "power"}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "current*voltage">
      %ratio = "nodal.analog_div"(%sum, %i1) <{metadata = {identity = "ratio"}}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> !nodal.quantity<"real", "current^-1*voltage">
      %negative = "nodal.analog_neg"(%sum) <{metadata = {identity = "negative"}}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %ordered = "nodal.analog_compare"(%v1, %v2) <{metadata = {}, predicate = "lt"}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> i1
      %condition = "nodal.analog_logic"(%ordered, %enable) <{metadata = {}, operator_name = "and"}> : (i1, i1) -> i1
      %selected = "nodal.analog_select"(%condition, %sum, %v1) <{metadata = {identity = "selected"}}> : (i1, !nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "voltage">
      %slope = "nodal.analog_ddt"(%selected) <{metadata = {identity = "slope"}}> : (!nodal.quantity<"real", "voltage">) -> !nodal.quantity<"real", "time^-1*voltage">
      %two = "nodal.analog_integer_literal"() <{metadata = {}, value = 2 : i64}> : () -> !nodal.quantity<"integer", "1">
      %four = "nodal.analog_integer_literal"() <{metadata = {}, value = 4 : i64}> : () -> !nodal.quantity<"integer", "1">
      %six = "nodal.analog_add"(%two, %four) <{metadata = {identity = "six"}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"integer", "1">) -> !nodal.quantity<"integer", "1">
      %two_exact = "nodal.analog_div"(%four, %two) <{metadata = {identity = "two_exact"}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"integer", "1">) -> !nodal.quantity<"integer", "1">
      %three = "nodal.real_literal"() <{metadata = {}, value = 3.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %mixed = "nodal.analog_mul"(%six, %three) <{metadata = {identity = "mixed"}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
