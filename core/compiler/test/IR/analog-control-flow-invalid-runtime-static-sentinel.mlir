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
        "nodal.analog_case"() <{metadata = {semantic_path = "ControlTop.case_0.selector"}, owner = "ControlTop", selector_dimension = "1", selector_kind = "integer", selector_reads = ["ControlTop.procedure.mode"], selector_value = "ControlTop.procedure.mode", statement_id = "ControlTop.case_0", static_value = "", static_value_present = false}> ({
        ^bb0:
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.arm_0", is_default = false, labels = ["integer:0"], metadata = {semantic_path = "ControlTop.case_0.arm_0"}, owner = "ControlTop"}> ({
          ^bb0:
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_0", value = "1.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_0", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> () loc("AnalogControlFlow.scala":20:9)
          }) : () -> ()
          "nodal.analog_case_arm"() <{arm_id = "ControlTop.case_0.default", is_default = true, labels = [], metadata = {semantic_path = "ControlTop.case_0.default"}, owner = "ControlTop"}> ({
          ^bb0:
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 1 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_1", value = "2.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_1", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> () loc("AnalogControlFlow.scala":21:9)
          }) : () -> ()
        }) : () -> ()
        "nodal.analog_loop"() <{bound_dimension = "1", bound_kind = "integer", bound_reads = ["ControlTop.procedure.mode"], bound_value = "ControlTop.procedure.mode", maximum_iterations = 4 : i64, metadata = {semantic_path = "ControlTop.loop_1"}, minimum_iterations = 1 : i64, owner = "ControlTop", stage = "runtime", statement_id = "ControlTop.loop_1", static_trip_count = 0 : i64, static_trip_count_present = false}> ({
        ^bb0:
          "nodal.analog_if"() <{metadata = {semantic_path = "ControlTop.if_2"}, owner = "ControlTop", statement_id = "ControlTop.if_2"}> ({
          ^bb0:
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_2.branch_0", condition_dimension = "1", condition_kind = "boolean", condition_reads = ["ControlTop.procedure.select"], condition_value = "ControlTop.procedure.select", is_else = false, metadata = {semantic_path = "ControlTop.if_2.condition_0"}, owner = "ControlTop", stage = "runtime", static_value = true, static_value_present = false}> ({
            ^bb0:
              "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 2 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_2", value = "3.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_2", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> () loc("AnalogControlFlow.scala":22:9)
              "nodal.analog_continue"() <{metadata = {semantic_path = "ControlTop.continue_3"}, owner = "ControlTop", statement_id = "ControlTop.continue_3"}> : () -> () loc("AnalogControlFlow.scala":31:11)
            }) : () -> ()
            "nodal.analog_if_arm"() <{arm_id = "ControlTop.if_2.otherwise", condition_dimension = "", condition_kind = "", condition_reads = [], condition_value = "", is_else = true, metadata = {semantic_path = "ControlTop.if_2.otherwise"}, owner = "ControlTop", stage = "else", static_value = false, static_value_present = false}> ({
            ^bb0:
              "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 3 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_3", value = "4.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_3", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> () loc("AnalogControlFlow.scala":23:9)
              "nodal.analog_break"() <{metadata = {semantic_path = "ControlTop.break_4"}, owner = "ControlTop", statement_id = "ControlTop.break_4"}> : () -> () loc("AnalogControlFlow.scala":35:11)
            }) : () -> ()
          }) : () -> ()
        }) : () -> ()
        %final_read = "nodal.analog_variable_read"(%value) <{metadata = {semantic_path = "ControlTop.final_read"}, owner = "ControlTop", read_id = "ControlTop.final_read"}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1"> loc("AnalogControlFlow.scala":80:7)
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
