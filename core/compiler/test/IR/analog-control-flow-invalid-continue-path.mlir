module {
  "nodal.module"() <{metadata = {}, sym_name = "ControlTop"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "ControlTop.analogProcedural"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {semantic_path = "ControlTop.analogProcedure"}, owner = "ControlTop"}> ({
      ^bb0:
        %mode = "nodal.analog_variable"() <{declaration_order = 0 : i64, identity = "ControlTop.procedure.mode", initialized = true, initializer_dimension = "1", initializer_kind = "integer", initializer_reads = [], initializer_value = "0", metadata = {semantic_path = "ControlTop.procedure.mode"}, owner = "ControlTop"}> : () -> !nodal.variable<"integer", "1"> loc("AnalogControlFlow.scala":10:5)
        %select = "nodal.analog_variable"() <{declaration_order = 1 : i64, identity = "ControlTop.procedure.select", initialized = true, initializer_dimension = "1", initializer_kind = "boolean", initializer_reads = [], initializer_value = "false", metadata = {semantic_path = "ControlTop.procedure.select"}, owner = "ControlTop"}> : () -> !nodal.variable<"boolean", "1"> loc("AnalogControlFlow.scala":11:5)
        %value = "nodal.analog_variable"() <{declaration_order = 2 : i64, identity = "ControlTop.procedure.value", initialized = false, initializer_dimension = "", initializer_kind = "", initializer_reads = [], initializer_value = "", metadata = {semantic_path = "ControlTop.procedure.value"}, owner = "ControlTop"}> : () -> !nodal.variable<"real", "1"> loc("AnalogControlFlow.scala":12:5)
        "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["ControlTop.procedure.mode"], bound_value = "ControlTop.procedure.mode", maximum_iterations = 4 : i64, metadata = {semantic_path = "ControlTop.loop_0"}, minimum_iterations = 1 : i64, owner = "ControlTop", stage = "runtime", statement_id = "ControlTop.loop_0", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
        ^bb0:
          "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_1"}, owner = "ControlTop", statement_id = "ControlTop.if_1"}> ({
          ^bb0:
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_1.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["ControlTop.procedure.select"], condition_value = "ControlTop.procedure.select", is_else = false, metadata = {semantic_path = "ControlTop.if_1.condition_0"}, owner = "ControlTop", stage = "runtime", static_value = false, static_value_present = false}> ({
            ^bb0:
              "nodal.analog_continue"() <{metadata = {semantic_path = "ControlTop.continue_2"}, owner = "ControlTop", statement_id = "ControlTop.continue_2"}> : () -> ()
            }) : () -> ()
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_1.otherwise", condition_dimension = "", condition_kind = "", condition_reads = [], condition_value = "", is_else = true, metadata = {semantic_path = "ControlTop.if_1.otherwise"}, owner = "ControlTop", stage = "else", static_value = false, static_value_present = false}> ({
            ^bb0:
              "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_0", value = "1.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_0", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> () loc("AnalogControlFlow.scala":30:9)
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
        %read_after_continue_loop = "nodal.analog_variable_read"(%value) <{metadata = {semantic_path = "ControlTop.read_after_continue_loop"}, owner = "ControlTop", read_id = "ControlTop.read_after_continue_loop"}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1"> loc("AnalogControlFlow.scala":80:7)
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
