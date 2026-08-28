module {
  "nodal.module"() <{metadata = {}, sym_name = "BadPromotion"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "B", type = i1, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %b = "nodal.parameter_ref"() <{metadata = {}, parameter = @B}> : () -> i1
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_add"(%b, %one) <{metadata = {}}> : (i1, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
