module {
  "nodal.nature"() <{abstol = 1.000000e-06 : f64, access = "Level", dimension = "voltage", metadata = {}, sym_name = "LevelNature", units = "V"}> : () -> ()
  "nodal.discipline"() <{domain = "discrete", metadata = {}, potential = @LevelNature, sym_name = "sampled"}> : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "BadDiscipline"}> ({
  ^bb0:
    %p = "nodal.terminal"() <{metadata = {declaration_kind = "analog-inout"}, name = "p"}> : () -> !nodal.terminal<"sampled">
    "nodal.analog"() <{metadata = {}}> ({
    ^bb0:
      %bad = "nodal.terminal_access"(%p) <{function = "Level", kind = "potential", metadata = {}, source_path = "BadDiscipline.bad"}> : (!nodal.terminal<"sampled">) -> !nodal.quantity<"real", "voltage">
    }) : () -> ()
  }) : () -> ()
}
