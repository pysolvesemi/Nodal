module {
  "nodal.module"() <{metadata = {}, sym_name = "BadForm"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.terminal_access"() <{function = "potential", kind = "potential", metadata = {}, source_path = "BadForm.bad"}> : () -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
  }) : () -> ()
}
