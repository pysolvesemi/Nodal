module {
  "nodal.module"() <{metadata = {}, sym_name = "SelectPromotion"}> ({
  ^bb0:
    "nodal.parameter"() <{classification = "ordinary", default_value = true, metadata = {}, parameter_kind = "boolean", sym_name = "ENABLE", type = i1, variability = "fixed"}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %enable = "nodal.parameter_ref"() <{metadata = {}, parameter = @ENABLE}> : () -> i1
      %two = "nodal.analog_integer_literal"() <{metadata = {}, value = 2 : i64}> : () -> !nodal.quantity<"integer", "1">
      %three = "nodal.real_literal"() <{metadata = {}, value = 3.5 : f64}> : () -> !nodal.quantity<"real", "1">
      %selected = "nodal.analog_select"(%enable, %two, %three) <{metadata = {identity = "select_promotion"}}> : (i1, !nodal.quantity<"integer", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
