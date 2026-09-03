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
            "nodal.analog_assign"(%value) <{analyses = ["dc", "transient"], authored_order = 0 : i64, guard_dimension = "", guard_kind = "", guard_present = false, guard_reads = [], guard_value = "", metadata = {semantic_path = "ControlTop.statement_0", value = "1.0"}, owner = "ControlTop", statement_id = "ControlTop.statement_0", value_dimension = "1", value_kind = "real"}> : (!nodal.variable<"real", "1">) -> () loc("AnalogControlFlow.scala":30:9)
          }) : () -> ()
        }) : () -> ()
        %read_after_case = "nodal.analog_variable_read"(%value) <{metadata = {semantic_path = "ControlTop.read_after_case"}, owner = "ControlTop", read_id = "ControlTop.read_after_case"}> : (!nodal.variable<"real", "1">) -> !nodal.quantity<"real", "1"> loc("AnalogControlFlow.scala":80:7)
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
