module {
  "nodal.module"() <{metadata = {}, sym_name = "BadLogic"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %one = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"real", "1">
      %bad = "nodal.analog_logic"(%one) <{metadata = {}, operator_name = "not"}> : (!nodal.quantity<"real", "1">) -> i1
    }) : () -> ()
  }) : () -> ()
}
