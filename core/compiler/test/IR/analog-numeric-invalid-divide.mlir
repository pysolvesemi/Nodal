module {
  "nodal.module"() <{metadata = {}, sym_name = "BadDivide"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %four = "nodal.analog_integer_literal"() <{metadata = {}, value = 4 : i64}> : () -> !nodal.quantity<"integer", "1">
      %zero = "nodal.analog_integer_literal"() <{metadata = {}, value = 0 : i64}> : () -> !nodal.quantity<"integer", "1">
      %bad = "nodal.analog_div"(%four, %zero) <{metadata = {}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"integer", "1">) -> !nodal.quantity<"real", "1">
    }) : () -> ()
  }) : () -> ()
}
