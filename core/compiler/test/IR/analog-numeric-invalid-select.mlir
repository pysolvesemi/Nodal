module {
  "nodal.module"() <{metadata = {}, sym_name = "BadSelect"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %two = "nodal.real_literal"() <{metadata = {}, value = 2.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_select"(%one, %one, %two) <{metadata = {}}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
