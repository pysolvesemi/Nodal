module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = " ControlTop"}> ({
      ^bb0:
        %value = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.value", initialized = true, initializer_dimension = "1", initializer_kind = "real", initializer_reads = [], initializer_value = "1.0", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = " ControlTop"}> : () -> !nodal.variable<"real", "1">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
