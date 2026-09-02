module {
  "nodal.module"() <{metadata = {}, sym_name = "ProcTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ProcTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ProcTop.analogProcedure"}, owner = "ProcTop"}> ({
      ^bb0:
        %x = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ProcTop.procedure.x", initialized = true, initializer_dimension = "voltage", initializer_kind = "real", initializer_reads = [], initializer_value = "1.0", metadata = {semantic_path = "ProcTop.procedure.x"}, owner = "ProcTop"}> : () -> !nodal.variable<"real", "voltage"> loc("AnalogProcedural.scala":10:5)
        %y = "nodal.analog_variable"() <{declaration_order = 1 : i64, identity = "ProcTop.procedure.y", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ProcTop.procedure.y"}, owner = "ProcTop"}> : () -> !nodal.variable<"real", "voltage"> loc("AnalogProcedural.scala":11:5)
        %read_x = "nodal.analog_variable_read"(%x) <{metadata = {semantic_path = "ProcTop.assign0.read_0"}, owner = "ProcTop", read_id = "ProcTop.assign0.read_0"}> : (!nodal.variable<"real", "voltage">) -> !nodal.quantity<"real", "voltage"> loc("AnalogProcedural.scala":14:7)
        "nodal.analog_assign"(%y, %read_x) <{analyses = ["unsupported"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ProcTop.assign0"}, owner = "ProcTop", statement_id = "ProcTop.assign0", value_dimension = "voltage", value_kind = "real"}> : (!nodal.variable<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> () loc("AnalogProcedural.scala":14:7)
        "nodal.analog_scope"() <{metadata = {semantic_path = "ProcTop.procedure.block_0"}, owner = "ProcTop", scope_id = "block_0#1"}> ({
        ^bb0:
          %read_y = "nodal.analog_variable_read"(%y) <{metadata = {semantic_path = "ProcTop.assign1.read_0"}, owner = "ProcTop", read_id = "ProcTop.assign1.read_0"}> : (!nodal.variable<"real", "voltage">) -> !nodal.quantity<"real", "voltage"> loc("AnalogProcedural.scala":16:9)
          "nodal.analog_assign"(%x, %read_y) <{analyses = ["transient"], authored_order = 1 : i64, guard_dimension = "1", guard_kind = "boolean", guard_present = true, guard_reads = [], guard_value = "enabled", metadata = {semantic_path = "ProcTop.assign1"}, owner = "ProcTop", statement_id = "ProcTop.assign1", value_dimension = "voltage", value_kind = "real"}> : (!nodal.variable<"real", "voltage">, !nodal.quantity<"real", "voltage">) -> () loc("AnalogProcedural.scala":16:9)
        }) : () -> () loc("AnalogProcedural.scala":15:7)
      }) : () -> () loc("AnalogProcedural.scala":9:3)
    }) : () -> () loc("AnalogProcedural.scala":9:3)
  }) : () -> ()
}
