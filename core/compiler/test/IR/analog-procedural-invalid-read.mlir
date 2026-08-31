module {
  "nodal.module"() <{metadata = {}, sym_name = "ReadBeforeWrite"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {}, owner = "ReadBeforeWrite"}> ({
      ^bb0:
        %x = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ReadBeforeWrite.x", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ReadBeforeWrite.x"}, owner = "ReadBeforeWrite"}> : () -> !nodal.variable<"real", "1">
        %read = "nodal.analog_variable_read"(%x) <{metadata = {semantic_path = "ReadBeforeWrite.read"}, owner = "ReadBeforeWrite", read_id = "ReadBeforeWrite.read"}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
