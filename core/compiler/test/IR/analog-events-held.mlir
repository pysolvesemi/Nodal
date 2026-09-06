module attributes {nodal.target.profile = "analog"} {
  "nodal.module"() <{sym_name = "EventTop", metadata = {}}> ({
    "nodal.parameter"() <{sym_name = "enable", type = i64, default_value = 0 : i64, variability = "symbolic", metadata = {semantic_path = "EventTop.enable"}}> : () -> ()
    "nodal.analog"() <{metadata = {}}> ({
      "nodal.analog_procedure"() <{owner = "EventTop", metadata = {}}> ({
        %held = "nodal.analog_variable"() <{identity = "EventTop.held", owner = "EventTop", declaration_order = 0 : i64, initialized = true, initializer_value = "0.0", initializer_kind = "real", initializer_dimension = "1", initializer_reads = [], metadata = {}}> : () -> !nodal.variable<"real", "1">
        %cross = "nodal.analog_cross"() <{event_id = "EventTop.cross", owner = "EventTop", contract = "increment37", name = "", arguments = [{slot = 0 : i64, value = "1.0 V", kind = "real", dimension = "voltage", reads = []}, {slot = 1 : i64, value = "1", kind = "integer", dimension = "1", reads = []}, {slot = 2 : i64, value = "0.0 s", kind = "real", dimension = "time", reads = []}, {slot = 3 : i64, value = "0.0 V", kind = "real", dimension = "voltage", reads = []}, {slot = 4 : i64, value = "EventTop.enable", kind = "integer", dimension = "1", reads = []}], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
        %above = "nodal.analog_above"() <{event_id = "EventTop.above", owner = "EventTop", contract = "increment37", name = "", arguments = [{slot = 0 : i64, value = "1.0 V", kind = "real", dimension = "voltage", reads = []}], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
        %timer = "nodal.analog_timer"() <{event_id = "EventTop.timer", owner = "EventTop", contract = "increment37", name = "", arguments = [{slot = 0 : i64, value = "1.0e-9 s", kind = "real", dimension = "time", reads = []}, {slot = 1 : i64, value = "-1.0e-9 s", kind = "real", dimension = "time", reads = []}, {slot = 2 : i64, value = "0.0 s", kind = "real", dimension = "time", reads = []}], analyses = [], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
        %initial = "nodal.analog_initial_step"() <{event_id = "EventTop.initial", owner = "EventTop", contract = "increment37", name = "", arguments = [], analyses = ["dc", "tran"], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
        %final = "nodal.analog_final_step"() <{event_id = "EventTop.final", owner = "EventTop", contract = "increment37", name = "", arguments = [], analyses = ["tran"], event_reads = [], metadata = {}}> : () -> !nodal.analog_event
        %either = "nodal.analog_event_or"(%cross, %timer) <{event_id = "EventTop.either", owner = "EventTop", contract = "increment37", name = "", metadata = {}}> : (!nodal.analog_event, !nodal.analog_event) -> !nodal.analog_event
        "nodal.analog_on"(%either) <{statement_id = "EventTop.on", owner = "EventTop", metadata = {}}> ({
          "nodal.analog_assign"(%held) <{statement_id = "EventTop.assignment", owner = "EventTop", authored_order = 0 : i64, value_kind = "real", value_dimension = "1", analyses = ["dc", "transient"], guard_present = false, guard_value = "", guard_kind = "", guard_dimension = "", guard_reads = [], metadata = {value = "1.0"}}> : (!nodal.variable<"real", "1">) -> ()
        }) : (!nodal.analog_event) -> ()
        "nodal.analog_on"(%final) <{statement_id = "EventTop.empty", owner = "EventTop", metadata = {}}> ({
        ^bb0:
        }) : (!nodal.analog_event) -> ()
      }) : () -> ()
      %continuous = "nodal.analog_held_read"() <{variable = "EventTop.held", owner = "EventTop", metadata = {}}> : () -> f64
      %smoothed = "nodal.analog_transition"(%continuous) <{analyses = ["ac", "dc", "initialization", "noise", "operating-point", "transient"], context = "legacy-analog", input_continuity = "piecewise-constant", metadata = {}, operand_dimensions = ["1"], operator_contract = "increment36", operator_id = "EventTop.smooth", output_continuity = "continuous", owner = "EventTop", result_dimension = "1", state_id = "EventTop.smooth.state"}> : (f64) -> f64
    }) : () -> ()
  }) : () -> ()
}
