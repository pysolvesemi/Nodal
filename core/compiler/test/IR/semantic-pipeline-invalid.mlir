// Construction closure failure.
module attributes {nodal.verify.construction_closed = false} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Driver coverage failure.
module attributes {
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = false
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Latch failure.
module attributes {
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.latch_free = false
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Combinational-cycle failure.
module attributes {
  nodal.verify.construction_closed = true,
  nodal.verify.driver_coverage = true,
  nodal.verify.latch_free = true,
  nodal.verify.combinational_acyclic = false
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Hierarchy resolution failure.
module attributes {nodal.verify.construction_closed = true} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    "nodal.instance"() <{
      domain_bindings = {},
      metadata = {},
      module = @Missing,
      parameter_bindings = {},
      sym_name = "missing"
    }> : () -> ()
  }) : () -> ()
}

// -----

// Symbolic shape failure.
module attributes {nodal.verify.construction_closed = true} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    "nodal.domain"() <{
      edge = "rising",
      metadata = {},
      reset_policy = "sync",
      sym_name = "core"
    }> : () -> ()
    "nodal.port"() <{
      direction = "output",
      domain = @core,
      metadata = {},
      sym_name = "data",
      type = !nodal.shaped<"MISSING", !nodal.uint<8>>
    }> : () -> ()
  }) : () -> ()
}

// -----

// Parameter binding failure.
module attributes {nodal.verify.construction_closed = true} {
  "nodal.module"() <{metadata = {}, sym_name = "Child"}> ({
    "nodal.parameter"() <{
      default_value = 8 : i64,
      metadata = {},
      sym_name = "WIDTH",
      type = i64,
      variability = "symbolic"
    }> : () -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    "nodal.instance"() <{
      domain_bindings = {},
      metadata = {},
      module = @Child,
      parameter_bindings = {UNKNOWN = 8 : i64},
      sym_name = "child"
    }> : () -> ()
  }) : () -> ()
}

// -----

// Enum/FSM failure.
module attributes {
  nodal.verify.construction_closed = true,
  nodal.verify.enum_fsm = false
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Domain resolution failure.
module attributes {nodal.verify.construction_closed = true} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    "nodal.port"() <{
      direction = "input",
      domain = @missing,
      metadata = {},
      sym_name = "data",
      type = !nodal.uint<8>
    }> : () -> ()
  }) : () -> ()
}

// -----

// Interface-role failure.
module attributes {nodal.verify.construction_closed = true} {
  "nodal.interface"() <{metadata = {}, sym_name = "Link"}> ({
    "nodal.interface_role"() <{
      kind = "producer",
      metadata = {},
      sym_name = "source"
    }> : () -> ()
    "nodal.interface_member"() <{
      metadata = {},
      protocol = "plain",
      roles = ["source"],
      sym_name = "data",
      type = !nodal.uint<8>
    }> : () -> ()
  }) : () -> ()
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    "nodal.interface_instance"() <{
      definition = @Link,
      metadata = {},
      role = "missing",
      sym_name = "link"
    }> : () -> ()
  }) : () -> ()
}

// -----

// Memory/effect contract failure.
module attributes {
  nodal.verify.construction_closed = true,
  nodal.verify.memory_effects = false
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Analog topology failure.
module attributes {
  nodal.verify.construction_closed = true,
  nodal.verify.analog_topology = false
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
  }) : () -> ()
}

// -----

// Target capability failure after analog topology succeeds.
module attributes {
  nodal.target.profile = "digital",
  nodal.verify.construction_closed = true
} {
  "nodal.module"() <{metadata = {}, sym_name = "Top"}> ({
    %positive = "nodal.terminal"() <{
      metadata = {},
      name = "p"
    }> : () -> !nodal.terminal<"electrical">
    %negative = "nodal.node"() <{
      metadata = {},
      name = "n"
    }> : () -> !nodal.terminal<"electrical">
    %branch = "nodal.branch"(%positive, %negative) <{
      metadata = {}
    }> : (!nodal.terminal<"electrical">, !nodal.terminal<"electrical">) -> !nodal.branch<"electrical">
  }) : () -> ()
}
