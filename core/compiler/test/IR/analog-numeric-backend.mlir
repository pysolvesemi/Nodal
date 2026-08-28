module attributes {
  nodal.backend.check_profile = "release",
  nodal.backend.materialization = "safe-inline",
  nodal.backend.naming = "semantic",
  nodal.backend.shaped_layout = "scalar-or-flat",
  nodal.target.profile = "analog"
} {
  "nodal.module"() <{metadata = {}, sym_name = "QuantityBackend"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-input"}, name = "p"}> : () -> !nodal.terminal<"electrical">
    %n = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "n"}> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%p, %n) <{metadata = {identity = "p_n"}}> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %two = "nodal.analog_integer_literal"() <{metadata = {}, value = 2 : i64}> : () -> !nodal.quantity<"integer", "1">
      %three = "nodal.real_literal"() <{metadata = {}, value = 3.5 : f64}> : () -> !nodal.quantity<"real", "1">
      %product = "nodal.analog_mul"(%two, %three) <{metadata = {}}> : (!nodal.quantity<"integer", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
      %zero = "nodal.analog_integer_literal"() <{metadata = {}, value = 0 : i64}> : () -> !nodal.quantity<"integer", "1">
      %positive = "nodal.analog_compare"(%product, %zero) <{metadata = {}, predicate = "gt"}> : (!nodal.quantity<"real", "1">, !nodal.quantity<"integer", "1">) -> i1
      %selected = "nodal.analog_select"(%positive, %product, %three) <{metadata = {}}> : (i1, !nodal.quantity<"real", "1">, !nodal.quantity<"real", "1">) -> !nodal.quantity<"real", "1">
      "nodal.contribute"(%branch, %selected) <{kind = "flow", metadata = {}}> : (!nodal.branch<"electrical">, !nodal.quantity<"real", "1">) -> ()
    }) : () -> ()
  }) : () -> ()
}
