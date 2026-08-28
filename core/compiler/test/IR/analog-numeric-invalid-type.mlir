module {
  "nodal.module"() <{metadata = {}, sym_name = "BadType"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.real_literal"() <{metadata = {}, value = 1.0 : f64}> : () -> !nodal.quantity<"integer", "1">
    }) : () -> ()
  }) : () -> ()
}
