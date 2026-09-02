module {
  "nodal.module"() <{metadata = {}, sym_name = "ProcTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ProcTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ProcTop.analogProcedure"}, owner = "ProcTop"}> ({
      ^bb0:
        %bad = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ProcTop.procedure.bad", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ProcTop.procedure.bad"}, owner = "ProcTop"}> : () -> !nodal.variable<"string", "1"> loc("AnalogProcedural.scala":10:5)
      }) : () -> () loc("AnalogProcedural.scala":9:3)
    }) : () -> () loc("AnalogProcedural.scala":9:3)
  }) : () -> ()
}
