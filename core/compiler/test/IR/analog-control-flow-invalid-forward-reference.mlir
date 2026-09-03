module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = "ControlTop"}> ({
      ^bb0:
        %value = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.value", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = "ControlTop"}> : () -> !nodal.variable<"real", "1">
        "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_0"}, owner = "ControlTop", statement_id = "ControlTop.if_0"}> ({
        ^bb0:
          "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_0.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = [], condition_value = "false", is_else = false, metadata = {semantic_path = "ControlTop.if_0.condition_0"}, owner = "ControlTop", stage = "static", static_value = false, static_value_present = true}> ({
          ^bb0:
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "1", guard_kind = "boolean", guard_present = true, guard_reads = ["ControlTop.procedure.future"], guard_value = "future", metadata = {semantic_path = "ControlTop.statement_0", value = "1.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_0", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> ()
          }) : () -> ()
        }) : () -> ()
        %future = "nodal.analog_variable"() <{declaration_order = 1 : i64, identity = "ControlTop.procedure.future", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.future"}, owner = "ControlTop"}> : () -> !nodal.variable<"boolean", "1">
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
