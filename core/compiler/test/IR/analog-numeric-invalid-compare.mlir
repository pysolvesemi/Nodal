module {
  "nodal.module"() <{metadata = {}, sym_name = "BadCompare"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %v = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "voltage">
      %i = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "current">
      %bad = "nodal.analog_compare"(%v, %i) <{metadata = {}, predicate = "lt"}> : (!nodal.quantity<"real", "voltage">, !nodal.quantity<"real", "current">) -> i1
    }) : () -> ()
  }) : () -> ()
}
