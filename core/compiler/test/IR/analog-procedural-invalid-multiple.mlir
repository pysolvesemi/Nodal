module {
  "nodal.module"() <{metadata = {}, sym_name = "MultipleProcedures"}> ({
  ^bb0:
    "nodal.analog"() <{metadata = {semantic_path = "MultipleProcedures.first"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {}, owner = "MultipleProcedures"}> ({
      ^bb0:
      }) : () -> ()
    }) : () -> ()
    "nodal.analog"() <{metadata = {semantic_path = "MultipleProcedures.second"}}> ({
    ^bb0:
      "nodal.analog_procedure"() <{metadata = {}, owner = "MultipleProcedures"}> ({
      ^bb0:
      }) : () -> ()
    }) : () -> ()
  }) : () -> ()
}
